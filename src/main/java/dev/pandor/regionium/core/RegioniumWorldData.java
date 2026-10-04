package dev.pandor.regionium.core;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Region-local world bookkeeping modelled after Folia's RegionizedWorldData. */
public final class RegioniumWorldData {
    private final ServerLevel level;
    private final ConcurrentHashMap<RegioniumRegion, Set<Entity>> entities = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RegioniumRegion, Set<ServerPlayer>> players = new ConcurrentHashMap<>();

    RegioniumWorldData(ServerLevel level) {
        this.level = level;
    }

    public ServerLevel level() {
        return level;
    }

    public Set<Entity> entities(RegioniumRegion region) {
        return entities.computeIfAbsent(region, ignored -> ConcurrentHashMap.newKeySet());
    }

    public Set<ServerPlayer> players(RegioniumRegion region) {
        return players.computeIfAbsent(region, ignored -> ConcurrentHashMap.newKeySet());
    }

    public void add(Entity entity, RegioniumRegion region) {
        if (entity instanceof ServerPlayer player) {
            players(region).add(player);
        }
        entities(region).add(entity);
    }

    public void remove(Entity entity, RegioniumRegion region) {
        if (entity instanceof ServerPlayer player) {
            players(region).remove(player);
        }
        entities(region).remove(entity);
    }

    public void clear(RegioniumRegion region) {
        entities.remove(region);
        players.remove(region);
    }
    public void clearPlayers(RegioniumRegion region) {
        Set<ServerPlayer> regionPlayers = players.get(region);
        if (regionPlayers != null) {
            regionPlayers.clear();
        }
    }
}
