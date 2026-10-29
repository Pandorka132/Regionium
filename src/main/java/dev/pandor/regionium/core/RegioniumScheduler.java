package dev.pandor.regionium.core;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.access.ServerCommonPacketListenerRegioniumAccess;
import dev.pandor.regionium.access.ServerPlayerRegioniumPacketAccess;
import dev.pandor.regionium.mixins.MinecraftServerRegioniumShutdownInvokerMixin;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.ticks.LevelTicks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Region scheduler.
 *
 * The server thread only performs global chunk lifecycle work and regionizer
 * topology maintenance. It never releases a global "tick batch". Region ticks
 * share one deadline-ordered scheduler queue, so any idle worker can execute the
 * next due region instead of leaving work pinned behind one region's assigned worker.
 */
public final class RegioniumScheduler implements AutoCloseable {
    // Accessed only on the Minecraft server thread. Refcounts prevent concurrent
    // portal searches from releasing a chunk ticket still used by another search.
    private static final TicketType REGIONIUM_ASYNC_TICKET =
        new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
    private static final Map<AsyncChunkTicketKey, Integer> ASYNC_CHUNK_TICKET_REFERENCES = new HashMap<>();

    private record AsyncChunkTicketKey(ServerLevel level, long chunkKey, int ticketLevel) {
    }

    public static final int DEFAULT_REGION_COUNT =
        Math.clamp(Runtime.getRuntime().availableProcessors(), 1, 16);

    // One shared deadline queue lets all workers claim the next due region tick.
    // A region is the ownership unit; its state is not tied to a physical thread.
    private final List<ScheduledThreadPoolExecutor> workers;
    private final int workerThreadCount;
    private final RegioniumRegionizer regionizer;

    private final Map<ServerLevel, List<RegioniumRegion>> levelRegions =
        Collections.synchronizedMap(new IdentityHashMap<>());
    private final Set<ServerLevel> knownLevels =
        Collections.newSetFromMap(new IdentityHashMap<>());

    private final PacketProcessor globalPacketProcessor = new PacketProcessor(null);
    private final Set<ChunkExecutionKey> activeChunkExecutions =
        ConcurrentHashMap.newKeySet();
    // A readiness check must not merely skip a region tick forever when a
    // required neighbour has no FULL holder. Deduplicate asynchronous requests
    // while the normal ChunkMap ticket/task pipeline brings those chunks up.
    private final Set<ChunkExecutionKey> pendingReadinessLoads = ConcurrentHashMap.newKeySet();
    private final Set<ChunkExecutionKey> loggedReadinessLoadFailures = ConcurrentHashMap.newKeySet();
    // Scheduled ticks can be produced while a chunk has a spatial region
    // owner but its LevelChunkTicks containers have not yet been attached to
    // that region's LevelTicks. Keep those ticks until addChunk() registers
    // the containers; otherwise vanilla logs that the position is not loaded
    // and drops the scheduled tick.
    private final ConcurrentMap<ChunkExecutionKey,
        ConcurrentLinkedQueue<DeferredScheduledTick>> deferredScheduledTicks =
        new ConcurrentHashMap<>();

    // Async chunk loads can finish before the next global chunk-lifecycle pass
    // publishes their FULL chunks into the region topology. Portal transitions
    // wait here instead of failing with a missing spatial owner.
    private final ConcurrentMap<ChunkExecutionKey, ConcurrentLinkedQueue<Runnable>> pendingOwnedTasks =
        new ConcurrentHashMap<>();

    /*
     * Vanilla's ChunkMap.move() is a global chunk-tracking operation. Region
     * workers must never call it, but the global server thread still has to
     * receive player position changes so DistanceManager can promote the
     * surrounding lazy/simulation chunks.
     */
    private final Set<ServerPlayer> pendingGlobalPlayerMoves =
        ConcurrentHashMap.newKeySet();

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean gameTestDriven = new AtomicBoolean();
    private final AtomicBoolean regionFailureShutdownRequested = new AtomicBoolean();
    private volatile MinecraftServer server;

    private volatile long tick;
    private static final AtomicInteger NEXT_REGION_ID = new AtomicInteger();
    private static final AtomicInteger DEBUG_ACTIVE_WORKERS = new AtomicInteger();
    private static final AtomicInteger DEBUG_MAX_WORKERS = new AtomicInteger();
    private static final long REGION_STALL_DIAGNOSTIC_NANOS =
        java.util.concurrent.TimeUnit.SECONDS.toNanos(15L);

    private record ChunkExecutionKey(ServerLevel level, long chunk) {}
    private record DeferredScheduledTick(net.minecraft.world.ticks.ScheduledTick<?> tick, long clockTime) {}

    public RegioniumScheduler() {
        this(DEFAULT_REGION_COUNT);
    }

    public RegioniumScheduler(int regionCount) {
        if (regionCount < 1) {
            throw new IllegalArgumentException("regionCount must be positive");
        }

        int workerThreads = Math.max(
            1,
            Math.min(regionCount, Runtime.getRuntime().availableProcessors())
        );
        AtomicInteger workerIndex = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            int index = workerIndex.getAndIncrement();
            Thread thread = new Thread(runnable, "Regionium-Worker-" + index);
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, error) -> {
                Regionium.LOGGER.error("Uncaught exception in Regionium worker {}", t.getName(), error);
            });
            return thread;
        };
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(workerThreads, factory);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        this.workers = List.of(scheduler);
        this.workerThreadCount = workerThreads;

        this.regionizer = new RegioniumRegionizer(this::createRegion);
        running.set(true);
        startRegionStallDiagnostics();
    }

    /**
     * Capture a JVM-wide thread dump when an individual live region stops
     * completing ticks. This runs on its own daemon thread so it still works
     * when every Regionium worker is blocked in a lock, future, or chunk task.
     */
    private void startRegionStallDiagnostics() {
        Thread monitor = new Thread(() -> {
            Map<Long, Long> observedTicks = new HashMap<>();
            Map<Long, Long> unchangedSince = new HashMap<>();
            Set<Long> reportedStalls = new java.util.HashSet<>();

            while (!closed.get()) {
                try {
                    Thread.sleep(5_000L);
                } catch (InterruptedException interrupted) {
                    if (closed.get()) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                long now = System.nanoTime();
                Set<RegioniumRegion> activeRegions = regionizer.activeRegions();
                Set<Long> present = new java.util.HashSet<>();
                for (RegioniumRegion region : activeRegions) {
                    present.add(region.id());
                    if (region.isRetired()
                        || region.lifecycleState() == RegioniumRegion.LifecycleState.DEAD
                        || region.lifecycleState() == RegioniumRegion.LifecycleState.TRANSIENT) {
                        observedTicks.remove(region.id());
                        unchangedSince.remove(region.id());
                        reportedStalls.remove(region.id());
                        continue;
                    }

                    long ticks = region.tickCount();
                    Long previous = observedTicks.put(region.id(), ticks);
                    if (previous == null || previous.longValue() != ticks) {
                        unchangedSince.put(region.id(), now);
                        reportedStalls.remove(region.id());
                        continue;
                    }

                    long since = unchangedSince.computeIfAbsent(region.id(), ignored -> now);
                    if (now - since >= REGION_STALL_DIAGNOSTIC_NANOS
                        && reportedStalls.add(region.id())) {
                        dumpStalledRegion(region, java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(now - since));
                    }
                }

                observedTicks.keySet().removeIf(id -> !present.contains(id));
                unchangedSince.keySet().removeIf(id -> !present.contains(id));
                reportedStalls.removeIf(id -> !present.contains(id));
            }
        }, "Regionium-Stall-Diagnostics");
        monitor.setDaemon(true);
        monitor.setUncaughtExceptionHandler((thread, error) ->
            Regionium.LOGGER.error("Regionium stall diagnostics thread failed", error));
        monitor.start();
    }

    private void dumpStalledRegion(RegioniumRegion stalledRegion, long stalledSeconds) {
        StringBuilder dump = new StringBuilder(16_384);
        dump.append("Regionium region stall detected: region=")
            .append(stalledRegion.id())
            .append(", level=").append(stalledRegion.level().dimension())
            .append(", lifecycle=").append(stalledRegion.lifecycleState())
            .append(", completedTicks=").append(stalledRegion.tickCount())
            .append(", lastWorker=").append(stalledRegion.lastWorkerThreadName())
            .append(", no tick progress for at least ").append(stalledSeconds).append("s\n")
            .append("Active regions:\n");
        for (RegioniumRegion region : regionizer.activeRegions()) {
            dump.append("  ").append(region).append(", lifecycle=")
                .append(region.lifecycleState())
                .append(", ticks=").append(region.tickCount())
                .append(", worker=").append(region.lastWorkerThreadName()).append('\n');
        }
        dump.append("JVM thread dump:\n");
        Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
        for (Map.Entry<Thread, StackTraceElement[]> entry : stacks.entrySet()) {
            Thread thread = entry.getKey();
            dump.append('\n').append('"').append(thread.getName()).append('"')
                .append(" id=").append(thread.threadId())
                .append(" state=").append(thread.getState());
            if (thread.isDaemon()) {
                dump.append(" daemon");
            }
            dump.append('\n');
            for (StackTraceElement frame : entry.getValue()) {
                dump.append("    at ").append(frame).append('\n');
            }
        }
        Regionium.LOGGER.error("{}", dump);
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
     * Execute queued packets through the same ownership-checked path used by
     * the region tick. Ownership can change while a movement packet executes,
     * so every subsequent packet must re-check the player's owner.
     */
    public void drainRegionPackets(RegioniumRegion region) {
        if (RegioniumContext.currentRegion() != region) {
            throw new IllegalStateException("Packet drain outside owning region");
        }
        drainPacketsForRegion(region);
    }

    public boolean isOwnedByCurrentRegion(Object object) {
        RegioniumRegion current = RegioniumContext.currentRegion();
        return current != null && ownerOf(object) == current;
    }

    public boolean isCurrentRegionFor(ServerLevel level, net.minecraft.core.BlockPos pos) {
        RegioniumRegion current = RegioniumContext.currentRegion();
        return current != null && regionizer.owner(level, ChunkPos.pack(pos)) == current;
    }

    public MinecraftServer server() {
        return server;
    }

    public void beginServerTick(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
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

        List<ChunkPos> missing = new ArrayList<>();
        for (int dz = -1; dz <= 1; ++dz) {
            for (int dx = -1; dx <= 1; ++dx) {
                ChunkPos pos = new ChunkPos(cx + dx, cz + dz);
                var holder = visible.get(pos.pack());
                if (holder == null
                    || holder.getChunkIfPresent(net.minecraft.world.level.chunk.status.ChunkStatus.FULL) == null) {
                    missing.add(pos);
                }
            }
        }

        if (!missing.isEmpty()) {
            requestMissingFullChunks(level, missing);
            return false;
        }
        return true;
    }

    /**
     * Re-enters the ordinary asynchronous chunk/ticket pipeline for missing
     * FULL chunks. The readiness guard remains in place to protect region
     * workers from synchronous cross-region loads, but it now actively repairs
     * a stalled/missing holder instead of repeatedly skipping the region tick
     * without asking ChunkMap to load anything.
     */
    private void requestMissingFullChunks(ServerLevel level, Collection<ChunkPos> missingChunks) {
        List<ChunkPos> requested = new ArrayList<>();
        for (ChunkPos pos : missingChunks) {
            ChunkExecutionKey key = new ChunkExecutionKey(level, pos.pack());
            if (pendingReadinessLoads.add(key)) {
                requested.add(pos);
            }
        }
        if (requested.isEmpty()) {
            return;
        }

        withChunksAsync(level, requested, ChunkStatus.FULL,
            loaded -> CompletableFuture.completedFuture(null)
        ).whenComplete((ignored, failure) -> {
            for (ChunkPos pos : requested) {
                ChunkExecutionKey key = new ChunkExecutionKey(level, pos.pack());
                pendingReadinessLoads.remove(key);
                if (failure == null) {
                    loggedReadinessLoadFailures.remove(key);
                } else if (loggedReadinessLoadFailures.add(key)) {
                    Regionium.LOGGER.warn(
                        "Could not prepare required FULL chunk {} in {} for region ticking; "
                            + "the readiness check will retry: {}",
                        pos, level.dimension(), failure.toString()
                    );
                }
            }
        });
    }

    /**
     * Advances the world's non-redstone game clock on a normal global server
     * tick. Calling vanilla tickTime() keeps scheduled events coupled to the
     * game-time increment; region threads advance their redstone clocks only.
     */
    public void tickGlobalTime(ServerLevel level) {
        if (level.tickRateManager().runsNormally()) {
            // Keep vanilla's tickTime() guard and scheduled-function update
            // together with the global game-time increment.
            ((dev.pandor.regionium.mixins.ServerLevelRegioniumAccessorMixin) (Object) level)
                .regionium$tickTime();
        }
    }

    /**
     * Runs vanilla world-wide border, weather, and sleep updates once per
     * global server tick. Regionium does not run the vanilla global
     * ServerLevel tick, and the region-thread wrapper skips these operations.
     */
    public void tickGlobalWorldState(ServerLevel level) {
        var invoker = (dev.pandor.regionium.mixins.ServerLevelRegioniumAccessorMixin) (Object) level;
        level.environmentAttributes().invalidateTickCache();
        level.getChunkSource().randomState().garbageCollect();
        boolean runsNormally = level.tickRateManager().runsNormally();
        // Folia's global world pass owns these world-wide operations. The
        // ServerLevel.tick() call is region-owned in Regionium, so its
        // vanilla invocations are suppressed by ServerLevelTickRegioniumMixin.
        if (runsNormally) {
            level.getWorldBorder().tick();
            invoker.regionium$advanceWeatherCycle();
        }

        // This block is inline in ServerLevel.tick() in 26.3 and runs even
        // while the tick-rate manager is frozen. Keep its operation order in
        // sync with vanilla.
        var sleepStatus = ((dev.pandor.regionium.mixins.ServerLevelRegioniumAccessorMixin) (Object) level)
            .regionium$getSleepStatus();
        int sleepPercentage = level.getGameRules().get(
            net.minecraft.world.level.gamerules.GameRules.PLAYERS_SLEEPING_PERCENTAGE
        );
        if (sleepStatus.areEnoughSleeping(sleepPercentage)
            && sleepStatus.areEnoughDeepSleeping(sleepPercentage, level.players())) {
            var defaultClock = level.dimensionType().defaultClock();
            if (level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME)
                && defaultClock.isPresent()) {
                level.getServer().clockManager().moveToTimeMarker(
                    defaultClock.get(),
                    net.minecraft.world.clock.ClockTimeMarkers.WAKE_UP_FROM_SLEEP
                );
            }

            invoker.regionium$wakeUpAllPlayers();
            if (level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER)
                && level.isRaining()) {
                level.resetWeatherCycle();
            }
        }
    }

    public void tickGlobalSkyBrightness(ServerLevel level) {
        // updateSkyBrightness is declared public on Level, not ServerLevel.
        // Call the inherited method directly instead of targeting it with a
        // ServerLevel @Invoker (which fails during Mixin accessor application).
        level.updateSkyBrightness();
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
     * A failed region tick can leave world state only partially updated. Match
     * Folia's fatal tick policy: stop admitting further region work immediately,
     * then let the server thread run the normal shutdown/save sequence. The
     * shutdown callback must not run on a worker because close() joins workers.
     */
    void regionTickFailed(RegioniumRegion region, long tick, Throwable error) {
        Regionium.LOGGER.error(
            "Region {} tick failed in {} at region tick {}",
            region.id(),
            region.level().dimension().identifier(),
            tick,
            error
        );

        if (!regionFailureShutdownRequested.compareAndSet(false, true)) {
            return;
        }

        running.set(false);
        for (RegioniumRegion activeRegion : regionizer.allRegions()) {
            activeRegion.requestStop();
        }

        MinecraftServer server = region.level().getServer();
        server.execute(() ->
            ((MinecraftServerRegioniumShutdownInvokerMixin) server).regionium$invokeStopServer()
        );
    }

    /**
     * GameTestServer deliberately does not sleep between server ticks. Its
     * server tick is therefore the authoritative clock, rather than wall-clock
     * time. Drive the same region tick body on worker threads once per GameTest
     * tick and wait only for those region jobs before GameTest advances. Normal
     * servers never enter this path and retain fully independent scheduling.
     */
    public void driveGameTestRegions() {
        if (!running.get() || workers.stream().anyMatch(ScheduledThreadPoolExecutor::isShutdown)) {
            return;
        }
        List<RegioniumRegion> active = new ArrayList<>(regionizer.activeRegions());
        if (active.isEmpty()) {
            return;
        }

        List<Future<?>> futures = new ArrayList<>(active.size());
        for (RegioniumRegion region : active) {
            try {
                futures.add(region.submitGameTestTick());
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                if (!running.get() || workers.stream().anyMatch(ScheduledThreadPoolExecutor::isShutdown)) {
                    break;
                }
                throw rejected;
            }
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
     * Number of dedicated Regionium worker threads available for region ticks.
     */
    public int workerCount() {
        return workerThreadCount;
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
        // A dimension can be registered after ChunkMap has already created
        // holders (notably when an async portal first touches the Nether/End).
        // Force one initial topology reconciliation so those existing FULL
        // holders are not left without a region owner.
        regionizer.markTopologyDirty(level);

        // Folia does not start a permanent tick loop for every possible region.
        // A region becomes schedulable only after the regionizer has acquired
        // actual chunk lifecycle state and marked that region active.

    }

    private RegioniumRegion createRegion(ServerLevel level) {
        RegioniumRegion region = new RegioniumRegion(
            NEXT_REGION_ID.getAndIncrement(),
            level,
            workers.get(0)
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
        long chunkKey = ChunkPos.pack(tick.pos());
        RegioniumRegion owner = regionizer.owner(level, chunkKey);
        RegioniumRegion current = RegioniumContext.currentRegion();
        long clockTime = owner != null
            ? owner.worldData().redstoneTime()
            : current != null && current.level() == level
                ? current.worldData().redstoneTime()
                : level.getGameTime();
        return routeScheduledTick(level, tick, clockTime);
    }

    private boolean routeScheduledTick(
        ServerLevel level,
        net.minecraft.world.ticks.ScheduledTick<?> tick,
        long clockTime
    ) {
        long chunkKey = ChunkPos.pack(tick.pos());
        RegioniumRegion region = regionizer.owner(level, chunkKey);
        RegioniumRegion current = RegioniumContext.currentRegion();
        if (region == null) {
            // During chunk promotion the target may not have been published in
            // the region topology yet. Preserve the clock that created the tick
            // so it can be rebased if a different region eventually owns it.
            if (current != null && current.level() == level) {
                deferScheduledTick(level, chunkKey, tick, clockTime);
                return true;
            }
            return false;
        }

        if (current == region) {
            if (region.worldData().hasChunk(chunkKey)) {
                if (clockTime == region.worldData().redstoneTime()) {
                    return false;
                }
                scheduleTickInRegion(region, rebaseScheduledTick(
                    tick, clockTime, region.worldData().redstoneTime()
                ));
                return true;
            }
            // A spatial owner may be published before the ticking LevelChunk
            // and its LevelChunkTicks containers are ready.
            deferScheduledTick(level, chunkKey, tick, clockTime);
            return true;
        }

        // A region's LevelTicks and its chunk containers are single-owner
        // state. Never inspect or mutate the target region's mutable world data
        // from a foreign thread; defer the decision to the owner worker.
        boolean accepted = region.execute(() -> {
            RegioniumRegion actualOwner = regionizer.owner(level, chunkKey);
            if (actualOwner != region) {
                if (actualOwner != null) {
                    routeScheduledTick(level, tick, clockTime);
                }
                return;
            }
            if (!region.worldData().hasChunk(chunkKey)) {
                deferScheduledTick(level, chunkKey, tick, clockTime);
                return;
            }
            scheduleTickInRegion(region, rebaseScheduledTick(
                tick, clockTime, region.worldData().redstoneTime()
            ));
        });

        if (!accepted) {
            RegioniumRegion actualOwner = regionizer.owner(level, chunkKey);
            if (actualOwner != null && actualOwner != region) {
                return routeScheduledTick(level, tick, clockTime);
            }
            deferScheduledTick(level, chunkKey, tick, clockTime);
        }
        return true;
    }

    private void deferScheduledTick(
        ServerLevel level,
        long chunkKey,
        net.minecraft.world.ticks.ScheduledTick<?> tick,
        long clockTime
    ) {
        deferredScheduledTicks.computeIfAbsent(
            new ChunkExecutionKey(level, chunkKey), ignored -> new ConcurrentLinkedQueue<>()
        ).add(new DeferredScheduledTick(tick, clockTime));
    }

    /** Called on the owner region thread immediately after its chunk containers are registered. */
    public void flushDeferredScheduledTicks(ServerLevel level, long chunkKey, RegioniumRegion region) {
        if (RegioniumContext.currentRegion() != region || !region.worldData().hasChunk(chunkKey)) {
            return;
        }

        ChunkExecutionKey key = new ChunkExecutionKey(level, chunkKey);
        ConcurrentLinkedQueue<DeferredScheduledTick> pending = deferredScheduledTicks.remove(key);
        if (pending == null) {
            return;
        }

        DeferredScheduledTick deferred;
        while ((deferred = pending.poll()) != null) {
            RegioniumRegion currentOwner = regionizer.owner(level, chunkKey);
            if (currentOwner != region) {
                if (currentOwner != null) {
                    routeScheduledTick(level, deferred.tick(), deferred.clockTime());
                }
                continue;
            }
            scheduleTickInRegion(region, rebaseScheduledTick(
                deferred.tick(), deferred.clockTime(), region.worldData().redstoneTime()
            ));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static net.minecraft.world.ticks.ScheduledTick<?> rebaseScheduledTick(
        net.minecraft.world.ticks.ScheduledTick<?> tick,
        long fromClock,
        long toClock
    ) {
        long offset = toClock - fromClock;
        if (offset == 0L) {
            return tick;
        }
        return new net.minecraft.world.ticks.ScheduledTick(
            tick.type(), tick.pos(), tick.triggerTick() + offset,
            tick.priority(), tick.subTickOrder()
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void scheduleTickInRegion(
        RegioniumRegion region,
        net.minecraft.world.ticks.ScheduledTick<?> tick
    ) {
        if (tick.type() instanceof net.minecraft.world.level.block.Block) {
            region.worldData().blockTicks().schedule((net.minecraft.world.ticks.ScheduledTick) tick);
        } else if (tick.type() instanceof net.minecraft.world.level.material.Fluid) {
            region.worldData().fluidTicks().schedule((net.minecraft.world.ticks.ScheduledTick) tick);
        }
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
        regionizer.ensureReadyRegionsScheduled(level);
        drainPendingOwnedTasks(level);
    }

    /**
     * Register a block-entity ticker on the region that currently owns its
     * chunk. Ticker registration can race a region split/merge, so the callback
     * resolves ownership again when it runs and follows the new owner instead
     * of being stranded in a retired region's executor queue.
     */
    public void registerBlockEntityTickerWhenOwned(
        ServerLevel level,
        net.minecraft.world.level.block.entity.TickingBlockEntity ticker
    ) {
        if (ticker.isRemoved()) {
            return;
        }

        BlockPos position = ticker.getPos();
        executeWhenOwned(level, position, () -> {
            if (ticker.isRemoved()) {
                return;
            }

            BlockPos currentPosition = ticker.getPos();
            RegioniumRegion actualOwner = regionizer.owner(
                level,
                ChunkPos.pack(currentPosition)
            );
            if (actualOwner == null || actualOwner.isRetired()
                || actualOwner != RegioniumContext.currentRegion()) {
                registerBlockEntityTickerWhenOwned(level, ticker);
                return;
            }

            actualOwner.worldData().addBlockEntityTicker(ticker);
        });
    }

    /**
     * Runs an operation on the region that spatially owns this position. If an
     * async chunk request completed before its FULL holder was reconciled into
     * the region topology, keep the operation queued until the next lifecycle
     * pass publishes that owner. The caller must retain its chunk tickets until
     * the operation completes.
     */
    public void executeWhenOwned(ServerLevel level, BlockPos pos, Runnable action) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(action, "action");
        long chunkKey = ChunkPos.pack(pos);
        boolean queuedByRegionizer = regionizer.enqueueOwnedTask(level, chunkKey, action);
        if (queuedByRegionizer) {
            // enqueueOwnedTask may accept the task while topology is dirty even
            // when no owner has been published yet. Ensure a lifecycle pass is
            // scheduled so that the queued callback cannot wait forever.
            if (regionizer.owner(level, chunkKey) == null) {
                level.getServer().execute(() -> prepareChunkExecution(level));
            }
            return;
        }

        ChunkExecutionKey key = new ChunkExecutionKey(level, chunkKey);
        pendingOwnedTasks.compute(key, (ignored, queue) -> {
            ConcurrentLinkedQueue<Runnable> pending = queue == null
                ? new ConcurrentLinkedQueue<>()
                : queue;
            pending.add(action);
            return pending;
        });

        // Close the race where topology became available just before the task
        // was enqueued. If it is still unavailable, the global lifecycle pass
        // will drain it after reconciling the FULL chunk holders.
        RegioniumRegion owner = regionizer.owner(level, chunkKey);
        if (owner != null && !owner.isRetired()) {
            drainPendingOwnedTasks(key, owner);
        } else {
            level.getServer().execute(() -> prepareChunkExecution(level));
        }
    }

    private void drainPendingOwnedTasks(ServerLevel level) {
        for (Map.Entry<ChunkExecutionKey, ConcurrentLinkedQueue<Runnable>> entry : pendingOwnedTasks.entrySet()) {
            ChunkExecutionKey key = entry.getKey();
            if (key.level() != level) {
                continue;
            }
            RegioniumRegion owner = regionizer.owner(level, key.chunk());
            if (owner != null) {
                drainPendingOwnedTasks(key, owner);
            }
        }
    }

    private void drainPendingOwnedTasks(ChunkExecutionKey key, RegioniumRegion owner) {
        ConcurrentLinkedQueue<Runnable> queue = pendingOwnedTasks.get(key);
        if (queue == null) {
            return;
        }
        if (owner.isRetired()) {
            return;
        }

        Runnable task;
        while ((task = queue.poll()) != null) {
            if (owner.isRetired() || regionizer.owner(key.level(), key.chunk()) != owner) {
                // Ownership may change after lookup. Keep the task pending for
                // the global lifecycle pass, which will enqueue it against the
                // newly published spatial owner.
                queue.offer(task);
                return;
            }
            if (!regionizer.enqueueOwnedTask(key.level(), key.chunk(), task)) {
                queue.offer(task);
                return;
            }
        }
        pendingOwnedTasks.computeIfPresent(key, (ignored, current) -> {
            // Serialize empty-queue removal with enqueue's map.compute so a
            // concurrent producer cannot append to a queue just as it is
            // detached from the map.
            return current == queue && queue.isEmpty() ? null : current;
        });
    }

    public void parallelTickChunks(
        ServerLevel level,
        Consumer<LevelChunk> vanillaTick
    ) {
        RegioniumRegion current = RegioniumContext.requireRegionThread();
        for (LevelChunk chunk : List.copyOf(current.worldData().tickingChunks())) {
            long chunkPos = chunk.getPos().pack();
            if (regionizer.owner(level, chunkPos) != current) {
                continue;
            }
            beginChunkExecution(level, chunkPos);
            try {
                if (regionizer.owner(level, chunkPos) == current && current.worldData().hasChunk(chunkPos)) {
                    vanillaTick.accept(chunk);
                }
            } finally {
                endChunkExecution(level, chunkPos);
            }
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
        var accessor = (dev.pandor.regionium.mixins.ServerLevelRegioniumAccessorMixin) (Object) level;
        if (ticks == accessor.regionium$getBlockTicks()) {
            @SuppressWarnings("unchecked")
            LevelTicks<T> selected = (LevelTicks<T>) current.worldData().blockTicks();
            regionTicks = selected;
        } else if (ticks == accessor.regionium$getFluidTicks()) {
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
                    data.removeBlockEntityTicker(ticker);
                    continue;
                }

                // Topology changes can assign a chunk to a different region
                // while its ticker is still in the old region's list. Never
                // tick it here; hand it off through the destination's task
                // queue so only that region mutates its ticker lists.
                var tickerPos = ticker.getPos();
                RegioniumRegion owner = regionizer.owner(
                    level,
                    net.minecraft.world.level.ChunkPos.containing(tickerPos).pack()
                );
                if (owner != current) {
                    data.removeBlockEntityTicker(ticker);
                    if (owner != null && !owner.isRetired()) {
                        registerBlockEntityTickerWhenOwned(level, ticker);
                    }
                    continue;
                }

                // Block-entity state is part of the chunk save snapshot. The
                // save path uses the same LevelChunk monitor, so ticking must
                // hold it for the complete block-entity mutation as well.
                var chunk = data.chunks().stream()
                    .filter(candidate -> candidate.getPos().equals(net.minecraft.world.level.ChunkPos.containing(tickerPos)))
                    .findFirst()
                    .orElse(null);
                if (chunk == null) {
                    continue;
                }

                long chunkKey = chunk.getPos().pack();
                // Level.tickBlockEntities() gates every ticker with
                // shouldTickBlocksAt(). Region-local ticker lists must preserve
                // that simulation-distance check instead of ticking every
                // loaded FULL chunk owned by this region.
                if (!level.shouldTickBlocksAt(chunkKey)) {
                    continue;
                }

                beginChunkExecution(level, chunkKey);
                try {
                    synchronized (chunk) {
                        // Unload maintenance must not detach this chunk while
                        // its block entity is midway through a multi-step tick.
                        if (!ticker.isRemoved()
                            && regionizer.owner(level, chunkKey) == current) {
                            ticker.tick();
                        }
                    }
                } finally {
                    endChunkExecution(level, chunkKey);
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

        if (object instanceof Entity entity
            && entity.level() instanceof ServerLevel level) {
            // Entity ownership follows position. A task accepted by the old
            // region's executor can be stranded when that region retires, and
            // simply checking ownerOf() inside that task silently loses the
            // action after a move. Route by the entity's current position and
            // re-resolve ownership when the task actually runs.
            this.executeEntityWhenOwned(entity, action);
            return;
        }

        owner.execute(() -> {
            if (ownerOf(object) == owner) {
                action.run();
            }
        });
    }

    private void executeEntityWhenOwned(Entity entity, Runnable action) {
        if (entity.isRemoved() || !(entity.level() instanceof ServerLevel level)) {
            return;
        }

        executeWhenOwned(level, entity.blockPosition(), () -> {
            if (entity.isRemoved()) {
                return;
            }
            if (!(entity.level() instanceof ServerLevel currentLevel)) {
                return;
            }
            if (currentLevel != level) {
                executeEntityWhenOwned(entity, action);
                return;
            }

            RegioniumRegion actualOwner = ownerOf(entity);
            if (actualOwner == RegioniumContext.currentRegion()) {
                action.run();
                return;
            }

            // The entity moved after this task was queued or the regionizer
            // published a new owner while the task was in flight. Requeue at
            // its latest position; do not silently discard entity work.
            executeEntityWhenOwned(entity, action);
        });
    }

    /**
     * Keeps explicit, non-expiring chunk tickets alive for the entire asynchronous
     * operation. Vanilla getChunkFuture() uses short-lived UNKNOWN tickets which
     * can expire before an asynchronous future completes; portal searches must
     * not rely on those tickets.
     */
    public <T> CompletableFuture<T> withChunksAsync(
        ServerLevel level,
        Collection<ChunkPos> requestedChunks,
        ChunkStatus status,
        Function<List<ChunkAccess>, CompletableFuture<T>> operation
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(requestedChunks, "requestedChunks");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(operation, "operation");
        List<ChunkPos> chunks = List.copyOf(new LinkedHashSet<>(requestedChunks));
        if (chunks.isEmpty()) {
            try {
                return Objects.requireNonNull(operation.apply(List.of()), "operation future");
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        CompletableFuture<Void> ticketsReady = new CompletableFuture<>();
        level.getServer().execute(() -> {
            try {
                int ticketLevel = ChunkLevel.byStatus(status);
                for (ChunkPos pos : chunks) {
                    AsyncChunkTicketKey key = new AsyncChunkTicketKey(level, ChunkPos.pack(pos.x(), pos.z()), ticketLevel);
                    int references = ASYNC_CHUNK_TICKET_REFERENCES.getOrDefault(key, 0);
                    if (references == 0) {
                        level.getChunkSource().addTicket(new Ticket(REGIONIUM_ASYNC_TICKET, ticketLevel), pos);
                    }
                    ASYNC_CHUNK_TICKET_REFERENCES.put(key, references + 1);
                }
                // Apply all explicit tickets before issuing futures. Otherwise
                // getChunkFuture may observe a stale holder during an unload /
                // promotion transition and complete with UNLOADED despite the
                // non-expiring ticket already being present in TicketStorage.
                ((dev.pandor.regionium.access.ServerChunkCacheRegioniumAccess) level.getChunkSource())
                    .regionium$runDistanceManagerUpdates();
                ticketsReady.complete(null);
            } catch (Throwable failure) {
                ticketsReady.completeExceptionally(failure);
            }
        });

        // ChunkSource.getChunkFuture mutates/consults the server's chunk map;
        // issue those requests on the server thread after applying tickets.
        CompletableFuture<T> work = ticketsReady.thenCompose(ignored -> {
            List<CompletableFuture<ChunkResult<ChunkAccess>>> futures = new ArrayList<>(chunks.size());
            for (ChunkPos pos : chunks) {
                futures.add(level.getChunkSource().getChunkFuture(pos.x(), pos.z(), status, true));
            }
            CompletableFuture<?>[] all = futures.toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(all).thenComposeAsync(nothing -> {
                List<ChunkAccess> loaded = new ArrayList<>(futures.size());
                for (int i = 0; i < futures.size(); i++) {
                    ChunkResult<ChunkAccess> result = futures.get(i).join();
                    ChunkAccess chunk = result.orElse(null);
                    if (chunk == null) {
                        ChunkPos failedPos = chunks.get(i);
                        int requestedTicketLevel = ChunkLevel.byStatus(status);
                        level.getServer().execute(() -> {
                            var visible = ((dev.pandor.regionium.mixins.ChunkMapRegioniumVisibleAccessorMixin)
                                (Object) level.getChunkSource().chunkMap).regionium$getVisibleChunkMap();
                            ChunkHolder holder = visible.get(ChunkPos.pack(failedPos.x(), failedPos.z()));
                            Regionium.LOGGER.error(
                                "[ASYNC-CHUNK-DIAG] level={} pos={} status={} ticketLevel={} refs={} holder={} holderTicket={} chunkPresent={}",
                                level.dimension(), failedPos, status, requestedTicketLevel,
                                ASYNC_CHUNK_TICKET_REFERENCES.getOrDefault(
                                    new AsyncChunkTicketKey(level, ChunkPos.pack(failedPos.x(), failedPos.z()), requestedTicketLevel), 0),
                                holder, holder == null ? null : holder.getTicketLevel(),
                                holder != null && holder.getChunkIfPresent(status) != null);
                        });
                        throw new IllegalStateException(
                            "Asynchronous chunk load failed for " + failedPos + " at status " + status + " in "
                                + level.dimension() + ": " + result.getError()
                        );
                    }
                    loaded.add(chunk);
                }
                // getChunkFuture can complete before Regionium has published
                // ownership for newly-created holders. Reconcile those holders
                // on the server thread before starting callbacks that schedule
                // work against the destination region.
                CompletableFuture<Void> topologyReady = new CompletableFuture<>();
                level.getServer().execute(() -> {
                    try {
                        var visible = ((dev.pandor.regionium.mixins.ChunkMapRegioniumVisibleAccessorMixin)
                            (Object) level.getChunkSource().chunkMap).regionium$getVisibleChunkMap();
                        for (ChunkAccess chunk : loaded) {
                            ChunkHolder holder = visible.get(chunk.getPos().pack());
                            if (holder != null) {
                                regionizer.addChunkHolder(level, holder);
                            }
                        }
                        prepareChunkExecution(level);
                        topologyReady.complete(null);
                    } catch (Throwable failure) {
                        topologyReady.completeExceptionally(failure);
                    }
                });
                return topologyReady.thenComposeAsync(topologyIgnored -> {
                    return Objects.requireNonNull(operation.apply(List.copyOf(loaded)), "operation future");
                });
            });
        });

        CompletableFuture<T> released = new CompletableFuture<>();
        work.whenComplete((value, failure) -> level.getServer().execute(() -> {
            Throwable completionFailure = failure;
            try {
                int ticketLevel = ChunkLevel.byStatus(status);
                int ticketRadius = ChunkLevel.byStatus(FullChunkStatus.FULL) - ticketLevel;
                for (ChunkPos pos : chunks) {
                    AsyncChunkTicketKey key = new AsyncChunkTicketKey(level, ChunkPos.pack(pos.x(), pos.z()), ticketLevel);
                    Integer references = ASYNC_CHUNK_TICKET_REFERENCES.get(key);
                    if (references == null) {
                        continue;
                    }
                    if (references > 1) {
                        ASYNC_CHUNK_TICKET_REFERENCES.put(key, references - 1);
                    } else {
                        ASYNC_CHUNK_TICKET_REFERENCES.remove(key);
                        level.getChunkSource().removeTicketWithRadius(REGIONIUM_ASYNC_TICKET, pos, ticketRadius);
                    }
                }
            } catch (Throwable releaseFailure) {
                if (completionFailure != null) {
                    completionFailure.addSuppressed(releaseFailure);
                } else {
                    completionFailure = releaseFailure;
                }
            }
            if (completionFailure == null) {
                released.complete(value);
            } else {
                released.completeExceptionally(completionFailure);
            }
        }));
        return released;
    }





    public <T> CompletableFuture<T> withChunksAsync(
        ServerLevel level,
        BlockPos center,
        int radius,
        ChunkStatus status,
        Function<List<ChunkAccess>, CompletableFuture<T>> operation
    ) {
        if (radius < 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("radius must be non-negative"));
        }
        ChunkPos centerChunk = ChunkPos.containing(center);
        List<ChunkPos> chunks = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        for (int x = centerChunk.x() - radius; x <= centerChunk.x() + radius; x++) {
            for (int z = centerChunk.z() - radius; z <= centerChunk.z() + radius; z++) {
                chunks.add(new ChunkPos(x, z));
            }
        }
        return withChunksAsync(level, chunks, status, operation);
    }

    /**
     * Loads a square of chunks asynchronously. The temporary tickets are released
     * when this future completes; use withChunksAsync when the caller must inspect
     * or mutate those chunks after loading, so their tickets remain held throughout.
     */
    public CompletableFuture<List<ChunkAccess>> prepareChunksAsync(
        ServerLevel level,
        BlockPos center,
        int radius,
        ChunkStatus status
    ) {
        return withChunksAsync(level, center, radius, status,
            CompletableFuture::completedFuture
        );
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

        // stopServer() runs before vanilla saves chunks. Region ticks must be
        // quiescent before that save begins; interrupting a tick can leave a
        // multi-step block update (for example a moving piston) half-applied.
        running.set(false);
        List<RegioniumRegion> regionsToStop = regionizer.allRegions();
        for (RegioniumRegion region : regionsToStop) {
            region.requestStop();
        }

        // cancel(false) in requestStop prevents future periodic executions but
        // lets an already-running tick reach its finally block. shutdown(),
        // unlike shutdownNow(), preserves that guarantee.
        for (ScheduledThreadPoolExecutor worker : workers) {
            worker.shutdown();
        }
        boolean interrupted = false;
        try {
            for (ScheduledThreadPoolExecutor worker : workers) {
                while (!worker.isTerminated()) {
                    try {
                        worker.awaitTermination(1L, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        // Do not let a shutdown interrupt corrupt world state.
                        // Restore the interrupt flag after all active ticks finish.
                        interrupted = true;
                    }
                }
            }
        } finally {
            synchronized (levelRegions) {
                levelRegions.clear();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
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
            && region.worldData().entities().isEmpty()
            && region.worldData().players().isEmpty()) {
            return;
        }

        // A region may only enter the vanilla world tick once every chunk
        // that can be synchronously inspected by that tick is already FULL.
        // Folia guarantees this through its chunk-task/ticket pipeline before
        // scheduling the region tick. Regionium must establish the same
        // invariant rather than letting Level.getChunk(FULL, true) discover a
        // half-promoted neighbour from inside a worker thread.
        // Unlike the world tick, connection ticking must not be gated by this
        // readiness check: Folia ticks each region's connections independently
        // after the world tick, so keepalives and queued packets remain active.
        boolean chunksReady = areRegionTickChunksReady(region);

        // Folia holds local-player flushes across the complete region tick,
        // including connection.tick() and sendNextChunks(). This keeps packets
        // produced by different phases in one ordered channel flush. A player
        // that changes owner during the tick is resumed by its new region.
        List<ServerGamePacketListenerImpl> suspendedFlushes = new ArrayList<>();

        try {
            for (ServerPlayer player : List.copyOf(region.worldData().players())) {
                if (player.connection != null && !player.isRemoved()
                    && player.level() == level
                    && regionizer.owner(level, player.chunkPosition().pack()) == region) {
                    ServerGamePacketListenerImpl connection = player.connection;
                    connection.suspendFlushing();
                    // Retain the acquired listener itself. Player membership,
                    // position, or connection may change before this tick's
                    // finally block; each successful suspension still needs
                    // exactly one matching resume on that same listener.
                    suspendedFlushes.add(connection);
                }
            }

            drainPacketsForRegion(region);

            if (chunksReady) {
                region.worldData().setHandlingTick(true);
                level.tick(() -> true);
            }
            // Folia runs region-owned Connection.tick() after the world tick in
            // TickRegionData.tickConnections(). Keep connection ticking active
            // even when this Regionium-specific readiness guard defers the
            // world tick; otherwise keepalives and queued packets stop and the
            // client times out. Folia does not gate region connections on this
            // readiness check.
            tickConnectionsForRegion(region);
        } finally {
            region.worldData().setHandlingTick(false);

            try {
                for (Entity entity : List.copyOf(region.worldData().entities())) {
                    if (entity.isRemoved()) {
                        region.worldData().remove(entity);
                    }
                }

                for (ServerPlayer player : List.copyOf(region.worldData().players())) {
                    ((ServerPlayerRegioniumPacketAccess) (Object) player)
                        .regionium$updateRegion(region.worldData());
                }
            } finally {
                for (ServerGamePacketListenerImpl connection : suspendedFlushes) {
                    try {
                        connection.resumeFlushing();
                    } catch (Throwable error) {
                        Regionium.LOGGER.error("Failed to resume packet flushing after region tick", error);
                    }
                }
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

        List<ChunkPos> missing = new ArrayList<>();
        for (long key : required) {
            var holder = visible.get(key);
            if (holder == null
                || holder.getChunkIfPresent(net.minecraft.world.level.chunk.status.ChunkStatus.FULL) == null) {
                missing.add(new ChunkPos(ChunkPos.getX(key), ChunkPos.getZ(key)));
            }
        }

        if (!missing.isEmpty()) {
            requestMissingFullChunks(region.level(), missing);
            return false;
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
            // A removed player may remain in the previous region's player set
            // until the entity-manager callback is reconciled. Never tick its
            // connection while a dimension/region transfer is in flight.
            if (player.isRemoved() || player.level() != region.level()
                || RegioniumPortalDispatcher.isTransferPending(player)) {
                continue;
            }
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
            Connection connection = ((ServerCommonPacketListenerRegioniumAccess) (Object) player.connection)
                .regionium$getConnection();
            try {
                // Folia region-ticks the transport Connection, not only the
                // packet listener. Connection.tick() also drains queued sends,
                // flushes the channel, runs the listener tick, and finalizes
                // disconnects. The global connection loop filters this owned
                // PLAY connection before its connected/disconnected branch.
                connection.tick();

                // Folia keeps chunk sending inside the player's region tick;
                // the global MinecraftServer send-chunks loop is disabled by
                // MinecraftServerLevelRegioniumMixin. PlayerChunkSender's
                // pending queues and tracking view are region-owned state, so
                // running this from the global thread races view updates and
                // can leave individual distant chunks unsent.
                if (!player.isRemoved()
                    && player.level() == region.level()
                    && player.connection != null
                    && ((ServerCommonPacketListenerRegioniumAccess) (Object) player.connection)
                        .regionium$getConnection() == connection
                    && !RegioniumPortalDispatcher.isTransferPending(player)
                    && regionizer.owner(region.level(), player.chunkPosition().pack()) == region) {
                    if (RegioniumChunkDebug.enabled()) {
                        RegioniumChunkDebug.log("send-next-chunks-begin", RegioniumChunkDebug.playerState(player), "connectionOpen=" + connection.isConnected());
                    }
                    player.connection.chunkSender.sendNextChunks(player);
                    if (RegioniumChunkDebug.enabled()) {
                        RegioniumChunkDebug.log("send-next-chunks-end", RegioniumChunkDebug.playerState(player));
                    }
                } else {
                    if (RegioniumChunkDebug.enabled()) {
                        RegioniumChunkDebug.log("send-next-chunks-skipped", RegioniumChunkDebug.playerState(player), "sameLevel=" + (player.level() == region.level()), "removed=" + player.isRemoved(), "hasListener=" + (player.connection != null), "transferPending=" + RegioniumPortalDispatcher.isTransferPending(player), "ownerMatches=" + (regionizer.owner(region.level(), player.chunkPosition().pack()) == region));
                    }
                }
            } catch (Exception exception) {
                if (connection.isMemoryConnection()) {
                    throw new ReportedException(
                        CrashReport.forThrowable(exception, "Ticking memory connection")
                    );
                }

                Component message = Component.literal("Internal server error");
                Regionium.LOGGER.warn(
                    "Failed to handle packet for {}",
                    connection.getLoggableAddress(region.level().getServer().logIPs()),
                    exception
                );
                connection.send(
                    new ClientboundDisconnectPacket(message),
                    PacketSendListener.thenRun(() -> connection.disconnect(message))
                );
                connection.setReadOnly();
            }

        }
    }
}
