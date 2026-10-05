package dev.pandor.regionium.core;

import dev.pandor.regionium.Regionium;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;

/**
 * A single independently ticking Regionium region.
 *
 * <p>The important property is the same as Folia's tick-region scheduler:
 * the region owns its own clock and its own execution context. Minecraft's
 * global server tick is not a barrier and does not release this region.</p>
 */
public final class RegioniumRegion {
    private static final long TIME_BETWEEN_TICKS_NANOS = TimeUnit.SECONDS.toNanos(1) / 20L;

    private final int id;
    private final AtomicBoolean ticking = new AtomicBoolean();
    private final AtomicBoolean tickLoopStarted = new AtomicBoolean();
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicBoolean externallyDriven = new AtomicBoolean();
    private final Queue<CompletableFuture<Void>> externalTicks = new ConcurrentLinkedQueue<>();
    private volatile Thread workerThread;
    private final AtomicLongHolder tickCount = new AtomicLongHolder();
    private volatile Runnable tickBody = () -> {};

    /*
     * Double-buffered mailbox. Tasks submitted while a region is ticking are
     * never picked up by the currently running tick; they belong to the next
     * region tick. This is the same basic execution rule as Folia's region
     * task queue.
     */
    private final AtomicMailbox mailbox = new AtomicMailbox();
    private final ForkJoinPool workers;

    RegioniumRegion(int id, ForkJoinPool workers) {
        if (id < 0) {
            throw new IllegalArgumentException("id must be non-negative");
        }
        this.id = id;
        this.workers = workers;
    }

    public int id() {
        return id;
    }

    public boolean isTicking() {
        return ticking.get();
    }

    public long tickCount() {
        return tickCount.get();
    }

    public String workerName() {
        return "Regionium-ForkJoin-" + id;
    }

    /**
     * Starts one long-lived region execution loop on the shared Regionium
     * ForkJoinPool. There is no separate clock thread and no per-region
     * executor.
     */
    public void startTickLoop() {
        if (!tickLoopStarted.compareAndSet(false, true)) {
            return;
        }
        workers.execute(this::runTickLoop);
    }

    void setExternallyDriven(boolean value) {
        externallyDriven.set(value);
    }

    CompletableFuture<Void> requestExternallyDrivenTick() {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        externalTicks.offer(completion);
        Thread worker = workerThread;
        if (worker != null) {
            java.util.concurrent.locks.LockSupport.unpark(worker);
        }
        return completion;
    }

    void setTickBody(Runnable tickBody) {
        this.tickBody = java.util.Objects.requireNonNull(tickBody, "tickBody");
    }

    private void runTickLoop() {
        workerThread = Thread.currentThread();
        long nextTickStart = System.nanoTime();

        while (!stopRequested.get() && !workers.isShutdown()) {
            if (externallyDriven.get()) {
                CompletableFuture<Void> completion = externalTicks.poll();
                if (completion == null) {
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                    continue;
                }

                try {
                    runRegionalTick();
                    completion.complete(null);
                } catch (Throwable error) {
                    completion.completeExceptionally(error);
                }
                continue;
            }

            nextTickStart += TIME_BETWEEN_TICKS_NANOS;

            long remaining;
            while ((remaining = nextTickStart - System.nanoTime()) > 0L
                && !stopRequested.get()
                && !workers.isShutdown()) {
                java.util.concurrent.locks.LockSupport.parkNanos(remaining);
            }

            if (stopRequested.get() || workers.isShutdown()) {
                break;
            }

            runRegionalTick();
        }
    }

    /**
     * Queues work for this region's next tick.
     *
     * <p>The server thread may collect work at any time. It does not publish
     * a global batch and it cannot wake or release a region. The region clock
     * alone decides when this work executes.</p>
     */
    boolean enqueueTickPhase(long ignoredGlobalTick, Runnable action) {
        mailbox.offer(action);
        return true;
    }

    /**
     * Folia-style region tick scheduling: a region has at most one outstanding
     * vanilla tick body. Missing ticks are represented by scheduler lateness,
     * not by an ever-growing FIFO of duplicate world ticks.
     */
    boolean enqueueCoalescedRegionTick(Runnable action) {
        return mailbox.offerCoalesced(action);
    }

    private void runRegionalTick() {
        if (!ticking.compareAndSet(false, true)) {
            return;
        }

        final long startTime = System.nanoTime();
        final long scheduledEnd = startTime + TIME_BETWEEN_TICKS_NANOS;
        tickCount.increment();

        RegioniumContext.enter(this);
        RegioniumScheduler.debugWorkerStarted();

        try {
            /*
             * Folia enters the region execution context before running its
             * region task queue and tick body. Do the same here.
             */
            runMailbox();
            tickBody.run();
        } catch (Throwable error) {
            Regionium.LOGGER.error(
                "Region {} tick failed at tick {}",
                id,
                tickCount.get(),
                error
            );
        } finally {
            RegioniumScheduler.debugWorkerFinished();
            RegioniumContext.exit();
            ticking.set(false);
        }

        long elapsed = System.nanoTime() - startTime;
        if (elapsed > TIME_BETWEEN_TICKS_NANOS) {
            Regionium.LOGGER.debug(
                "[MT] region={} tick={} overran by {} ms (scheduledEnd={})",
                id,
                tickCount.get(),
                TimeUnit.NANOSECONDS.toMillis(elapsed - TIME_BETWEEN_TICKS_NANOS),
                scheduledEnd
            );
        }
    }

    private void runMailbox() {
        Queue<Runnable> current = mailbox.swap();
        Runnable action;

        while ((action = current.poll()) != null) {
            try {
                action.run();
            } catch (Throwable error) {
                Regionium.LOGGER.error(
                    "Region {} task failed during tick {}",
                    id,
                    tickCount.get(),
                    error
                );
            }
        }
    }

    public void execute(Runnable action) {
        mailbox.offer(action);
    }

    /** Diagnostic primitive; never used as a tick barrier. */
    void awaitIdle() {
        while (ticking.get()) {
            Thread.onSpinWait();
        }
    }

    /** Runs one synchronous world unit when the worker is already reserved. */
    void runWorldTick(Runnable worldTick) {
        RegioniumContext.enter(this);
        try {
            runMailbox();
            worldTick.run();
        } finally {
            RegioniumContext.exit();
        }
    }

    void requestStop() {
        stopRequested.set(true);
        java.util.concurrent.locks.LockSupport.unpark(findWorkerThread());
    }

    /*
     * ForkJoinPool does not expose a stable region->worker mapping. The loop
     * also wakes naturally when shutdown() is called, so this is intentionally
     * a no-op hook rather than trying to identify/interrupt another worker.
     */
    private Thread findWorkerThread() {
        return Thread.currentThread();
    }

    @Override
    public String toString() {
        return "RegioniumRegion[" + id + "]";
    }

    private static final class AtomicMailbox {
        private final java.util.concurrent.atomic.AtomicReference<Queue<Runnable>> current =
            new java.util.concurrent.atomic.AtomicReference<>(new ConcurrentLinkedQueue<>());
        private final AtomicBoolean coalescedQueued = new AtomicBoolean();

        void offer(Runnable action) {
            current.get().offer(action);
        }

        boolean offerCoalesced(Runnable action) {
            if (!coalescedQueued.compareAndSet(false, true)) {
                return false;
            }

            current.get().offer(() -> {
                try {
                    action.run();
                } finally {
                    coalescedQueued.set(false);
                }
            });
            return true;
        }

        Queue<Runnable> swap() {
            return current.getAndSet(new ConcurrentLinkedQueue<>());
        }
    }

    private static final class AtomicLongHolder {
        private final java.util.concurrent.atomic.AtomicLong value =
            new java.util.concurrent.atomic.AtomicLong();

        void increment() {
            value.incrementAndGet();
        }

        long get() {
            return value.get();
        }
    }
}
