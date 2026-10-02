package dev.pandor.regionium.core;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private volatile boolean running;
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
            Thread thread = new Thread(runnable, "Regionium-Worker-" + workerId.getAndIncrement());
            thread.setDaemon(true);
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
        return tick;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Starts a new Regionium tick and waits for every region to finish.
     *
     * <p>At this stage regions only process Regionium-owned tasks. No vanilla
     * world tick is redirected yet; this keeps the server behaviour unchanged
     * while the execution infrastructure is validated.</p>
     */
    public void tick(MinecraftServer server) {
        Objects.requireNonNull(server, "server");

        synchronized (tickLock) {
            if (!running) {
                running = true;
            }

            tick++;

            var futures = new ArrayList<java.util.concurrent.Future<?>>(regions.size());
            for (RegioniumRegion region : regions) {
                futures.add(workers.submit(region::tick));
            }

            for (var future : futures) {
                try {
                    future.get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for Regionium regions", interrupted);
                } catch (java.util.concurrent.ExecutionException failure) {
                    throw new IllegalStateException("Regionium region tick failed", failure.getCause());
                }
            }
        }
    }

    public void execute(int regionId, Runnable action) {
        region(regionId).execute(action);
    }

    @Override
    public void close() {
        running = false;
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
