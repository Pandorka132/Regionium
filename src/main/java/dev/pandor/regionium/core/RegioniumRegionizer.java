package dev.pandor.regionium.core;

import dev.pandor.regionium.Regionium;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * Region membership modelled after Folia's threaded regionizer.
 *
 * <p>A region is a connected component of player simulation areas. When two
 * simulation areas touch, their work must execute in one region because both
 * players can synchronously observe and mutate the same simulation space.
 * Components may merge when players approach and split when they separate.</p>
 *
 * <p>The vanilla world remains shared. This class only owns the mapping from
 * live players to Regionium execution regions.</p>
 */
public final class RegioniumRegionizer {
    private final List<RegioniumRegion> regions;
    private final Map<ServerPlayer, RegioniumRegion> playerRegions = new IdentityHashMap<>();
    private final Set<RegioniumRegion> activeRegions = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    public RegioniumRegionizer(List<RegioniumRegion> regions) {
        this.regions = List.copyOf(regions);
        if (regions.isEmpty()) {
            throw new IllegalArgumentException("At least one region is required");
        }
    }

    /**
     * Recomputes connected components from the current player simulation
     * areas. This is deliberately a server-thread operation and only changes
     * membership at a scheduler boundary.
     */
    public synchronized void rebalance(ServerLevel level) {
        List<ServerPlayer> players = new ArrayList<>(level.players());
        if (players.isEmpty()) {
            for (RegioniumRegion region : regions) {
                activeRegions.remove(region);
            }
            playerRegions.clear();
            return;
        }

        int radius = simulationDistance(level);
        Map<ServerPlayer, Set<ServerPlayer>> graph = new IdentityHashMap<>();
        for (ServerPlayer player : players) {
            graph.put(player, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
        }

        for (int i = 0; i < players.size(); i++) {
            ServerPlayer a = players.get(i);
            for (int j = i + 1; j < players.size(); j++) {
                ServerPlayer b = players.get(j);
                if (areasOverlap(a, b, radius)) {
                    graph.get(a).add(b);
                    graph.get(b).add(a);
                }
            }
        }

        List<List<ServerPlayer>> components = new ArrayList<>();
        Set<ServerPlayer> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (ServerPlayer root : players) {
            if (!visited.add(root)) {
                continue;
            }
            List<ServerPlayer> component = new ArrayList<>();
            ArrayDeque<ServerPlayer> queue = new ArrayDeque<>();
            queue.add(root);
            while (!queue.isEmpty()) {
                ServerPlayer player = queue.removeFirst();
                component.add(player);
                for (ServerPlayer next : graph.get(player)) {
                    if (visited.add(next)) {
                        queue.addLast(next);
                    }
                }
            }
            components.add(component);
        }

        components.sort(Comparator
            .comparingInt((List<ServerPlayer> c) -> c.size()).reversed()
            .thenComparing(c -> c.getFirst().getUUID()));

        Set<RegioniumRegion> used = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Map<ServerPlayer, RegioniumRegion> next = new IdentityHashMap<>();

        // Preserve an existing region for a component whenever possible.
        for (List<ServerPlayer> component : components) {
            RegioniumRegion selected = null;
            for (ServerPlayer player : component) {
                RegioniumRegion previous = playerRegions.get(player);
                if (previous != null && !used.contains(previous)) {
                    selected = previous;
                    break;
                }
            }
            if (selected == null) {
                selected = leastLoadedUnused(used);
            }
            if (selected == null) {
                // More connected components than workers: the only safe
                // fallback is to coalesce them into the least-loaded region.
                selected = leastLoaded(components, next);
            }

            used.add(selected);
            activeRegions.add(selected);
            for (ServerPlayer player : component) {
                next.put(player, selected);
            }
        }

        playerRegions.clear();
        playerRegions.putAll(next);

        for (RegioniumRegion region : regions) {
            if (!used.contains(region)) {
                activeRegions.remove(region);
            }
        }

        Regionium.LOGGER.debug(
            "[REGIONIZER] level={} players={} components={} activeRegions={}",
            level.dimension().identifier(), players.size(), components.size(), activeRegions.size()
        );
    }

    public synchronized RegioniumRegion regionFor(ServerPlayer player) {
        return playerRegions.get(player);
    }

    public synchronized boolean isActive(RegioniumRegion region) {
        return activeRegions.contains(region);
    }

    public synchronized Set<ServerPlayer> players(RegioniumRegion region) {
        Set<ServerPlayer> result = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map.Entry<ServerPlayer, RegioniumRegion> entry : playerRegions.entrySet()) {
            if (entry.getValue() == region) {
                result.add(entry.getKey());
            }
        }
        return Set.copyOf(result);
    }

    private RegioniumRegion leastLoadedUnused(Set<RegioniumRegion> used) {
        return regions.stream()
            .filter(region -> !used.contains(region))
            .min(Comparator.comparingInt(region -> players(region).size()))
            .orElse(null);
    }

    private RegioniumRegion leastLoaded(
        List<List<ServerPlayer>> components,
        Map<ServerPlayer, RegioniumRegion> assignments
    ) {
        Map<RegioniumRegion, Integer> counts = new IdentityHashMap<>();
        for (RegioniumRegion region : regions) {
            counts.put(region, 0);
        }
        for (RegioniumRegion region : assignments.values()) {
            counts.merge(region, 1, Integer::sum);
        }
        return regions.stream()
            .min(Comparator.comparingInt(region -> counts.get(region)))
            .orElse(regions.getFirst());
    }

    private static int simulationDistance(ServerLevel level) {
        DistanceManager distanceManager = level.getChunkSource().chunkMap.getDistanceManager();
        try {
            return ((dev.pandor.regionium.mixins.DistanceManagerRegioniumAccessorMixin) distanceManager)
                .regionium$getSimulationDistance();
        } catch (Throwable ignored) {
            return 8;
        }
    }

    private static boolean areasOverlap(ServerPlayer a, ServerPlayer b, int radius) {
        int dx = Math.abs(a.chunkPosition().x() - b.chunkPosition().x());
        int dz = Math.abs(a.chunkPosition().z() - b.chunkPosition().z());
        return dx <= radius * 2 && dz <= radius * 2;
    }
}
