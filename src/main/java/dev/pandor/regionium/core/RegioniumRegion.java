package dev.pandor.regionium.core;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.pandor.regionium.Regionium;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Execution domain for a group of Minecraft objects.
 *
 * <p>The region does not own a World instance. The final design intentionally
 * keeps Minecraft's world, chunks, entities and players in shared memory and
 * only moves execution ownership between regions.</p>
 *
 * <p>Mailbox submission is separated at the tick boundary with an atomic
 * queue swap. Work submitted while a region is ticking therefore cannot
 * re-enter the current tick.</p>
 */
public final class RegioniumRegion {
    private final int id;
    private final AtomicReference<Queue<RegioniumTask>> mailbox =
        new AtomicReference<>(new ConcurrentLinkedQueue<>());
    private final AtomicBoolean ticking = new AtomicBoolean();
    private final AtomicBoolean workerLogged = new AtomicBoolean();

    RegioniumRegion(int id) {
        if (id < 0) {
            throw new IllegalArgumentException("id must be non-negative");
        }
        this.id = id;
    }

    public int id() {
        return id;
    }

    public boolean isTicking() {
        return ticking.get();
    }

    public void execute(Runnable action) {
        mailbox.get().add(new RegioniumTask(action));
    }

    /**
     * Executes exactly the mailbox that existed at the beginning of this
     * region tick. Tasks submitted after the queue swap belong to the next
     * tick.
     */
    void tick() {
        if (!ticking.compareAndSet(false, true)) {
            // Never execute two ticks for the same region concurrently.
            // Work submitted to the mailbox remains queued for a later tick.
            return;
        }

        Queue<RegioniumTask> currentMailbox = mailbox.getAndSet(new ConcurrentLinkedQueue<>());

        RegioniumContext.enter(this);
        try {
            if (workerLogged.compareAndSet(false, true)) {
                Regionium.LOGGER.info("Region {} is executing on {}", id, Thread.currentThread().getName());
            }
            RegioniumTask task;
            while ((task = currentMailbox.poll()) != null) {
                task.run();
            }
        } finally {
            RegioniumContext.exit();
            ticking.set(false);
        }
    }

    @Override
    public String toString() {
        return "RegioniumRegion[" + id + "]";
    }
}
