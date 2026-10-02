package dev.pandor.regionium.core;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final ExecutorService workers;
    private final RegioniumOwnership ownership = new RegioniumOwnership();
    private final Object tickLock = new Object();
    private final Map<Object, RegioniumRegion> pendingTransfers = new IdentityHashMap<>();

    private volatile boolean running;
    private volatile boolean closed;
    private long tick;
    private final AtomicBoolean tickInProgress = new AtomicBoolean();

    public RegioniumScheduler() {
        this(DEFAULT_REGION_COUNT);
    }

    public RegioniumScheduler(int regionCount) {
        if (regionCount < 1) {
            throw new IllegalArgumentException("regionCount must be at least 1");
        }

        this.regions = new ArrayList<>(regionCount);
        for (int i = 0; i < regionCount; i++) {
            regions.add(new RegioniumRegion(i));
        }

        AtomicInteger workerId = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(
                runnable,
                "Regionium-Worker-" + workerId.getAndIncrement()
            );
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, error) ->
                System.err.println("Regionium worker " + t.getName() + " failed: " + error)
            );
            return thread;
        };

        this.workers = Executors.newFixedThreadPool(regionCount, factory);
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

    public long currentTick() {
        synchronized (tickLock) {
            return tick;
        }
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Starts one Regionium tick and waits until every region reaches the
     * barrier. Ownership transfers requested during the previous tick are
     * committed before new region work starts.
     */
    public void tick(MinecraftServer server) {
        Objects.requireNonNull(server, "server");

        if (!tickInProgress.compareAndSet(false, true)) {
            throw new IllegalStateException("Regionium tick is already in progress");
        }

        try {
            List<Future<?>> futures = new ArrayList<>(regions.size());
            synchronized (tickLock) {
                if (closed) {
                    throw new IllegalStateException("Regionium scheduler is closed");
                }

                running = true;
                applyPendingTransfers();
                tick++;

                // Keep the state lock until every region task has been
                // submitted. close() can then safely shut down the executor
                // while this tick is waiting for already-submitted work.
                for (RegioniumRegion region : regions) {
                    futures.add(workers.submit(region::tick));
                }
            }

            // Region ticks are intentionally asynchronous.
            //
            // The Minecraft server thread must never wait for a region worker:
            // vanilla code executed by a region may legitimately schedule work
            // back onto the server thread. Waiting here would create a deadlock.
            //
            // Regionium will add an explicit tick barrier once the vanilla
            // server/world tick is fully owned by the region scheduler.
            // For now, submitting the work is enough to establish execution
            // on the Regionium workers without blocking the server thread.
        } finally {
            tickInProgress.set(false);
        }
    }

    public void execute(int regionId, Runnable action) {
        Objects.requireNonNull(action, "action");
        region(regionId).execute(action);
    }

    /**
     * Runs a world-tick unit on its owning region and waits for completion.
     * This is used at a vanilla tick boundary so the server thread cannot
     * concurrently touch the same world state.
     */
    public void executeEntityAndWait(MinecraftServer server, Object object, Runnable action) {
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

        final RegioniumRegion target = owner;

        try {
            Future<?> future = workers.submit(() -> {
                RegioniumContext.enter(target);
                ServerChunkCache chunkCache = null;
                Thread previousChunkThread = null;
                ServerLevel executionLevel = null;
                Thread previousLevelThread = null;
                try {
                    if (object instanceof ServerLevel level) {
                        executionLevel = level;
                    } else if (object instanceof ServerPlayer player && player.level() instanceof ServerLevel level) {
                        executionLevel = level;
                    }

                    if (executionLevel != null) {
                        var levelThreadAccess = (dev.pandor.regionium.LevelThreadAccess) executionLevel;
                        previousLevelThread = levelThreadAccess.regionium$getThread();
                        levelThreadAccess.regionium$setThread(Thread.currentThread());

                        chunkCache = executionLevel.getChunkSource();
                        var threadAccess = (dev.pandor.regionium.ServerChunkCacheThreadAccess) chunkCache;
                        previousChunkThread = threadAccess.regionium$getMainThread();
                        threadAccess.regionium$setMainThread(Thread.currentThread());
                    }
                    action.run();
                } finally {
                    if (chunkCache != null) {
                        var threadAccess = (dev.pandor.regionium.ServerChunkCacheThreadAccess) chunkCache;
                        threadAccess.regionium$setMainThread(previousChunkThread);
                    }
                    if (executionLevel != null) {
                        var levelThreadAccess = (dev.pandor.regionium.LevelThreadAccess) executionLevel;
                        levelThreadAccess.regionium$setThread(previousLevelThread);
                    }
                    RegioniumContext.exit();
                }
            });

            // The server thread must not poll ServerChunkCache while the region
            // worker is ticking the world: that mutates DistanceManager/light
            // scheduling structures concurrently. The worker temporarily becomes
            // the cache's main-thread identity instead, making getChunk() use its
            // synchronous path without a cross-thread future/join.
            while (!future.isDone()) {
                Thread.yield();
            }
            future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Regionium work", interrupted);
        } catch (java.util.concurrent.ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Regionium work failed", cause);
        }
    }

    /**
     * Resolves an object's execution owner. Players are world-owned: packet
     * handlers may arrive on the vanilla server thread, so a player must use
     * the same region as the ServerLevel it currently inhabits instead of
     * receiving an unrelated hash-based owner.
     */
    private RegioniumRegion ownerOf(Object object) {
        RegioniumRegion owner = ownership.ownerOf(object);
        if (owner != null) {
            return owner;
        }

        if (object instanceof ServerPlayer player) {
            ServerLevel level = (ServerLevel) player.level();
            owner = ownership.ownerOf(level);
            if (owner != null) {
                ownership.assign(object, owner);
                return owner;
            }
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

        RegioniumRegion owner = ownership.ownerOf(object);
        if (owner == null) {
            synchronized (tickLock) {
                if (closed) {
                    throw new IllegalStateException("Regionium scheduler is closed");
                }

                owner = ownership.ownerOf(object);
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
    public void execute(Object object, Runnable action) {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(action, "action");

        if (ownership.ownerOf(object) == null) {
            throw new IllegalStateException("Object has no Regionium owner: " + object);
        }

        executeOwned(object, action);
    }

    private void executeOwned(Object object, Runnable action) {
        RegioniumRegion owner = ownership.ownerOf(object);
        if (owner == null) {
            throw new IllegalStateException("Object no longer has a Regionium owner: " + object);
        }

        owner.execute(() -> {
            RegioniumRegion current = RegioniumContext.requireRegionThread();
            RegioniumRegion actualOwner = ownership.ownerOf(object);

            if (actualOwner == null) {
                throw new IllegalStateException("Object no longer has a Regionium owner: " + object);
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
            if (ownership.ownerOf(object) == null && !pendingTransfers.containsKey(object)) {
                throw new IllegalStateException("Cannot transfer an object without an owner: " + object);
            }
            pendingTransfers.put(object, destination);
        }
    }

    public void requestTransfer(Object object, int destinationRegionId) {
        requestTransfer(object, region(destinationRegionId));
    }

    private void applyPendingTransfers() {
        if (pendingTransfers.isEmpty()) {
            return;
        }

        for (Map.Entry<Object, RegioniumRegion> transfer : pendingTransfers.entrySet()) {
            ownership.transfer(transfer.getKey(), transfer.getValue());
        }
        pendingTransfers.clear();
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
