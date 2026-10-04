package dev.pandor.regionium.core;

import dev.pandor.regionium.Regionium;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import dev.pandor.regionium.mixins.DistanceManagerRegioniumAccessorMixin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks execution leases for vanilla's simulation chunks.
 *
 * <p>Render-only chunks deliberately never enter this map. A lease is only
 * meaningful for chunks that vanilla considers part of the simulation area.
 * The current phase records conflicts and ownership; the actual per-chunk
 * execution split is introduced separately so we do not concurrently run the
 * same ServerLevel through two workers before its tick boundary is safe.</p>
 */
public final class RegioniumChunkLeaseManager {
    private record ChunkKey(ServerLevel level, long pos) {}

    private final Map<ChunkKey, RegioniumRegion> leases = new HashMap<>();
    private final Map<ServerPlayer, Set<Long>> playerSimulation = new HashMap<>();

    public synchronized void refresh(ServerLevel level, RegioniumScheduler scheduler) {
        Regionium.LOGGER.debug("[LEASE] refresh START level={} players={} thread={}", level.dimension().identifier(), level.players().size(), Thread.currentThread().getName());
        playerSimulation.clear();
        int overlapCount = 0;

        // Remove stale leases for this level. Other dimensions remain intact.
        leases.entrySet().removeIf(entry -> entry.getKey().level() == level);

        for (ServerPlayer player : level.players()) {
            RegioniumRegion region = scheduler.regionizer().regionFor(player);
            if (region == null) {
                region = scheduler.ownerOf(player);
            }
            if (region == null) {
                continue;
            }

            Set<Long> simulated = simulatedChunks(level, player);
            Regionium.LOGGER.debug("[LEASE] player={} region={} simulatedChunks={}", player.getGameProfile().name(), region.id(), simulated.size());
            playerSimulation.put(player, simulated);

            for (long chunk : simulated) {
                ChunkKey key = new ChunkKey(level, chunk);
                RegioniumRegion previous = leases.putIfAbsent(key, region);
                if (previous != null && previous != region) {
                    // A player's simulation area can legitimately overlap
                    // another region's area across many loaded chunks. Do
                    // not log every chunk: a normal ~400-chunk view can
                    // otherwise flood the server log on every refresh.
                    overlapCount++;
                }
            }
        }
        Regionium.LOGGER.debug(
            "[LEASE] refresh END level={} leases={} overlaps={}",
            level.dimension().identifier(), leases.size(), overlapCount
        );
    }

    /** Resolves overlapping simulation areas by moving one player onto the other player's region. */
    public synchronized void resolveConflicts(ServerLevel level, RegioniumScheduler scheduler) {
        ServerPlayer[] players = level.players().toArray(ServerPlayer[]::new);
        for (int i = 0; i < players.length; i++) {
            for (int j = i + 1; j < players.length; j++) {
                ServerPlayer a = players[i];
                ServerPlayer b = players[j];
                if (!overlaps(a, b)) continue;

                RegioniumRegion aRegion = scheduler.ownerOf(a);
                RegioniumRegion bRegion = scheduler.ownerOf(b);
                if (aRegion == null || bRegion == null || aRegion == bRegion) continue;

                Regionium.LOGGER.info(
                    "[TRANSFER] overlap a={} region={} b={} region={}",
                    a.getGameProfile().name(), aRegion.id(),
                    b.getGameProfile().name(), bRegion.id()
                );

                RegioniumRegion aChunkRegion = owner(level, a.chunkPosition().pack());
                RegioniumRegion bChunkRegion = owner(level, b.chunkPosition().pack());
                ServerPlayer migrate;
                RegioniumRegion destination;

                if (aChunkRegion == bRegion && bChunkRegion != aRegion) {
                    migrate = a;
                    destination = bRegion;
                } else if (bChunkRegion == aRegion && aChunkRegion != bRegion) {
                    migrate = b;
                    destination = aRegion;
                } else {
                    UUID aId = a.getUUID();
                    UUID bId = b.getUUID();
                    if (aId.compareTo(bId) > 0) {
                        migrate = a;
                        destination = bRegion;
                    } else {
                        migrate = b;
                        destination = aRegion;
                    }
                }

                // Regionizer owns player-region membership now. The overlap
                // is resolved by its connected-component rebalance; do not
                // enqueue a second ownership transfer here.
                Regionium.LOGGER.debug(
                    "Simulation overlap resolved by regionizer: Player {} -> Region {}",
                    migrate.getGameProfile().name(), destination.id()
                );
            }
        }
    }

    /** Returns whether this region currently owns at least one simulation chunk. */
    public synchronized boolean hasOwnedChunks(ServerLevel level, RegioniumRegion region) {
        for (Map.Entry<ChunkKey, RegioniumRegion> entry : leases.entrySet()) {
            if (entry.getKey().level() == level && entry.getValue() == region) {
                return true;
            }
        }
        return false;
    }

    /** Returns the currently leased region, or null for render-only/unleased chunks. */
    public synchronized RegioniumRegion owner(ServerLevel level, long chunkPos) {
        return leases.get(new ChunkKey(level, chunkPos));
    }

    /** Returns true when two player simulation areas intersect. */
    public synchronized boolean overlaps(ServerPlayer a, ServerPlayer b) {
        Set<Long> first = playerSimulation.get(a);
        Set<Long> second = playerSimulation.get(b);
        if (first == null || second == null) {
            return false;
        }
        Set<Long> smaller = first.size() <= second.size() ? first : second;
        Set<Long> larger = smaller == first ? second : first;
        for (long chunk : smaller) {
            if (larger.contains(chunk)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds a player whose simulation area overlaps another region's area.
     * This intentionally returns the player pair, not a block-coordinate
     * destination: migration policy is a separate decision.
     */
    public synchronized ServerPlayer[] findConflict(ServerLevel level) {
        ServerPlayer[] players = level.players().toArray(ServerPlayer[]::new);
        for (int i = 0; i < players.length; i++) {
            for (int j = i + 1; j < players.length; j++) {
                if (overlaps(players[i], players[j])) {
                    RegioniumRegion a = playerSimulationRegion(players[i]);
                    RegioniumRegion b = playerSimulationRegion(players[j]);
                    if (a != null && b != null && a != b) {
                        return new ServerPlayer[]{players[i], players[j]};
                    }
                }
            }
        }
        return null;
    }

    private RegioniumRegion playerSimulationRegion(ServerPlayer player) {
        // This is resolved by the scheduler at the call site; the lease map
        // itself deliberately stores only chunk -> region ownership.
        for (Map.Entry<ChunkKey, RegioniumRegion> entry : leases.entrySet()) {
            if (entry.getKey().level() == player.level()
                && playerSimulation.getOrDefault(player, Set.of()).contains(entry.getKey().pos())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Set<Long> simulatedChunks(ServerLevel level, ServerPlayer player) {
        int radius = ((DistanceManagerRegioniumAccessorMixin) level.getChunkSource().chunkMap.getDistanceManager()).regionium$getSimulationDistance();
        int centerX = player.chunkPosition().x();
        int centerZ = player.chunkPosition().z();
        Set<Long> result = new HashSet<>((radius * 2 + 1) * (radius * 2 + 1));

        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                long packed = ChunkPos.pack(x, z);
                if (level.getChunkSource().chunkMap.getDistanceManager().inBlockTickingRange(packed)) {
                    result.add(packed);
                }
            }
        }
        return result;
    }
}
