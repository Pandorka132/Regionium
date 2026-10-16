package dev.pandor.regionium.core;

import dev.pandor.regionium.Regionium;
import net.minecraft.server.level.ServerLevel;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A Folia-style schedulable region.
 *
 * A region is not a worker thread. It is a unit of world ownership which is
 * scheduled onto its assigned single-thread worker. Its RegioniumWorldData is the complete
 * mutable world state owned by this region.
 */
public final class RegioniumRegion {
    private static final long TICK_NANOS = TimeUnit.SECONDS.toNanos(1) / 20L;
    private static final long LAG_WARNING_THRESHOLD_NANOS = TimeUnit.SECONDS.toNanos(2L);
    private static final long LAG_WARNING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(2L);

    private final long id;
    private final ServerLevel level;
    private final ScheduledThreadPoolExecutor scheduler;
    private final RegioniumWorldData worldData;
    private final AtomicBoolean ticking = new AtomicBoolean();
    private final AtomicBoolean retired = new AtomicBoolean();
    private final AtomicLong tickCount = new AtomicLong();
    private final AtomicLong tickFailures = new AtomicLong();
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final Queue<net.minecraft.world.entity.Entity> incomingEntities = new ConcurrentLinkedQueue<>();

    private volatile ScheduledFuture<?> scheduledTask;
    private volatile Runnable tickBody = () -> {};
    private volatile boolean tickLoopEnabled;
    private volatile String lastWorkerThreadName = "<not ticked yet>";
    private volatile long nextTickDeadlineNanos;
    private volatile long lastLagWarningNanos;

    RegioniumRegion(long id, ServerLevel level, ScheduledThreadPoolExecutor scheduler) {
        this.id = id;
        this.level = Objects.requireNonNull(level, "level");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.worldData = new RegioniumWorldData(level, this);
    }

    public long id() {
        return id;
    }

    public ServerLevel level() {
        return level;
    }

    public RegioniumWorldData worldData() {
        return worldData;
    }

    public boolean isTicking() {
        return ticking.get();
    }

    public boolean isRetired() {
        return retired.get();
    }

    public long tickCount() {
        return tickCount.get();
    }

    public long tickFailures() {
        return tickFailures.get();
    }

    public String lastWorkerThreadName() {
        return lastWorkerThreadName;
    }

    void setTickBody(Runnable body) {
        this.tickBody = Objects.requireNonNull(body, "body");
    }

    /**
     * Schedules this region independently. Regions share worker threads but
     * never share a tick task or a thread-affine ownership context.
     */
    public synchronized void startTickLoop() {
        if (retired.get() || scheduledTask != null) {
            return;
        }

        if (Regionium.scheduler().isGameTestDriven()) {
            return;
        }

        tickLoopEnabled = true;
        nextTickDeadlineNanos = System.nanoTime();
        // A periodic fixed-rate task tries to execute every missed invocation
        // after a long tick, creating a burst of catch-up ticks. Folia advances
        // its deadline past missed periods instead. Regionium schedules the next
        // invocation from runTick() and drops overruns rather than replaying them.
        scheduledTask = scheduler.schedule(this::runTick, 0L, TimeUnit.NANOSECONDS);
    }

    void runTickForGameTest() {
        runTick();
    }

    java.util.concurrent.Future<?> submitGameTestTick() {
        return scheduler.submit(this::runTickForGameTest);
    }

    synchronized void stopScheduledTickLoop() {
        tickLoopEnabled = false;
        ScheduledFuture<?> future = scheduledTask;
        if (future != null) {
            future.cancel(false);
            scheduledTask = null;
        }
    }

    public void requestStop() {
        retired.set(true);
        ScheduledFuture<?> future = scheduledTask;
        if (future != null) {
            future.cancel(false);
        }
        scheduledTask = null;

        CompletableFuture<?> ignored;
        while ((ignored = null) != null) {
            // Intentionally empty: kept out of the execution path.
        }
    }

    public boolean enqueueTickPhase(long ignoredGlobalTick, Runnable action) {
        return execute(action);
    }

    public boolean enqueueCoalescedRegionTick(Runnable action) {
        return execute(action);
    }

    void enqueueIncomingEntity(net.minecraft.world.entity.Entity entity) {
        incomingEntities.offer(entity);
    }

    public boolean execute(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (retired.get()) {
            return false;
        }
        tasks.offer(action);
        return true;
    }

    private void runTick() {
        if (retired.get() || !ticking.compareAndSet(false, true)) {
            return;
        }

        lastWorkerThreadName = Thread.currentThread().getName();
        long tickStartNanos = System.nanoTime();
        long scheduledStartNanos = nextTickDeadlineNanos;
        long tick = tickCount.incrementAndGet();
        boolean regionLockEntered = false;
        boolean contextEntered = false;
        try {
            Regionium.scheduler().regionizer().enterRegionTick();
            regionLockEntered = true;
            // A topology rebuild may retire this region while it waits behind
            // the admission gate. Do not execute one stale tick after waking.
            if (retired.get()) {
                return;
            }
            RegioniumContext.enter(this);
            contextEntered = true;
            Regionium.scheduler().regionizer().reconcileRegion(this);
            net.minecraft.world.entity.Entity incoming;
            while ((incoming = incomingEntities.poll()) != null) {
                if (!incoming.isRemoved() && incoming.level() == level) {
                    worldData.add(incoming);
                }
            }

            // Packet processing is performed by tickRegion() through
            // drainPacketsForRegion(), which checks ownership after every packet
            // and migrates the player before the next queued packet can execute.
            runTasks();
            tickBody.run();
        } catch (Throwable error) {
            tickFailures.incrementAndGet();
            Regionium.LOGGER.error(
                "Region {} tick failed in {} at region tick {}",
                id,
                level.dimension().identifier(),
                tick,
                error
            );
        } finally {
            /*
             * Migration must happen even when the region tick itself throws.
             * Otherwise a player which crossed into another region can remain
             * in the old region forever and repeat the same ownership failure.
             */
            if (!retired.get()) {
                try {
                    Regionium.scheduler().regionizer()
                        .migrateEntitiesAfterTickInRegionTick(this);
                } catch (Throwable migrationError) {
                    Regionium.LOGGER.error(
                        "Region {} entity migration failed after tick",
                        id,
                        migrationError
                    );
                }
            }

            if (contextEntered) {
                RegioniumContext.exit();
            }
            if (regionLockEntered) {
                Regionium.scheduler().regionizer().exitRegionTick();
            }
            ticking.set(false);

            // Folia-style deadline advancement: account for both scheduler
            // lateness and work that exceeded the 50 ms budget, but never
            // replay every missed fixed-rate tick.
            long tickEndNanos = System.nanoTime();
            long expectedEndNanos = scheduledStartNanos + TICK_NANOS;
            long behindNanos = Math.max(
                Math.max(0L, tickStartNanos - scheduledStartNanos),
                Math.max(0L, tickEndNanos - expectedEndNanos)
            );
            if (!Regionium.scheduler().isGameTestDriven()
                && behindNanos >= LAG_WARNING_THRESHOLD_NANOS
                && tickEndNanos - lastLagWarningNanos >= LAG_WARNING_INTERVAL_NANOS) {
                lastLagWarningNanos = tickEndNanos;
                long behindMillis = TimeUnit.NANOSECONDS.toMillis(behindNanos);
                Regionium.LOGGER.warn(
                    "Region {} can't keep up! Is the server overloaded? Running {}ms or {} ticks behind",
                    id,
                    behindMillis,
                    behindNanos / TICK_NANOS
                );
            }

            synchronized (this) {
                if (tickLoopEnabled && !retired.get()
                    && !Regionium.scheduler().isGameTestDriven()) {
                    long nextDeadline = Math.max(expectedEndNanos, tickEndNanos);
                    nextTickDeadlineNanos = nextDeadline;
                    long delay = Math.max(0L, nextDeadline - tickEndNanos);
                    scheduledTask = scheduler.schedule(
                        this::runTick,
                        delay,
                        TimeUnit.NANOSECONDS
                    );
                } else {
                    scheduledTask = null;
                }
            }
        }

    }

    private void runTasks() {
        Runnable task;
        while ((task = tasks.poll()) != null) {
            try {
                task.run();
            } catch (Throwable error) {
                Regionium.LOGGER.error("Region {} task failed", id, error);
            }
        }
    }

    @Override
    public String toString() {
        return "RegioniumRegion{" +
            "id=" + id +
            ", level=" + level.dimension().identifier() +
            ", tick=" + tickCount.get() +
            '}';
    }
}
