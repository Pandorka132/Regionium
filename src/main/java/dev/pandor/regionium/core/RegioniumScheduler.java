package dev.pandor.regionium.core;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.pandor.regionium.Regionium;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.SimulationChunkTracker;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.PacketListener;
import dev.pandor.regionium.access.PacketProcessorRegioniumAccess;
import net.minecraft.network.PacketProcessor;
import dev.pandor.regionium.access.ServerPlayerRegioniumPacketAccess;
import dev.pandor.regionium.mixins.DistanceManagerRegioniumAccessorMixin;
import dev.pandor.regionium.mixins.SimulationChunkTrackerRegioniumAccessorMixin;
import dev.pandor.regionium.mixins.ChunkMapRegioniumInvokerMixin;

import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Coordinates Regionium worker threads and tick boundaries.
 *
 * <p>This is deliberately independent of Minecraft's World implementation.
 * The scheduler provides the concurrency primitive first; Minecraft systems
 * are integrated into it in separate, narrowly scoped Mixins.</p>
 */
public final class RegioniumScheduler implements AutoCloseable {
    static int processors = Runtime.getRuntime().availableProcessors();
    public static final int DEFAULT_REGION_COUNT = Math.clamp(processors, 1, 16);

    private final List<RegioniumRegion> regions;
    private final ForkJoinPool workers;
    private final RegioniumOwnership ownership = new RegioniumOwnership();
    private final RegioniumChunkLeaseManager chunkLeases = new RegioniumChunkLeaseManager();
    private final RegioniumRegionizer regionizer;
    private final RegioniumEntityTracker entityTracker = new RegioniumEntityTracker();
    private final PacketProcessor globalPacketProcessor = new PacketProcessor(null);
    private final Object tickLock = new Object();
    private final Map<Object, RegioniumRegion> pendingTransfers = new IdentityHashMap<>();
    private final Map<ServerLevel, List<BlockEventData>> deferredBlockEvents = new IdentityHashMap<>();
    private final Map<Object, List<net.minecraft.world.ticks.ScheduledTick<?>>> deferredScheduledTicks = new IdentityHashMap<>();
    /**
     * Chunks already handed to a region worker whose callback has not finished.
     * ChunkMap may try to unload chunks immediately after tickChunks(), so an
     * in-flight regional tick must keep its live LevelChunk valid.
     */
    private final Set<ChunkExecutionKey> activeChunkExecutions = ConcurrentHashMap.newKeySet();
    private final Set<ServerLevel> knownLevels = ConcurrentHashMap.newKeySet();
    private final Set<ServerLevel> initializedRegionizers = ConcurrentHashMap.newKeySet();
    private final Map<ServerLevel, RegioniumWorldData> worldData = new ConcurrentHashMap<>();
    private final Map<ServerLevel, Long> scheduledTickDispatchTime = new ConcurrentHashMap<>();
    private final Map<ServerLevel, Object> scheduledTickDispatchLocks = new ConcurrentHashMap<>();
    private final Map<Object, ServerLevel> vanillaTickOwners = new java.util.IdentityHashMap<>();
    private final Map<ServerPlayer, Long> playerOwnershipChunks = new java.util.IdentityHashMap<>();
    private record ChunkExecutionKey(ServerLevel level, long pos) {}

    private volatile boolean running;
    private volatile boolean closed;
    private long tick;
    private static volatile long debugTick;
    private static final AtomicInteger debugActiveWorkers = new AtomicInteger();
    private static final AtomicInteger debugMaxConcurrentWorkers = new AtomicInteger();
    private final AtomicBoolean tickInProgress = new AtomicBoolean();

    public RegioniumScheduler() {
        this(DEFAULT_REGION_COUNT);
    }

    public RegioniumScheduler(int regionCount) {
        if (regionCount < 1) {
            throw new IllegalArgumentException("regionCount must be at least 1");
        }

        this.regions = new ArrayList<>(regionCount);
        this.workers = new ForkJoinPool(
            regionCount,
            pool -> {
                ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                thread.setName("Regionium-ForkJoin-" + thread.getPoolIndex());
                thread.setDaemon(true);
                thread.setUncaughtExceptionHandler((t, error) ->
                    Regionium.LOGGER.error("Regionium worker {} failed", t.getName(), error)
                );
                return thread;
            },
            (thread, error) -> Regionium.LOGGER.error("Regionium worker {} failed", thread == null ? "<unknown>" : thread.getName(), error),
            true
        );
        for (int i = 0; i < regionCount; i++) {
            regions.add(new RegioniumRegion(i, workers));
        }
        this.regionizer = new RegioniumRegionizer(regions);
        for (RegioniumRegion region : regions) {
            region.setTickBody(() -> tickRegionBody(region));
            region.startTickLoop();
        }
        running = true;
    }

    public List<RegioniumRegion> regions() {
        return List.copyOf(regions);
    }

    public RegioniumRegion region(int id) {
        return regions.get(id);
    }

    public RegioniumOwnership ownership() {
        return ownership;
    }

    public RegioniumChunkLeaseManager chunkLeases() {
        return chunkLeases;
    }

    public RegioniumRegionizer regionizer() {
        return regionizer;
    }

    public RegioniumEntityTracker entityTracker() {
        return entityTracker;
    }

    public <T extends PacketListener> void scheduleGlobalPacket(T listener, net.minecraft.network.protocol.Packet<T> packet) {
        globalPacketProcessor.scheduleIfPossible(listener, packet);
    }

    public void drainGlobalPackets() {
        dev.pandor.regionium.access.PacketProcessorRegioniumAccess access = (dev.pandor.regionium.access.PacketProcessorRegioniumAccess) (Object) globalPacketProcessor;
        while (access.regionium$executeSinglePacket()) {
            // Global login/configuration packets are intentionally drained here.
        }
    }

    /** Packet arrival is already lock-free; the region loop is independently clocked. */
    public void notifyRegionPackets(RegioniumWorldData region) {
        // The next regional tick drains the player's queue. No global wakeup/barrier is used.
    }

    /**
     * Folia-style ownership check: being on a region worker is not enough;
     * the current region must actually own the object being touched.
     */
    public boolean isOwnedByCurrentRegion(Object object) {
        RegioniumRegion current = RegioniumContext.currentRegion();
        if (current == null) {
            return false;
        }
        RegioniumRegion dynamicOwner = ownerOf(object);
        return dynamicOwner == current;
    }

    /**
     * Folia-style world ownership check for block/redstone access.
     * The global/server thread is allowed through; region workers may only
     * synchronously access their currently leased chunk.
     */
    public boolean isCurrentRegionFor(ServerLevel level, net.minecraft.core.BlockPos pos) {
        RegioniumRegion current = RegioniumContext.currentRegion();
        if (current == null) {
            return true;
        }
        RegioniumRegion owner = chunkLeases.owner(
            level,
            net.minecraft.world.level.ChunkPos.pack(pos)
        );
        return owner == current;
    }


    /**
     * Opens the server-thread collection window for one vanilla server tick.
     *
     * <p>This counter is diagnostic and provides a batch identifier. It is not
     * a regional clock, barrier, or release mechanism.</p>
     */
    public void beginServerTick() {
        synchronized (tickLock) {
            tick++;
            debugTick = tick;
            tickInProgress.set(true);
        }
        drainGlobalPackets();
    }
    public static long currentDebugTick() {
        return debugTick;
    }

    static void debugWorkerStarted() {
        int active = debugActiveWorkers.incrementAndGet();
        debugMaxConcurrentWorkers.accumulateAndGet(active, Math::max);
    }

    static void debugWorkerFinished() {
        debugActiveWorkers.decrementAndGet();
    }

    public long currentTick() {
        synchronized (tickLock) {
            return tick;
        }
    }

    /**
     * Closes the global collection window. Regional tick timing is owned by
     * each RegioniumRegion; this method only publishes the completed input
     * batch for the next independent regional ticks.
     */
    public void finishServerTick() {
        // Compatibility hook only. Regional ticking is independent of the
        // global Minecraft server tick and has no publish/barrier step here.
        tickInProgress.set(false);
    }

    /**
     * Records the end of one vanilla tick phase.
     *
     * <p>This method deliberately does NOT submit work. Phase callbacks are
     * collected into the region's current server-tick batch. That batch is
     * published only after all worlds have finished their vanilla collection
     * pass.</p>
     */
    public void finishRegionalPhase(ServerLevel level, String phase) {
        Regionium.LOGGER.debug(
            "[MT] tick={} level={} phase={} collected (not released) fjp-active={} fjp-queued={}",
            currentTick(), level.dimension().identifier(), phase,
            workers.getActiveThreadCount(), workers.getQueuedTaskCount()
        );
    }

    /** Refreshes vanilla-derived simulation leases after a world tick. */
    public void refreshChunkLeases(ServerLevel level) {
        chunkLeases.refresh(level, this);
    }

    /** Refreshes leases and queues player migrations before a world tick. */
    public void registerLevel(ServerLevel level) {
        level = Objects.requireNonNull(level, "level");
        knownLevels.add(level);
        var visibleAtRegistration = ((dev.pandor.regionium.mixins.ChunkMapRegioniumVisibleAccessorMixin)
            level.getChunkSource().chunkMap).regionium$getVisibleChunkMap();
        boolean needsInitialRegionizer = !visibleAtRegistration.isEmpty()
            && (initializedRegionizers.add(level)
                || regionizer.owner(level, visibleAtRegistration.keySet().iterator().nextLong()) == null);
        if (needsInitialRegionizer) {
            // Folia's regionizer is not rebuilt from the global 20 TPS loop.
            // It is initialized when chunk ownership first exists, then its
            // topology changes through chunk lifecycle / merge / split logic.
            regionizer.rebalance(level);
        }
        RegioniumWorldData data = worldData.computeIfAbsent(level, RegioniumWorldData::new);
        synchronized (vanillaTickOwners) {
            vanillaTickOwners.put(level.getBlockTicks(), level);
            vanillaTickOwners.put(level.getFluidTicks(), level);
        }

        // Player-region membership is a server-thread boundary operation.
        // Never rebalance from a region worker through worldData().
        Set<ServerPlayer> livePlayers = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        livePlayers.addAll(level.players());
        for (RegioniumRegion region : regions) {
            data.clearPlayers(region);
        }
        for (ServerPlayer player : livePlayers) {
            RegioniumRegion owner = regionizer.regionFor(player);
            if (owner != null) {
                long chunk = player.chunkPosition().pack();
                Long previousChunk = playerOwnershipChunks.get(player);
                RegioniumRegion explicitOwner = ownership.ownerOf(player);

                // A manual /regionium transfer is an execution-owner override.
                // Keep it while the player remains in the same chunk; once the
                // player actually moves, normal Folia-style chunk ownership wins.
                if (previousChunk == null) {
                    playerOwnershipChunks.put(player, chunk);
                    if (explicitOwner == null) {
                        ownership.assign(player, owner);
                    }
                } else if (previousChunk.longValue() != chunk) {
                    playerOwnershipChunks.put(player, chunk);
                    if (explicitOwner != null && explicitOwner != owner) {
                        ownership.transfer(player, owner);
                    }
                }

                RegioniumRegion executionOwner = ownership.ownerOf(player);
                if (executionOwner == null) {
                    executionOwner = owner;
                    ownership.assign(player, executionOwner);
                }
                data.add(player, executionOwner);
                ((ServerPlayerRegioniumPacketAccess) (Object) player).regionium$updateRegion(data);
            }
        }

        // Rebuild the region-local chunk/tick containers from the same
        // visible-chunk snapshot used by the Folia-style regionizer.
        var visible = ((dev.pandor.regionium.mixins.ChunkMapRegioniumVisibleAccessorMixin)
            level.getChunkSource().chunkMap).regionium$getVisibleChunkMap();
        for (var entry : visible.long2ObjectEntrySet()) {
            long packed = entry.getLongKey();
            RegioniumRegion owner = regionizer.owner(level, packed);
            if (owner == null) continue;
            var chunk = entry.getValue().getTickingChunk();
            if (chunk != null) {
                data.addChunk(chunk, owner);
                data.tickingChunks(owner).add(chunk);
                data.entityTickingChunks(owner).add(chunk);
            }
        }
    }

    public RegioniumWorldData worldData(ServerLevel level) {
        return worldData.computeIfAbsent(level, RegioniumWorldData::new);
    }

    public ServerLevel levelForVanillaTicks(Object ticks) {
        synchronized (vanillaTickOwners) {
            return vanillaTickOwners.get(ticks);
        }
    }

    /** Routes a vanilla scheduled tick into the Folia-style region queue. */
    public boolean routeScheduledTick(ServerLevel level, net.minecraft.world.ticks.ScheduledTick<?> tick) {
        RegioniumRegion region = chunkLeases.owner(level, net.minecraft.world.level.ChunkPos.pack(tick.pos()));
        if (region == null) {
            region = regionizer.owner(level, net.minecraft.world.level.ChunkPos.pack(tick.pos()));
        }
        if (region == null) {
            return false;
        }

        RegioniumWorldData data = worldData(level);
        long globalNow = level.getLevelData().getGameTime();
        long delay = Math.max(0L, tick.triggerTick() - globalNow);
        long trigger = data.redstoneTime(region) + delay;
        long order = data.nextSubTickOrder(region);
        if (tick.type() instanceof net.minecraft.world.level.block.Block block) {
            @SuppressWarnings("unchecked")
            var target = new net.minecraft.world.ticks.ScheduledTick<>(
                (net.minecraft.world.level.block.Block) tick.type(), tick.pos(), trigger, tick.priority(), order
            );
            data.blockTicks(region).schedule(target);
            return true;
        }
        if (tick.type() instanceof net.minecraft.world.level.material.Fluid fluid) {
            @SuppressWarnings("unchecked")
            var target = new net.minecraft.world.ticks.ScheduledTick<>(
                (net.minecraft.world.level.material.Fluid) tick.type(), tick.pos(), trigger, tick.priority(), order
            );
            data.fluidTicks(region).schedule(target);
            return true;
        }
        return false;
    }

    /**
     * Updates region-local entity membership. The entity itself remains in the
     * shared vanilla object graph; only the execution index moves.
     */
    public void trackEntity(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        RegioniumRegion target = chunkLeases.owner(level, entity.chunkPosition().pack());
        if (target == null) {
            target = ownerOf(level);
        }
        if (target != null) {
            worldData(level).add(entity, target);
            entityTracker.track(entity, target);
        }
    }

    public void untrackEntity(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        RegioniumWorldData data = worldData(level);
        entityTracker.untrack(entity);
        for (RegioniumRegion region : regions) {
            data.remove(entity, region);
        }
    }

    public void refreshEntityRegion(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        RegioniumWorldData data = worldData(level);
        RegioniumRegion target = chunkLeases.owner(level, entity.chunkPosition().pack());
        if (target == null) {
            target = ownerOf(level);
        }
        if (target == null) {
            return;
        }
        for (RegioniumRegion region : regions) {
            if (region == target) {
                data.add(entity, region);
                entityTracker.move(entity, region);
            } else {
                data.remove(entity, region);
            }
        }
    }

    public Set<Entity> entitiesForCurrentRegion(ServerLevel level) {
        RegioniumRegion current = RegioniumContext.currentRegion();
        return current == null ? Set.of() : worldData(level).entities(current);
    }

    public void prepareChunkExecution(ServerLevel level) {
        knownLevels.add(level);
        Regionium.LOGGER.debug("[TRANSFER] prepare START tick={} level={} thread={}", currentTick(), level.dimension().identifier(), Thread.currentThread().getName());
        chunkLeases.refresh(level, this);
        chunkLeases.resolveConflicts(level, this);
        Regionium.LOGGER.debug("[TRANSFER] applying pending transfers tick={} count={}", currentTick(), pendingTransfers.size());
        applyPendingTransfers();
        chunkLeases.refresh(level, this);
        Regionium.LOGGER.debug("[TRANSFER] prepare END tick={} level={}", currentTick(), level.dimension().identifier());
    }

    /**
     * Dispatches the block-ticking chunk phase to the region that owns each
     * simulation chunk. The server thread only enumerates vanilla's current
     * simulation set; actual chunk mutation happens on the region worker.
     */
    public void parallelTickChunks(ServerLevel level, Consumer<LevelChunk> vanillaTick) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(vanillaTick, "vanillaTick");

        final long debugTick = currentTick();
        DistanceManager distanceManager = level.getChunkSource().chunkMap.getDistanceManager();
        SimulationChunkTracker tracker =
            ((DistanceManagerRegioniumAccessorMixin) distanceManager).regionium$getSimulationChunkTracker();
        var simulationChunks =
            ((SimulationChunkTrackerRegioniumAccessorMixin) tracker).regionium$getChunks();

        Map<RegioniumRegion, List<LevelChunk>> byRegion = new IdentityHashMap<>();

        for (var entry : simulationChunks.long2ByteEntrySet()) {
            long packed = entry.getLongKey();
            if (!ChunkLevel.isBlockTicking(entry.getByteValue())) {
                continue;
            }

            var holder = ((ChunkMapRegioniumInvokerMixin) level.getChunkSource().chunkMap)
                .regionium$getVisibleChunkIfPresent(packed);
            LevelChunk chunk = holder == null ? null : holder.getTickingChunk();
            if (chunk == null) {
                continue;
            }

            RegioniumRegion target = chunkLeases.owner(level, packed);
            if (target == null) {
                target = ownerOf(level);
            }
            if (target == null) {
                continue;
            }

            byRegion.computeIfAbsent(target, ignored -> new ArrayList<>()).add(chunk);
        }

        int collected = 0;
        int submitted = 0;
        for (Map.Entry<RegioniumRegion, List<LevelChunk>> entry : byRegion.entrySet()) {
            RegioniumRegion region = entry.getKey();
            List<LevelChunk> chunks = entry.getValue();
            collected += chunks.size();
            if (region.enqueueCoalescedRegionTick(() -> {
                // Folia-style: the region, not each chunk, is the scheduling unit.
                for (LevelChunk regionChunk : chunks) {
                    long regionChunkPos = regionChunk.getPos().pack();
                    beginChunkExecution(level, regionChunkPos);
                    try {
                        runChunkLocked(level, regionChunk, () -> vanillaTick.accept(regionChunk));
                    } finally {
                        endChunkExecution(level, regionChunkPos);
                    }
                }
            })) {
                submitted++;
            }
        }

        Regionium.LOGGER.debug(
            "[MT] tick={} level={} chunk phase collected={} submitted={} regions={}",
            debugTick, level.dimension().identifier(), collected, submitted, regions.size()
        );
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

    /**
     * Dispatches vanilla scheduled block/fluid tick callbacks by chunk. The
     * LevelTicks data structure itself remains on the server thread; only the
     * actual callback that mutates the world is moved to the owning worker.
     */
    public void deferScheduledTick(Object levelTicks, net.minecraft.world.ticks.ScheduledTick<?> tick) {
        synchronized (tickLock) {
            deferredScheduledTicks
                .computeIfAbsent(levelTicks, ignored -> new ArrayList<>())
                .add(tick);
        }
    }

    public void flushScheduledTickWrites(Object levelTicks) {
        synchronized (tickLock) {
            List<net.minecraft.world.ticks.ScheduledTick<?>> deferred = deferredScheduledTicks.remove(levelTicks);
            if (deferred == null || deferred.isEmpty()) {
                return;
            }
            @SuppressWarnings({"rawtypes", "unchecked"})
            net.minecraft.world.ticks.LevelTicks raw = (net.minecraft.world.ticks.LevelTicks) levelTicks;
            for (net.minecraft.world.ticks.ScheduledTick<?> tick : deferred) {
                raw.schedule(tick);
            }
        }
    }

    /**
     * Drains a LevelTicks container at most once for a given global game-time
     * value, while executing each callback on the region owning its chunk.
     * This keeps the shared LevelTicks structure serialized without making
     * the actual block/fluid callback run on the wrong region.
     */
    /**
     * Ticks the current region's private LevelTicks queue.
     *
     * Folia does not drain one shared ServerLevel queue and then fan callbacks
     * out to workers. The queue itself is part of RegionizedWorldData. The
     * server thread therefore never calls LevelTicks.tick() for simulation.
     */
    public <T> void tickScheduledTicksRegionally(
        ServerLevel level,
        net.minecraft.world.ticks.LevelTicks<T> vanillaTicks,
        int maxTicks,
        java.util.function.BiConsumer<net.minecraft.core.BlockPos, T> callback
    ) {
        RegioniumRegion region = RegioniumContext.requireRegionThread();
        RegioniumWorldData data = worldData(level);
        long regionTime = data.redstoneTime(region);

        net.minecraft.world.ticks.LevelTicks<T> local;
        boolean isBlockQueue = vanillaTicks == ((dev.pandor.regionium.mixins.ServerLevelRegioniumAccessorMixin) level).regionium$getBlockTicks();
        if (isBlockQueue) {
            @SuppressWarnings("unchecked")
            net.minecraft.world.ticks.LevelTicks<T> cast = (net.minecraft.world.ticks.LevelTicks<T>) data.blockTicks(region);
            local = cast;
        } else {
            @SuppressWarnings("unchecked")
            net.minecraft.world.ticks.LevelTicks<T> cast = (net.minecraft.world.ticks.LevelTicks<T>) data.fluidTicks(region);
            local = cast;
        }

        local.tick(regionTime, maxTicks, (pos, type) -> {
            callback.accept(pos, type);
        });
    }

    public void dispatchScheduledTick(ServerLevel level, net.minecraft.core.BlockPos pos, Runnable action) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(action, "action");

        RegioniumRegion target = chunkLeases.owner(level, net.minecraft.world.level.ChunkPos.pack(pos));
        if (target == null) {
            target = ownerOf(level);
        }
        if (target == null) {
            return;
        }

        RegioniumRegion region = target;
        long debugTick = currentTick();
        if (!region.enqueueTickPhase(debugTick, () -> runChunkLocked(level, pos, action))) {
            Regionium.LOGGER.debug(
                "[MT] tick={} region={} SKIP scheduled tick at {} (previous tick still running)",
                debugTick, region.id(), pos
            );
        }
    }

    /** Queues a block event directly into the owning region, like Folia. */
    public void deferBlockEvent(ServerLevel level, BlockEventData event) {
        RegioniumRegion region = chunkLeases.owner(level, net.minecraft.world.level.ChunkPos.pack(event.pos()));
        if (region == null) {
            region = RegioniumContext.currentRegion();
        }
        if (region == null) {
            synchronized (tickLock) {
                deferredBlockEvents.computeIfAbsent(level, ignored -> new ArrayList<>()).add(event);
            }
            return;
        }
        worldData(level).deferredBlockEvents(region).add(event);
    }

    /** Executes only the current region's block-event queue. */
    public void dispatchBlockEventsForRegion(ServerLevel level, RegioniumRegion region) {
        var events = worldData(level).deferredBlockEvents(region);

        for (BlockEventData event : List.copyOf(events)) {
            long chunk = net.minecraft.world.level.ChunkPos.pack(event.pos());
            if (chunkLeases.owner(level, chunk) != region) {
                continue;
            }

            var state = level.getBlockState(event.pos());
            if (state.is(event.block()) && state.triggerEvent(level, event.pos(), event.paramA(), event.paramB())) {
                level.getServer().getPlayerList().broadcast(
                    null,
                    event.pos().getX(), event.pos().getY(), event.pos().getZ(),
                    64.0,
                    level.dimension(),
                    new ClientboundBlockEventPacket(event.pos(), event.block(), event.paramA(), event.paramB())
                );
            }
            events.remove(event);
        }
    }

    /**
     * Dispatches the entity phase by entity chunk. The original EntityTickList
     * iteration remains on the server thread, while every actual entity tick
     * is executed in its owning region. Passenger chains are intentionally
     * kept inside vanilla's callback.
     */
    public void parallelTickEntities(
        ServerLevel level,
        EntityTickList entityTickList,
        Consumer<Entity> vanillaTick,
        Operation<Void> original
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(entityTickList, "entityTickList");
        Objects.requireNonNull(vanillaTick, "vanillaTick");
        Objects.requireNonNull(original, "original");

        final long debugTick = currentTick();
        final int[] collected = {0};
        final int[] submitted = {0};

        Consumer<Entity> dispatch = entity -> {
            if (entity == null || entity.isRemoved()) {
                return;
            }

            /*
             * Entities have no independent Regionium owner. Their current
             * simulation chunk is the authority and is re-evaluated every
             * tick. This lets projectiles, minecarts, cannon shots, etc.
             * cross a region boundary without carrying the old region with
             * them.
             *
             * Do not use the vehicle/root entity here: a passenger and its
             * vehicle can cross a chunk/region boundary independently.
             */
            long entityChunk = entity.chunkPosition().pack();
            RegioniumRegion target = chunkLeases.owner(level, entityChunk);
            if (target == null) {
                // Outside the current simulation lease: vanilla would not
                // execute this entity from this simulation pass.
                return;
            }

            RegioniumRegion region = target;
            collected[0]++;
            if (region.enqueueTickPhase(debugTick, () -> runEntityLocked(level, region, entity, vanillaTick))) {
                submitted[0]++;
            }
        };

        original.call(entityTickList, dispatch);

        Regionium.LOGGER.debug(
            "[MT] tick={} level={} entity phase collected={} submitted={}",
            debugTick, level.dimension().identifier(), collected[0], submitted[0]
        );
    }

    /**
     * Dispatches block-entity ticking using the same chunk ownership model as
     * blocks and entities. The list bookkeeping stays on the server thread;
     * the ticker itself runs on the owning region worker.
     */
    public void parallelTickBlockEntities(ServerLevel level) {
        Objects.requireNonNull(level, "level");

        var access = (dev.pandor.regionium.mixins.LevelRegioniumBlockEntityAccessorMixin) level;
        var tickers = access.regionium$getBlockEntityTickers();
        var pending = access.regionium$getPendingBlockEntityTickers();
        var current = RegioniumContext.currentRegion();

        synchronized (tickers) {
            if (!pending.isEmpty()) {
                tickers.addAll(pending);
                pending.clear();
            }
        }

        if (!level.tickRateManager().runsNormally()) {
            return;
        }

        // ServerLevel.tick() is already running on the owning region thread.
        // Do not enqueue another task: that would move the block entity one
        // tick behind the rest of the world tick.
        if (current != null) {
            for (var ticker : List.copyOf(tickers)) {
                if (ticker.isRemoved() || !level.shouldTickBlocksAt(ticker.getPos())) {
                    continue;
                }
                long packed = net.minecraft.world.level.ChunkPos.pack(ticker.getPos());
                if (chunkLeases.owner(level, packed) == current) {
                    ticker.tick();
                }
            }
            return;
        }

        // Compatibility path for callers outside a region tick.
        long debugTick = currentTick();
        for (var ticker : List.copyOf(tickers)) {
            if (ticker.isRemoved() || !level.shouldTickBlocksAt(ticker.getPos())) {
                continue;
            }
            RegioniumRegion target = chunkLeases.owner(
                level, net.minecraft.world.level.ChunkPos.pack(ticker.getPos())
            );
            if (target != null) {
                target.enqueueTickPhase(debugTick, () -> ticker.tick());
            }
        }
    }

    /**
     * Runs the actual world work for one independently ticking region.
     *
     * <p>This is the important architectural split from the old model:
     * MinecraftServer's 20 TPS loop only maintains global bookkeeping. The
     * region clock owns the actual chunk/entity/block-entity tick body.</p>
     */
    /**
     * LevelTicks requires one LevelChunkTicks container for every chunk that
     * belongs to the region. Vanilla owns those containers on LevelChunk, so
     * refresh the region-local LevelTicks view immediately before simulation.
     * This is deliberately done before ServerLevel.tick(): block placement can
     * schedule a tick before the chunk-tick phase itself runs.
     */
    private void ensureRegionTickContainers(ServerLevel level, RegioniumRegion region) {
        var data = worldData(level);
        var visible = ((dev.pandor.regionium.mixins.ChunkMapRegioniumVisibleAccessorMixin)
            level.getChunkSource().chunkMap).regionium$getVisibleChunkMap();

        for (var entry : visible.long2ObjectEntrySet()) {
            long packed = entry.getLongKey();
            if (chunkLeases.owner(level, packed) != region) {
                continue;
            }

            var chunk = entry.getValue().getTickingChunk();
            if (chunk == null) {
                int cx = net.minecraft.world.level.ChunkPos.getX(packed);
                int cz = net.minecraft.world.level.ChunkPos.getZ(packed);
                chunk = level.getChunkSource().getChunkNow(cx, cz);
            }
            if (chunk != null) {
                data.addChunk(chunk, region);
            }
        }
    }

    private void tickRegionBody(RegioniumRegion region) {
        final long tick = region.tickCount();

        for (ServerLevel level : snapshotLevels()) {
            if (!chunkLeases.hasOwnedChunks(level, region)) {
                continue;
            }

            try {
                // Folia-shaped execution: the region clock owns the complete
                // ServerLevel tick. Regionium mixins constrain the phases to
                // the chunks/entities owned by the current region.
                RegioniumContext.enterWorld(worldData(level));
                try {
                    ensureRegionTickContainers(level, region);
                    drainPacketsForRegion(level, region);
                    tickConnectionsForRegion(level, region);
                    level.tick(() -> true);

                    // Vanilla normally flushes ServerChunkCache's
                    // chunkHoldersToBroadcast during its global chunk tick.
                    // Regionium deliberately prevents that global tick from
                    // consuming simulation work, so block changes would stay
                    // queued until some later interaction forced a refresh.
                    // Folia moves this queue into RegionizedWorldData and
                    // broadcasts it from the owning region.
                    broadcastChangedChunksForRegion(level, region);

                    entityTracker.tick(level, region);
                } finally {
                    RegioniumContext.exitWorld();
                }
            } catch (Throwable error) {
                Regionium.LOGGER.error(
                    "Region {} world tick failed for {} at region tick {}",
                    region.id(),
                    level.dimension().identifier(),
                    tick,
                    error
                );
            }
        }
    }

    private void broadcastChangedChunksForRegion(ServerLevel level, RegioniumRegion region) {
        var pending = worldData(level).chunkHoldersToBroadcast(region);

        for (var holder : List.copyOf(pending)) {
            var chunk = holder.getTickingChunk();
            if (chunk == null) {
                continue;
            }

            long packed = chunk.getPos().pack();
            if (chunkLeases.owner(level, packed) != region) {
                continue;
            }

            holder.broadcastChanges(chunk);
            pending.remove(holder);
        }
    }

    private void tickConnectionsForRegion(ServerLevel level, RegioniumRegion region) {
        RegioniumWorldData data = worldData(level);
        for (ServerPlayer player : List.copyOf(data.players(region))) {
            if (!isOwnedByCurrentRegion(player)) {
                continue;
            }

            Regionium.LOGGER.info(
                "[CONNECTION-TICK] player={} region={} owner={} thread={}",
                player.getGameProfile().name(),
                region,
                ownerOf(player),
                Thread.currentThread().getName()
            );
            // Folia ticks each local player's Connection from the owning region.
            player.connection.tick();
        }
    }

    private void drainPacketsForRegion(ServerLevel level, RegioniumRegion region) {
        RegioniumWorldData data = worldData(level);

        /*
         * Match Folia's region task loop: one packet per local player per
         * pass, then immediately repeat while work remains. A single pass is
         * not enough because normal Minecraft clients continuously generate
         * movement/input packets. With only one packet consumed per 50 ms,
         * the queue can grow by hundreds of packets and input appears seconds
         * behind reality.
         *
         * Folia also bounds the region task loop, so do the same here instead
         * of allowing a malicious/broken connection to monopolise a worker.
         */
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10L);

        do {
            boolean processed = false;

            for (ServerPlayer player : List.copyOf(data.players(region))) {
                if (!isOwnedByCurrentRegion(player)) {
                    continue;
                }

                dev.pandor.regionium.access.PacketProcessorRegioniumAccess access =
                    (dev.pandor.regionium.access.PacketProcessorRegioniumAccess) (Object)
                        ((ServerPlayerRegioniumPacketAccess) (Object) player).regionium$getPacketProcessor();

                if (access.regionium$hasPackets()) {
                    processed |= access.regionium$executeSinglePacket();
                }
            }

            if (!processed || System.nanoTime() >= deadline) {
                break;
            }
        } while (true);
    }

    private List<ServerLevel> snapshotLevels() {
        return List.copyOf(knownLevels);
    }

    private void tickChunksForRegion(ServerLevel level, RegioniumRegion region) {
        DistanceManager distanceManager = level.getChunkSource().chunkMap.getDistanceManager();
        SimulationChunkTracker tracker =
            ((DistanceManagerRegioniumAccessorMixin) distanceManager).regionium$getSimulationChunkTracker();
        var simulationChunks =
            ((SimulationChunkTrackerRegioniumAccessorMixin) tracker).regionium$getChunks();

        for (var entry : simulationChunks.long2ByteEntrySet()) {
            long packed = entry.getLongKey();
            if (!ChunkLevel.isBlockTicking(entry.getByteValue())) {
                continue;
            }

            var holder = ((ChunkMapRegioniumInvokerMixin) level.getChunkSource().chunkMap)
                .regionium$getVisibleChunkIfPresent(packed);
            LevelChunk chunk = holder == null ? null : holder.getTickingChunk();
            if (chunk == null || chunkLeases.owner(level, packed) != region) {
                continue;
            }

            beginChunkExecution(level, packed);
            try {
                level.tickChunk(
                    chunk,
                    level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED)
                );
            } finally {
                endChunkExecution(level, packed);
            }
        }
    }

    private void tickEntitiesForRegion(ServerLevel level, RegioniumRegion region) {
        var access = (dev.pandor.regionium.mixins.ServerLevelRegioniumAccessorMixin) level;
        EntityTickList list = access.regionium$getEntityTickList();
        list.forEach(entity -> {
            if (entity == null || entity.isRemoved()) {
                return;
            }
            long packed = entity.chunkPosition().pack();
            if (chunkLeases.owner(level, packed) != region) {
                return;
            }
            runEntityLocked(level, region, entity, level::tickNonPassenger);
        });
    }

    private void tickBlockEntitiesForRegion(ServerLevel level, RegioniumRegion region) {
        var access = (dev.pandor.regionium.mixins.LevelRegioniumBlockEntityAccessorMixin) level;
        var tickers = access.regionium$getBlockEntityTickers();
        var pending = access.regionium$getPendingBlockEntityTickers();

        List<?> snapshot;
        synchronized (tickers) {
            if (!pending.isEmpty()) {
                tickers.addAll(pending);
                pending.clear();
            }
            snapshot = List.copyOf(tickers);
        }

        if (!level.tickRateManager().runsNormally()) {
            return;
        }

        for (var tickerObject : snapshot) {
            var ticker = (net.minecraft.world.level.block.entity.TickingBlockEntity) tickerObject;
            if (ticker.isRemoved()) {
                continue;
            }
            long packed = net.minecraft.world.level.ChunkPos.pack(ticker.getPos());
            if (chunkLeases.owner(level, packed) != region) {
                continue;
            }
            runChunkLocked(level, ticker.getPos(), ticker::tick);
        }
    }

    /** Executes a world callback while holding the live chunk monitor. */
    private void runChunkLocked(ServerLevel level, net.minecraft.core.BlockPos pos, Runnable action) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            return;
        }
        action.run();
    }

    private void runChunkLocked(ServerLevel level, LevelChunk chunk, Runnable action) {
        action.run();
    }

    /**
     * Entity ticks are not owned by a Regionium region. The entity's current
     * chunk chooses the executing region, while this transient monitor prevents
     * the same entity from being ticked concurrently during a cross-region move.
     */
    private void runEntityLocked(
        ServerLevel level,
        RegioniumRegion executingRegion,
        Entity entity,
        Consumer<Entity> vanillaTick
    ) {
        synchronized (entity) {
            if (entity.isRemoved()) {
                return;
            }

            /*
             * The entity may have crossed a lease boundary after the server
             * thread collected EntityTickList but before this region's next
             * clock fired. Never let the old region tick the entity after that
             * handoff: re-resolve the current chunk at execution time.
             */
            long currentChunk = entity.chunkPosition().pack();
            RegioniumRegion currentOwner = chunkLeases.owner(level, currentChunk);
            if (currentOwner != null && currentOwner != executingRegion) {
                currentOwner.execute(() -> runEntityLocked(level, currentOwner, entity, vanillaTick));
                return;
            }

            /*
             * Do not acquire a multi-chunk lock here. Regionium regions tick
             * independently, and nested chunk monitors create a classic
             * cross-region deadlock when two entities inspect each other's
             * neighbouring chunks. Cross-region entity/block interaction must
             * be handed off or made snapshot-safe instead of synchronizing a
             * live chunk neighbourhood.
             */
            vanillaTick.accept(entity);
        }
    }

    public boolean isRunning() {
        return running;
    }

    public void execute(int regionId, Runnable action) {
        Objects.requireNonNull(action, "action");
        region(regionId).execute(action);
    }

    /**
     * Resolves an object's execution owner. Players are world-owned: packet
     * handlers may arrive on the vanilla server thread, so a player must use
     * the same region as the ServerLevel it currently inhabits instead of
     * receiving an unrelated hash-based owner.
     */
    public RegioniumRegion ownerOf(Object object) {
        if (object instanceof ServerPlayer player && player.level() instanceof ServerLevel level) {
            RegioniumRegion explicitOwner = ownership.ownerOf(player);
            if (explicitOwner != null) {
                return explicitOwner;
            }
            RegioniumRegion regionizedOwner = regionizer.regionFor(player);
            if (regionizedOwner != null) {
                return regionizedOwner;
            }
            RegioniumRegion chunkOwner = chunkLeases.owner(level, player.chunkPosition().pack());
            if (chunkOwner != null) {
                return chunkOwner;
            }
        } else if (object instanceof Entity entity && entity.level() instanceof ServerLevel level) {
            RegioniumRegion chunkOwner = chunkLeases.owner(level, entity.chunkPosition().pack());
            if (chunkOwner != null) {
                return chunkOwner;
            }
        }

        RegioniumRegion owner = ownership.ownerOf(object);
        if (owner != null) {
            return owner;
        }

        ServerLevel level = null;
        if (object instanceof ServerLevel serverLevel) {
            level = serverLevel;
        } else if (object instanceof ServerPlayer player) {
            level = (ServerLevel) player.level();
        } else if (object instanceof Entity entity && entity.level() instanceof ServerLevel serverLevel) {
            level = serverLevel;
        }

        if (level != null) {
            owner = ownership.ownerOf(level);
            if (owner == null) {
                int regionId = Math.floorMod(System.identityHashCode(level), regions.size());
                owner = regions.get(regionId);
                ownership.assign(level, owner);
            }

            if (object != level) {
                boolean wasUnownedPlayer = object instanceof ServerPlayer
                    && ownership.ownerOf(object) == null;
                ownership.assign(object, owner);

                if (wasUnownedPlayer) {
                    ServerPlayer player = (ServerPlayer) object;
                    Regionium.LOGGER.info(
                        "Player {} entered Region {} on {}",
                        player.getGameProfile().name(),
                        owner.id(),
                        owner.workerName()
                    );
                }
            }
            return owner;
        }

        return null;
    }

    /**
     * Schedules a Minecraft object on its Regionium owner, assigning an
     * initial deterministic owner when it is first seen.
     */
    public void executeEntity(Object object, Runnable action) {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(action, "action");

        RegioniumRegion owner = ownerOf(object);
        if (owner == null) {
            synchronized (tickLock) {
                if (closed) {
                    throw new IllegalStateException("Regionium scheduler is closed");
                }

                owner = ownerOf(object);
                if (owner == null) {
                    int regionId = Math.floorMod(System.identityHashCode(object), regions.size());
                    owner = regions.get(regionId);
                    ownership.assign(object, owner);
                }
            }
        }

        executeOwned(object, action);
    }

    /**
     * Registers an object with its initial execution owner.
     *
     * <p>This is registration, not migration. Reassigning an already-owned
     * object to another region is rejected by the ownership registry.</p>
     */
    public void assign(Object object, RegioniumRegion region) {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(region, "region");

        synchronized (tickLock) {
            if (closed) {
                throw new IllegalStateException("Regionium scheduler is closed");
            }
            if (tickInProgress.get()) {
                throw new IllegalStateException("Cannot assign ownership during an active Regionium tick");
            }
            if (region.id() < 0 || region.id() >= regions.size() || regions.get(region.id()) != region) {
                throw new IllegalArgumentException("Region does not belong to this scheduler");
            }
            ownership.assign(object, region);
        }
    }

    /**
     * Schedules work for the current owner of an object.
     *
     * <p>The owner is resolved when this method is called. The task itself is
     * then executed by that region's next tick.</p>
     */
    /** Executes work on the region owning a specific world position. */
    public void execute(ServerLevel level, net.minecraft.core.BlockPos pos, Runnable action) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(action, "action");

        RegioniumRegion owner = chunkLeases.owner(level, net.minecraft.world.level.ChunkPos.pack(pos));
        if (owner == null) {
            owner = regionizer.owner(level, net.minecraft.world.level.ChunkPos.pack(pos));
        }
        if (owner == null) {
            throw new IllegalStateException("No region owns world position " + pos);
        }
        owner.execute(action);
    }

    public void execute(Object object, Runnable action) {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(action, "action");

        if (ownership.ownerOf(object) == null) {
            ownerOf(object);
        }
        if (ownership.ownerOf(object) == null) {
            throw new IllegalStateException("Object has no Regionium owner: " + object);
        }

        executeOwned(object, action);
    }

    private void executeOwned(Object object, Runnable action) {
        // The regionizer/chunk lease map is authoritative for movable objects.
        // The legacy ownership registry is only a compatibility index and may
        // legitimately lag behind a player entering the server.
        RegioniumRegion owner = ownerOf(object);
        if (owner == null) {
            throw new IllegalStateException("Object no longer has a Regionium owner: " + object);
        }

        owner.execute(() -> {
            RegioniumRegion current = RegioniumContext.requireRegionThread();
            RegioniumRegion actualOwner = ownerOf(object);

            if (actualOwner == null) {
                // A player/entity may have been removed between packet arrival
                // and mailbox execution. Dropping that task is safer than
                // turning a disconnect race into a hard connection exception.
                return;
            }

            if (actualOwner != current) {
                executeOwned(object, action);
                return;
            }

            action.run();
        });
    }

    /**
     * Requests an ownership migration. The migration is committed only at
     * the next scheduler tick boundary.
     */
    public void requestTransfer(Object object, RegioniumRegion destination) {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(destination, "destination");

        synchronized (tickLock) {
            if (closed) {
                throw new IllegalStateException("Regionium scheduler is closed");
            }
            if (destination.id() < 0
                || destination.id() >= regions.size()
                || regions.get(destination.id()) != destination) {
                throw new IllegalArgumentException("Region does not belong to this scheduler");
            }
            // Players/entities may be owned by the regionizer without having
            // an entry in the auxiliary ownership table yet. Resolve their
            // real current owner before accepting the migration request.
            if (ownerOf(object) == null && !pendingTransfers.containsKey(object)) {
                throw new IllegalStateException("Cannot transfer an object without an owner: " + object);
            }
            pendingTransfers.put(object, destination);
        }
    }

    public void requestTransfer(Object object, int destinationRegionId) {
        requestTransfer(object, region(destinationRegionId));
    }

    /** Applies queued transfers at a safe server-thread boundary. */
    public void applyPendingTransfers() {
        if (pendingTransfers.isEmpty()) {
            return;
        }

        List<Object> completed = new ArrayList<>();
        for (Map.Entry<Object, RegioniumRegion> transfer : pendingTransfers.entrySet()) {
            Object object = transfer.getKey();
            RegioniumRegion destination = transfer.getValue();
            RegioniumRegion source = ownership.ownerOf(object);

            if (source != null && source != destination && source.isTicking()) {
                continue;
            }

            ownership.transfer(object, destination);

            if (object instanceof ServerPlayer player && source != destination) {
                ServerLevel level = (ServerLevel) player.level();
                RegioniumWorldData data = worldData(level);

                // Move the player between the region-local mailboxes at the
                // same boundary as ownership. PacketProcessor itself is
                // player-owned and therefore remains the same queue; what
                // changes is which region is allowed to drain that queue.
                if (source != null) {
                    data.remove(player, source);
                }
                data.add(player, destination);
                ((ServerPlayerRegioniumPacketAccess) (Object) player).regionium$updateRegion(data);

                Regionium.LOGGER.info(
                    "Player {} moved Region {} -> {} ({} -> {})",
                    player.getGameProfile().name(),
                    source == null ? "?" : source.id(),
                    destination.id(),
                    source == null ? "?" : source.workerName(),
                    destination.workerName()
                );
            }
            completed.add(object);
        }
        for (Object object : completed) {
            pendingTransfers.remove(object);
        }
    }

    @Override
    public void close() {
        synchronized (tickLock) {
            if (closed) {
                return;
            }
            closed = true;
            running = false;
        }

        for (RegioniumRegion region : regions) {
            region.requestStop();
        }

        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

}
