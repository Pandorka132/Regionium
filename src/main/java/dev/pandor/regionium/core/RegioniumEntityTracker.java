package dev.pandor.regionium.core;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Region-local replacement for ChunkMap's global entity tracker.
 *
 * <p>The vanilla object graph remains shared. Tracker state is not: each
 * (world, region) has its own watcher set and ServerEntity instances. This is
 * the same important ownership boundary used by Folia's regionized tracker
 * data.</p>
 */
public final class RegioniumEntityTracker {
    private record TrackerKey(ServerLevel level, RegioniumRegion region) {}

    private final Map<TrackerKey, RegioniumWorldTracker> trackers = new java.util.HashMap<>();

    public synchronized RegioniumWorldTracker forRegion(ServerLevel level, RegioniumRegion region) {
        return trackers.computeIfAbsent(
            new TrackerKey(level, region),
            ignored -> new RegioniumWorldTracker(level, region)
        );
    }

    public synchronized void track(Entity entity, RegioniumRegion region) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }

        for (RegioniumWorldTracker tracker : List.copyOf(trackers.values())) {
            if (tracker.contains(entity) && tracker.region() != region) {
                tracker.remove(entity);
            }
        }
        forRegion(level, region).track(entity);
    }

    public synchronized void untrack(Entity entity) {
        for (RegioniumWorldTracker tracker : List.copyOf(trackers.values())) {
            tracker.remove(entity);
        }
    }

    public synchronized void move(Entity entity, RegioniumRegion destination) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }

        for (RegioniumWorldTracker tracker : List.copyOf(trackers.values())) {
            if (tracker.contains(entity) && tracker.region() != destination) {
                tracker.remove(entity);
            }
        }
        forRegion(level, destination).track(entity);
    }

    public synchronized void tick(ServerLevel level, RegioniumRegion region) {
        RegioniumWorldTracker tracker = trackers.get(new TrackerKey(level, region));
        if (tracker != null) {
            tracker.tick();
        }
    }

    public synchronized void clear(ServerLevel level, RegioniumRegion region) {
        trackers.remove(new TrackerKey(level, region));
    }

    public static final class RegioniumWorldTracker {
        private final ServerLevel level;
        private final RegioniumRegion region;
        private final Map<Entity, TrackedEntity> entities = new IdentityHashMap<>();

        private RegioniumWorldTracker(ServerLevel level, RegioniumRegion region) {
            this.level = level;
            this.region = region;
        }

        public RegioniumRegion region() {
            return region;
        }

        public boolean contains(Entity entity) {
            return entities.containsKey(entity);
        }

        public void track(Entity entity) {
            entities.computeIfAbsent(entity, TrackedEntity::new);
        }

        public void remove(Entity entity) {
            TrackedEntity tracked = entities.remove(entity);
            if (tracked != null) {
                tracked.removeAll();
            }
        }

        public void tick() {
            RegioniumWorldData data = dev.pandor.regionium.Regionium.scheduler().worldData(level);
            List<ServerPlayer> players = new ArrayList<>(data.players(region));

            // Lifecycle callbacks can legitimately lag one server-thread
            // maintenance pass behind the region clock. Make the region's
            // local entity index self-healing before tracker evaluation.
            for (ServerPlayer player : players) {
                track(player);
            }
            for (Entity entity : List.copyOf(data.entities(region))) {
                if (!entity.isRemoved() && entity.level() == level) {
                    track(entity);
                }
            }

            for (TrackedEntity tracked : List.copyOf(entities.values())) {
                if (tracked.entity.isRemoved() || tracked.entity.level() != level) {
                    entities.remove(tracked.entity);
                    tracked.removeAll();
                    continue;
                }

                tracked.updatePlayers(players);
                if (tracked.entity.needsSync || !players.isEmpty()) {
                    tracked.serverEntity.sendChanges();
                }
            }
        }

        private final class TrackedEntity implements ServerEntity.Synchronizer {
            private final Entity entity;
            private final ServerEntity serverEntity;
            private final java.util.Set<ServerPlayer> seenBy =
                java.util.Collections.newSetFromMap(new IdentityHashMap<>());

            private TrackedEntity(Entity entity) {
                this.entity = entity;
                net.minecraft.world.entity.EntityType<?> type = entity.getType();
                this.serverEntity = new ServerEntity(
                    level,
                    entity,
                    type.hasUpdateInterval()
                        ? net.minecraft.world.entity.UpdateInterval.periodic(type.updateInterval())
                        : net.minecraft.world.entity.UpdateInterval.NEVER,
                    type.trackDeltas(),
                    this
                );
            }

            private void updatePlayers(List<ServerPlayer> players) {
                for (ServerPlayer player : players) {
                    if (player == entity || player.level() != level) {
                        continue;
                    }

                    double dx = player.getX() - entity.getX();
                    double dz = player.getZ() - entity.getZ();
                    double range = Math.min(
                        entity.getType().clientTrackingRange() * 16.0,
                        level.getServer().getScaledTrackingDistance(
                            entity.getType().clientTrackingRange() * 16
                        )
                    );

                    boolean visible = dx * dx + dz * dz <= range * range
                        && entity.broadcastToPlayer(player);

                    if (visible) {
                        if (seenBy.add(player)) {
                            serverEntity.addPairing(player);
                        }
                    } else if (seenBy.remove(player)) {
                        serverEntity.removePairing(player);
                    }
                }

                seenBy.removeIf(player -> {
                    if (!players.contains(player) || player.level() != level) {
                        serverEntity.removePairing(player);
                        return true;
                    }
                    return false;
                });
            }

            private void removeAll() {
                for (ServerPlayer player : List.copyOf(seenBy)) {
                    serverEntity.removePairing(player);
                }
                seenBy.clear();
            }

            @Override
            public void sendToTrackingPlayers(Packet<? super ClientGamePacketListener> packet) {
                for (ServerPlayer player : List.copyOf(seenBy)) {
                    player.connection.send(packet);
                }
            }

            @Override
            public void sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener> packet) {
                sendToTrackingPlayers(packet);
                if (entity instanceof ServerPlayer player) {
                    player.connection.send(packet);
                }
            }

            @Override
            public void sendToTrackingPlayersFiltered(
                Packet<? super ClientGamePacketListener> packet,
                Predicate<ServerPlayer> predicate
            ) {
                for (ServerPlayer player : List.copyOf(seenBy)) {
                    if (predicate.test(player)) {
                        player.connection.send(packet);
                    }
                }
            }
        }
    }
}
