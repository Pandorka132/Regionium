package dev.pandor.regionium.core;

import net.minecraft.server.MinecraftServer;

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
 * <p>This is deliberately independent from Minecraft's World implementation.
 * The scheduler provides the concurrency primitive first; Minecraft systems
 * are integrated into it in separate, narrowly scoped Mixins.</p>
 */
public final class RegioniumScheduler implements AutoCloseable {
    public static final int DEFAULT_REGION_COUNT = Math.max(
        1,
        Math.min(Runtime.getRuntime().availableProcessors(), 16)
    );

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

            Throwable failure = null;
            boolean interrupted = false;

            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException interruption) {
                    interrupted = true;
                    if (failure == null) {
                        failure = interruption;
                    }
                } catch (java.util.concurrent.ExecutionException executionFailure) {
                    if (failure == null) {
                        failure = executionFailure.getCause();
                    }
                }
            }

            if (interrupted) {
                Thread.currentThread().interrupt();
            }

            if (failure != null) {
                if (failure instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException("Regionium region tick failed", failure);
            }
        } finally {
            tickInProgress.set(false);
        }
    }

    public void execute(int regionId, Runnable action) {
        Objects.requireNonNull(action, "action");
        region(regionId).execute(action);
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
