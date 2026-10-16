package dev.pandor.regionium.core;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.mixins.ChunkMapRegioniumVisibleAccessorMixin;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.concurrent.locks.StampedLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stateful spatial region manager.
 *
 * This is the Fabric-side equivalent of Folia's ThreadedRegionizer:
 * chunk topology is persistent, regions own RegioniumWorldData, and split/
 * merge/topology changes migrate the complete region-local state.
 *
 * There is deliberately no second chunk lease or object ownership table.
 */
public final class RegioniumRegionizer {
    private static final int REGION_SECTION_SHIFT = 3; // 8x8 chunks per region section
    private static final int MERGE_RADIUS = 1;

    private final Map<ServerLevel, LevelState> worlds =
        Collections.synchronizedMap(new IdentityHashMap<>());
    private final StampedLock lifecycleLock = new StampedLock();
    // A topology publication is an ownership transfer. It must wait until every
    // in-flight region tick has left the old ownership snapshot; otherwise a
    // departing and arriving region can concurrently tick the same LevelChunk.
    // Non-fair is intentional: a topology/save writer may wait for a slow
    // region, but it must not prevent unrelated regions from acquiring the
    // read side while that writer is queued. A fair lock turns one slow region
    // into a global tick barrier.
    private final ReentrantReadWriteLock regionTickBarrier = new ReentrantReadWriteLock(false);
    // Stop admitting new region ticks while a dirty topology waits for the
    // current readers to drain; otherwise independently scheduled regions can
    // keep overlapping and starve the non-blocking topology writer forever.
    private volatile boolean topologyWritePending;
    private final Function<ServerLevel, RegioniumRegion> regionFactory;

    public RegioniumRegionizer(Function<ServerLevel, RegioniumRegion> regionFactory) {
        this.regionFactory = Objects.requireNonNull(regionFactory, "regionFactory");
    }

    /**
     * Pins the published chunk ownership snapshot for the full region tick.
     * Topology changes take the write lock, so ownership cannot change while
     * a region is mutating chunks selected from the previous snapshot.
     */
    public void enterRegionTick() {
        while (true) {
            while (topologyWritePending) {
                LockSupport.parkNanos(100_000L);
            }

            regionTickBarrier.readLock().lock();
            // Close the race between checking the gate and acquiring the lock.
            if (!topologyWritePending) {
                return;
            }
            regionTickBarrier.readLock().unlock();
        }
    }

    public void exitRegionTick() {
        regionTickBarrier.readLock().unlock();
    }

    /**
     * Excludes every region tick while vanilla copies a chunk into its
     * SerializableChunkData snapshot. Synchronizing on LevelChunk alone is
     * insufficient: vanilla block/random ticks and block-entity ticks do not
     * all acquire that monitor before mutating the chunk.
     *
     * The lock is held only for the in-memory snapshot, never for asynchronous
     * disk I/O. ChunkMap.save() invokes copyOf() synchronously before it queues
     * the serialized data for background writing.
     */
    public boolean enterChunkSaveSnapshot() {
        // GameTests rely on deterministic snapshots and have no real command
        // latency to protect, so retain the strict barrier there.
        if (Regionium.scheduler().isGameTestDriven()) {
            while (!regionTickBarrier.writeLock().tryLock()) {
                LockSupport.parkNanos(100_000L);
            }
            return true;
        }

        // ChunkMap.save() runs synchronously on the server thread. It must
        // never wait for every region to finish ticking: one slow region would
        // otherwise freeze command dispatch and all global-server work. The
        // caller skips this save attempt if the barrier is unavailable, keeping
        // the snapshot consistent and allowing a later save pass to retry.
        return regionTickBarrier.writeLock().tryLock();
    }

    public void exitChunkSaveSnapshot() {
        regionTickBarrier.writeLock().unlock();
    }

    public void registerLevel(ServerLevel level) {
        worlds.computeIfAbsent(level, ignored -> new LevelState(level));
    }

    public void unregisterLevel(ServerLevel level) {
        worlds.remove(level);
        refreshTopologyWritePending();
    }

    public void markTopologyDirty(ServerLevel level) {
        LevelState state = worlds.get(level);
        if (state != null) {
            state.topologyDirty = true;
        }
    }

    /**
     * Registers a ChunkHolder in the persistent region topology. Folia's
     * ThreadedRegionizer is driven by holder creation/destruction, not by
     * ticking-state transitions.
     */
    public void addChunkHolder(ServerLevel level, ChunkHolder holder) {
        lifecycleLock.writeLock();
        try {
            LevelState state = worlds.get(level);
            if (state == null) return;

            long chunkKey = holder.getPos().pack();
            if (isRegionChunk(holder)) {
                ChunkHolder previous = state.chunkHolders.put(chunkKey, holder);
                if (previous != holder && state.regionChunks.add(chunkKey)) {
                    state.topologyDirty = true;
                }
                if (state.leasedChunks.add(chunkKey)) {
                    state.topologyDirty = true;
                }
            } else {
                state.chunkHolders.remove(chunkKey);
                if (state.regionChunks.remove(chunkKey)) {
                    state.topologyDirty = true;
                }
                if (state.leasedChunks.remove(chunkKey)) {
                    state.topologyDirty = true;
                }
            }
        } finally {
            lifecycleLock.tryUnlockWrite();
        }
    }

    /** Removes a ChunkHolder at the actual vanilla unload boundary. */
    public void removeChunkHolder(ServerLevel level, long chunkKey) {
        long stamp = lifecycleLock.writeLock();
        try {
            LevelState state = worlds.get(level);
            if (state == null) return;
            if (state.chunkHolders.remove(chunkKey) != null) {
                state.regionChunks.remove(chunkKey);
                state.leasedChunks.remove(chunkKey);
                state.topologyDirty = true;
            }
        } finally {
            lifecycleLock.tryUnlockWrite();
        }
    }

    /**
     * Synchronizes one ChunkHolder's simulation status into the owning
     * region. Ticking/entity-ticking are region-local state and do not change
     * the holder's spatial ownership.
     */
    public void updateChunkLifecycle(ServerLevel level, ChunkHolder holder) {
        long stamp = lifecycleLock.writeLock();
        try {
            LevelState state = worlds.get(level);
            if (state == null) return;

            long chunkKey = holder.getPos().pack();

            // ChunkLevel.isLoaded() includes generation-dependency holders well
            // outside FULL chunks. Folia's spatial topology must include actual
            // FULL chunk leases, including non-ticking FULL chunks, but not
            // every holder needed only to generate a neighbouring chunk.
            boolean leased = isRegionChunk(holder);
            boolean tracked = state.regionChunks.contains(chunkKey);
            if (leased) {
                state.chunkHolders.put(chunkKey, holder);
                if (!tracked && state.regionChunks.add(chunkKey)) {
                    state.topologyDirty = true;
                }
                if (state.leasedChunks.add(chunkKey)) {
                    state.topologyDirty = true;
                }
            } else {
                state.chunkHolders.remove(chunkKey);
                if (state.regionChunks.remove(chunkKey)) {
                    state.topologyDirty = true;
                }
                if (state.leasedChunks.remove(chunkKey)) {
                    state.topologyDirty = true;
                }
            }
        } finally {
            lifecycleLock.unlockWrite(stamp);
        }
    }

    private void updateChunkLifecycleLocked(ServerLevel level, ChunkHolder holder) {
        LevelState state = worlds.get(level);
        if (state == null) {
            return;
        }

        long chunkKey = holder.getPos().pack();
        boolean leased = isRegionChunk(holder);
        if (leased) {
            state.chunkHolders.put(chunkKey, holder);
            if (state.regionChunks.add(chunkKey)) {
                state.topologyDirty = true;
            }
            if (state.leasedChunks.add(chunkKey)) {
                state.topologyDirty = true;
            }
        } else {
            state.chunkHolders.remove(chunkKey);
            if (state.regionChunks.remove(chunkKey)) {
                state.topologyDirty = true;
            }
            if (state.leasedChunks.remove(chunkKey)) {
                state.topologyDirty = true;
            }
        }

        // Region-local chunk state is reconciled by the owning region thread.
        // Never mutate RegioniumWorldData from the server/chunk lifecycle thread.
    }

    public void rebalance(ServerLevel level) {
        LevelState state = worlds.get(level);
        if (state == null || !state.topologyDirty) {
            return;
        }

        // Lock order is always tick barrier -> lifecycle lock. A region tick
        // holds the read side before it consults the lifecycle snapshot, so
        // taking these locks in the reverse order here would deadlock. Never
        // queue a topology writer behind an active region tick: a queued writer
        // can prevent unrelated region ticks from acquiring the read lock. If
        // any region is still ticking, leave topologyDirty set and retry on the
        // next global server tick instead of turning this into a global stall.
        topologyWritePending = true;
        if (!regionTickBarrier.writeLock().tryLock()) {
            return;
        }
        try {
            lifecycleLock.writeLock();
            try {
                state = worlds.get(level);
                if (state == null || !state.topologyDirty) {
                    return;
                }
                rebalanceLocked(level);
            } finally {
                lifecycleLock.tryUnlockWrite();
            }
        } finally {
            regionTickBarrier.writeLock().unlock();
            refreshTopologyWritePending();
        }
    }

    /** Keep the gate raised until every registered level has a clean topology. */
    private void refreshTopologyWritePending() {
        synchronized (worlds) {
            for (LevelState state : worlds.values()) {
                if (state.topologyDirty) {
                    topologyWritePending = true;
                    return;
                }
            }
            topologyWritePending = false;
        }
    }

    private void rebalanceLocked(ServerLevel level) {
        LevelState state = worlds.get(level);
        if (state == null) {
            return;
        }

        /*
         * ChunkMap's visible map is the lifecycle snapshot for holder state,
         * but ChunkLevel.isLoaded() includes generation-dependency holders
         * beyond FULL chunks. Region topology must include leased FULL chunks
         * (including non-ticking chunks), not every holder needed to generate
         * a neighbouring chunk. Reconcile against the current FULL ticket
         * status so stale/recycled holders cannot keep regions connected.
         *
         * This follows Folia's persistent holder/section topology model:
         * ownership is not derived from player coordinates or simulation
         * distance.
         */
        Map<Long, ChunkHolder> visible =
            ((ChunkMapRegioniumVisibleAccessorMixin) level.getChunkSource().chunkMap)
                .regionium$getVisibleChunkMap();

        Map<Long, ChunkHolder> liveHolders = new LinkedHashMap<>();
        for (ChunkHolder holder : visible.values()) {
            // isLoaded() is deliberately broader than FULL: it includes
            // generation dependencies and creates a large false topology halo.
            if (!isRegionChunk(holder)) {
                continue;
            }

            liveHolders.put(holder.getPos().pack(), holder);
        }

        // Reconcile removals as well as additions. Stale holders must disappear
        // from the topology before connected components are calculated.
        state.regionChunks.retainAll(liveHolders.keySet());
        state.chunkHolders.keySet().retainAll(liveHolders.keySet());
        state.leasedChunks.retainAll(liveHolders.keySet());
        state.regionChunks.addAll(liveHolders.keySet());
        state.chunkHolders.putAll(liveHolders);

        Set<Long> currentChunks = new LinkedHashSet<>(liveHolders.keySet());
        state.topologyDirty = false;

        Set<Long> currentSections = new LinkedHashSet<>();
        for (long chunk : currentChunks) {
            currentSections.add(sectionKey(
                ChunkPos.getX(chunk) >> REGION_SECTION_SHIFT,
                ChunkPos.getZ(chunk) >> REGION_SECTION_SHIFT
            ));
        }

        if (currentSections.isEmpty()) {
            // Publish the empty topology while the lifecycle write lock and
            // region-tick barrier are held, then synchronously clear and retire
            // every former region. Inactive regions must not retain ownership.
            state.sectionOwners = Map.of();
            state.active = Set.of();
            for (RegioniumRegion region : List.copyOf(state.regions)) {
                region.stopScheduledTickLoop();
                migrateRegionState(region, state, Map.of());
                region.requestStop();
            }
            state.regions.clear();
            return;
        }

        // Only sections containing at least one leased FULL chunk participate
        // in the topology. Non-FULL chunks and empty neighbouring sections do
        // not independently create or retain an owner.
        List<Set<Long>> components = connectedComponents(currentSections);
        components.sort(Comparator
            .<Set<Long>>comparingInt(Set::size)
            .reversed()
            .thenComparingLong(set -> set.iterator().next()));

        Map<Long, RegioniumRegion> oldOwners = new LinkedHashMap<>(state.sectionOwners);
        Set<RegioniumRegion> used = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Long, RegioniumRegion> nextOwners = new LinkedHashMap<>();

        for (Set<Long> component : components) {
            RegioniumRegion selected = selectRegion(component, oldOwners, state.regions, used);
            if (selected == null) {
                // This is a genuinely new spatial region. Do not recycle an
                // inactive region object: Folia region identities have a
                // lifecycle and a retired region must never come back to life.
                selected = regionFactory.apply(level);
                state.regions.add(selected);
            }

            used.add(selected);
            for (long section : component) {
                // Ownership is recorded once per 8x8 region section. Chunk
                // coordinates are translated to this key only at lookup time.
                nextOwners.put(section, selected);
            }
        }

        // Publish the next topology under the lifecycle write lock. The tick
        // barrier is still held, so no region can observe this snapshot until
        // every region-local container/entity has been moved to its new owner.
        state.sectionOwners = Map.copyOf(nextOwners);
        state.active = Set.copyOf(used);

        for (RegioniumRegion region : List.copyOf(state.regions)) {
            migrateRegionState(region, state, nextOwners);
            if (!used.contains(region)) {
                region.stopScheduledTickLoop();
                region.requestStop();
                state.regions.remove(region);
            }
        }

        // Match Folia's RegionCallbacks.onRegionActive/onRegionInactive: a
        // region is scheduled only after the regionizer has established its
        // chunk ownership. This prevents a region tick from observing an
        // owned-by-null chunk during initial chunk promotion.
        for (RegioniumRegion region : state.regions) {
            if (state.active.contains(region)) {
                region.startTickLoop();
            } else {
                region.stopScheduledTickLoop();
            }
        }

        // Entity state is migrated by the owning region after its tick.
        // Rebuilding topology must never touch another region's mutable data.

    }

    private void migrateEntities(ServerLevel level, LevelState state) {
        Set<Entity> all = Collections.newSetFromMap(new IdentityHashMap<>());

        for (RegioniumRegion region : state.regions) {
            all.addAll(region.worldData().entities());
        }

        for (ServerPlayer player : level.players()) {
            all.add(player);
        }

        for (Entity entity : all) {
            if (entity.isRemoved() || entity.level() != level) {
                for (RegioniumRegion region : state.regions) {
                    region.worldData().remove(entity);
                }
                continue;
            }

            RegioniumRegion destination = ownerForChunk(state.sectionOwners, entity.chunkPosition().pack());
            if (destination == null) {
                continue;
            }

            RegioniumRegion source = findEntityRegion(state.regions, entity);
            if (source == destination) {
                continue;
            }

            if (source != null) {
                source.worldData().remove(entity);
            }
            destination.worldData().add(entity);

            if (entity instanceof ServerPlayer player) {
                ((dev.pandor.regionium.access.ServerPlayerRegioniumPacketAccess) (Object) player)
                    .regionium$updateRegion(destination.worldData());
            }

        }
    }

    private static boolean isRegionChunk(ChunkHolder holder) {
        return holder != null
            && net.minecraft.server.level.ChunkLevel.fullStatus(holder.getTicketLevel())
                .isOrAfter(net.minecraft.server.level.FullChunkStatus.FULL);
    }

    private static RegioniumRegion findEntityRegion(
        List<RegioniumRegion> regions,
        Entity entity
    ) {
        for (RegioniumRegion region : regions) {
            if (region.worldData().hasEntity(entity)) {
                return region;
            }
        }
        return null;
    }

    private static RegioniumRegion selectRegion(
        Set<Long> component,
        Map<Long, RegioniumRegion> oldOwners,
        List<RegioniumRegion> regions,
        Set<RegioniumRegion> used
    ) {
        Map<RegioniumRegion, Integer> scores = new IdentityHashMap<>();

        for (long section : component) {
            RegioniumRegion owner = oldOwners.get(section);
            if (owner != null && !used.contains(owner)) {
                scores.merge(owner, 1, Integer::sum);
            }
        }

        return scores.entrySet().stream()
            .filter(entry -> !entry.getKey().isRetired())
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    private static List<Set<Long>> connectedComponents(Set<Long> sections) {
        Map<Long, Set<Long>> graph = new LinkedHashMap<>();
        for (long section : sections) {
            graph.put(section, new LinkedHashSet<>());
        }

        for (long section : sections) {
            int x = sectionX(section);
            int z = sectionZ(section);

            for (int dz = -MERGE_RADIUS; dz <= MERGE_RADIUS; ++dz) {
                for (int dx = -MERGE_RADIUS; dx <= MERGE_RADIUS; ++dx) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    long adjacent = sectionKey(x + dx, z + dz);
                    if (sections.contains(adjacent)) {
                        graph.get(section).add(adjacent);
                    }
                }
            }
        }

        List<Set<Long>> result = new ArrayList<>();
        Set<Long> visited = new LinkedHashSet<>();

        for (long root : sections) {
            if (!visited.add(root)) {
                continue;
            }

            Set<Long> component = new LinkedHashSet<>();
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(root);

            while (!queue.isEmpty()) {
                long current = queue.removeFirst();
                component.add(current);

                for (long next : graph.get(current)) {
                    if (visited.add(next)) {
                        queue.addLast(next);
                    }
                }
            }

            result.add(component);
        }

        return result;
    }

    /**
     * Reconciles only this region's private mutable world state against the
     * latest topology. The global topology lock is held only while taking a
     * short immutable snapshot; all RegioniumWorldData mutations happen on
     * the owning region thread.
     */
    public void reconcileRegion(RegioniumRegion region) {
        LevelState state = worlds.get(region.level());
        if (state == null || region.isRetired()) {
            return;
        }

        List<LevelChunk> desiredChunks = new ArrayList<>();
        long stamp = lifecycleLock.readLock();
        try {
            // Iterate actual holders; the ownership map is keyed by region
            // section and must not be mistaken for a chunk-holder registry.
            for (Map.Entry<Long, ChunkHolder> entry : state.chunkHolders.entrySet()) {
                long chunkKey = entry.getKey();
                if (ownerForChunk(state.sectionOwners, chunkKey) != region) {
                    continue;
                }
                LevelChunk chunk = entry.getValue().getTickingChunk();
                if (chunk != null) {
                    desiredChunks.add(chunk);
                }
            }
        } finally {
            lifecycleLock.unlockRead(stamp);
        }

        Set<Long> desiredKeys = new LinkedHashSet<>();
        for (LevelChunk chunk : desiredChunks) {
            desiredKeys.add(chunk.getPos().pack());
        }

        RegioniumWorldData data = region.worldData();
        for (LevelChunk chunk : List.copyOf(data.chunks())) {
            if (!desiredKeys.contains(chunk.getPos().pack())) {
                data.removeChunk(chunk);
            }
        }
        for (LevelChunk chunk : desiredChunks) {
            if (!data.hasChunk(chunk)) {
                data.addChunk(chunk);
            }
            ChunkHolder holder;
            stamp = lifecycleLock.readLock();
            try {
                holder = state.chunkHolders.get(chunk.getPos().pack());
            } finally {
                lifecycleLock.unlockRead(stamp);
            }
            long chunkKey = chunk.getPos().pack();
            var distanceManager = region.level().getChunkSource().chunkMap.getDistanceManager();
            boolean inBlockTickingRange = distanceManager.inBlockTickingRange(chunkKey);
            boolean inEntityTickingRange = distanceManager.inEntityTickingRange(chunkKey);
            data.setTickingChunk(chunk, inBlockTickingRange);

            LevelChunk entityTicking = holder == null
                ? null
                : holder.getEntityTickingChunkFuture()
                    .getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK)
                    .orElse(null);
            data.setEntityTickingChunk(chunk, inEntityTickingRange && chunk == entityTicking);
        }

        // PlayerList.placeNewPlayer can run before chunk topology exists. Keep
        // player membership authoritative by reconciling it on the owning
        // region thread instead of relying on a one-shot registration hook.
        for (ServerPlayer player : List.copyOf(region.level().players())) {
            if (player.isRemoved() || player.level() != region.level()) {
                data.remove(player);
                continue;
            }

            RegioniumRegion owner = owner(region.level(), player.chunkPosition().pack());
            if (owner == region) {
                if (!data.hasEntity(player)) {
                    data.add(player);
                    ((dev.pandor.regionium.access.ServerPlayerRegioniumPacketAccess) (Object) player)
                        .regionium$updateRegion(data);
                }
            } else {
                data.remove(player);
            }
        }
    }

    /**
     * Completes entity migration after the region tick. Destination state is
     * delivered through the destination region's incoming queue, so one
     * region never directly mutates another region's live world data.
     */
    public void migrateEntitiesAfterTickInRegionTick(RegioniumRegion source) {
        ServerLevel level = source.level();

        for (Entity entity : List.copyOf(source.worldData().entities())) {
            if (entity.isRemoved() || entity.level() != level) {
                source.worldData().remove(entity);
                continue;
            }

            migrateEntityWithoutLock(level, entity);
        }
    }

    /**
     * Compatibility entry point for callers which are not already inside a
     * region read-locked tick.
     */
    public void migrateEntitiesAfterTick(RegioniumRegion source) {
        lifecycleLock.writeLock();
        try {
            for (Entity entity : List.copyOf(source.worldData().entities())) {
                if (entity.isRemoved() || entity.level() != source.level()) {
                    source.worldData().remove(entity);
                    continue;
                }
                migrateEntityWithoutLock(source.level(), entity);
            }
        } finally {
            lifecycleLock.tryUnlockWrite();
        }
    }

    private void migrateEntityWithoutLock(ServerLevel level, Entity entity) {
        LevelState state = worlds.get(level);
        if (state == null || entity.level() != level || entity.isRemoved()) {
            return;
        }

        long chunkKey = entity.chunkPosition().pack();
        RegioniumRegion destination = ownerForChunk(state.sectionOwners, chunkKey);
        RegioniumRegion source = findEntityRegion(state.regions, entity);

        if (destination == null || source == destination) {
            return;
        }

        if (source != null) {
            source.worldData().remove(entity);
        }
        destination.enqueueIncomingEntity(entity);

        if (entity instanceof ServerPlayer player) {
            ((dev.pandor.regionium.access.ServerPlayerRegioniumPacketAccess) (Object) player)
                .regionium$updateRegion(destination.worldData());
            Regionium.LOGGER.info(
                "[PLAYER-REGION] player={} {} -> {} chunk={}",
                player.getGameProfile().name(),
                source == null ? -1 : source.id(),
                destination.id(),
                player.chunkPosition()
            );
        }
    }

    public void migrateEntity(ServerLevel level, Entity entity) {
        lifecycleLock.writeLock();
        try {
            migrateEntityLocked(level, entity);
        } finally {
            lifecycleLock.tryUnlockWrite();
        }
    }

    /** Moves an entity after a region-owned action; the caller holds the read lock. */
    public void migrateEntityAfterRegionAction(ServerLevel level, Entity entity) {
        migrateEntityWithoutLock(level, entity);
    }

    private void migrateEntityLocked(ServerLevel level, Entity entity) {
        LevelState state = worlds.get(level);
        if (state == null || entity.level() != level || entity.isRemoved()) {
            return;
        }

        long chunkKey = entity.chunkPosition().pack();
        RegioniumRegion destination = ownerForChunk(state.sectionOwners, chunkKey);
        RegioniumRegion source = findEntityRegion(state.regions, entity);

        if (destination == null) {
            return;
        }

        if (source == destination) {
            return;
        }

        if (source != null) {
            source.worldData().remove(entity);
        }
        destination.enqueueIncomingEntity(entity);

        if (entity instanceof ServerPlayer player) {
            ((dev.pandor.regionium.access.ServerPlayerRegioniumPacketAccess) (Object) player)
                .regionium$updateRegion(destination.worldData());
            Regionium.LOGGER.debug(
                "[PLAYER-REGION] player={} {} -> {} chunk={}",
                player.getGameProfile().name(),
                source == null ? -1 : source.id(),
                destination.id(),
                player.chunkPosition()
            );
        }
    }

    public RegioniumRegion regionFor(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return null;
        }
        return owner(level, player.chunkPosition().pack());
    }

    public RegioniumRegion owner(ServerLevel level, long chunkKey) {
        long optimistic = lifecycleLock.tryOptimisticRead();
        LevelState state = worlds.get(level);
        RegioniumRegion result = state == null ? null : ownerForChunk(state.sectionOwners, chunkKey);
        if (lifecycleLock.validate(optimistic)) {
            return result;
        }

        long stamp = lifecycleLock.readLock();
        try {
            state = worlds.get(level);
            return state == null ? null : ownerForChunk(state.sectionOwners, chunkKey);
        } finally {
            lifecycleLock.unlockRead(stamp);
        }
    }

    public RegioniumRegion owner(ServerLevel level, int chunkX, int chunkZ) {
        return owner(level, ChunkPos.pack(chunkX, chunkZ));
    }

    public boolean isActive(RegioniumRegion region) {
        LevelState state = worlds.get(region.level());
        return state != null && state.active.contains(region);
    }

    public List<RegioniumRegion> allRegions() {
        List<RegioniumRegion> result = new ArrayList<>();
        for (LevelState state : worlds.values()) {
            result.addAll(state.regions);
        }
        return result;
    }

    public Set<RegioniumRegion> activeRegions(ServerLevel level) {
        LevelState state = worlds.get(level);
        if (state == null) {
            return Set.of();
        }
        return Set.copyOf(state.active);
    }

    public Set<ServerPlayer> players(RegioniumRegion region) {
        return Set.copyOf(region.worldData().players());
    }

    public List<RegioniumRegion> regions(ServerLevel level) {
        LevelState state = worlds.get(level);
        return state == null ? List.of() : List.copyOf(state.regions);
    }

    /**
     * Takes an immutable view of the current holder topology for diagnostics.
     * This reports the published owner map rather than deriving regions from
     * player positions or simulation distances.
     */
    public List<DebugChunk> debugChunks(ServerLevel level) {
        long stamp = lifecycleLock.readLock();
        try {
            LevelState state = worlds.get(level);
            if (state == null) {
                return List.of();
            }

            Map<Long, RegioniumRegion> owners = state.sectionOwners;
            List<DebugChunk> result = new ArrayList<>();
            for (Map.Entry<Long, ChunkHolder> entry : state.chunkHolders.entrySet()) {
                ChunkHolder holder = entry.getValue();
                if (!isRegionChunk(holder)) {
                    continue;
                }

                long chunkKey = entry.getKey();
                int chunkX = ChunkPos.getX(chunkKey);
                int chunkZ = ChunkPos.getZ(chunkKey);

                RegioniumRegion owner = ownerForChunk(owners, chunkKey);
                LevelChunk entityTickingChunk = holder.getEntityTickingChunkFuture()
                    .getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK)
                    .orElse(null);
                var distanceManager = level.getChunkSource().chunkMap.getDistanceManager();
                boolean inBlockTickingRange = distanceManager.inBlockTickingRange(chunkKey);
                boolean inEntityTickingRange = distanceManager.inEntityTickingRange(chunkKey);
                result.add(new DebugChunk(
                    chunkX,
                    chunkZ,
                    chunkX >> REGION_SECTION_SHIFT,
                    chunkZ >> REGION_SECTION_SHIFT,
                    owner == null ? null : owner.id(),
                    inBlockTickingRange && holder.getTickingChunk() != null,
                    inEntityTickingRange && entityTickingChunk != null
                ));
            }
            result.sort(Comparator.comparingInt(DebugChunk::x).thenComparingInt(DebugChunk::z));
            return List.copyOf(result);
        } finally {
            lifecycleLock.unlockRead(stamp);
        }
    }

    public record DebugChunk(
        int x,
        int z,
        int sectionX,
        int sectionZ,
        Long regionId,
        boolean ticking,
        boolean entityTicking
    ) {}

    public Set<RegioniumRegion> activeRegions() {
        Set<RegioniumRegion> result =
            Collections.newSetFromMap(new IdentityHashMap<>());

        for (LevelState state : worlds.values()) {
            result.addAll(state.active);
        }

        return Set.copyOf(result);
    }

    /**
     * Migrates the mutable state of one region to the owner selected by the
     * next topology. Called with both the region-tick barrier and lifecycle
     * write lock held, so this is the equivalent of Folia's region split/merge
     * callback: no old region can tick while its containers are rebound.
     */
    private void migrateRegionState(
        RegioniumRegion source,
        LevelState state,
        Map<Long, RegioniumRegion> nextOwners
    ) {
        RegioniumWorldData sourceData = source.worldData();

        // Block events have positions but are not tied to a live chunk holder.
        for (BlockEventData event : sourceData.drainBlockEvents()) {
            RegioniumRegion destination = ownerForChunk(nextOwners, ChunkPos.pack(
                event.pos().getX() >> 4, event.pos().getZ() >> 4
            ));
            if (destination != null) {
                destination.worldData().pushBlockEventDuringMigration(event);
            }
        }

        // Move entities before retiring any region. A player may temporarily be
        // present in more than one old world-data set after a merge; identity
        // sets plus the destination check make this migration idempotent.
        for (Entity entity : List.copyOf(sourceData.entities())) {
            RegioniumRegion destination = ownerForChunk(nextOwners, entity.chunkPosition().pack());
            if (destination == source) {
                if (entity instanceof ServerPlayer player) {
                    ((dev.pandor.regionium.access.ServerPlayerRegioniumPacketAccess) (Object) player)
                        .regionium$updateRegion(sourceData);
                }
                continue;
            }

            sourceData.remove(entity);
            if (destination != null) {
                if (!destination.worldData().hasEntity(entity)) {
                    destination.worldData().add(entity);
                }
                if (entity instanceof ServerPlayer player) {
                    ((dev.pandor.regionium.access.ServerPlayerRegioniumPacketAccess) (Object) player)
                        .regionium$updateRegion(destination.worldData());
                }
            } else if (entity instanceof ServerPlayer player) {
                ((dev.pandor.regionium.access.ServerPlayerRegioniumPacketAccess) (Object) player)
                    .regionium$updateRegion(null);
            }
        }

        // Repair any player-only membership left by an earlier inconsistent
        // transfer; RegionizedWorldData.add/remove normally keep these sets in
        // sync, but topology migration must not depend on that invariant.
        for (ServerPlayer player : List.copyOf(sourceData.players())) {
            RegioniumRegion destination = ownerForChunk(nextOwners, player.chunkPosition().pack());
            if (destination == source) {
                ((dev.pandor.regionium.access.ServerPlayerRegioniumPacketAccess) (Object) player)
                    .regionium$updateRegion(sourceData);
                continue;
            }
            sourceData.remove(player);
            if (destination != null) {
                if (!destination.worldData().hasEntity(player)) {
                    destination.worldData().add(player);
                }
                ((dev.pandor.regionium.access.ServerPlayerRegioniumPacketAccess) (Object) player)
                    .regionium$updateRegion(destination.worldData());
            } else {
                ((dev.pandor.regionium.access.ServerPlayerRegioniumPacketAccess) (Object) player)
                    .regionium$updateRegion(null);
            }
        }

        // Folia migrates both block-entity ticker lists explicitly. A ticker
        // that is pending insertion at the end of the current tick may not yet
        // be present in LevelChunk's ticker map, so rebuilding from the chunk
        // alone can silently lose it during a region transfer.
        for (net.minecraft.world.level.block.entity.TickingBlockEntity ticker
            : List.copyOf(sourceData.blockEntityTickers())) {
            long tickerChunk = ChunkPos.containing(ticker.getPos()).pack();
            RegioniumRegion destination = ownerForChunk(nextOwners, tickerChunk);
            if (destination == source) {
                continue;
            }
            sourceData.removeBlockEntityTicker(ticker);
            if (destination != null) {
                destination.worldData().addMigratedBlockEntityTicker(ticker, false);
            }
        }
        for (net.minecraft.world.level.block.entity.TickingBlockEntity ticker
            : List.copyOf(sourceData.pendingBlockEntityTickers())) {
            long tickerChunk = ChunkPos.containing(ticker.getPos()).pack();
            RegioniumRegion destination = ownerForChunk(nextOwners, tickerChunk);
            if (destination == source) {
                continue;
            }
            sourceData.removeBlockEntityTicker(ticker);
            if (destination != null) {
                destination.worldData().addMigratedBlockEntityTicker(ticker, true);
            }
        }

        // LevelChunkTicks is the authoritative storage for pending ticks, but
        // LevelTicks also indexes each container in its own scheduling queues.
        // Unregister from the old manager before registering it in the new one;
        // if stale data already registered the same chunk at the destination,
        // remove/re-add that registration to restore its onTickAdded callback.
        for (LevelChunk chunk : List.copyOf(sourceData.chunks())) {
            long chunkKey = chunk.getPos().pack();
            RegioniumRegion destination = ownerForChunk(nextOwners, chunkKey);
            if (destination == source) {
                continue;
            }

            ChunkHolder holder = state.chunkHolders.get(chunkKey);
            LevelChunk authoritativeChunk = holder == null ? null : holder.getTickingChunk();
            RegioniumWorldData destinationData = destination == null ? null : destination.worldData();
            boolean destinationWasEmpty = destinationData != null && destinationData.chunks().isEmpty();
            if (destinationData != null) {
                LevelChunk existing = destinationData.chunks().stream()
                    .filter(candidate -> candidate.getPos().pack() == chunkKey)
                    .findFirst()
                    .orElse(null);
                if (existing != null) {
                    destinationData.removeChunk(existing);
                }
            }

            // Split-created regions inherit the source clock. When a chunk is
            // merged into an already active region, preserve the remaining
            // delay of each pending block/fluid tick by rebasing its absolute
            // trigger time from the source clock to the destination clock.
            long tickTimeOffset = 0L;
            if (destinationData != null) {
                if (destinationWasEmpty) {
                    destinationData.inheritRedstoneTimeIfEmpty(sourceData);
                } else {
                    tickTimeOffset = destinationData.redstoneTime() - sourceData.redstoneTime();
                }
            }

            sourceData.removeChunk(chunk);
            if (destinationData != null && authoritativeChunk != null) {
                RegioniumWorldData.shiftScheduledTicks(authoritativeChunk, tickTimeOffset);
                destinationData.addChunk(authoritativeChunk);
                var distanceManager = source.level().getChunkSource().chunkMap.getDistanceManager();
                boolean inBlockTickingRange = distanceManager.inBlockTickingRange(chunkKey);
                boolean inEntityTickingRange = distanceManager.inEntityTickingRange(chunkKey);
                destinationData.setTickingChunk(authoritativeChunk, inBlockTickingRange);
                LevelChunk entityTickingChunk = holder.getEntityTickingChunkFuture()
                    .getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK)
                    .orElse(null);
                destinationData.setEntityTickingChunk(
                    authoritativeChunk, inEntityTickingRange && authoritativeChunk == entityTickingChunk
                );
                // addChunk normally flushes deferred ticks immediately on the
                // owner thread. Topology migration runs under the global write
                // barrier instead, so enqueue the flush for the destination's
                // first region tick rather than silently leaving it deferred.
                destination.execute(() -> Regionium.scheduler().flushDeferredScheduledTicks(
                    source.level(), chunkKey, destination
                ));
            }
        }

        // Folia keeps redstone-torch toggle history in region data. Minecraft
        // 26.3 stores it in one static Level-keyed map, which would be shared
        // concurrently by every Regionium worker. Move each history entry with
        // the chunk that owns its position and rebase its timestamp to the new
        // region clock, just like pending scheduled ticks.
        for (net.minecraft.world.level.block.RedstoneTorchBlock.Toggle toggle
            : List.copyOf(sourceData.redstoneUpdateInfos())) {
            BlockPos togglePos = ((dev.pandor.regionium.mixins.RedstoneTorchToggleRegioniumAccessorMixin)
                (Object) toggle).regionium$getPos();
            long toggleChunk = ChunkPos.pack(togglePos);
            RegioniumRegion destination = ownerForChunk(nextOwners, toggleChunk);
            if (destination == source) {
                continue;
            }

            sourceData.redstoneUpdateInfos().remove(toggle);
            if (destination != null) {
                long offset = destination.worldData().redstoneTime() - sourceData.redstoneTime();
                long when = ((dev.pandor.regionium.mixins.RedstoneTorchToggleRegioniumAccessorMixin)
                    (Object) toggle).regionium$getWhen();
                destination.worldData().redstoneUpdateInfos().add(
                    new net.minecraft.world.level.block.RedstoneTorchBlock.Toggle(togglePos, when + offset)
                );
            }
        }

        for (ChunkHolder holder : List.copyOf(sourceData.chunkHoldersToBroadcast())) {
            RegioniumRegion destination = ownerForChunk(nextOwners, holder.getPos().pack());
            if (destination == source) {
                continue;
            }
            if (destination != null) {
                destination.worldData().chunkHoldersToBroadcast().add(holder);
            }
            sourceData.chunkHoldersToBroadcast().remove(holder);
        }

        if (!state.active.contains(source)) {
            sourceData.clearPlayers();
            sourceData.blockEntityTickers().clear();
            sourceData.pendingBlockEntityTickers().clear();
            sourceData.redstoneUpdateInfos().clear();
            sourceData.chunkHoldersToBroadcast().clear();
        }
    }

    private static RegioniumRegion ownerForChunk(
        Map<Long, RegioniumRegion> sectionOwners,
        long chunkKey
    ) {
        return sectionOwners.get(sectionKey(
            ChunkPos.getX(chunkKey) >> REGION_SECTION_SHIFT,
            ChunkPos.getZ(chunkKey) >> REGION_SECTION_SHIFT
        ));
    }

    private static long sectionKey(int sectionX, int sectionZ) {
        return ChunkPos.pack(sectionX, sectionZ);
    }

    private static int sectionX(long key) {
        return ChunkPos.getX(key);
    }

    private static int sectionZ(long key) {
        return ChunkPos.getZ(key);
    }

    private static final class LevelState {
        private final ServerLevel level;
        private final List<RegioniumRegion> regions = new ArrayList<>();
        private volatile Map<Long, RegioniumRegion> sectionOwners = Map.of();
        private final Map<Long, ChunkHolder> chunkHolders = new ConcurrentHashMap<>();
        /** Persistent region topology: one owner for every existing holder. */
        private final Set<Long> regionChunks = new LinkedHashSet<>();
        /** Simulation state only; does not determine region ownership. */
        private final Set<Long> leasedChunks = new LinkedHashSet<>();
        private volatile boolean topologyDirty = true;
        private volatile Set<RegioniumRegion> active = Set.of();

        private LevelState(ServerLevel level) {
            this.level = level;
        }
    }
}
