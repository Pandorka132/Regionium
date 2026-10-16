package dev.pandor.regionium.core;

import dev.pandor.regionium.Regionium;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.redstone.NeighborUpdater;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.BlockEventData;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

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
    // Primitive chunk-key index for O(1) chunk ownership checks.
    private final LongSet chunkPositions = new LongOpenHashSet();
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
    private final ObjectLinkedOpenHashSet<BlockEventData> blockEvents = new ObjectLinkedOpenHashSet<>();
    private final List<RedstoneTorchBlock.Toggle> redstoneUpdateInfos = new ArrayList<>();
    // Folia keeps redstone/scheduled-tick time per region instead of reusing
    // the global daylight/game-time clock. The value is volatile because
    // topology migration may inspect it while holding the region tick barrier.
    private volatile long redstoneTime;
    private final AtomicLong subTickCount = new AtomicLong();
    private volatile boolean handlingTick;
    private volatile boolean tickingBlockEntities;

    RegioniumWorldData(ServerLevel level, RegioniumRegion region) {
        this.level = level;
        this.region = region;
        this.redstoneTime = level.getLevelData().getGameTime();

        // Match Folia's RegionizedWorldData: ownership alone does not make a
        // scheduled tick eligible to run. The vanilla predicate also checks
        // entity loading and the DistanceManager's simulation ticking range.
        this.blockTicks = new LevelTicks<>(
            pos -> ownsChunk(pos) && level.isPositionTickingWithEntitiesLoaded(pos)
        );
        this.fluidTicks = new LevelTicks<>(
            pos -> ownsChunk(pos) && level.isPositionTickingWithEntitiesLoaded(pos)
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

    public long redstoneTime() {
        return redstoneTime;
    }

    /** Recent redstone-torch toggles owned exclusively by this region. */
    public List<RedstoneTorchBlock.Toggle> redstoneUpdateInfos() {
        return redstoneUpdateInfos;
    }

    public void advanceRedstoneTime() {
        redstoneTime++;
    }

    public long nextSubTickCount() {
        return subTickCount.getAndIncrement();
    }

    /**
     * A newly created region produced by a split inherits the source region's
     * clock. Existing regions keep their own clock; pending chunk ticks moved
     * into them are shifted by the clock difference during migration.
     */
    public void inheritRedstoneTimeIfEmpty(RegioniumWorldData source) {
        if (chunks.isEmpty()) {
            redstoneTime = source.redstoneTime;
        }
    }

    /**
     * Rebase the pending scheduled ticks stored by a chunk when its region
     * changes. LevelChunkTicks stores absolute trigger times, so moving the
     * container without shifting these values changes the delay of every
     * pending block/fluid tick.
     */
    public static void shiftScheduledTicks(LevelChunk chunk, long offset) {
        if (offset == 0L) {
            return;
        }
        @SuppressWarnings("unchecked")
        LevelChunkTicks<Block> block = (LevelChunkTicks<Block>)
            ((dev.pandor.regionium.mixins.LevelChunkRegioniumTickAccessorMixin) chunk)
                .regionium$getBlockTicks();
        @SuppressWarnings("unchecked")
        LevelChunkTicks<Fluid> fluid = (LevelChunkTicks<Fluid>)
            ((dev.pandor.regionium.mixins.LevelChunkRegioniumTickAccessorMixin) chunk)
                .regionium$getFluidTicks();
        shiftTicks(block, offset);
        shiftTicks(fluid, offset);
    }

    private static <T> void shiftTicks(LevelChunkTicks<T> ticks, long offset) {
        List<net.minecraft.world.ticks.ScheduledTick<T>> pending = ticks.getAll().toList();
        if (pending.isEmpty()) {
            return;
        }
        ticks.removeIf(ignored -> true);
        for (net.minecraft.world.ticks.ScheduledTick<T> tick : pending) {
            ticks.schedule(new net.minecraft.world.ticks.ScheduledTick<>(
                tick.type(), tick.pos(), tick.triggerTick() + offset,
                tick.priority(), tick.subTickOrder()
            ));
        }
    }

    public void pushBlockEvent(BlockEventData event) {
        if (RegioniumContext.currentRegion() != region
            || !Regionium.scheduler().isCurrentRegionFor(level, event.pos())) {
            throw new IllegalStateException(
                "Cannot queue block event outside its owning region: " + event.pos()
            );
        }
        blockEvents.add(event);
    }

    public void pushBlockEventDuringMigration(BlockEventData event) {
        blockEvents.add(event);
    }

    public void pushBlockEvents(Iterable<BlockEventData> events) {
        for (BlockEventData event : events) {
            pushBlockEvent(event);
        }
    }

    public BlockEventData removeFirstBlockEvent() {
        while (!blockEvents.isEmpty()) {
            BlockEventData event = blockEvents.removeFirst();
            if (Regionium.scheduler().regionizer().owner(level, event.pos().getX() >> 4, event.pos().getZ() >> 4) == region) {
                return event;
            }
        }
        return null;
    }

    public List<BlockEventData> drainBlockEvents() {
        List<BlockEventData> result = new ArrayList<>(blockEvents);
        blockEvents.clear();
        return result;
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
        chunkPositions.add(chunk.getPos().pack());

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
            Regionium.scheduler().flushDeferredScheduledTicks(
                level, chunk.getPos().pack(), region
            );

            // The vanilla chunk owns the authoritative, rebindable ticker
            // wrappers. Reconcile them whenever a chunk enters a region so
            // both freshly loaded chunks and ownership migrations recover all
            // existing block-entity tickers instead of silently losing them.
            for (TickingBlockEntity ticker : access.regionium$getTickersInLevel().values()) {
                addBlockEntityTicker(ticker);
            }
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
        chunkPositions.remove(chunk.getPos().pack());
        blockTicks.removeContainer(chunk.getPos());
        fluidTicks.removeContainer(chunk.getPos());
        tickingChunks.remove(chunk);
        entityTickingChunks.remove(chunk);

        // A chunk unload invalidates every ticker wrapper backed by that
        // chunk. Keeping one in the region list lets an obsolete piston-moving
        // block entity continue ticking after the chunk was detached, and can
        // reintroduce stale block-entity state when the chunk is reloaded.
        long chunkKey = chunk.getPos().pack();
        blockEntityTickers.removeIf(ticker ->
            net.minecraft.world.level.ChunkPos.containing(ticker.getPos()).pack() == chunkKey);
        pendingBlockEntityTickers.removeIf(ticker ->
            net.minecraft.world.level.ChunkPos.containing(ticker.getPos()).pack() == chunkKey);
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

    /** Remove a ticker from this region's lists while its ownership is being transferred. */
    public void removeBlockEntityTicker(TickingBlockEntity ticker) {
        blockEntityTickers.removeIf(candidate -> candidate == ticker);
        pendingBlockEntityTickers.removeIf(candidate -> candidate == ticker);
    }

    public void addBlockEntityTicker(TickingBlockEntity ticker) {
        if (containsTicker(ticker)) {
            return;
        }
        (tickingBlockEntities ? pendingBlockEntityTickers : blockEntityTickers).add(ticker);
    }

    /**
     * Transfers an existing ticker without changing whether it was waiting to
     * be appended at the end of the current block-entity tick pass. Folia keeps
     * these two lists separate during region merge/split for this reason.
     */
    public void addMigratedBlockEntityTicker(TickingBlockEntity ticker, boolean pending) {
        if (containsTicker(ticker)) {
            return;
        }
        (pending ? pendingBlockEntityTickers : blockEntityTickers).add(ticker);
    }

    public boolean containsTicker(TickingBlockEntity ticker) {
        for (TickingBlockEntity candidate : blockEntityTickers) {
            if (candidate == ticker) {
                return true;
            }
        }
        for (TickingBlockEntity candidate : pendingBlockEntityTickers) {
            if (candidate == ticker) {
                return true;
            }
        }
        return false;
    }

    public void pushPendingBlockEntityTickers() {
        if (pendingBlockEntityTickers.isEmpty()) {
            return;
        }
        blockEntityTickers.addAll(pendingBlockEntityTickers);
        pendingBlockEntityTickers.clear();
    }

    public boolean hasEntity(Entity entity) {
        return entities.contains(entity);
    }

    public boolean hasChunk(LevelChunk chunk) {
        return chunks.contains(chunk);
    }

    public boolean hasChunk(long chunkPos) {
        return chunkPositions.contains(chunkPos);
    }

    public void removeBlockEvents(java.util.function.Predicate<BlockEventData> predicate) {
        blockEvents.removeIf(predicate);
    }

    private boolean ownsChunk(long chunkPos) {
        return chunkPositions.contains(chunkPos);
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
