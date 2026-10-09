package dev.pandor.regionium.core;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.access.ServerPlayerRegioniumPacketAccess;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.ticks.LevelTicks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Region scheduler.
 *
 * The server thread only performs global chunk lifecycle work and regionizer
 * topology maintenance. It never releases a global "tick batch". Each region
 * is independently scheduled onto a shared worker pool.
 */
public final class RegioniumScheduler implements AutoCloseable {
    public static final int DEFAULT_REGION_COUNT =
        Math.clamp(Runtime.getRuntime().availableProcessors(), 1, 16);

    private final int regionCount;
    private final ScheduledThreadPoolExecutor workers;
    private final RegioniumRegionizer regionizer;

    private final Map<ServerLevel, List<RegioniumRegion>> levelRegions =
        Collections.synchronizedMap(new IdentityHashMap<>());
    private final Set<ServerLevel> knownLevels =
        Collections.newSetFromMap(new IdentityHashMap<>());

    private final PacketProcessor globalPacketProcessor = new PacketProcessor(null);
    private final Set<ChunkExecutionKey> activeChunkExecutions =
        ConcurrentHashMap.newKeySet();

    /*
     * Vanilla's ChunkMap.move() is a global chunk-tracking operation. Region
     * workers must never call it, but the global server thread still has to
     * receive player position changes so DistanceManager can promote the
     * surrounding lazy/simulation chunks.
     */
    private final Set<ServerPlayer> pendingGlobalPlayerMoves =
        ConcurrentHashMap.newKeySet();

    private final Set<ServerPlayer> pendingWaypointPlayerUpdates =
        ConcurrentHashMap.newKeySet();

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean gameTestDriven = new AtomicBoolean();

    private volatile long tick;
    private static final AtomicInteger NEXT_REGION_ID = new AtomicInteger();
    private static final AtomicInteger NEXT_WORKER_ID = new AtomicInteger();
    private static final AtomicInteger DEBUG_ACTIVE_WORKERS = new AtomicInteger();
    private static final AtomicInteger DEBUG_MAX_WORKERS = new AtomicInteger();

    private record ChunkExecutionKey(ServerLevel level, long chunk) {}

    public RegioniumScheduler() {
        this(DEFAULT_REGION_COUNT);
    }

    public RegioniumScheduler(int regionCount) {
        if (regionCount < 1) {
            throw new IllegalArgumentException("regionCount must be positive");
        }

        this.regionCount = regionCount;

        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(
                runnable,
                "Regionium-Worker-" + NEXT_WORKER_ID.getAndIncrement()
            );
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, error) -> {
                Regionium.LOGGER.error("Uncaught exception in Regionium worker {}", t.getName(), error);
            });
            return thread;
        };

        int workerThreads = Math.max(
            1,
            Math.min(regionCount, Runtime.getRuntime().availableProcessors())
        );
        this.workers = new ScheduledThreadPoolExecutor(
            workerThreads,
            factory
        );
        this.workers.setRemoveOnCancelPolicy(true);
        this.workers.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.workers.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);

        this.regionizer = new RegioniumRegionizer(this::createRegion);
        running.set(true);
    }

    /** Active spatial regions only. Worker capacity is reported separately. */
    public List<RegioniumRegion> regions() {
        return List.copyOf(regionizer.activeRegions());
    }

    public RegioniumRegion region(int id) {
        for (RegioniumRegion region : regions()) {
            if (region.id() == id) {
                return region;
            }
        }
        throw new IllegalArgumentException("Unknown Regionium region: " + id);
    }

    public RegioniumRegionizer regionizer() {
        return regionizer;
    }

    public <T extends PacketListener> void scheduleGlobalPacket(
        T listener,
        Packet<T> packet
    ) {
        globalPacketProcessor.scheduleIfPossible(listener, packet);
    }

    public void drainGlobalPackets() {
        // Configuration/login packets remain vanilla/global. They are not
        // region-owned until a ServerPlayer enters a world region.
        while (((dev.pandor.regionium.access.PacketProcessorRegioniumAccess)
            (Object) globalPacketProcessor).regionium$hasPackets()) {
            if (!((dev.pandor.regionium.access.PacketProcessorRegioniumAccess)
                (Object) globalPacketProcessor).regionium$executeSinglePacket()) {
                break;
            }
        }
    }

    public void notifyRegionPackets(RegioniumWorldData data) {
        // The owning region observes the processor on its next tick.
    }

    /**
     * Execute all packets currently queued for players owned by this region.
     * PacketProcessor itself is the queue; the network thread never executes
     * world-mutating packet handlers.
     */
    public void drainRegionPackets(RegioniumRegion region) {
        if (RegioniumContext.currentRegion() != region) {
            throw new IllegalStateException("Packet drain outside owning region");
        }

        for (ServerPlayer player : List.copyOf(region.worldData().players())) {
            if (player.isRemoved()) {
                continue;
            }

            if (regionizer.owner(region.level(), player.chunkPosition().pack()) != region) {
                regionizer.migrateEntityAfterRegionAction(region.level(), player);
                continue;
            }

            var access = (ServerPlayerRegioniumPacketAccess) (Object) player;
            var processor = (dev.pandor.regionium.access.PacketProcessorRegioniumAccess)
                (Object) access.regionium$getPacketProcessor();

            while (processor.regionium$hasPackets()) {
                if (!processor.regionium$executeSinglePacket()) {
                    break;
                }
            }
        }
    }

    public boolean isOwnedByCurrentRegion(Object object) {
        RegioniumRegion current = RegioniumContext.currentRegion();
        return current != null && ownerOf(object) == current;
    }

    public boolean isCurrentRegionFor(ServerLevel level, net.minecraft.core.BlockPos pos) {
        RegioniumRegion current = RegioniumContext.currentRegion();
        return current != null && regionizer.owner(level, ChunkPos.pack(pos)) == current;
    }

    public void beginServerTick(MinecraftServer server) {
        tick++;

        /*
         * Chunk loading/tracking is global vanilla infrastructure. It runs
         * only on the server thread; region workers merely enqueue the latest
         * player position.
         */
        for (ServerPlayer player : List.copyOf(pendingGlobalPlayerMoves)) {
            pendingGlobalPlayerMoves.remove(player);
            if (player.isRemoved() || !(player.level() instanceof ServerLevel level)) {
                continue;
            }
            level.getChunkSource().move(player);
        }

        for (ServerPlayer player : List.copyOf(pendingWaypointPlayerUpdates)) {
            pendingWaypointPlayerUpdates.remove(player);
            if (player.isRemoved() || !(player.level() instanceof ServerLevel level)) {
                continue;
            }
            level.getWaypointManager().updatePlayer(player);
        }

        /*
         * ChunkMap/DistanceManager maintenance is performed exactly once by
         * RegioniumServerTickMixin after all levels have been registered.
         * Do not tick ServerChunkCache here as well: doing so advances the
         * global chunk lifecycle twice per server tick and can race the
         * ownership reconciliation pass.
         */
        drainGlobalPackets();
    }

    public void queueGlobalPlayerMove(ServerPlayer player) {
        if (!player.isRemoved()) {
            pendingGlobalPlayerMoves.add(player);
        }
    }

    public void queueWaypointPlayerUpdate(ServerPlayer player) {
        if (!player.isRemoved()) {
            pendingWaypointPlayerUpdates.add(player);
        }
    }

    /**
     * A player entity tick requires its immediate physical neighbourhood to be
     * fully prepared. Chunk loading is asynchronous and belongs to the global
     * server thread, so a newly spawned/teleported player may temporarily have
     * a valid region owner while one of the neighbouring FULL chunks is still
     * being promoted.
     */
    public boolean arePlayerPhysicsChunksReady(ServerLevel level, ServerPlayer player) {
        var chunkMap = level.getChunkSource().chunkMap;
        var visible = ((dev.pandor.regionium.mixins.ChunkMapRegioniumVisibleAccessorMixin)
            (Object) chunkMap).regionium$getVisibleChunkMap();

        int cx = player.chunkPosition().x();
        int cz = player.chunkPosition().z();

        for (int dz = -1; dz <= 1; ++dz) {
            for (int dx = -1; dx <= 1; ++dx) {
                long key = net.minecraft.world.level.ChunkPos.pack(cx + dx, cz + dz);
                var holder = visible.get(key);
                if (holder == null
                    || holder.getChunkIfPresent(net.minecraft.world.level.chunk.status.ChunkStatus.FULL) == null) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Advances the world's non-redstone game clock on the global server tick.
     * Folia keeps this clock separate from each region's redstone/game tick
     * time; ServerLevel.tickTime() must therefore not update LevelData.gameTime
     * from a region thread.
     */
    public void tickGlobalTime(ServerLevel level) {
        if (((dev.pandor.regionium.mixins.ServerLevelRegioniumTimeAccessor) (Object) level)
            .regionium$isTickTime()) {
            var data = (net.minecraft.world.level.storage.ServerLevelData) level.getLevelData();
            data.setGameTime(data.getGameTime() + 1L);
        }
    }

    public void setGameTestDriven(boolean enabled) {
        boolean previous = gameTestDriven.getAndSet(enabled);
        if (enabled && !previous) {
            // GameTestServer advances the authoritative tick counter without
            // wall-clock pacing. Any normal 20 TPS region timers must be
            // stopped before GameTest starts driving the same regions, or the
            // simulation would receive fewer/multiple region ticks per
            // GameTest tick.
            for (RegioniumRegion region : regionizer.allRegions()) {
                region.stopScheduledTickLoop();
            }
        } else if (!enabled && previous) {
            for (RegioniumRegion region : regionizer.allRegions()) {
                region.startTickLoop();
            }
        }
    }

    public boolean isGameTestDriven() {
        return gameTestDriven.get();
    }

    /**
     * GameTestServer deliberately does not sleep between server ticks. Its
     * server tick is therefore the authoritative clock, rather than wall-clock
     * time. Drive the same region tick body on worker threads once per GameTest
     * tick and wait only for those region jobs before GameTest advances. Normal
     * servers never enter this path and retain fully independent scheduling.
     */
    public void driveGameTestRegions() {
        List<RegioniumRegion> active = new ArrayList<>(regionizer.activeRegions());
        if (active.isEmpty()) {
            return;
        }

        List<Future<?>> futures = new ArrayList<>(active.size());
        for (RegioniumRegion region : active) {
            futures.add(workers.submit(region::runTickForGameTest));
        }

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while driving GameTest regions", interrupted);
            } catch (ExecutionException execution) {
                throw new IllegalStateException("Region tick failed while driving GameTest", execution.getCause());
            }
        }
    }

    public static long currentDebugTick() {
        return Regionium.scheduler().currentTick();
    }

    static void debugWorkerStarted() {
        int active = DEBUG_ACTIVE_WORKERS.incrementAndGet();
        DEBUG_MAX_WORKERS.accumulateAndGet(active, Math::max);
    }

    static void debugWorkerFinished() {
        DEBUG_ACTIVE_WORKERS.decrementAndGet();
    }

    public long currentTick() {
        return tick;
    }

    /**
     * Number of worker threads currently created by the shared scheduler.
     * Regions are scheduling units and are not permanently assigned to workers.
     */
    public int workerCount() {
        return workers.getCorePoolSize();
    }

    public void finishServerTick() {
        // Intentionally no barrier. Region ticks are independent.
    }

    public void finishRegionalPhase(ServerLevel level, String phase) {
        // Compatibility hook for old mixins. A phase belongs to the current
        // region and is completed synchronously by that region.
    }

    public void refreshChunkLeases(ServerLevel level) {
        // ChunkHolder promotion is the lifecycle event which makes a
        // LevelChunk available for simulation. Reconcile region ownership only
        // after ChunkMap has processed that promotion; otherwise a region can
        // own a chunk before its LevelTicks containers exist.
        level.getChunkSource().tick(() -> true, false);
        prepareChunkExecution(level);
    }

    public synchronized void registerLevel(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        if (!knownLevels.add(level)) {
            return;
        }

        levelRegions.put(level, List.of());
        regionizer.registerLevel(level);

        // Folia does not start a permanent tick loop for every possible region.
        // A region becomes schedulable only after the regionizer has acquired
        // actual chunk lifecycle state and marked that region active.

        Regionium.LOGGER.trace(
            "Registered level {}; spatial regions are created dynamically by the regionizer",
            level.dimension().identifier()
        );
    }

    private RegioniumRegion createRegion(ServerLevel level) {
        RegioniumRegion region = new RegioniumRegion(
            NEXT_REGION_ID.getAndIncrement(),
            level,
            workers
        );
        region.setTickBody(() -> tickRegion(region));
        return region;
    }

    public RegioniumWorldData worldData(ServerLevel level) {
        RegioniumRegion current = RegioniumContext.currentRegion();
        if (current == null || current.level() != level) {
            throw new IllegalStateException(
                "No current Regionium region for " + level.dimension().identifier()
            );
        }
        return current.worldData();
    }

    public ServerLevel levelForVanillaTicks(Object ticks) {
        RegioniumRegion current = RegioniumContext.currentRegion();
        if (current != null) {
            return current.level();
        }

        synchronized (levelRegions) {
            for (Map.Entry<ServerLevel, List<RegioniumRegion>> entry : levelRegions.entrySet()) {
                for (RegioniumRegion region : entry.getValue()) {
                    if (ticks == region.worldData().blockTicks()
                        || ticks == region.worldData().fluidTicks()) {
                        return entry.getKey();
                    }
                }
            }
        }

        return null;
    }

    public boolean routeScheduledTick(
        ServerLevel level,
        net.minecraft.world.ticks.ScheduledTick<?> tick
    ) {
        RegioniumRegion region = regionizer.owner(level, ChunkPos.pack(tick.pos()));
        if (region == null) {
            return false;
        }

        if (RegioniumContext.currentRegion() == region) {
            return false;
        }

        long now = level.getLevelData().getGameTime();
        long delay = Math.max(0L, tick.triggerTick() - now);
        long trigger = region.worldData().redstoneTime() + delay;
        long order = region.worldData().nextSubTickOrder();

        if (tick.type() instanceof net.minecraft.world.level.block.Block block) {
            region.worldData().blockTicks().schedule(
                new net.minecraft.world.ticks.ScheduledTick<>(
                    block, tick.pos(), trigger, tick.priority(), order
                )
            );
            return true;
        }

        if (tick.type() instanceof net.minecraft.world.level.material.Fluid fluid) {
            region.worldData().fluidTicks().schedule(
                new net.minecraft.world.ticks.ScheduledTick<>(
                    fluid, tick.pos(), trigger, tick.priority(), order
                )
            );
            return true;
        }

        return false;
    }

    public void trackEntity(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }

        RegioniumRegion region = regionizer.owner(level, entity.chunkPosition().pack());
        if (region == null) {
            return;
        }

        region.worldData().add(entity);
        if (entity instanceof ServerPlayer player) {
            ((ServerPlayerRegioniumPacketAccess) (Object) player)
                .regionium$updateRegion(region.worldData());
            queueGlobalPlayerMove(player);
        }
    }

    public void untrackEntity(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }

        for (RegioniumRegion region : regionizer.regions(level)) {
            region.worldData().remove(entity);
        }
    }

    public void refreshEntityRegion(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        regionizer.migrateEntity(level, entity);
    }

    public Set<Entity> entitiesForCurrentRegion(ServerLevel level) {
        return Set.copyOf(worldData(level).entities());
    }

    public void prepareChunkExecution(ServerLevel level) {
        registerLevel(level);
        regionizer.rebalance(level);
    }

    public void parallelTickChunks(
        ServerLevel level,
        Consumer<LevelChunk> vanillaTick
    ) {
        RegioniumRegion current = RegioniumContext.requireRegionThread();
        for (LevelChunk chunk : List.copyOf(current.worldData().tickingChunks())) {
            if (regionizer.owner(level, chunk.getPos().pack()) != current) {
                continue;
            }
            vanillaTick.accept(chunk);
        }
    }

    public void beginChunkExecution(ServerLevel level, long chunkPos) {
        activeChunkExecutions.add(new ChunkExecutionKey(level, chunkPos));
    }

    public void endChunkExecution(ServerLevel level, long chunkPos) {
        activeChunkExecutions.remove(new ChunkExecutionKey(level, chunkPos));
    }

    public boolean isChunkExecutionActive(ServerLevel level, long chunkPos) {
        return activeChunkExecutions.contains(new ChunkExecutionKey(level, chunkPos));
    }

    public void deferScheduledTick(Object levelTicks, net.minecraft.world.ticks.ScheduledTick<?> tick) {
        ServerLevel level = levelForVanillaTicks(levelTicks);
        if (level != null) {
            routeScheduledTick(level, tick);
        }
    }

    public void flushScheduledTickWrites(Object levelTicks) {
        // Region-local LevelTicks writes are immediately owned by the region.
    }

    public <T> void tickScheduledTicksRegionally(
        ServerLevel level,
        LevelTicks<T> ticks,
        int maxTicks,
        BiConsumer<net.minecraft.core.BlockPos, T> callback
    ) {
        RegioniumRegion current = RegioniumContext.requireRegionThread();
        if (current.level() != level) {
            throw new IllegalStateException("Region/world mismatch");
        }


        LevelTicks<T> regionTicks;
        var accessor = (dev.pandor.regionium.mixins.ServerLevelRegioniumTicksAccessorMixin) (Object) level;
        if (ticks == accessor.regionium$getVanillaBlockTicks()) {
            @SuppressWarnings("unchecked")
            LevelTicks<T> selected = (LevelTicks<T>) current.worldData().blockTicks();
            regionTicks = selected;
        } else if (ticks == accessor.regionium$getVanillaFluidTicks()) {
            @SuppressWarnings("unchecked")
            LevelTicks<T> selected = (LevelTicks<T>) current.worldData().fluidTicks();
            regionTicks = selected;
        } else {
            throw new IllegalStateException("Unknown ServerLevel tick queue: " + ticks);
        }

        regionTicks.tick(
            current.worldData().redstoneTime(),
            maxTicks,
            callback
        );

    }

    public void dispatchScheduledTick(
        ServerLevel level,
        net.minecraft.core.BlockPos pos,
        Runnable action
    ) {
        RegioniumRegion region = regionizer.owner(level, ChunkPos.pack(pos));
        if (region == null) {
            return;
        }

        if (RegioniumContext.currentRegion() == region) {
            action.run();
        } else {
            region.execute(action);
        }
    }

    public void deferBlockEvent(ServerLevel level, BlockEventData event) {
        RegioniumRegion current = RegioniumContext.currentRegion();
        RegioniumRegion target = regionizer.owner(level, ChunkPos.pack(event.pos()));

        if (current == target && current != null) {
            current.worldData().pushBlockEvent(event);
            return;
        }

        if (target != null) {
            target.execute(() -> target.worldData().pushBlockEvent(event));
        }
    }

    public void dispatchBlockEventsForRegion(ServerLevel level, RegioniumRegion region) {
        RegioniumWorldData data = region.worldData();
        if (RegioniumContext.currentRegion() != region) {
            return;
        }

        for (BlockEventData event : List.copyOf(data.blockEvents())) {
            var state = level.getBlockState(event.pos());
            if (state.is(event.block())
                && state.triggerEvent(
                    level,
                    event.pos(),
                    event.paramA(),
                    event.paramB()
                )) {
                level.getServer().getPlayerList().broadcast(
                    null,
                    event.pos().getX(),
                    event.pos().getY(),
                    event.pos().getZ(),
                    64.0,
                    level.dimension(),
                    new ClientboundBlockEventPacket(
                        event.pos(),
                        event.block(),
                        event.paramA(),
                        event.paramB()
                    )
                );
            }
            data.blockEvents().remove(event);
        }
    }

    public void parallelTickEntities(
        ServerLevel level,
        net.minecraft.world.level.entity.EntityTickList entityTickList,
        Consumer<Entity> vanillaTick,
        Object ignoredOriginal
    ) {
        RegioniumRegion current = RegioniumContext.requireRegionThread();
        for (Entity entity : List.copyOf(current.worldData().entities())) {
            if (!entity.isRemoved() && entity.level() == level) {
                vanillaTick.accept(entity);
            }
        }
    }

    public void parallelTickBlockEntities(ServerLevel level) {
        RegioniumRegion current = RegioniumContext.requireRegionThread();
        RegioniumWorldData data = current.worldData();

        data.pushPendingBlockEntityTickers();
        if (!level.tickRateManager().runsNormally()) {
            return;
        }

        data.setTickingBlockEntities(true);
        try {
            for (var ticker : List.copyOf(data.blockEntityTickers())) {
                if (ticker.isRemoved()) {
                    continue;
                }

                // Block-entity state is part of the chunk save snapshot. The
                // save path uses the same LevelChunk monitor, so ticking must
                // hold it for the complete block-entity mutation as well.
                var tickerPos = ticker.getPos();
                var chunk = data.chunks().stream()
                    .filter(candidate -> candidate.getPos().equals(net.minecraft.world.level.ChunkPos.containing(tickerPos)))
                    .findFirst()
                    .orElse(null);
                if (chunk == null) {
                    continue;
                }

                synchronized (chunk) {
                    if (!ticker.isRemoved()) {
                        ticker.tick();
                    }
                }
            }
        } finally {
            data.setTickingBlockEntities(false);
        }
    }

    public boolean isRunning() {
        return running.get() && !closed.get();
    }

    public void execute(int regionId, Runnable action) {
        region(regionId).execute(action);
    }

    public RegioniumRegion ownerOf(Object object) {
        if (object instanceof Entity entity
            && entity.level() instanceof ServerLevel level) {
            return regionizer.owner(level, entity.chunkPosition().pack());
        }

        RegioniumRegion current = RegioniumContext.currentRegion();
        if (current != null && object == current.level()) {
            return current;
        }

        return null;
    }

    public void executeEntity(Object object, Runnable action) {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(action, "action");

        RegioniumRegion owner = ownerOf(object);
        if (owner == null) {
            throw new IllegalStateException("Object has no Regionium region: " + object);
        }

        owner.execute(() -> {
            if (ownerOf(object) == owner) {
                action.run();
            }
        });
    }

    public void assign(Object object, RegioniumRegion region) {
        throw new UnsupportedOperationException(
            "Direct object ownership assignment was removed; spatial ownership is managed by RegioniumRegionizer"
        );
    }

    public void execute(
        ServerLevel level,
        net.minecraft.core.BlockPos pos,
        Runnable action
    ) {
        Objects.requireNonNull(action, "action");
        RegioniumRegion owner = regionizer.owner(level, ChunkPos.pack(pos));
        if (owner == null) {
            throw new IllegalStateException("No region owns " + pos);
        }

        owner.execute(action);
    }

    public void execute(Object object, Runnable action) {
        executeEntity(object, action);
    }

    public void requestTransfer(Object object, RegioniumRegion destination) {
        /*
         * Manual transfer is deliberately not a second ownership mechanism.
         * A player moves regions by spatial ownership, exactly like every
         * other entity. This method remains only for old command callers and
         * schedules a migration check instead of overriding the regionizer.
         */
        if (object instanceof Entity entity) {
            destination.execute(() -> refreshEntityRegion(entity));
        }
    }

    public void requestTransfer(Object object, int destinationRegionId) {
        requestTransfer(object, region(destinationRegionId));
    }

    public void applyPendingTransfers() {
        // No transfer queue exists. Topology/entity migration is performed by
        // the regionizer at ownership boundaries.
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        running.set(false);
        for (RegioniumRegion region : regionizer.allRegions()) {
            region.requestStop();
        }
        synchronized (levelRegions) {
            levelRegions.clear();
        }

        workers.shutdownNow();
    }

    private void tickRegion(RegioniumRegion region) {
        if (region.isRetired()) {
            return;
        }

        ServerLevel level = region.level();
        RegioniumContext.requireRegionThread();

        /*
         * The regionizer is the sole owner of chunks/entities. ServerLevel
         * accessors are redirected to this RegioniumWorldData by the mixins.
         */
        if (!regionizer.isActive(region)
            && region.worldData().chunks().isEmpty()
            && region.worldData().entities().isEmpty()) {
            return;
        }

        // A region may only enter the vanilla world tick once every chunk
        // that can be synchronously inspected by that tick is already FULL.
        // Folia guarantees this through its chunk-task/ticket pipeline before
        // scheduling the region tick. Regionium must establish the same
        // invariant rather than letting Level.getChunk(FULL, true) discover a
        // half-promoted neighbour from inside a worker thread.
        if (!areRegionTickChunksReady(region)) {
            return;
        }

        try {
            drainPacketsForRegion(region);
            tickConnectionsForRegion(region);

            region.worldData().setHandlingTick(true);
            level.tick(() -> true);
        } finally {
            region.worldData().setHandlingTick(false);

            for (Entity entity : List.copyOf(region.worldData().entities())) {
                if (entity.isRemoved()) {
                    region.worldData().remove(entity);
                }
            }

            for (ServerPlayer player : List.copyOf(region.worldData().players())) {
                ((ServerPlayerRegioniumPacketAccess) (Object) player)
                    .regionium$updateRegion(region.worldData());
                queueGlobalPlayerMove(player);
            }
        }
    }

    private boolean areRegionTickChunksReady(RegioniumRegion region) {
        var visible = ((dev.pandor.regionium.mixins.ChunkMapRegioniumVisibleAccessorMixin)
            (Object) region.level().getChunkSource().chunkMap)
            .regionium$getVisibleChunkMap();

        // ServerLevel.tickChunk() and entity physics may inspect immediate
        // neighbours. The vanilla/Folia chunk pipeline keeps this local
        // neighbourhood FULL before a region is scheduled.
        Set<Long> required = new LinkedHashSet<>();
        for (var chunk : region.worldData().tickingChunks()) {
            int cx = chunk.getPos().x();
            int cz = chunk.getPos().z();
            for (int dz = -1; dz <= 1; ++dz) {
                for (int dx = -1; dx <= 1; ++dx) {
                    required.add(ChunkPos.pack(cx + dx, cz + dz));
                }
            }
        }

        for (long key : required) {
            var holder = visible.get(key);
            if (holder == null
                || holder.getChunkIfPresent(net.minecraft.world.level.chunk.status.ChunkStatus.FULL) == null) {
                return false;
            }
        }

        return true;
    }

    private void drainPacketsForRegion(RegioniumRegion region) {
        for (ServerPlayer player : List.copyOf(region.worldData().players())) {
            var access = (dev.pandor.regionium.access.PacketProcessorRegioniumAccess)
                (Object) ((ServerPlayerRegioniumPacketAccess) (Object) player)
                    .regionium$getPacketProcessor();

            // Mirror Folia's ownership guard: a packet queued before a
            // player transfer must never be executed by the old region.
            if (regionizer.owner(region.level(), player.chunkPosition().pack()) != region) {
                continue;
            }

            int budget = 1024;
            while (budget-- > 0 && access.regionium$hasPackets()) {
                if (regionizer.owner(region.level(), player.chunkPosition().pack()) != region) {
                    break;
                }
                if (!access.regionium$executeSinglePacket()) {
                    break;
                }

                // Folia's regionized entity tracker observes the new entity
                // section as part of the movement path. Our vanilla
                // PersistentEntitySectionManager callback is deliberately not
                // allowed to mutate its global index from a region thread, so
                // perform the equivalent spatial ownership migration here.
                regionizer.migrateEntityAfterRegionAction(region.level(), player);
            }
        }
    }

    private void tickConnectionsForRegion(RegioniumRegion region) {
        for (ServerPlayer player : List.copyOf(region.worldData().players())) {
            /*
             * A player can cross a region boundary on the server thread
             * (teleport/portal) between two region ticks. The entity remains
             * in the old region's list until the regionizer performs the
             * ownership migration, but it must not execute its connection
             * tick there. Spatial chunk ownership is authoritative.
             */
            if (regionizer.owner(region.level(), player.chunkPosition().pack()) != region) {
                continue;
            }
            if (!arePlayerPhysicsChunksReady(region.level(), player)) {
                continue;
            }
            /*
             * PlayerList registers the entity before the PLAY connection has
             * necessarily been installed on ServerPlayer. Folia keeps the
             * network Connection itself in regionized world data and only
             * ticks connections which have crossed the PLAY lifecycle
             * boundary. Do the same here instead of treating the temporary
             * null ServerPlayer.connection as a region tick failure.
             */
            if (player.connection == null) {
                continue;
            }
            player.connection.tick();
            queueGlobalPlayerMove(player);
        }
    }
}
