package dev.pandor.regionium.core;

import net.minecraft.server.level.ServerLevel;
import dev.pandor.regionium.Regionium;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.level.redstone.NeighborUpdater;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ChunkHolder;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric equivalent of Folia's RegionizedWorldData.
 *
 * The Minecraft world object is shared. Mutable tick state which vanilla keeps
 * once per ServerLevel is indexed once per active region so region ticks do
 * not consume the same queue/list concurrently.
 */
public final class RegioniumWorldData {
    private final ServerLevel level;

    private final Map<RegioniumRegion, Set<Entity>> entities = new ConcurrentHashMap<>();
    private final Map<RegioniumRegion, Set<ServerPlayer>> players = new ConcurrentHashMap<>();
    private final Map<RegioniumRegion, Set<LevelChunk>> chunks = new ConcurrentHashMap<>();
    private final Map<RegioniumRegion, Set<LevelChunk>> tickingChunks = new ConcurrentHashMap<>();
    private final Map<RegioniumRegion, Set<LevelChunk>> entityTickingChunks = new ConcurrentHashMap<>();
    private final Map<RegioniumRegion, Set<ChunkHolder>> chunkHoldersToBroadcast = new ConcurrentHashMap<>();
    private final Map<RegioniumRegion, List<TickingBlockEntity>> blockEntityTickers = new ConcurrentHashMap<>();

    private final Map<RegioniumRegion, LevelTicks<Block>> blockTicks = new ConcurrentHashMap<>();
    private final Map<RegioniumRegion, LevelTicks<Fluid>> fluidTicks = new ConcurrentHashMap<>();
    private final Map<RegioniumRegion, NeighborUpdater> neighborUpdaters = new ConcurrentHashMap<>();

    private final Map<RegioniumRegion, List<BlockEventData>> deferredBlockEvents = new ConcurrentHashMap<>();
    private final Map<RegioniumRegion, Long> redstoneTime = new ConcurrentHashMap<>();
    private final Map<RegioniumRegion, java.util.concurrent.atomic.AtomicLong> subTickOrder = new ConcurrentHashMap<>();

    private final Map<RegioniumRegion, Boolean> handlingTick = new ConcurrentHashMap<>();

    RegioniumWorldData(ServerLevel level) {
        this.level = level;
    }

    public ServerLevel level() { return level; }

    public Set<Entity> entities(RegioniumRegion region) {
        return entities.computeIfAbsent(region, ignored -> ConcurrentHashMap.newKeySet());
    }

    public Set<ServerPlayer> players(RegioniumRegion region) {
        return players.computeIfAbsent(region, ignored -> ConcurrentHashMap.newKeySet());
    }

    public Set<LevelChunk> chunks(RegioniumRegion region) {
        return chunks.computeIfAbsent(region, ignored -> ConcurrentHashMap.newKeySet());
    }

    public Set<LevelChunk> tickingChunks(RegioniumRegion region) {
        return tickingChunks.computeIfAbsent(region, ignored -> ConcurrentHashMap.newKeySet());
    }

    public Set<LevelChunk> entityTickingChunks(RegioniumRegion region) {
        return entityTickingChunks.computeIfAbsent(region, ignored -> ConcurrentHashMap.newKeySet());
    }

    public Set<ChunkHolder> chunkHoldersToBroadcast(RegioniumRegion region) {
        return chunkHoldersToBroadcast.computeIfAbsent(region, ignored -> ConcurrentHashMap.newKeySet());
    }

    public List<TickingBlockEntity> blockEntityTickers(RegioniumRegion region) {
        return blockEntityTickers.computeIfAbsent(region, ignored -> Collections.synchronizedList(new ArrayList<>()));
    }

    private boolean canScheduleTick(RegioniumRegion region, long chunkPos) {
        // The queue is already region-owned, so use the region's loaded chunk
        // set as the ownership predicate. Vanilla's global shouldTickBlocksAt()
        // is tied to the server-wide ticket view and can reject a perfectly
        // valid chunk while its region is executing it.
        return chunks(region).stream().anyMatch(chunk -> chunk.getPos().pack() == chunkPos);
    }

    public LevelTicks<Block> blockTicks(RegioniumRegion region) {
        return blockTicks.computeIfAbsent(region,
            ignored -> new LevelTicks<>(pos -> canScheduleTick(region, pos)));
    }

    public LevelTicks<Fluid> fluidTicks(RegioniumRegion region) {
        return fluidTicks.computeIfAbsent(region,
            ignored -> new LevelTicks<>(pos -> canScheduleTick(region, pos)));
    }

    public NeighborUpdater neighborUpdater(RegioniumRegion region) {
        return neighborUpdaters.computeIfAbsent(region, ignored -> {
            
            
            return new net.minecraft.world.level.redstone.CollectingNeighborUpdater(
                level,
                level.getServer().getMaxChainedNeighborUpdates()
            );
        });
    }

    public List<BlockEventData> deferredBlockEvents(RegioniumRegion region) {
        return deferredBlockEvents.computeIfAbsent(region,
            ignored -> Collections.synchronizedList(new ArrayList<>()));
    }

    public long redstoneTime(RegioniumRegion region) {
        return redstoneTime.getOrDefault(region, 1L);
    }

    public void setRedstoneTime(RegioniumRegion region, long time) {
        redstoneTime.put(region, time);
    }

    public long nextSubTickOrder(RegioniumRegion region) {
        return subTickOrder.computeIfAbsent(region, ignored -> new java.util.concurrent.atomic.AtomicLong())
            .incrementAndGet();
    }

    public boolean isHandlingTick(RegioniumRegion region) {
        return handlingTick.getOrDefault(region, false);
    }

    public void setHandlingTick(RegioniumRegion region, boolean value) {
        handlingTick.put(region, value);
    }

    public void add(Entity entity, RegioniumRegion region) {
        entities(region).add(entity);
        if (entity instanceof ServerPlayer player) players(region).add(player);
    }

    public void remove(Entity entity, RegioniumRegion region) {
        entities(region).remove(entity);
        if (entity instanceof ServerPlayer player) players(region).remove(player);
    }

    public void addChunk(LevelChunk chunk, RegioniumRegion region) {
        chunks(region).add(chunk);
        // These containers are the actual vanilla tick queues belonging to the
        // chunk. Registering the same container in one region-local LevelTicks
        // preserves vanilla ordering while isolating consumers by region.
        try {
            var access = (dev.pandor.regionium.mixins.LevelChunkRegioniumTickAccessorMixin) chunk;
            @SuppressWarnings("unchecked")
            var block = (net.minecraft.world.ticks.LevelChunkTicks<Block>) access.regionium$getBlockTicks();
            @SuppressWarnings("unchecked")
            var fluid = (net.minecraft.world.ticks.LevelChunkTicks<Fluid>) access.regionium$getFluidTicks();
            blockTicks(region).addContainer(chunk.getPos(), block);
            fluidTicks(region).addContainer(chunk.getPos(), fluid);
        } catch (Throwable error) {
            Regionium.LOGGER.error("[FOLIA-TICKS] Failed to register chunk {} in region {}", chunk.getPos(), region.id(), error);
        }
    }

    public void removeChunk(LevelChunk chunk, RegioniumRegion region) {
        chunks(region).remove(chunk);
        blockTicks(region).removeContainer(chunk.getPos());
        fluidTicks(region).removeContainer(chunk.getPos());
    }

    public void clear(RegioniumRegion region) {
        entities.remove(region);
        players.remove(region);
        chunks.remove(region);
        tickingChunks.remove(region);
        entityTickingChunks.remove(region);
        chunkHoldersToBroadcast.remove(region);
        blockEntityTickers.remove(region);
        blockTicks.remove(region);
        fluidTicks.remove(region);
        neighborUpdaters.remove(region);
        deferredBlockEvents.remove(region);
        redstoneTime.remove(region);
        subTickOrder.remove(region);
        handlingTick.remove(region);
    }

    public void clearPlayers(RegioniumRegion region) {
        Set<ServerPlayer> set = players.get(region);
        if (set == null) return;
        for (ServerPlayer player : set) {
            ((dev.pandor.regionium.access.ServerPlayerRegioniumPacketAccess) (Object) player)
                .regionium$updateRegion(null);
        }
        set.clear();
    }
}
