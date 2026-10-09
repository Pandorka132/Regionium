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
 * scheduled onto a shared worker pool. Its RegioniumWorldData is the complete
 * mutable world state owned by this region.
 */
public final class RegioniumRegion {
    private static final long TICK_NANOS = TimeUnit.SECONDS.toNanos(1) / 20L;

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
    private volatile String lastWorkerThreadName = "<not ticked yet>";

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

        scheduledTask = scheduler.scheduleAtFixedRate(
            this::runTick,
            0L,
            TICK_NANOS,
            TimeUnit.NANOSECONDS
        );
    }

    void runTickForGameTest() {
        runTick();
    }

    void stopScheduledTickLoop() {
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
        long tick = tickCount.incrementAndGet();
        boolean regionLockEntered = false;
        boolean contextEntered = false;
        try {
            Regionium.scheduler().regionizer().enterRegionTick();
            regionLockEntered = true;
            RegioniumContext.enter(this);
            contextEntered = true;
            Regionium.scheduler().regionizer().reconcileRegion(this);
            net.minecraft.world.entity.Entity incoming;
            while ((incoming = incomingEntities.poll()) != null) {
                if (!incoming.isRemoved() && incoming.level() == level) {
                    worldData.add(incoming);
                }
            }

            // PacketProcessor is the ownership queue for ServerGamePacketListenerImpl.
            // Network threads only enqueue here; the owning region must execute the
            // queued packet before its world tick. Without this drain, packets sent
            // after a region transfer remain queued forever, making the client appear
            // dead (no movement/input, gamemode changes, block interaction, etc.).
            Regionium.scheduler().drainRegionPackets(this);

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
