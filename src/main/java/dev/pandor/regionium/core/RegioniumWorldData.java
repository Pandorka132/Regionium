package dev.pandor.regionium.core;

import dev.pandor.regionium.Regionium;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.redstone.NeighborUpdater;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The mutable state owned by exactly one region.
 *
 * This intentionally does not contain Map<Region, ...> structures. A
 * RegioniumWorldData instance IS the region-local state, mirroring Folia's
 * RegionizedWorldData model.
 */
public final class RegioniumWorldData {
    private final ServerLevel level;
    private final RegioniumRegion region;

    private final Set<Entity> entities =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ServerPlayer> players =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<LevelChunk> chunks =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<LevelChunk> tickingChunks =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<LevelChunk> entityTickingChunks =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ChunkHolder> chunkHoldersToBroadcast =
        Collections.newSetFromMap(new IdentityHashMap<>());

    private final List<TickingBlockEntity> pendingBlockEntityTickers = new ArrayList<>();
    private final List<TickingBlockEntity> blockEntityTickers = new ArrayList<>();

    private final LevelTicks<Block> blockTicks;
    private final LevelTicks<Fluid> fluidTicks;
    private final NeighborUpdater neighborUpdater;

    private final List<BlockEventData> blockEvents = new ArrayList<>();
    private final AtomicLong redstoneTime = new AtomicLong(1L);
    private final AtomicLong subTickOrder = new AtomicLong();
    private volatile boolean handlingTick;
    private volatile boolean tickingBlockEntities;

    RegioniumWorldData(ServerLevel level, RegioniumRegion region) {
        this.level = level;
        this.region = region;

        this.blockTicks = new LevelTicks<>(
            pos -> ownsChunk(pos)
        );
        this.fluidTicks = new LevelTicks<>(
            pos -> ownsChunk(pos)
        );
        this.neighborUpdater = new CollectingNeighborUpdater(
            level,
            level.getServer().getMaxChainedNeighborUpdates()
        );
    }

    public ServerLevel level() {
        return level;
    }

    public RegioniumRegion region() {
        return region;
    }

    public Set<Entity> entities() {
        return entities;
    }

    public Set<ServerPlayer> players() {
        return players;
    }

    public Set<LevelChunk> chunks() {
        return chunks;
    }

    public Set<LevelChunk> tickingChunks() {
        return tickingChunks;
    }

    public Set<LevelChunk> entityTickingChunks() {
        return entityTickingChunks;
    }

    public Set<ChunkHolder> chunkHoldersToBroadcast() {
        return chunkHoldersToBroadcast;
    }

    public List<TickingBlockEntity> blockEntityTickers() {
        return blockEntityTickers;
    }

    public List<TickingBlockEntity> pendingBlockEntityTickers() {
        return pendingBlockEntityTickers;
    }

    public LevelTicks<Block> blockTicks() {
        return blockTicks;
    }

    public LevelTicks<Fluid> fluidTicks() {
        return fluidTicks;
    }

    public NeighborUpdater neighborUpdater() {
        return neighborUpdater;
    }

    public List<BlockEventData> blockEvents() {
        return blockEvents;
    }

    public long redstoneTime() {
        return redstoneTime.get();
    }

    public void setRedstoneTime(long value) {
        redstoneTime.set(value);
    }

    public long nextSubTickOrder() {
        return subTickOrder.incrementAndGet();
    }

    public boolean isHandlingTick() {
        return handlingTick;
    }

    public void setHandlingTick(boolean value) {
        handlingTick = value;
    }

    public boolean isTickingBlockEntities() {
        return tickingBlockEntities;
    }

    public void setTickingBlockEntities(boolean value) {
        tickingBlockEntities = value;
    }

    public void add(Entity entity) {
        entities.add(entity);
        if (entity instanceof ServerPlayer player) {
            players.add(player);
        }
    }

    public void remove(Entity entity) {
        entities.remove(entity);
        if (entity instanceof ServerPlayer player) {
            players.remove(player);
        }
    }

    public void addChunk(LevelChunk chunk) {
        if (!chunks.add(chunk)) {
            return;
        }

        try {
            var access = (dev.pandor.regionium.mixins.LevelChunkRegioniumTickAccessorMixin) chunk;
            @SuppressWarnings("unchecked")
            LevelChunkTicks<Block> block =
                (LevelChunkTicks<Block>) access.regionium$getBlockTicks();
            @SuppressWarnings("unchecked")
            LevelChunkTicks<Fluid> fluid =
                (LevelChunkTicks<Fluid>) access.regionium$getFluidTicks();

            blockTicks.addContainer(chunk.getPos(), block);
            fluidTicks.addContainer(chunk.getPos(), fluid);
        } catch (Throwable error) {
            Regionium.LOGGER.error(
                "Failed to register chunk {} in region {}",
                chunk.getPos(),
                region.id(),
                error
            );
        }
    }

    public void removeChunk(LevelChunk chunk) {
        if (!chunks.remove(chunk)) {
            return;
        }
        blockTicks.removeContainer(chunk.getPos());
        fluidTicks.removeContainer(chunk.getPos());
        tickingChunks.remove(chunk);
        entityTickingChunks.remove(chunk);
    }

    public void setTickingChunk(LevelChunk chunk, boolean ticking) {
        if (!chunks.contains(chunk)) {
            return;
        }
        if (ticking) {
            tickingChunks.add(chunk);
        } else {
            tickingChunks.remove(chunk);
        }
    }

    public void setEntityTickingChunk(LevelChunk chunk, boolean ticking) {
        if (!chunks.contains(chunk)) {
            return;
        }
        if (ticking) {
            entityTickingChunks.add(chunk);
        } else {
            entityTickingChunks.remove(chunk);
        }
    }

    public void addBlockEntityTicker(TickingBlockEntity ticker) {
        blockEntityTickers.add(ticker);
    }

    public void pushPendingBlockEntityTickers() {
        if (pendingBlockEntityTickers.isEmpty()) {
            return;
        }
        blockEntityTickers.addAll(pendingBlockEntityTickers);
        pendingBlockEntityTickers.clear();
    }

    public void pushBlockEvent(BlockEventData event) {
        blockEvents.add(event);
    }

    public BlockEventData removeFirstBlockEvent() {
        return blockEvents.isEmpty() ? null : blockEvents.removeFirst();
    }

    public boolean hasEntity(Entity entity) {
        return entities.contains(entity);
    }

    public boolean hasChunk(LevelChunk chunk) {
        return chunks.contains(chunk);
    }

    private boolean ownsChunk(long chunkPos) {
        for (LevelChunk chunk : chunks) {
            if (chunk.getPos().pack() == chunkPos) {
                return true;
            }
        }
        return false;
    }

    /*
     * Compatibility accessors used while the ServerLevel mixins are being
     * converted. The argument is deliberately ignored: region-local data is
     * already scoped by this object.
     */
    public Set<Entity> entities(RegioniumRegion ignored) { return entities; }
    public Set<ServerPlayer> players(RegioniumRegion ignored) { return players; }
    public Set<LevelChunk> chunks(RegioniumRegion ignored) { return chunks; }
    public Set<LevelChunk> tickingChunks(RegioniumRegion ignored) { return tickingChunks; }
    public Set<LevelChunk> entityTickingChunks(RegioniumRegion ignored) { return entityTickingChunks; }
    public Set<ChunkHolder> chunkHoldersToBroadcast(RegioniumRegion ignored) { return chunkHoldersToBroadcast; }
    public List<TickingBlockEntity> blockEntityTickers(RegioniumRegion ignored) { return blockEntityTickers; }
    public LevelTicks<Block> blockTicks(RegioniumRegion ignored) { return blockTicks; }
    public LevelTicks<Fluid> fluidTicks(RegioniumRegion ignored) { return fluidTicks; }
    public NeighborUpdater neighborUpdater(RegioniumRegion ignored) { return neighborUpdater; }
    public long redstoneTime(RegioniumRegion ignored) { return redstoneTime(); }
    public void setRedstoneTime(RegioniumRegion ignored, long value) { setRedstoneTime(value); }
    public List<BlockEventData> deferredBlockEvents(RegioniumRegion ignored) { return blockEvents; }

    public void add(Entity entity, RegioniumRegion ignored) { add(entity); }
    public void remove(Entity entity, RegioniumRegion ignored) { remove(entity); }
    public void addChunk(LevelChunk chunk, RegioniumRegion ignored) { addChunk(chunk); }
    public void removeChunk(LevelChunk chunk, RegioniumRegion ignored) { removeChunk(chunk); }

    public void clearPlayers() {
        for (ServerPlayer player : List.copyOf(players)) {
            ((dev.pandor.regionium.access.ServerPlayerRegioniumPacketAccess) (Object) player)
                .regionium$updateRegion(null);
        }
        players.clear();
    }
}
