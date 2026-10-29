package dev.pandor.regionium.core;

import dev.pandor.regionium.Regionium;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stateful spatial region manager.
 * <p>
 * This is the Fabric-side equivalent of Folia's ThreadedRegionizer:
 * chunk topology is persistent, regions own RegioniumWorldData, and split/
 * merge/topology changes migrate the complete region-local state.
 * <p>
 * There is deliberately no second chunk lease or object ownership table.
 */
public final class RegioniumRegionizer {
    private static final int REGION_SECTION_SHIFT = 3; // 8x8 chunks per region section
    // Folia keeps these policies separate even though Regionium currently uses
    // the same radius for each one.
    private static final int EMPTY_SECTION_CREATE_RADIUS = 1;
    private static final int REGION_SECTION_MERGE_RADIUS = 1;

    private final Map<ServerLevel, LevelState> worlds =
        Collections.synchronizedMap(new IdentityHashMap<>());
    private final StampedLock lifecycleLock = new StampedLock();
    // This barrier is retained for consistent chunk-save snapshots and level
    // shutdown only. Merge/split publication no longer takes its write side:
    // affected regions are individually marked TRANSIENT and release before
    // their data is handed off, while unrelated regions continue ticking.
    private final ReentrantReadWriteLock regionTickBarrier = new ReentrantReadWriteLock(false);
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
        // Region ticks share the read side only with chunk-save snapshots and
        // level shutdown. Merge/split transitions use per-region release state
        // instead, so one slow region cannot stall unrelated owners.
        regionTickBarrier.readLock().lock();
    }

    public void exitRegionTick() {
        regionTickBarrier.readLock().unlock();
    }

    /**
     * Begins a region tick under the lifecycle lock used by holder creation,
     * deferred merge publication, and region state transitions. The per-region
     * pending flag prevents an affected owner from starting on stale topology.
     */
    boolean beginRegionTick(RegioniumRegion region) {
        long stamp = lifecycleLock.writeLock();
        try {
            if (region.isRetired()
                || region.lifecycleState() != RegioniumRegion.LifecycleState.READY
                || region.hasPendingMerges()
                || region.hasPendingTopologyTransition()) {
                return false;
            }
            region.setLifecycleState(RegioniumRegion.LifecycleState.TICKING);
            return true;
        } finally {
            lifecycleLock.unlockWrite(stamp);
        }
    }

    /** Completes the tick lifecycle transition before releasing the tick barrier. */
    void finishRegionTick(RegioniumRegion region) {
        long stamp = lifecycleLock.writeLock();
        try {
            if (region.lifecycleState() == RegioniumRegion.LifecycleState.TICKING) {
                boolean pendingTransition = region.hasPendingMerges()
                    || region.hasPendingTopologyTransition();
                region.setLifecycleState(region.isRetired()
                    ? RegioniumRegion.LifecycleState.DEAD
                    : pendingTransition
                        ? RegioniumRegion.LifecycleState.TRANSIENT
                        : RegioniumRegion.LifecycleState.READY);
                if (pendingTransition) {
                    region.stopScheduledTickLoop();
                }
            }
        } finally {
            lifecycleLock.unlockWrite(stamp);
        }
    }

    /**
     * Excludes every region tick while vanilla copies a chunk into its
     * SerializableChunkData snapshot. Synchronizing on LevelChunk alone is
     * insufficient: vanilla block/random ticks and block-entity ticks do not
     * all acquire that monitor before mutating the chunk.
     * <p>
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
        LevelState state = worlds.computeIfAbsent(level, ignored -> new LevelState(level));
        if (state.holdersSeeded) {
            return;
        }

        // A dimension can be registered after ChunkMap has already created
        // holders. Seed the registry exactly once; all later changes are driven
        // by holder creation/destruction callbacks rather than visible-map scans.
        var chunkMap = (dev.pandor.regionium.mixins.ChunkMapRegioniumVisibleAccessorMixin)
            (Object) level.getChunkSource().chunkMap;
        long stamp = lifecycleLock.writeLock();
        try {
            for (ChunkHolder holder : chunkMap.regionium$getVisibleChunkMap().values()) {
                // Match the same spatial-membership rule used by live holder
                // updates: generation-only holders must not connect regions.
                if (!isRegionChunk(holder)) {
                    continue;
                }
                long chunkKey = holder.getPos().pack();
                state.chunkHolders.put(chunkKey, holder);
                if (state.regionChunks.add(chunkKey)) {
                    addSectionChunkLocked(state, chunkKey);
                }
                state.leasedChunks.add(chunkKey);
            }
            state.holdersSeeded = true;
            markTopologyDirty(state);
        } finally {
            lifecycleLock.unlockWrite(stamp);
        }
        prepareDeferredMerges(level);
    }

    public void unregisterLevel(ServerLevel level) {
        // Serialize shutdown against an in-progress per-level merge/split
        // callback before taking the global tick barrier.
        LevelState existing = worlds.get(level);
        if (existing != null) {
            existing.transitionLock.lock();
        }
        regionTickBarrier.writeLock().lock();
        try {
            long stamp = lifecycleLock.writeLock();
            try {
                LevelState removed = worlds.remove(level);
                if (removed == null) {
                    return;
                }
                for (RegioniumRegion region : List.copyOf(removed.regions)) {
                    region.stopScheduledTickLoop();
                    region.clearMergeRelationships();
                    region.setPendingTopologyTransition(false);
                    region.setLifecycleState(RegioniumRegion.LifecycleState.DEAD);
                    region.requestStop();
                }
                removed.regions.clear();
                removed.sectionOwners = Map.of();
                removed.active = Set.of();
            } finally {
                lifecycleLock.unlockWrite(stamp);
            }
        } finally {
            regionTickBarrier.writeLock().unlock();
            if (existing != null) {
                existing.transitionLock.unlock();
            }
        }
    }

    public void markTopologyDirty(ServerLevel level) {
        LevelState state;
        long stamp = lifecycleLock.writeLock();
        try {
            state = worlds.get(level);
            if (state != null) {
                markTopologyDirty(state);
            }
        } finally {
            lifecycleLock.unlockWrite(stamp);
        }
        if (state != null) {
            prepareDeferredMerges(level);
        }
    }

    /**
     * Registers a ChunkHolder in the persistent region topology. Folia's
     * ThreadedRegionizer is driven by holder creation/destruction, not by
     * ticking-state transitions.
     */
    public void addChunkHolder(ServerLevel level, ChunkHolder holder) {
        boolean topologyChanged = false;
        lifecycleLock.writeLock();
        try {
            LevelState state = worlds.get(level);
            if (state == null) return;

            long chunkKey = holder.getPos().pack();
            if (isRegionChunk(holder)) {
                state.chunkHolders.put(chunkKey, holder);
                if (state.regionChunks.add(chunkKey)) {
                    topologyChanged = addSectionChunkLocked(state, chunkKey);
                    if (topologyChanged) {
                        markTopologyDirty(state);
                    }
                }
                state.leasedChunks.add(chunkKey);
            } else {
                // 26.3 can retain generation-dependency holders after a chunk
                // is no longer FULL. They are not Folia-style spatial leases
                // and must not form bridges between distant player regions.
                state.chunkHolders.remove(chunkKey, holder);
                if (state.regionChunks.remove(chunkKey)) {
                    topologyChanged = removeSectionChunkLocked(state, chunkKey);
                    if (topologyChanged) {
                        markTopologyDirty(state);
                    }
                }
                state.leasedChunks.remove(chunkKey);
            }
        } finally {
            lifecycleLock.tryUnlockWrite();
        }
        if (topologyChanged) {
            prepareDeferredMerges(level);
        }
    }

    /**
     * Checks whether the exact chunk has a FULL holder without requesting or
     * synchronously loading it. Section ownership alone is insufficient here:
     * a region owns its 8x8 section, including positions whose chunks are not
     * currently leased.
     */
    public boolean hasLoadedFullChunk(ServerLevel level, long chunkKey) {
        long stamp = lifecycleLock.readLock();
        try {
            LevelState state = worlds.get(level);
            if (state == null) {
                return false;
            }
            ChunkHolder holder = state.chunkHolders.get(chunkKey);
            return isRegionChunk(holder)
                && holder.getChunkIfPresent(net.minecraft.world.level.chunk.status.ChunkStatus.FULL) != null;
        } finally {
            lifecycleLock.unlockRead(stamp);
        }
    }

    /** Removes a ChunkHolder at the actual vanilla unload boundary. */
    public void removeChunkHolder(ServerLevel level, ChunkHolder expectedHolder) {
        boolean topologyChanged = false;
        long stamp = lifecycleLock.writeLock();
        try {
            LevelState state = worlds.get(level);
            if (state == null) return;
            long chunkKey = expectedHolder.getPos().pack();
            if (state.chunkHolders.get(chunkKey) != expectedHolder) {
                return;
            }
            ChunkHolder removed = state.chunkHolders.remove(chunkKey);
            boolean wasTracked = state.regionChunks.remove(chunkKey);
            state.leasedChunks.remove(chunkKey);
            if (wasTracked) {
                topologyChanged = removeSectionChunkLocked(state, chunkKey);
                if (topologyChanged) {
                    markTopologyDirty(state);
                }
            } else if (removed != null) {
                // A holder without spatial membership indicates an incomplete
                // seed/callback sequence; retain a topology reconciliation.
                topologyChanged = true;
                markTopologyDirty(state);
            }
        } finally {
            lifecycleLock.tryUnlockWrite();
        }
        if (topologyChanged) {
            prepareDeferredMerges(level);
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

            if (state.regionChunks.contains(chunkKey)
                && state.chunkHolders.get(chunkKey) == holder) {
                state.chunkHolders.put(chunkKey, holder);
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
        if (state.regionChunks.contains(chunkKey)) {
            state.chunkHolders.put(chunkKey, holder);
        }

        // Region-local chunk state is reconciled by the owning region thread.
        // Never mutate RegioniumWorldData from the server/chunk lifecycle thread.
    }

    public void rebalance(ServerLevel level) {
        LevelState state = worlds.get(level);
        if (state == null || !state.topologyDirty) {
            return;
        }
        LevelState transitionState = state;
        if (!transitionState.transitionLock.tryLock()) {
            return;
        }
        try {
            // Publish deferred merge links and stop only the regions touched by this
            // topology change. Unrelated regions continue ticking while an affected
            // source finishes its current tick.
            prepareDeferredMerges(level);

            TopologyHandoff handoff;
            long stamp = lifecycleLock.writeLock();
            try {
                state = worlds.get(level);
                if (state == null || !state.topologyDirty) {
                    return;
                }
                for (RegioniumRegion region : state.regions) {
                    if (region.hasPendingTopologyTransition()
                        && region.lifecycleState() == RegioniumRegion.LifecycleState.TICKING) {
                        return;
                    }
                }
                handoff = rebalanceLocked(level);
            } finally {
                lifecycleLock.unlockWrite(stamp);
            }

            if (handoff != null) {
                completeRegionTransition(handoff);
                for (RegioniumRegion region : handoff.state().regions) {
                    if (handoff.state().active.contains(region)) {
                        if (region.lifecycleState() == RegioniumRegion.LifecycleState.READY
                            && !region.hasPendingTopologyTransition()) {
                            region.startTickLoop();
                        }
                    } else {
                        region.stopScheduledTickLoop();
                    }
                }
            } else {
                // The topology may have returned to its previously published shape
                // while a region was finishing its tick. In that case there is no
                // handoff, so release any now-obsolete deferred graph and reactivate
                // the still-published regions.
                long cleanupStamp = lifecycleLock.writeLock();
                try {
                    state = worlds.get(level);
                    if (state != null && !state.topologyDirty) {
                        for (RegioniumRegion region : state.regions) {
                            region.clearMergeRelationships();
                            region.setPendingTopologyTransition(false);
                            if (state.active.contains(region)
                                && region.lifecycleState() == RegioniumRegion.LifecycleState.TRANSIENT) {
                                region.setLifecycleState(RegioniumRegion.LifecycleState.READY);
                            }
                        }
                    }
                } finally {
                    lifecycleLock.unlockWrite(cleanupStamp);
                }
                if (state != null) {
                    for (RegioniumRegion region : state.regions) {
                        if (state.active.contains(region)
                            && region.lifecycleState() == RegioniumRegion.LifecycleState.READY
                            && !region.hasPendingTopologyTransition()) {
                            region.startTickLoop();
                        }
                    }
                }
            }
        } finally {
            transitionState.transitionLock.unlock();
            if (transitionState.topologyDirty) {
                prepareDeferredMerges(level);
            }
            drainPendingOwnedTasks(transitionState);
        }
    }

    /**
     * Keeps the scheduler invariant that every published READY region has a
     * tick scheduled. Region activation normally starts the loop as part of the
     * topology handoff; this idempotent pass also repairs a missed activation
     * wake-up without changing ownership or lifecycle state.
     */
    public void ensureReadyRegionsScheduled(ServerLevel level) {
        LevelState state = worlds.get(level);
        if (state == null || state.topologyDirty) {
            return;
        }
        for (RegioniumRegion region : state.active) {
            if (!region.isRetired()
                && region.lifecycleState() == RegioniumRegion.LifecycleState.READY
                && !region.hasPendingTopologyTransition()) {
                region.startTickLoop();
            }
        }
    }

    /** Marks the level for an opportunistic lifecycle pass. */
    private void markTopologyDirty(LevelState state) {
        state.topologyDirty = true;
    }

    /**
     * Folia's onRegionRelease equivalent. A region that was transient because
     * of a pending topology change retries the transition immediately after
     * leaving its tick, instead of waiting for a global server-tick barrier.
     */
    public void onRegionRelease(RegioniumRegion region) {
        if (region.hasPendingTopologyTransition()) {
            rebalance(region.level());
        }
    }

    /**
     * Prepares the affected region lifecycle before publishing changed section
     * ownership. A merge may wait for ticking sources; a split/removal waits for
     * the old owner to release as well. Regions outside the affected component
     * remain schedulable throughout the transition.
     */
    private void prepareDeferredMerges(ServerLevel level) {
        LevelState transitionState = worlds.get(level);
        if (transitionState == null || !transitionState.transitionLock.tryLock()) {
            return;
        }
        try {
            long stamp = lifecycleLock.writeLock();
            try {
                LevelState state = worlds.get(level);
                if (state == null || !state.topologyDirty) {
                    return;
                }

                Set<Long> oldSections = state.sectionOwners.keySet();
                Set<Long> currentSections = new LinkedHashSet<>();
                for (Map.Entry<Long, RegionSectionState> entry : state.sections.entrySet()) {
                    if (!entry.getValue().isDead()) {
                        currentSections.add(entry.getKey());
                    }
                }

                Set<Long> changedSections = new LinkedHashSet<>(oldSections);
                changedSections.removeAll(currentSections);
                for (long section : currentSections) {
                    if (!oldSections.contains(section)) {
                        changedSections.add(section);
                    }
                }
                if (changedSections.isEmpty()) {
                    return;
                }

                int searchRadius = EMPTY_SECTION_CREATE_RADIUS + REGION_SECTION_MERGE_RADIUS;
                Set<RegioniumRegion> affected =
                    Collections.newSetFromMap(new IdentityHashMap<>());
                List<Long> addedSections = new ArrayList<>();
                for (long section : currentSections) {
                    if (!oldSections.contains(section)) {
                        addedSections.add(section);
                    }
                }

                for (long changed : changedSections) {
                    RegioniumRegion previousOwner = state.sectionOwners.get(changed);
                    if (previousOwner != null && !previousOwner.isRetired()) {
                        affected.add(previousOwner);
                    }
                    int x = sectionX(changed);
                    int z = sectionZ(changed);
                    for (int dz = -searchRadius; dz <= searchRadius; ++dz) {
                        for (int dx = -searchRadius; dx <= searchRadius; ++dx) {
                            RegioniumRegion neighbour = state.sectionOwners.get(sectionKey(x + dx, z + dz));
                            if (neighbour != null && !neighbour.isRetired()) {
                                affected.add(neighbour);
                            }
                        }
                    }
                }

                // Folia's addChunk path creates deferred merge links only when a
                // newly-created section can connect multiple previously distinct
                // regions. Repeated topology passes reuse the existing target.
                for (long added : addedSections) {
                    int x = sectionX(added);
                    int z = sectionZ(added);
                    Set<RegioniumRegion> neighbours =
                        Collections.newSetFromMap(new IdentityHashMap<>());
                    for (int dz = -searchRadius; dz <= searchRadius; ++dz) {
                        for (int dx = -searchRadius; dx <= searchRadius; ++dx) {
                            if ((dx | dz) == 0) {
                                continue;
                            }
                            RegioniumRegion owner = state.sectionOwners.get(sectionKey(x + dx, z + dz));
                            if (owner != null && !owner.isRetired()) {
                                neighbours.add(owner);
                            }
                        }
                    }
                    if (neighbours.size() < 2) {
                        continue;
                    }

                    RegioniumRegion target = null;
                    for (RegioniumRegion candidate : state.regions) {
                        if (candidate.isRetired()
                            || candidate.lifecycleState() == RegioniumRegion.LifecycleState.TICKING) {
                            continue;
                        }
                        // Reuse the existing deferred-merge participant regardless
                        // of edge direction. Folia's target may itself be waiting
                        // to merge into a still-ticking neighbour.
                        if (candidate.mergeIntoLater().stream().anyMatch(neighbours::contains)
                            || candidate.expectingMergeFrom().stream().anyMatch(neighbours::contains)) {
                            target = candidate;
                            break;
                        }
                    }
                    if (target == null) {
                        for (RegioniumRegion candidate : neighbours) {
                            if (candidate.lifecycleState() != RegioniumRegion.LifecycleState.TICKING
                                && !candidate.hasPendingMerges()) {
                                target = candidate;
                                break;
                            }
                        }
                    }
                    if (target == null) {
                        target = regionFactory.apply(level);
                        state.regions.add(target);
                    }
                    affected.add(target);
                    for (RegioniumRegion source : neighbours) {
                        if (source == target || source.isRetired()) {
                            continue;
                        }

                        // Match ThreadedRegionizer.addChunk(): an idle source
                        // merges into the selected target immediately after the
                        // topology handoff, but a ticking source cannot be killed
                        // yet. In that case the non-ticking target records that
                        // it must merge into the ticking source on release.
                        if (source.lifecycleState() == RegioniumRegion.LifecycleState.TICKING) {
                            if (!source.expectingMergeFrom().contains(target)) {
                                target.addMergeIntoLater(source);
                            }
                        } else if (!target.mergeIntoLater().contains(source)) {
                            source.addMergeIntoLater(target);
                        }
                    }
                }

                for (RegioniumRegion participant : affected) {
                    participant.setPendingTopologyTransition(true);
                    if (participant.lifecycleState() != RegioniumRegion.LifecycleState.TICKING) {
                        participant.setLifecycleState(RegioniumRegion.LifecycleState.TRANSIENT);
                        participant.stopScheduledTickLoop();
                    }
                }
            } finally {
                lifecycleLock.unlockWrite(stamp);
            }
        } finally {
            transitionState.transitionLock.unlock();
        }
    }

    private TopologyHandoff rebalanceLocked(ServerLevel level) {
        LevelState state = worlds.get(level);
        if (state == null) {
            return null;
        }

        /*
         * ChunkMapRegioniumHolderLifecycleMixin maintains this registry on
         * holder creation/destruction; ChunkHolderRegioniumMixin refreshes the
         * reference as futures change. The registry is the lifecycle source,
         * rather than rescanning ChunkMap.visible on each topology update.
         * Region ownership follows holder existence; FULL status is checked
         * separately by simulation-chunk accessors.
         *
         * The registry is protected by lifecycleLock (held by rebalance()), so
         * holder and region membership are read from one consistent snapshot.
         */
        Set<Long> currentChunks = new LinkedHashSet<>();
        for (long chunkKey : List.copyOf(state.regionChunks)) {
            ChunkHolder holder = state.chunkHolders.get(chunkKey);
            if (holder != null && holder.getPos().pack() == chunkKey) {
                currentChunks.add(chunkKey);
            } else {
                state.regionChunks.remove(chunkKey);
                state.chunkHolders.remove(chunkKey);
                state.leasedChunks.remove(chunkKey);
                state.topologyDirty |= removeSectionChunkLocked(state, chunkKey);
            }
        }
        Set<Long> currentSections = new LinkedHashSet<>();
        for (Map.Entry<Long, RegionSectionState> entry : state.sections.entrySet()) {
            if (!entry.getValue().isDead()) {
                currentSections.add(entry.getKey());
            }
        }

        if (currentSections.isEmpty()) {
            // If a stale-holder reconciliation discovered this topology change
            // after the preparation pass, defer retirement until every source
            // region has released. Never migrate a ticking region's state.
            if (state.regions.stream().anyMatch(region ->
                region.lifecycleState() == RegioniumRegion.LifecycleState.TICKING)) {
                for (RegioniumRegion region : state.regions) {
                    region.setPendingTopologyTransition(true);
                    if (region.lifecycleState() != RegioniumRegion.LifecycleState.TICKING) {
                        region.setLifecycleState(RegioniumRegion.LifecycleState.TRANSIENT);
                        region.stopScheduledTickLoop();
                    }
                }
                return null;
            }
            state.topologyDirty = false;
            // Publish empty ownership and retire the old identities while the
            // lifecycle lock is held. Region data callbacks and world-state
            // handoff run after releasing lifecycleLock.
            Map<Long, RegioniumRegion> previousOwners = new LinkedHashMap<>(state.sectionOwners);
            state.sectionOwners = Map.of();
            state.active = Set.of();
            state.sections.clear();
            List<RegioniumRegion> sources = List.copyOf(state.regions);
            for (RegioniumRegion region : sources) {
                region.stopScheduledTickLoop();
                region.setLifecycleState(RegioniumRegion.LifecycleState.TRANSIENT);
            }
            state.regions.clear();
            return new TopologyHandoff(
                state, sources, Map.of(), Map.copyOf(previousOwners),
                Map.copyOf(state.chunkHolders), Set.of()
            );
        }

        // Ownership changes only when the set of occupied 8x8 sections
        // changes. A holder replacement or ticking-status update must not cause
        // every region in the dimension to be recalculated.
        Map<Long, RegioniumRegion> oldOwners = new LinkedHashMap<>(state.sectionOwners);
        Set<Long> changedSections = new LinkedHashSet<>(oldOwners.keySet());
        changedSections.removeAll(currentSections);
        for (long section : currentSections) {
            if (!oldOwners.containsKey(section)) {
                changedSections.add(section);
            }
        }

        if (changedSections.isEmpty()) {
            // The holder registry was refreshed above, but spatial ownership
            // did not change. Keep all region identities and ownership intact.
            state.topologyDirty = false;
            removeDeadSectionsLocked(state);
            return null;
        }

        // Recalculate only the old regions touching a changed section and its
        // immediate neighbours. Any region adjacent to an addition/removal is
        // included as a whole, so a local split can create new regions and a
        // local bridge can merge existing regions without rebuilding unrelated
        // topology elsewhere in the dimension.
        Set<RegioniumRegion> affectedRegions =
            Collections.newSetFromMap(new IdentityHashMap<>());
        for (long changed : changedSections) {
            RegioniumRegion previousOwner = oldOwners.get(changed);
            if (previousOwner != null) {
                affectedRegions.add(previousOwner);
            }

            int x = sectionX(changed);
            int z = sectionZ(changed);
            // Adding an occupied section can create an empty halo out to the
            // create radius. Those new halo sections can in turn merge with
            // an existing region as far as the merge radius beyond that halo.
            // Folia's addChunk() searches this combined radius when collecting
            // nearby regions; searching only the merge radius around `changed`
            // can leave adjacent owners unmerged.
            int nearbyRegionRadius = EMPTY_SECTION_CREATE_RADIUS + REGION_SECTION_MERGE_RADIUS;
            for (int dz = -nearbyRegionRadius; dz <= nearbyRegionRadius; ++dz) {
                for (int dx = -nearbyRegionRadius; dx <= nearbyRegionRadius; ++dx) {
                    RegioniumRegion neighbourOwner = oldOwners.get(sectionKey(x + dx, z + dz));
                    if (neighbourOwner != null) {
                        affectedRegions.add(neighbourOwner);
                    }
                }
            }
        }

        if (affectedRegions.stream().anyMatch(region ->
            region.lifecycleState() == RegioniumRegion.LifecycleState.TICKING)) {
            state.topologyDirty = true;
            for (RegioniumRegion region : affectedRegions) {
                region.setPendingTopologyTransition(true);
                if (region.lifecycleState() != RegioniumRegion.LifecycleState.TICKING) {
                    region.setLifecycleState(RegioniumRegion.LifecycleState.TRANSIENT);
                    region.stopScheduledTickLoop();
                }
            }
            return null;
        }

        state.topologyDirty = false;
        Set<Long> affectedSections = new LinkedHashSet<>(changedSections);
        for (Map.Entry<Long, RegioniumRegion> entry : oldOwners.entrySet()) {
            if (affectedRegions.contains(entry.getValue()) && currentSections.contains(entry.getKey())) {
                affectedSections.add(entry.getKey());
            }
        }
        affectedSections.retainAll(currentSections);

        List<Set<Long>> components = connectedComponents(affectedSections);
        components.sort(Comparator
            .<Set<Long>>comparingInt(Set::size)
            .reversed()
            .thenComparingLong(set -> set.iterator().next()));

        // Folia destroys the old region identity when a region splits into
        // multiple components; none of those components may reuse that object.
        Map<RegioniumRegion, Integer> sourceComponentCounts = new IdentityHashMap<>();
        for (Set<Long> component : components) {
            Set<RegioniumRegion> ownersInComponent =
                Collections.newSetFromMap(new IdentityHashMap<>());
            for (long section : component) {
                RegioniumRegion owner = oldOwners.get(section);
                if (owner != null) {
                    ownersInComponent.add(owner);
                }
            }
            for (RegioniumRegion owner : ownersInComponent) {
                sourceComponentCounts.merge(owner, 1, Integer::sum);
            }
        }
        Set<RegioniumRegion> splitSources =
            Collections.newSetFromMap(new IdentityHashMap<>());
        sourceComponentCounts.forEach((owner, count) -> {
            if (count > 1) {
                splitSources.add(owner);
            }
        });

        Set<RegioniumRegion> used = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Long, RegioniumRegion> nextOwners = new LinkedHashMap<>();
        for (Map.Entry<Long, RegioniumRegion> entry : oldOwners.entrySet()) {
            if (!affectedRegions.contains(entry.getValue()) && currentSections.contains(entry.getKey())) {
                nextOwners.put(entry.getKey(), entry.getValue());
                used.add(entry.getValue());
            }
        }

        Set<RegioniumRegion> migrationRegions =
            Collections.newSetFromMap(new IdentityHashMap<>());
        migrationRegions.addAll(affectedRegions);
        // Include transient merge targets that do not own sections yet, so
        // their graph links and lifecycle are retired or activated with the
        // same transition rather than leaving orphaned region identities.
        for (RegioniumRegion region : state.regions) {
            if (region.hasPendingTopologyTransition() || region.hasPendingMerges()) {
                migrationRegions.add(region);
            }
        }

        for (Set<Long> component : components) {
            RegioniumRegion selected = selectRegion(component, oldOwners, state.regions, used, splitSources);
            if (selected == null) {
                // Region identity has a lifecycle. Split-created regions receive
                // new identities; retired regions are never recycled.
                selected = regionFactory.apply(level);
                state.regions.add(selected);
            }

            used.add(selected);
            migrationRegions.add(selected);
            for (long section : component) {
                nextOwners.put(section, selected);
            }
        }

        // Publish only the affected component under lifecycleLock. Regions
        // outside it retain their ownership and may continue ticking.
        state.sectionOwners = Map.copyOf(nextOwners);
        state.active = Set.copyOf(used);
        removeDeadSectionsLocked(state);

        List<RegioniumRegion> sources = List.copyOf(migrationRegions);
        // Affected regions stay non-tickable until both migration phases have
        // completed. Their pending flag and TRANSIENT lifecycle state enforce
        // this locally without blocking unrelated regions.
        for (RegioniumRegion region : sources) {
            if (used.contains(region)) {
                region.setLifecycleState(RegioniumRegion.LifecycleState.TRANSIENT);
            }
        }
        for (RegioniumRegion region : List.copyOf(state.regions)) {
            if (!used.contains(region)) {
                region.stopScheduledTickLoop();
                region.setLifecycleState(RegioniumRegion.LifecycleState.TRANSIENT);
                state.regions.remove(region);
            }
        }

        return new TopologyHandoff(
            state,
            sources,
            Map.copyOf(nextOwners),
            Map.copyOf(oldOwners),
            Map.copyOf(state.chunkHolders),
            Set.copyOf(state.active)
        );
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
                updateTickOffsets(entity, source, destination);
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

    /**
     * Applies Folia's section transition rules while lifecycleLock is held.
     * Each holder contributes one chunk to its 8x8 region section. Neighbour
     * counts change only when that section crosses empty/non-empty.
     */
    private static boolean addSectionChunkLocked(LevelState state, long chunkKey) {
        int chunkX = ChunkPos.getX(chunkKey);
        int chunkZ = ChunkPos.getZ(chunkKey);
        int x = chunkX >> REGION_SECTION_SHIFT;
        int z = chunkZ >> REGION_SECTION_SHIFT;
        long key = sectionKey(x, z);
        RegionSectionState section = state.sections.computeIfAbsent(key, ignored -> new RegionSectionState());
        boolean becameNonEmpty = section.chunkCount++ == 0;
        if (!becameNonEmpty) {
            return false;
        }

        // Any empty -> occupied transition changes the graph used for region
        // connectivity, even when this section already exists as an empty
        // neighbor halo. In particular, it can bridge two existing regions.
        boolean topologyChanged = true;
        for (int dz = -EMPTY_SECTION_CREATE_RADIUS; dz <= EMPTY_SECTION_CREATE_RADIUS; ++dz) {
            for (int dx = -EMPTY_SECTION_CREATE_RADIUS; dx <= EMPTY_SECTION_CREATE_RADIUS; ++dx) {
                if ((dx | dz) == 0) {
                    continue;
                }
                long neighbourKey = sectionKey(x + dx, z + dz);
                RegionSectionState neighbour = state.sections.get(neighbourKey);
                if (neighbour == null) {
                    neighbour = new RegionSectionState();
                    neighbour.nonEmptyNeighbours = 1;
                    state.sections.put(neighbourKey, neighbour);
                } else {
                    boolean wasDead = neighbour.isDead();
                    neighbour.nonEmptyNeighbours++;
                }
            }
        }
        return topologyChanged;
    }

    /**
     * Removes one holder contribution, retaining newly dead nodes until the
     * next topology publication can retire/split their owning region.
     */
    private static boolean removeSectionChunkLocked(LevelState state, long chunkKey) {
        int x = ChunkPos.getX(chunkKey) >> REGION_SECTION_SHIFT;
        int z = ChunkPos.getZ(chunkKey) >> REGION_SECTION_SHIFT;
        RegionSectionState section = state.sections.get(sectionKey(x, z));
        if (section == null || section.chunkCount <= 0) {
            throw new IllegalStateException("Missing section chunk membership for "
                + ChunkPos.getX(chunkKey) + "," + ChunkPos.getZ(chunkKey));
        }

        if (--section.chunkCount != 0) {
            return false;
        }
        // Any occupied -> empty transition changes connectivity, even when
        // neighbouring sections remain occupied. This is the critical case
        // for splitting one region after its last holder in a bridge section
        // unloads; checking only whether the section becomes fully dead misses it.
        boolean topologyChanged = true;
        for (int dz = -EMPTY_SECTION_CREATE_RADIUS; dz <= EMPTY_SECTION_CREATE_RADIUS; ++dz) {
            for (int dx = -EMPTY_SECTION_CREATE_RADIUS; dx <= EMPTY_SECTION_CREATE_RADIUS; ++dx) {
                if ((dx | dz) == 0) {
                    continue;
                }
                RegionSectionState neighbour = state.sections.get(sectionKey(x + dx, z + dz));
                if (neighbour == null || neighbour.nonEmptyNeighbours <= 0) {
                    throw new IllegalStateException("Missing/nonpositive neighbour count around section "
                        + x + "," + z);
                }
                neighbour.nonEmptyNeighbours--;
                topologyChanged |= neighbour.isDead();
            }
        }
        return topologyChanged;
    }

    private static void removeDeadSectionsLocked(LevelState state) {
        state.sections.entrySet().removeIf(entry -> entry.getValue().isDead());
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
        Set<RegioniumRegion> used,
        Set<RegioniumRegion> splitSources
    ) {
        Map<RegioniumRegion, Integer> scores = new IdentityHashMap<>();

        for (long section : component) {
            RegioniumRegion owner = oldOwners.get(section);
            if (owner != null && !used.contains(owner) && !splitSources.contains(owner)) {
                scores.merge(owner, 1, Integer::sum);
            }
        }

        // Resolve both directions of Folia's deferred merge graph. A region
        // with no sections can be waiting to merge into an existing owner
        // (mergeIntoLater), while an existing owner can be waiting for one or
        // more sources (expectingMergeFrom). Prefer the existing owner identity
        // represented by the affected component in either case.
        RegioniumRegion pendingTarget = null;
        int pendingScore = 0;
        for (RegioniumRegion candidate : regions) {
            if (used.contains(candidate) || candidate.isRetired()) {
                continue;
            }

            for (RegioniumRegion destination : candidate.mergeIntoLater()) {
                int score = scores.getOrDefault(destination, 0);
                if (score > pendingScore) {
                    pendingTarget = destination;
                    pendingScore = score;
                }
            }

            int score = 0;
            for (RegioniumRegion source : candidate.expectingMergeFrom()) {
                score += scores.getOrDefault(source, 0);
            }
            if (score > pendingScore) {
                pendingTarget = candidate;
                pendingScore = score;
            }
        }
        if (pendingTarget != null) {
            return pendingTarget;
        }

        // As in Folia, preserve one eligible existing region for a merge, but
        // never reuse a source that has split into multiple disconnected parts.
        for (RegioniumRegion candidate : regions) {
            if (!used.contains(candidate) && !splitSources.contains(candidate)
                && !candidate.isRetired() && scores.containsKey(candidate)
                && !candidate.hasPendingMerges()) {
                return candidate;
            }
        }
        return null;
    }

    private static List<Set<Long>> connectedComponents(Set<Long> sections) {
        Map<Long, Set<Long>> graph = new LinkedHashMap<>();
        for (long section : sections) {
            graph.put(section, new LinkedHashSet<>());
        }

        for (long section : sections) {
            int x = sectionX(section);
            int z = sectionZ(section);

            int recalculateRadius = Math.max(
                EMPTY_SECTION_CREATE_RADIUS,
                REGION_SECTION_MERGE_RADIUS
            );
            for (int dz = -recalculateRadius; dz <= recalculateRadius; ++dz) {
                for (int dx = -recalculateRadius; dx <= recalculateRadius; ++dx) {
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

        Map<Long, LevelChunk> desiredByKey = new LinkedHashMap<>();
        for (LevelChunk chunk : desiredChunks) {
            desiredByKey.put(chunk.getPos().pack(), chunk);
        }

        RegioniumWorldData data = region.worldData();
        for (LevelChunk chunk : List.copyOf(data.chunks())) {
            // A holder can replace its LevelChunk instance without changing the
            // chunk key (for example during a FULL/entity-ticking transition).
            // Key-only reconciliation retained the obsolete instance alongside
            // the replacement, leaving ticking sets and LevelTicks containers
            // attached to stale chunk state.
            if (desiredByKey.get(chunk.getPos().pack()) != chunk) {
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
            // Do not query DistanceManager here: its SimulationChunkTracker is
            // global mutable state owned by the server thread, and reading it
            // from region workers can corrupt its fastutil maps. These chunks
            // were selected from getTickingChunk() above, so the holder future
            // is the authoritative block-ticking state. The entity-ticking
            // future likewise publishes the holder's current entity-ticking
            // state without consulting the global DistanceManager.
            boolean blockTicking = holder != null && holder.getTickingChunk() == chunk;
            boolean entityTicking = holder != null && holder.getEntityTickingChunkFuture()
                .getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null) != null;
            data.setTickingChunk(chunk, blockTicking);
            data.setEntityTickingChunk(chunk, entityTicking);
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
        LevelState state = worlds.get(level);
        if (state == null || source.hasPendingTopologyTransition()
            || !state.transitionLock.tryLock()) {
            return;
        }
        try {
            if (source.hasPendingTopologyTransition()) {
                return;
            }

        for (Entity entity : List.copyOf(source.worldData().entities())) {
            if (entity.isRemoved() || entity.level() != level) {
                source.worldData().remove(entity);
                continue;
            }

            migrateEntityWithoutLock(level, entity);
        }
        } finally {
            state.transitionLock.unlock();
        }
    }

    /**
     * Compatibility entry point for callers which are not already inside a
     * region read-locked tick.
     */
    public void migrateEntitiesAfterTick(RegioniumRegion source) {
        LevelState state = worlds.get(source.level());
        if (state == null || source.hasPendingTopologyTransition()
            || !state.transitionLock.tryLock()) {
            return;
        }
        // Compatibility callers may run outside the region tick. Serialize
        // them with the per-level topology handoff before taking lifecycleLock.
        regionTickBarrier.readLock().lock();
        try {
            if (source.hasPendingTopologyTransition()) {
                return;
            }
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
        } finally {
            regionTickBarrier.readLock().unlock();
            state.transitionLock.unlock();
        }
    }

    private static void updateTickOffsets(Entity entity, RegioniumRegion source, RegioniumRegion destination) {
        if (source == destination) {
            return;
        }
        long tickOffset = destination.tickCount() - source.tickCount();
        long redstoneTimeOffset = destination.worldData().redstoneTime() - source.worldData().redstoneTime();
        ((RegioniumTickOffsetAccess) (Object) entity).regionium$updateTicks(tickOffset, redstoneTimeOffset);
    }

    private static net.minecraft.world.level.block.entity.BlockEntity blockEntityForTicker(
        RegioniumRegion source,
        net.minecraft.world.level.block.entity.TickingBlockEntity ticker
    ) {
        BlockPos pos = ticker.getPos();
        net.minecraft.world.level.chunk.ChunkAccess chunk = RegioniumPortalChunkAccess.getLoadedChunk(
            source.level(), pos.getX() >> 4, pos.getZ() >> 4,
            net.minecraft.world.level.chunk.status.ChunkStatus.FULL
        );
        if (chunk instanceof LevelChunk levelChunk) {
            return levelChunk.getBlockEntities().get(pos);
        }
        return null;
    }

    private static void updateTickOffsets(
        net.minecraft.world.level.block.entity.BlockEntity blockEntity,
        RegioniumRegion source,
        RegioniumRegion destination
    ) {
        if (source == destination) {
            return;
        }
        long tickOffset = destination.tickCount() - source.tickCount();
        long redstoneTimeOffset = destination.worldData().redstoneTime() - source.worldData().redstoneTime();
        ((RegioniumTickOffsetAccess) (Object) blockEntity).regionium$updateTicks(tickOffset, redstoneTimeOffset);
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

        if (source != null && RegioniumContext.currentRegion() != source) {
            // Never remove an entity from another region's entity set. Let its
            // owner perform the transfer; if that owner is already pending a
            // topology transition, the lifecycle handoff will move it instead.
            if (!source.hasPendingTopologyTransition()) {
                source.execute(() -> migrateEntity(level, entity));
            }
            return;
        }

        if (source != null) {
            updateTickOffsets(entity, source, destination);
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
        LevelState state = worlds.get(level);
        if (state == null || !state.transitionLock.tryLock()) {
            return;
        }
        // Serialize with topology handoff before taking lifecycleLock. Region
        // callers already hold the read side of the save/shutdown barrier.
        regionTickBarrier.readLock().lock();
        try {
            lifecycleLock.writeLock();
            try {
                migrateEntityLocked(level, entity);
            } finally {
                lifecycleLock.tryUnlockWrite();
            }
        } finally {
            regionTickBarrier.readLock().unlock();
            state.transitionLock.unlock();
        }
    }

    /** Moves an entity after a region-owned action without racing a topology handoff. */
    public void migrateEntityAfterRegionAction(ServerLevel level, Entity entity) {
        LevelState state = worlds.get(level);
        if (state == null || !state.transitionLock.tryLock()) {
            return;
        }
        try {
            migrateEntityWithoutLock(level, entity);
        } finally {
            state.transitionLock.unlock();
        }
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

        if (source != null && RegioniumContext.currentRegion() != source) {
            if (!source.hasPendingTopologyTransition()) {
                source.execute(() -> migrateEntity(level, entity));
            }
            return;
        }

        if (source != null) {
            updateTickOffsets(entity, source, destination);
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

    /**
     * Admit spatial work while topology is stable, so it cannot be appended
     * after the source region's migration snapshot has already been taken.
     */
    public boolean enqueueOwnedTask(ServerLevel level, long chunkKey, Runnable action) {
        LevelState state = worlds.get(level);
        if (state == null) {
            return false;
        }

        PendingOwnedTask pending = new PendingOwnedTask(chunkKey, Objects.requireNonNull(action, "action"));
        state.pendingOwnedTasks.add(pending);

        // If a transition is in progress, leave the task queued at level scope;
        // the transition's finally path drains it against the newly published
        // owner map. Adding before tryLock closes the unlock/flush race.
        if (!state.transitionLock.tryLock()) {
            return true;
        }

        try {
            if (state.topologyDirty) {
                return true;
            }
            RegioniumRegion owner = owner(level, chunkKey);
            if (owner == null || owner.isRetired()) {
                state.pendingOwnedTasks.remove(pending);
                drainPendingOwnedTasksLocked(state);
                return false;
            }
            drainPendingOwnedTasksLocked(state);
            return true;
        } finally {
            state.transitionLock.unlock();
            drainPendingOwnedTasks(state);
        }
    }

    private void drainPendingOwnedTasks(LevelState state) {
        if (state.topologyDirty || !state.transitionLock.tryLock()) {
            return;
        }
        try {
            drainPendingOwnedTasksLocked(state);
        } finally {
            state.transitionLock.unlock();
        }
    }

    /** Caller holds state.transitionLock. */
    private void drainPendingOwnedTasksLocked(LevelState state) {
        // Process only the entries present at the start. Tasks whose chunks
        // temporarily have no published owner must remain queued for a later
        // topology pass, rather than being silently discarded or causing this
        // drain loop to spin forever by immediately re-polling themselves.
        int pendingCount = state.pendingOwnedTasks.size();
        for (int i = 0; i < pendingCount; i++) {
            PendingOwnedTask pending = state.pendingOwnedTasks.poll();
            if (pending == null) {
                return;
            }

            RegioniumRegion owner = owner(state.level, pending.chunkKey());
            if (owner != null && !owner.isRetired()) {
                owner.worldData().enqueueOwnedTask(pending.chunkKey(), pending.action());
            } else {
                state.pendingOwnedTasks.add(pending);
            }
        }
    }

    public RegioniumRegion owner(ServerLevel level, int chunkX, int chunkZ) {
        return owner(level, ChunkPos.pack(chunkX, chunkZ));
    }

    /**
     * Checks ownership of every chunk in the inclusive square around a chunk
     * position. Missing worlds, unowned chunks, and ranges outside the chunk
     * coordinate space fail closed.
     */
    public boolean ownsChunkSquare(RegioniumRegion region, ServerLevel level, int centerChunkX, int centerChunkZ, int radius) {
        if (region == null || level == null || region.level() != level || radius < 0) {
            return false;
        }

        long minX = (long) centerChunkX - radius;
        long maxX = (long) centerChunkX + radius;
        long minZ = (long) centerChunkZ - radius;
        long maxZ = (long) centerChunkZ + radius;
        if (minX < Integer.MIN_VALUE || maxX > Integer.MAX_VALUE
            || minZ < Integer.MIN_VALUE || maxZ > Integer.MAX_VALUE) {
            return false;
        }

        long stamp = lifecycleLock.readLock();
        try {
            LevelState state = worlds.get(level);
            if (state == null) {
                return false;
            }
            Map<Long, RegioniumRegion> owners = state.sectionOwners;
            for (long chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
                for (long chunkX = minX; chunkX <= maxX; chunkX++) {
                    if (ownerForChunk(owners, ChunkPos.pack((int) chunkX, (int) chunkZ)) != region) {
                        return false;
                    }
                }
            }
            return true;
        } finally {
            lifecycleLock.unlockRead(stamp);
        }
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
                // Debug requests may be served by the HTTP thread. Derive the
                // reported ticking state from the holder futures rather than
                // reading the server-thread-owned DistanceManager off-thread.
                boolean inBlockTickingRange = holder.getTickingChunk() != null;
                boolean inEntityTickingRange = holder.getEntityTickingChunkFuture()
                    .getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null) != null;
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
     * Folia-style data callback: move only region-owned queues and metadata
     * from an immutable ownership plan. This method does not inspect entities,
     * holders, chunks, or world services. The lifecycle lock has been released;
     * the per-level transition lock serializes callbacks, and every source
     * and destination in this plan is non-tickable until publication completes.
     */
    private void migrateRegionData(
        RegioniumRegion source,
        Map<Long, RegioniumRegion> nextOwners,
        Set<RegioniumRegion> activeAfterTransition
    ) {
        RegioniumWorldData sourceData = source.worldData();

        // Chunk-targeted callbacks remain attached to their spatial owner
        // across split/merge publication instead of being stranded in a
        // retiring region's generic executor queue.
        LevelState levelState = worlds.get(source.level());
        sourceData.migrateOwnedTasks(
            chunkKey -> ownerForChunk(nextOwners, chunkKey),
            (chunkKey, task) -> levelState.pendingOwnedTasks.add(
                new PendingOwnedTask(chunkKey, task)
            )
        );

        // A split-created region inherits its source clock before any
        // timestamped region metadata is rebased. The world-state phase moves
        // the chunks later, but toggle histories are part of this data phase.
        for (LevelChunk chunk : List.copyOf(sourceData.chunks())) {
            RegioniumRegion destination = ownerForChunk(nextOwners, chunk.getPos().pack());
            if (destination != null && destination != source
                && destination.worldData().chunks().isEmpty()) {
                destination.worldData().inheritRedstoneTimeIfEmpty(sourceData);
            }
        }

        // Block events have positions but are not tied to a live chunk holder.
        for (BlockEventData event : sourceData.drainBlockEvents()) {
            RegioniumRegion destination = ownerForChunk(nextOwners, ChunkPos.pack(
                event.pos().getX() >> 4, event.pos().getZ() >> 4
            ));
            if (destination != null) {
                destination.worldData().pushBlockEventDuringMigration(event);
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
                net.minecraft.world.level.block.entity.BlockEntity blockEntity = blockEntityForTicker(source, ticker);
                if (blockEntity != null) {
                    updateTickOffsets(blockEntity, source, destination);
                }
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
                net.minecraft.world.level.block.entity.BlockEntity blockEntity = blockEntityForTicker(source, ticker);
                if (blockEntity != null) {
                    updateTickOffsets(blockEntity, source, destination);
                }
                destination.worldData().addMigratedBlockEntityTicker(ticker, true);
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

        if (!activeAfterTransition.contains(source)) {
            sourceData.blockEntityTickers().clear();
            sourceData.pendingBlockEntityTickers().clear();
            sourceData.redstoneUpdateInfos().clear();
            sourceData.chunkHoldersToBroadcast().clear();
            sourceData.clearOwnedTasks();
        }
    }

    /**
     * Folia's preMerge/preSplit callback phase. Region-owned queues are still
     * untouched here; migrateRegionData immediately follows and partitions or
     * combines them before the world-state handoff.
     */
    private void invokePreMergeAndPreSplit(TopologyHandoff handoff) {
        long stamp = lifecycleLock.writeLock();
        try {
            for (RegioniumRegion source : handoff.sources()) {
                Set<RegioniumRegion> destinations =
                    Collections.newSetFromMap(new IdentityHashMap<>());
                for (Map.Entry<Long, RegioniumRegion> entry : handoff.previousOwners().entrySet()) {
                    if (entry.getValue() != source) {
                        continue;
                    }
                    RegioniumRegion destination = handoff.owners().get(entry.getKey());
                    if (destination != null) {
                        destinations.add(destination);
                    }
                }

                if (destinations.size() > 1) {
                    preSplit(source, destinations);
                } else if (destinations.size() == 1) {
                    RegioniumRegion destination = destinations.iterator().next();
                    if (destination != source && !handoff.active().contains(source)) {
                        preMerge(source, destination);
                    }
                }
            }
        } finally {
            lifecycleLock.unlockWrite(stamp);
        }
    }

    private void preMerge(RegioniumRegion source, RegioniumRegion destination) {
        if (source == destination
            || source.lifecycleState() == RegioniumRegion.LifecycleState.TICKING
            || destination.lifecycleState() == RegioniumRegion.LifecycleState.TICKING) {
            throw new IllegalStateException(
                "Invalid preMerge lifecycle: source=" + source + ", destination=" + destination
            );
        }
        source.forwardMergeGraphInto(destination);
    }

    private void preSplit(RegioniumRegion source, Set<RegioniumRegion> destinations) {
        if (destinations.size() < 2
            || source.lifecycleState() == RegioniumRegion.LifecycleState.TICKING
            || destinations.stream().anyMatch(destination ->
                destination.lifecycleState() == RegioniumRegion.LifecycleState.TICKING)) {
            throw new IllegalStateException(
                "Invalid preSplit lifecycle: source=" + source + ", destinations=" + destinations
            );
        }
    }

    /**
     * World-state handoff runs after lifecycleLock is released. Only regions in
     * the transition are quiescent; unrelated region owners remain tickable.
     * Holder callbacks may proceed, so this uses the holder snapshot captured
     * with the plan.
     */
    private void completeRegionTransition(TopologyHandoff handoff) {
        Map<Long, RegioniumRegion> nextOwners = handoff.owners();
        Map<Long, ChunkHolder> holders = handoff.holders();
        Set<RegioniumRegion> activeAfterTransition = handoff.active();

        // The per-level transition lock is held by rebalance(). The sources
        // were marked pending before they released; assert that none restarted
        // while the region-data and world-state phases run.
        for (RegioniumRegion source : handoff.sources()) {
            if (source.lifecycleState() == RegioniumRegion.LifecycleState.TICKING) {
                throw new IllegalStateException(
                    "Cannot hand off a ticking region: " + source
                );
            }
        }
        // activeAfterTransition also contains unrelated regions that are
        // deliberately allowed to keep ticking. Only transition participants
        // may be touched by this lifecycle handoff.
        for (RegioniumRegion destination : handoff.sources()) {
            if (activeAfterTransition.contains(destination)
                && destination.lifecycleState() == RegioniumRegion.LifecycleState.TICKING) {
                throw new IllegalStateException(
                    "Cannot publish a ticking transition destination: " + destination
                );
            }
        }

        // Run Folia-equivalent lifecycle callbacks before mutating region data.
        // A split creates fresh destination identities; a merge keeps one
        // eligible destination and retires its sources.
        invokePreMergeAndPreSplit(handoff);

        // This is the region-data callback phase. It contains only Regionium's
        // region-owned queues and metadata. Apply it before moving chunks and
        // entities, matching Folia's data-before-world-state transition order.
        for (RegioniumRegion source : handoff.sources()) {
            migrateRegionData(source, nextOwners, activeAfterTransition);
        }

        handoffWorldState(handoff);

        // Folia exposes/activates replacement regions only after the region
        // data split or merge callback has completed. Regionium also waits for
        // its separate world-state handoff before publishing READY.
        long stamp = lifecycleLock.writeLock();
        try {
            Set<RegioniumRegion> participants =
                Collections.newSetFromMap(new IdentityHashMap<>());
            participants.addAll(handoff.sources());
            for (RegioniumRegion participant : participants) {
                participant.clearMergeRelationshipsWith(participants);
                participant.setPendingTopologyTransition(false);
            }

            for (RegioniumRegion region : handoff.sources()) {
                if (activeAfterTransition.contains(region)) {
                    if (region.hasPendingMerges()) {
                        throw new IllegalStateException(
                            "Cannot activate region with unresolved deferred merges: " + region
                        );
                    }
                    region.setLifecycleState(RegioniumRegion.LifecycleState.READY);
                } else {
                    region.setLifecycleState(RegioniumRegion.LifecycleState.DEAD);
                    region.requestStop();
                }
            }
        } finally {
            lifecycleLock.unlockWrite(stamp);
        }
    }

    /**
     * Moves entities, players, and live chunk instances after the region-data
     * phase. The per-level transition lock remains held, while lifecycleLock is
     * free so holder callbacks do not wait for substantial world-state work.
     */
    private void handoffWorldState(TopologyHandoff handoff) {
        Map<Long, RegioniumRegion> nextOwners = handoff.owners();
        Map<Long, ChunkHolder> holders = handoff.holders();
        Set<RegioniumRegion> activeAfterTransition = handoff.active();

        // World-state handoff is deliberately a separate phase: it touches
        // entities, players, LevelChunks and ChunkHolders, none of which belong
        // in the region-data merge/split callback.
        for (RegioniumRegion source : handoff.sources()) {
            RegioniumWorldData sourceData = source.worldData();

            // Entity transfers can be queued after their old owner removed
            // them from its entity set. Fold those transfers into the source
            // snapshot before resolving the new owner, or retiring this region
            // would orphan entities that were only present in incomingEntities.
            for (Entity incoming : source.drainIncomingEntities()) {
                if (!incoming.isRemoved() && incoming.level() == source.level()) {
                    sourceData.add(incoming);
                }
            }

            for (Entity entity : List.copyOf(sourceData.entities())) {
                RegioniumRegion destination = ownerForChunk(nextOwners, entity.chunkPosition().pack());
                if (destination == source) {
                    if (entity instanceof ServerPlayer player) {
                        ((dev.pandor.regionium.access.ServerPlayerRegioniumPacketAccess) (Object) player)
                            .regionium$updateRegion(sourceData);
                    }
                    continue;
                }
                if (destination != null) {
                    updateTickOffsets(entity, source, destination);
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

            // Repair player-only membership left by an earlier inconsistent transfer.
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

            for (LevelChunk chunk : List.copyOf(sourceData.chunks())) {
                long chunkKey = chunk.getPos().pack();
                RegioniumRegion destination = ownerForChunk(nextOwners, chunkKey);
                if (destination == source) continue;

                ChunkHolder holder = holders.get(chunkKey);
                LevelChunk authoritativeChunk = holder == null ? null : holder.getTickingChunk();
                RegioniumWorldData destinationData = destination == null ? null : destination.worldData();
                boolean destinationWasEmpty = destinationData != null && destinationData.chunks().isEmpty();
                if (destinationData != null) {
                    LevelChunk existing = destinationData.chunks().stream()
                        .filter(candidate -> candidate.getPos().pack() == chunkKey).findFirst().orElse(null);
                    if (existing != null) destinationData.removeChunk(existing);
                }

                long tickTimeOffset = 0L;
                if (destinationData != null) {
                    if (destinationWasEmpty) destinationData.inheritRedstoneTimeIfEmpty(sourceData);
                    else tickTimeOffset = destinationData.redstoneTime() - sourceData.redstoneTime();
                }
                sourceData.removeChunk(chunk);
                if (destinationData != null && authoritativeChunk != null) {
                    RegioniumWorldData.shiftScheduledTicks(authoritativeChunk, tickTimeOffset);
                    destinationData.addChunk(authoritativeChunk);
                    // The topology migration runs outside the DistanceManager's
                    // owner thread. Use the holder's published futures instead
                    // of querying its global SimulationChunkTracker maps.
                    destinationData.setTickingChunk(authoritativeChunk, holder.getTickingChunk() == authoritativeChunk);
                    boolean entityTicking = holder.getEntityTickingChunkFuture()
                        .getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null) != null;
                    destinationData.setEntityTickingChunk(authoritativeChunk, entityTicking);
                    destination.execute(() -> Regionium.scheduler().flushDeferredScheduledTicks(
                        source.level(), chunkKey, destination
                    ));
                }
            }

            if (!activeAfterTransition.contains(source)) {
                sourceData.clearPlayers();
                sourceData.chunks().clear();
                sourceData.tickingChunks().clear();
                sourceData.entityTickingChunks().clear();
                sourceData.entities().clear();
            }
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
        /** Serializes each dimension's data/topology handoff without pausing other dimensions. */
        private final ReentrantLock transitionLock = new ReentrantLock();
        /** Cross-region owned tasks arriving during a handoff are routed afterwards. */
        private final java.util.concurrent.ConcurrentLinkedQueue<PendingOwnedTask> pendingOwnedTasks =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final List<RegioniumRegion> regions = new ArrayList<>();
        private volatile Map<Long, RegioniumRegion> sectionOwners = Map.of();
        private final Map<Long, ChunkHolder> chunkHolders = new ConcurrentHashMap<>();
        /** Persistent 8x8 section nodes; dead nodes await topology publication. */
        private final Map<Long, RegionSectionState> sections = new LinkedHashMap<>();
        /** Persistent region topology: one owner for every existing holder. */
        private final Set<Long> regionChunks = new LinkedHashSet<>();
        /** Simulation state only; does not determine region ownership. */
        private final Set<Long> leasedChunks = new LinkedHashSet<>();
        private boolean holdersSeeded;
        private volatile boolean topologyDirty = true;
        private volatile Set<RegioniumRegion> active = Set.of();

        private LevelState(ServerLevel level) {
            this.level = level;
        }
    }

    private static final class RegionSectionState {
        private int chunkCount;
        private int nonEmptyNeighbours;

        private boolean isDead() {
            return this.chunkCount == 0 && this.nonEmptyNeighbours == 0;
        }
    }

    private record PendingOwnedTask(long chunkKey, Runnable action) {}

    private record TopologyHandoff(
        LevelState state,
        List<RegioniumRegion> sources,
        Map<Long, RegioniumRegion> owners,
        Map<Long, RegioniumRegion> previousOwners,
        Map<Long, ChunkHolder> holders,
        Set<RegioniumRegion> active
    ) {}
}
