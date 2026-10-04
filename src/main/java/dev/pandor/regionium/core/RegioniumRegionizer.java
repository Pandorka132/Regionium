package dev.pandor.regionium.core;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.mixins.ChunkMapRegioniumVisibleAccessorMixin;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fabric port of Folia's section based ThreadedRegionizer.
 *
 * Paper's SWMR/concurrentutil structures are intentionally not copied here:
 * Fabric does not ship Paper/Moonrise. The region topology and ownership
 * rules are kept, while the backing maps use ordinary Java collections.
 */
public final class RegioniumRegionizer {
    private static final int SECTION_SHIFT = 4;
    private static final int MERGE_RADIUS = 1;

    private final List<RegioniumRegion> regions;
    private final Map<ServerLevel, Map<Long, RegioniumRegion>> chunkOwners =
        Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<ServerLevel, Map<ServerPlayer, RegioniumRegion>> playerRegions =
        Collections.synchronizedMap(new IdentityHashMap<>());
    private final Set<RegioniumRegion> activeRegions =
        Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    public RegioniumRegionizer(List<RegioniumRegion> regions) {
        this.regions = List.copyOf(regions);
        if (regions.isEmpty()) throw new IllegalArgumentException("At least one region is required");
    }

    /** Rebuilds ownership from Folia-style loaded chunk sections. */
    public synchronized void rebalance(ServerLevel level) {
        Long2ObjectLinkedOpenHashMap<ChunkHolder> visible =
            ((ChunkMapRegioniumVisibleAccessorMixin) level.getChunkSource().chunkMap)
                .regionium$getVisibleChunkMap();

        Map<Long, RegioniumRegion> oldOwners = chunkOwners.getOrDefault(level, Map.of());
        Map<Long, RegioniumRegion> nextOwners = new java.util.HashMap<>();
        Set<Long> sections = new LinkedHashSet<>();

        for (long chunkKey : visible.keySet()) {
            sections.add(sectionKey(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey)));
        }

        if (sections.isEmpty()) {
            chunkOwners.put(level, nextOwners);
            playerRegions.computeIfAbsent(level, ignored -> new IdentityHashMap<>()).clear();
            return;
        }

        Map<Long, Set<Long>> graph = new java.util.HashMap<>();
        for (long section : sections) graph.put(section, new LinkedHashSet<>());
        for (long section : sections) {
            int sx = sectionX(section), sz = sectionZ(section);
            for (int dz = -MERGE_RADIUS; dz <= MERGE_RADIUS; dz++) {
                for (int dx = -MERGE_RADIUS; dx <= MERGE_RADIUS; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    long other = packSection(sx + dx, sz + dz);
                    if (sections.contains(other)) graph.get(section).add(other);
                }
            }
        }

        List<List<Long>> components = new ArrayList<>();
        Set<Long> visited = new LinkedHashSet<>();
        for (long root : sections) {
            if (!visited.add(root)) continue;
            List<Long> component = new ArrayList<>();
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(root);
            while (!queue.isEmpty()) {
                long current = queue.removeFirst();
                component.add(current);
                for (long next : graph.get(current)) if (visited.add(next)) queue.addLast(next);
            }
            components.add(component);
        }

        components.sort(Comparator.<List<Long>>comparingInt(List::size).reversed()
            .thenComparingLong(c -> c.getFirst()));

        Set<RegioniumRegion> used = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Long, RegioniumRegion> sectionOwners = new java.util.HashMap<>();

        for (List<Long> component : components) {
            RegioniumRegion selected = null;
            for (Map.Entry<Long, RegioniumRegion> old : oldOwners.entrySet()) {
                int cx = ChunkPos.getX(old.getKey()), cz = ChunkPos.getZ(old.getKey());
                if (component.contains(sectionKey(cx, cz)) && !used.contains(old.getValue())) {
                    selected = old.getValue();
                    break;
                }
            }
            if (selected == null) selected = leastLoadedUnused(used);
            if (selected == null) selected = leastLoaded();

            used.add(selected);
            activeRegions.add(selected);
            for (long section : component) sectionOwners.put(section, selected);
        }

        for (long chunkKey : visible.keySet()) {
            RegioniumRegion owner = sectionOwners.get(
                sectionKey(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey))
            );
            if (owner != null) nextOwners.put(chunkKey, owner);
        }

        chunkOwners.put(level, nextOwners);
        Map<ServerPlayer, RegioniumRegion> players =
            playerRegions.computeIfAbsent(level, ignored -> new IdentityHashMap<>());
        players.clear();
        for (ServerPlayer player : level.players()) {
            RegioniumRegion owner = nextOwners.get(player.chunkPosition().pack());
            if (owner == null) owner = nearestOwner(nextOwners, player.chunkPosition());
            if (owner != null) players.put(player, owner);
        }

        for (RegioniumRegion region : regions) if (!used.contains(region)) activeRegions.remove(region);

        Regionium.LOGGER.debug("[FOLIA-REGIONIZER] level={} chunks={} sections={} components={} active={}",
            level.dimension().identifier(), nextOwners.size(), sections.size(), components.size(), activeRegions.size());
    }

    public synchronized RegioniumRegion regionFor(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return null;
        RegioniumRegion owner = owner(level, player.chunkPosition().pack());
        if (owner != null) return owner;
        return playerRegions.getOrDefault(level, Map.of()).get(player);
    }

    public synchronized RegioniumRegion owner(ServerLevel level, long chunkKey) {
        Map<Long, RegioniumRegion> owners = chunkOwners.get(level);
        return owners == null ? null : owners.get(chunkKey);
    }

    public synchronized boolean isActive(RegioniumRegion region) { return activeRegions.contains(region); }

    public synchronized Set<ServerPlayer> players(RegioniumRegion region) {
        Set<ServerPlayer> result = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map<ServerPlayer, RegioniumRegion> players : playerRegions.values()) {
            for (Map.Entry<ServerPlayer, RegioniumRegion> entry : players.entrySet()) {
                if (entry.getValue() == region) result.add(entry.getKey());
            }
        }
        return Set.copyOf(result);
    }

    public synchronized Set<RegioniumRegion> activeRegions() { return Set.copyOf(activeRegions); }

    private RegioniumRegion leastLoadedUnused(Set<RegioniumRegion> used) {
        return regions.stream().filter(r -> !used.contains(r))
            .min(Comparator.comparingInt(r -> players(r).size())).orElse(null);
    }

    private RegioniumRegion leastLoaded() {
        return regions.stream().min(Comparator.comparingInt(r -> players(r).size()))
            .orElse(regions.getFirst());
    }

    private static RegioniumRegion nearestOwner(Map<Long, RegioniumRegion> owners, ChunkPos pos) {
        RegioniumRegion best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Map.Entry<Long, RegioniumRegion> entry : owners.entrySet()) {
            int distance = Math.abs(ChunkPos.getX(entry.getKey()) - pos.x())
                + Math.abs(ChunkPos.getZ(entry.getKey()) - pos.z());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entry.getValue();
            }
        }
        return best;
    }

    private static long sectionKey(int chunkX, int chunkZ) {
        return packSection(chunkX >> SECTION_SHIFT, chunkZ >> SECTION_SHIFT);
    }
    private static long packSection(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }
    private static int sectionX(long key) { return (int)(key >> 32); }
    private static int sectionZ(long key) { return (int)key; }
}
