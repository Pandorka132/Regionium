package dev.pandor.regionium.core;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Execution domain for a group of Minecraft objects.
 *
 * <p>The region does not own a World instance. The final design intentionally
 * keeps Minecraft's world, chunks, entities and players in shared memory and
 * only moves execution ownership between regions.</p>
 */
public final class RegioniumRegion {
    private final int id;
    private final Queue<RegioniumTask> mailbox = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean ticking = new AtomicBoolean();

    RegioniumRegion(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public boolean isTicking() {
        return ticking.get();
    }

    public void execute(Runnable action) {
        mailbox.add(new RegioniumTask(action));
    }

    void tick() {
        if (!ticking.compareAndSet(false, true)) {
            throw new IllegalStateException("Region " + id + " is already ticking");
        }

        RegioniumContext.enter(this);
        try {
            /*
             * Do not drain tasks into a local list here. Tasks submitted while
             * this region is running must remain visible only at the next tick
             * boundary, so a bounded snapshot is used.
             */
            int taskCount = mailbox.size();
            for (int i = 0; i < taskCount; i++) {
                RegioniumTask task = mailbox.poll();
                if (task == null) {
                    break;
                }
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
