package dev.pandor.regionium.core;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.mixins.ChunkMapRegioniumVisibleAccessorMixin;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
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
    private final Function<ServerLevel, RegioniumRegion> regionFactory;

    public RegioniumRegionizer(Function<ServerLevel, RegioniumRegion> regionFactory) {
        this.regionFactory = Objects.requireNonNull(regionFactory, "regionFactory");
    }

    /**
     * Region ticks do not hold the global topology lock. Folia regions continue
     * ticking while the global chunk topology is being updated; the topology
     * lock is only held for the short ownership-map rebuild itself.
     */
    public void enterRegionTick() {
    }

    public void exitRegionTick() {
    }

    public void registerLevel(ServerLevel level) {
        worlds.computeIfAbsent(level, ignored -> new LevelState(level));
    }

    public void unregisterLevel(ServerLevel level) {
        worlds.remove(level);
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
            ChunkHolder previous = state.chunkHolders.put(chunkKey, holder);
            if (previous != holder && state.regionChunks.add(chunkKey)) {
                state.topologyDirty = true;
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
            state.chunkHolders.put(chunkKey, holder);
            state.topologyDirty = true;
        } finally {
            lifecycleLock.tryUnlockWrite();
        }
    }

    private void updateChunkLifecycleLocked(ServerLevel level, ChunkHolder holder) {
        LevelState state = worlds.get(level);
        if (state == null) {
            return;
        }

        long chunkKey = holder.getPos().pack();
        state.chunkHolders.put(chunkKey, holder);

        boolean leased = isRegionChunk(holder);
        boolean wasLeased = state.leasedChunks.contains(chunkKey);
        if (leased) {
            if (state.leasedChunks.add(chunkKey)) {
                state.topologyDirty = true;
            }
        } else if (state.leasedChunks.remove(chunkKey)) {
            state.topologyDirty = true;
        }

        if (leased != wasLeased) {
            Regionium.LOGGER.trace(
                "[REGION-LIFECYCLE] dim={} chunk={} leased={} ticking={} entityTicking={} owner={}",
                level.dimension().identifier(),
                holder.getPos(),
                leased,
                holder.getTickingChunk() != null,
                holder.getEntityTickingChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null) != null,
                state.chunkOwners.get(chunkKey) == null ? "none" : state.chunkOwners.get(chunkKey).id()
            );
        }

        // Region-local chunk state is reconciled by the owning region thread.
        // Never mutate RegioniumWorldData from the server/chunk lifecycle thread.
    }

    public void rebalance(ServerLevel level) {
        LevelState state = worlds.get(level);
        if (state == null) {
            return;
        }

        if (!state.topologyDirty) {
            return;
        }

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
    }

    private void rebalanceLocked(ServerLevel level) {
        LevelState state = worlds.get(level);
        if (state == null) {
            return;
        }

        /*
         * ChunkMap's visible map is the lifecycle snapshot for the current
         * render-distance topology. The explicit holder callbacks are useful
         * for dirtying the regionizer, but they cannot be the sole source of
         * removal state: vanilla can keep/recycle a ChunkHolder while its
         * ticket crosses the loaded boundary. Reconcile the persistent set
         * against the actual loaded visible holders on every topology pass.
         *
         * This is still Folia's holder/section topology model: ownership is
         * not derived from player coordinates or simulation distance.
         */
        Map<Long, ChunkHolder> visible =
            ((ChunkMapRegioniumVisibleAccessorMixin) level.getChunkSource().chunkMap)
                .regionium$getVisibleChunkMap();

        Map<Long, ChunkHolder> liveHolders = new LinkedHashMap<>();
        for (ChunkHolder holder : visible.values()) {
            // visibleChunkMap can retain a holder while its ticket is already
            // below the loaded threshold. Such a holder is not part of the
            // render-distance topology and must not reconnect two regions.
            if (!net.minecraft.server.level.ChunkLevel.isLoaded(holder.getTicketLevel())) {
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
            for (RegioniumRegion region : state.regions) {
                region.stopScheduledTickLoop();
                migrateRegionState(region, null, Set.of());
            }
            state.chunkOwners.clear();
            state.active.clear();
            return;
        }

        List<Set<Long>> components = connectedComponents(currentSections);
        components.sort(Comparator
            .<Set<Long>>comparingInt(Set::size)
            .reversed()
            .thenComparingLong(set -> set.iterator().next()));

        Map<Long, RegioniumRegion> oldOwners = new LinkedHashMap<>(state.chunkOwners);
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
                int sectionX = sectionX(section);
                int sectionZ = sectionZ(section);

                for (int dz = 0; dz < (1 << REGION_SECTION_SHIFT); ++dz) {
                    for (int dx = 0; dx < (1 << REGION_SECTION_SHIFT); ++dx) {
                        long chunk = ChunkPos.pack(
                            (sectionX << REGION_SECTION_SHIFT) + dx,
                            (sectionZ << REGION_SECTION_SHIFT) + dz
                        );
                        if (currentChunks.contains(chunk)) {
                            nextOwners.put(chunk, selected);
                        }
                    }
                }
            }
        }

        /*
         * Apply chunk ownership changes as actual region-data migration.
         * The old region stops owning the chunk before the destination starts
         * ticking it. There is never a tick where both own the same chunk.
         */
        for (Map.Entry<Long, RegioniumRegion> entry : oldOwners.entrySet()) {
            long chunkKey = entry.getKey();
            RegioniumRegion from = entry.getValue();
            RegioniumRegion to = nextOwners.get(chunkKey);

            ChunkHolder holder = state.chunkHolders.get(chunkKey);
            LevelChunk chunk = holder == null ? null : holder.getTickingChunk();

            if (to == from) {
                // Ownership can become established before ChunkMap has
                // produced the actual ticking LevelChunk. Reconcile the
                // region-local chunk state on every topology pass so the
                // later promotion is not lost just because the owner itself
                // did not change.
                continue;
            }

        }

        for (Map.Entry<Long, RegioniumRegion> entry : nextOwners.entrySet()) {
            if (oldOwners.get(entry.getKey()) != null) {
                continue;
            }

            ChunkHolder holder = state.chunkHolders.get(entry.getKey());
            LevelChunk chunk = holder == null ? null : holder.getTickingChunk();
        }

        state.chunkOwners.clear();
        state.chunkOwners.putAll(nextOwners);

        state.active.clear();
        state.active.addAll(used);

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

        Regionium.LOGGER.trace(
            "[REGIONIZER] level={} chunks={} sections={} regions={}",
            level.dimension().identifier(),
            nextOwners.size(),
            currentSections.size(),
            used.size()
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

            RegioniumRegion destination = state.chunkOwners.get(entity.chunkPosition().pack());
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

            Regionium.LOGGER.trace(
                "[REGION-MIGRATE] entity={} {} -> {} chunk={}",
                entity.getType(),
                source == null ? -1 : source.id(),
                destination.id(),
                entity.chunkPosition()
            );
        }
    }

    private static boolean isRegionChunk(ChunkHolder holder) {
        if (holder == null) {
            return false;
        }

        if (holder.getTickingChunk() != null) {
            return true;
        }

        return holder.getEntityTickingChunkFuture()
            .getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK)
            .orElse(null) != null;
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

        for (long chunk : component) {
            int baseX = sectionX(chunk);
            int baseZ = sectionZ(chunk);

            for (int dz = 0; dz < (1 << REGION_SECTION_SHIFT); ++dz) {
                for (int dx = 0; dx < (1 << REGION_SECTION_SHIFT); ++dx) {
                    long chunkKey = ChunkPos.pack(
                        (baseX << REGION_SECTION_SHIFT) + dx,
                        (baseZ << REGION_SECTION_SHIFT) + dz
                    );
                    RegioniumRegion owner = oldOwners.get(chunkKey);
                    if (owner != null && !used.contains(owner)) {
                        scores.merge(owner, 1, Integer::sum);
                    }
                }
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
            for (Map.Entry<Long, RegioniumRegion> entry : state.chunkOwners.entrySet()) {
                if (entry.getValue() != region) {
                    continue;
                }
                ChunkHolder holder = state.chunkHolders.get(entry.getKey());
                if (holder == null) {
                    continue;
                }
                LevelChunk chunk = holder.getTickingChunk();
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
            data.setTickingChunk(chunk, true);

            ChunkHolder holder;
            stamp = lifecycleLock.readLock();
            try {
                holder = state.chunkHolders.get(chunk.getPos().pack());
            } finally {
                lifecycleLock.unlockRead(stamp);
            }
            LevelChunk entityTicking = holder == null
                ? null
                : holder.getEntityTickingChunkFuture()
                    .getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK)
                    .orElse(null);
            data.setEntityTickingChunk(chunk, chunk == entityTicking);
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
        RegioniumRegion destination = state.chunkOwners.get(chunkKey);
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
        RegioniumRegion destination = state.chunkOwners.get(chunkKey);
        RegioniumRegion source = findEntityRegion(state.regions, entity);

        if (entity instanceof ServerPlayer player) {
            if (destination == null || source != destination) {
                Regionium.LOGGER.trace(
                    "[PLAYER-OWNER] player={} chunk={} source={} destination={} leased={}",
                    player.getGameProfile().name(),
                    player.chunkPosition(),
                    source == null ? "none" : source.id(),
                    destination == null ? "none" : destination.id(),
                    state.leasedChunks.contains(chunkKey)
                );
            }
        }

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
            Regionium.LOGGER.info(
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
        RegioniumRegion result = state == null ? null : state.chunkOwners.get(chunkKey);
        if (lifecycleLock.validate(optimistic)) {
            return result;
        }

        lifecycleLock.readLock();
        try {
            state = worlds.get(level);
            return state == null ? null : state.chunkOwners.get(chunkKey);
        } finally {
            lifecycleLock.tryUnlockRead();
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

    public Set<RegioniumRegion> activeRegions() {
        Set<RegioniumRegion> result =
            Collections.newSetFromMap(new IdentityHashMap<>());

        for (LevelState state : worlds.values()) {
            result.addAll(state.active);
        }

        return Set.copyOf(result);
    }

    private static void migrateRegionState(
        RegioniumRegion region,
        RegioniumRegion ignored,
        Set<Long> ignoredChunks
    ) {
        for (Entity entity : List.copyOf(region.worldData().entities())) {
            region.worldData().remove(entity);
        }

        for (LevelChunk chunk : List.copyOf(region.worldData().chunks())) {
            region.worldData().removeChunk(chunk);
        }
        region.worldData().clearPlayers();
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
        private final Map<Long, RegioniumRegion> chunkOwners = new ConcurrentHashMap<>();
        private final Map<Long, ChunkHolder> chunkHolders = new ConcurrentHashMap<>();
        /** Persistent region topology: one owner for every existing holder. */
        private final Set<Long> regionChunks = new LinkedHashSet<>();
        /** Simulation state only; does not determine region ownership. */
        private final Set<Long> leasedChunks = new LinkedHashSet<>();
        private boolean topologyDirty = true;
        private final Set<RegioniumRegion> active =
            Collections.newSetFromMap(new IdentityHashMap<>());

        private LevelState(ServerLevel level) {
            this.level = level;
        }
    }
}
