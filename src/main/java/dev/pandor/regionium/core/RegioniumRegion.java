package dev.pandor.regionium.core;

import dev.pandor.regionium.Regionium;
import net.minecraft.server.level.ServerLevel;

import java.util.Objects;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;
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
 * scheduled onto the shared worker pool. Its RegioniumWorldData is the complete
 * mutable world state owned by this region.
 */
public final class RegioniumRegion {
    /** Regionizer lifecycle. Transitions are coordinated by RegioniumRegionizer.lifecycleLock. */
    public enum LifecycleState {
        TRANSIENT,
        READY,
        TICKING,
        DEAD
    }

    private static final long TICK_NANOS = TimeUnit.SECONDS.toNanos(1) / 20L;
    private static final long LAG_WARNING_THRESHOLD_NANOS = TimeUnit.SECONDS.toNanos(2L);
    private static final long LAG_WARNING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(2L);

    private final long id;
    private final ServerLevel level;
    private final ScheduledThreadPoolExecutor scheduler;
    private final RegioniumWorldData worldData;
    private final AtomicBoolean retired = new AtomicBoolean();
    private final AtomicBoolean taskDrainScheduled = new AtomicBoolean();
    private final AtomicLong tickCount = new AtomicLong();
    private final AtomicLong tickFailures = new AtomicLong();
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final Queue<net.minecraft.world.entity.Entity> incomingEntities = new ConcurrentLinkedQueue<>();

    // Folia ThreadedRegionizer's deferred merge graph. Both directions are
    // updated together and may only be mutated while RegioniumRegionizer's
    // lifecycle lock is held.
    private final Set<RegioniumRegion> mergeIntoLater =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<RegioniumRegion> expectingMergeFrom =
        Collections.newSetFromMap(new IdentityHashMap<>());

    private volatile ScheduledFuture<?> scheduledTask;
    private volatile Runnable tickBody = () -> {};
    private volatile boolean tickLoopEnabled;
    private volatile String lastWorkerThreadName = "<not ticked yet>";
    private volatile long nextTickDeadlineNanos;
    private volatile long lastLagWarningNanos;
    private volatile LifecycleState lifecycleState = LifecycleState.TRANSIENT;
    private volatile boolean topologyTransitionPending;

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
        return lifecycleState == LifecycleState.TICKING;
    }

    public LifecycleState lifecycleState() {
        return lifecycleState;
    }

    /** Called only while RegioniumRegionizer.lifecycleLock is held. */
    void setLifecycleState(LifecycleState state) {
        this.lifecycleState = Objects.requireNonNull(state, "state");
    }

    boolean hasPendingMerges() {
        return !mergeIntoLater.isEmpty() || !expectingMergeFrom.isEmpty();
    }

    boolean hasPendingTopologyTransition() {
        return topologyTransitionPending;
    }

    void setPendingTopologyTransition(boolean pending) {
        topologyTransitionPending = pending;
    }

    Set<RegioniumRegion> mergeIntoLater() {
        return mergeIntoLater;
    }

    Set<RegioniumRegion> expectingMergeFrom() {
        return expectingMergeFrom;
    }

    void addMergeIntoLater(RegioniumRegion target) {
        if (target == this || target.isRetired()) {
            throw new IllegalStateException("Invalid deferred merge target " + target + " for " + this);
        }
        boolean sourceAdded = mergeIntoLater.add(target);
        boolean targetAdded = target.expectingMergeFrom.add(this);
        if (sourceAdded != targetAdded) {
            throw new IllegalStateException("Inconsistent deferred merge graph between " + this + " and " + target);
        }
    }

    void removeMergeIntoLater(RegioniumRegion target) {
        boolean sourceRemoved = mergeIntoLater.remove(target);
        boolean targetRemoved = target.expectingMergeFrom.remove(this);
        if (sourceRemoved != targetRemoved) {
            throw new IllegalStateException("Inconsistent deferred merge graph removal between " + this + " and " + target);
        }
    }

    void clearMergeRelationships() {
        for (RegioniumRegion target : List.copyOf(mergeIntoLater)) {
            removeMergeIntoLater(target);
        }
        for (RegioniumRegion source : List.copyOf(expectingMergeFrom)) {
            source.removeMergeIntoLater(this);
        }
    }

    void clearMergeRelationshipsWith(Set<RegioniumRegion> participants) {
        for (RegioniumRegion target : List.copyOf(mergeIntoLater)) {
            if (participants.contains(target)) {
                removeMergeIntoLater(target);
            }
        }
        for (RegioniumRegion source : List.copyOf(expectingMergeFrom)) {
            if (participants.contains(source)) {
                source.removeMergeIntoLater(this);
            }
        }
    }

    /** Folia ThreadedRegion.mergeInto(): forward every outstanding merge edge. */
    void forwardMergeGraphInto(RegioniumRegion target) {
        if (target == this || target.isRetired()) {
            throw new IllegalStateException("Invalid merge target " + target + " for " + this);
        }

        for (RegioniumRegion source : List.copyOf(expectingMergeFrom)) {
            if (source == target) {
                throw new IllegalStateException("Cyclic deferred merge involving " + this + " and " + target);
            }
            source.removeMergeIntoLater(this);
            source.addMergeIntoLater(target);
        }

        for (RegioniumRegion destination : List.copyOf(mergeIntoLater)) {
            removeMergeIntoLater(destination);
            if (destination != target) {
                target.addMergeIntoLater(destination);
            }
        }
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
     * Schedules this region independently on the shared deadline queue. A
     * region may run on different physical workers on successive ticks, but its
     * mutable world state remains owned by this region and ticks never overlap.
     */
    public synchronized void startTickLoop() {
        if (retired.get()) {
            return;
        }

        ScheduledFuture<?> current = scheduledTask;
        if (current != null && !current.isDone()) {
            return;
        }
        // A failed lifecycle/finalization path can leave the last completed
        // ScheduledFuture published even though no next tick was queued. Treat
        // a completed future as an absent schedule so activation can recover.
        scheduledTask = null;

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

    /** Drains cross-region transfers before ticking or during a region lifecycle handoff. */
    List<net.minecraft.world.entity.Entity> drainIncomingEntities() {
        List<net.minecraft.world.entity.Entity> result = new java.util.ArrayList<>();
        net.minecraft.world.entity.Entity entity;
        while ((entity = incomingEntities.poll()) != null) {
            result.add(entity);
        }
        return result;
    }

    public boolean execute(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (retired.get()) {
            return false;
        }
        tasks.offer(action);
        scheduleTaskDrain();
        return true;
    }

    /**
     * Folia runs region tasks independently of world ticking. Run a task-only
     * pass when work is queued, serialized against normal ticks by the regionizer
     * lifecycle state, so destination chunks need not be entity-ticking.
     */
    void scheduleTaskDrain() {
        if (retired.get() || !taskDrainScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            scheduler.execute(this::runTaskOnlyPass);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            taskDrainScheduled.set(false);
            if (!retired.get()) {
                throw rejected;
            }
        }
    }

    private void runTaskOnlyPass() {
        boolean barrierEntered = false;
        boolean lifecycleEntered = false;
        boolean contextEntered = false;
        try {
            Regionium.scheduler().regionizer().enterRegionTick();
            barrierEntered = true;
            if (!Regionium.scheduler().regionizer().beginRegionTick(this)) {
                return;
            }
            lifecycleEntered = true;
            RegioniumContext.enter(this);
            contextEntered = true;
            worldData.runOwnedTasks();
            runTasks();
        } catch (Throwable failure) {
            Regionium.scheduler().regionTickFailed(this, tickCount.get(), failure);
        } finally {
            try {
                if (contextEntered) {
                    RegioniumContext.exit();
                }
            } finally {
                try {
                    if (lifecycleEntered) {
                        Regionium.scheduler().regionizer().finishRegionTick(this);
                    }
                } finally {
                    if (barrierEntered) {
                        Regionium.scheduler().regionizer().exitRegionTick();
                    }
                    if (lifecycleEntered && topologyTransitionPending) {
                        try {
                            Regionium.scheduler().regionizer().onRegionRelease(this);
                        } catch (Throwable transitionError) {
                            Regionium.scheduler().regionTickFailed(this, tickCount.get(), transitionError);
                        }
                    }
                    taskDrainScheduled.set(false);
                    if (!retired.get() && (!tasks.isEmpty() || worldData.hasRunnableOwnedTasks())) {
                        scheduler.schedule(this::scheduleTaskDrain, 1L, TimeUnit.MILLISECONDS);
                    }
                }
            }
        }
    }

    private void runTick() {
        if (retired.get()) {
            return;
        }

        lastWorkerThreadName = Thread.currentThread().getName();
        long tickStartNanos = System.nanoTime();
        long scheduledStartNanos = nextTickDeadlineNanos;
        long tick = 0L;
        boolean regionLockEntered = false;
        boolean contextEntered = false;
        boolean lifecycleEntered = false;
        try {
            Regionium.scheduler().regionizer().enterRegionTick();
            regionLockEntered = true;
            // A deferred topology transition may retire this region while it
            // waits for admission. Do not execute a stale tick after waking.
            if (retired.get()) {
                return;
            }
            if (!Regionium.scheduler().regionizer().beginRegionTick(this)) {
                return;
            }
            lifecycleEntered = true;
            // Count admitted region ticks only. A scheduled invocation can
            // become stale while waiting for the regionizer admission gate.
            tick = tickCount.incrementAndGet();
            RegioniumContext.enter(this);
            contextEntered = true;
            Regionium.scheduler().regionizer().reconcileRegion(this);
            // Spatially-owned async work (portal transfers, chunk-preparation
            // continuations, etc.) must run before the world-tick readiness gate.
            // Waiting until tickBody would deadlock operations whose own chunk
            // tickets are what make the region's full ticking neighborhood ready.
            worldData.runOwnedTasks();
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
            Regionium.scheduler().regionTickFailed(this, tick, error);
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

            try {
                if (contextEntered) {
                    RegioniumContext.exit();
                }
            } finally {
                if (regionLockEntered) {
                    try {
                        if (lifecycleEntered) {
                            Regionium.scheduler().regionizer().finishRegionTick(this);
                        }
                    } finally {
                        // A lifecycle transition failure must not leak the shared
                        // tick barrier and permanently block topology/save work.
                        Regionium.scheduler().regionizer().exitRegionTick();
                    }
                }
            }

            // Folia advances the schedule by the number of periods already
            // elapsed when this tick started. Keep that phase when the worker
            // starts late, while never scheduling the next tick before this
            // tick has finished.
            long tickEndNanos = System.nanoTime();
            long expectedEndNanos = scheduledStartNanos + TICK_NANOS;
            long elapsedPeriods = Math.max(
                1L,
                (tickStartNanos - scheduledStartNanos) / TICK_NANOS + 1L
            );
            long idealNextDeadlineNanos = scheduledStartNanos + elapsedPeriods * TICK_NANOS;
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
                if (tickLoopEnabled && !retired.get() && !topologyTransitionPending
                    && !Regionium.scheduler().isGameTestDriven()) {
                    long nextDeadline = Math.max(idealNextDeadlineNanos, tickEndNanos);
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

            // Run release callbacks only after this invocation has completed
            // its scheduling phase. A merge may reactivate this region; doing
            // so earlier would let startTickLoop() race with this tick's finally
            // block and schedule the same region twice.
            if (lifecycleEntered && topologyTransitionPending) {
                try {
                    Regionium.scheduler().regionizer().onRegionRelease(this);
                } catch (Throwable transitionError) {
                    Regionium.scheduler().regionTickFailed(this, tick, transitionError);
                }
            }
        }

    }

    private void runTasks() {
        Runnable task;
        while ((task = tasks.poll()) != null) {
            // Folia runs intermediate region tasks inside the region scheduler's
            // failure boundary. Let failures reach runTick() so the server
            // stops instead of continuing a partially applied region tick.
            task.run();
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
