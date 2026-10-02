package dev.pandor.regionium.core;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final List<OwnershipTransfer> pendingTransfers = new ArrayList<>();

    private volatile boolean running;
    private volatile boolean closed;
    private long tick;

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

        synchronized (tickLock) {
            if (closed) {
                throw new IllegalStateException("Regionium scheduler is closed");
            }

            running = true;
            applyPendingTransfers();
            tick++;

            List<Future<?>> futures = new ArrayList<>(regions.size());
            for (RegioniumRegion region : regions) {
                futures.add(workers.submit(region::tick));
            }

            RuntimeException failure = null;
            Error error = null;

            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure = new IllegalStateException(
                        "Interrupted while waiting for Regionium regions",
                        interrupted
                    );
                    break;
                } catch (java.util.concurrent.ExecutionException executionFailure) {
                    Throwable cause = executionFailure.getCause();
                    if (cause instanceof Error e) {
                        error = e;
                    } else if (cause instanceof RuntimeException e) {
                        failure = e;
                    } else {
                        failure = new IllegalStateException(
                            "Regionium region tick failed",
                            cause
                        );
                    }
                    break;
                }
            }

            if (failure != null) {
                throw new IllegalStateException("Regionium region tick failed", failure);
            }
            if (error != null) {
                throw error;
            }
        }
    }

    public void execute(int regionId, Runnable action) {
        Objects.requireNonNull(action, "action");
        region(regionId).execute(action);
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

        RegioniumRegion owner = ownership.ownerOf(object);
        if (owner == null) {
            throw new IllegalStateException("Object has no Regionium owner: " + object);
        }

        owner.execute(action);
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
            pendingTransfers.add(new OwnershipTransfer(object, destination));
        }
    }

    public void requestTransfer(Object object, int destinationRegionId) {
        requestTransfer(object, region(destinationRegionId));
    }

    private void applyPendingTransfers() {
        if (pendingTransfers.isEmpty()) {
            return;
        }

        for (OwnershipTransfer transfer : pendingTransfers) {
            ownership.transfer(transfer.object(), transfer.destination());
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

    private record OwnershipTransfer(Object object, RegioniumRegion destination) {
    }
}
