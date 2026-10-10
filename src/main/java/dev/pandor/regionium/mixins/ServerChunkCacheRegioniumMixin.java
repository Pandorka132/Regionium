package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheRegioniumMixin {
    @Shadow private ServerLevel level;
    @Shadow private ChunkMap chunkMap;

    /**
     * ServerLevel.tick() is the region tick body now. Its ChunkSource must
     * therefore not run global ChunkMap maintenance or a second global tick.
     *
     * The global server thread separately calls ServerChunkCache.tick(...,
     * false) for ticket/unload maintenance.
     */
    @Inject(method = "blockChanged", at = @At("HEAD"), cancellable = true)
    private void regionium$regionLocalBlockChanged(
        net.minecraft.core.BlockPos pos,
        CallbackInfo ci
    ) {
        int xc = SectionPos.blockToSectionCoord(pos.getX());
        int zc = SectionPos.blockToSectionCoord(pos.getZ());
        long chunkKey = ChunkPos.pack(xc, zc);
        var current = RegioniumContext.isRegionThread()
            ? RegioniumContext.currentRegion()
            : null;
        var owner = Regionium.scheduler().regionizer().owner(level, chunkKey);

        if (owner == null) {
            // Chunks outside the region topology still use vanilla's queue.
            return;
        }

        net.minecraft.core.BlockPos immutablePos = pos.immutable();
        if (owner != current) {
            // Commands (including /setblock) commonly run on the server thread,
            // where the vanilla global queue is no longer broadcast because
            // the global tick's tickChunks pass is suppressed. Route the
            // notification to the chunk owner's region instead of stranding it
            // in that queue. This also covers cross-region callers.
            if (!owner.execute(() -> regionium$recordBlockChange(immutablePos, chunkKey, owner))) {
                return;
            }
            ci.cancel();
            return;
        }

        regionium$recordBlockChange(immutablePos, chunkKey, owner);
        // Never mutate ServerChunkCache's global broadcast queue from a region
        // worker. The region-owned queue is flushed by this region's tick.
        ci.cancel();
    }

    private void regionium$recordBlockChange(
        net.minecraft.core.BlockPos pos,
        long chunkKey,
        dev.pandor.regionium.core.RegioniumRegion expectedOwner
    ) {
        var owner = Regionium.scheduler().regionizer().owner(level, chunkKey);
        if (owner == null) {
            return;
        }
        if (owner != expectedOwner) {
            // Topology may have changed while this notification was queued.
            // Re-dispatch instead of mutating the new owner's state from the
            // previous owner's thread.
            owner.execute(() -> regionium$recordBlockChange(pos, chunkKey, owner));
            return;
        }

        ChunkHolder holder = ((ChunkMapRegioniumVisibleAccessorMixin) chunkMap)
            .regionium$getVisibleChunkMap().get(chunkKey);
        if (holder != null && holder.blockChanged(pos)) {
            Regionium.scheduler().worldData(level).chunkHoldersToBroadcast(expectedOwner).add(holder);
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void regionium$regionLocalChunkTick(
        java.util.function.BooleanSupplier haveTime,
        boolean tickChunks,
        CallbackInfo ci
    ) {
        if (!RegioniumContext.isRegionThread()) {
            // ServerLevel.tick() performs a true tickChunks call, but the
            // global server maintenance pass already invokes tick(false).
            // Prevent the global world tick from running a second chunk tick.
            if (tickChunks) {
                ci.cancel();
            }
            return;
        }

        var current = RegioniumContext.currentRegion();
        if (current == null) {
            ci.cancel();
            return;
        }

        if (tickChunks && level.tickRateManager().runsNormally() && !level.isDebug()) {
            int tickSpeed = level.getGameRules().get(
                net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED
            );

            for (var chunk : List.copyOf(current.worldData().entityTickingChunks())) {
                long packed = chunk.getPos().pack();
                if (Regionium.scheduler().regionizer().owner(level, packed) != current) {
                    continue;
                }

                Regionium.scheduler().beginChunkExecution(level, packed);
                try {
                    level.tickChunk(chunk, tickSpeed);
                } finally {
                    Regionium.scheduler().endChunkExecution(level, packed);
                }
            }
        }

        ((dev.pandor.regionium.access.ChunkMapRegioniumTrackingAccess) (Object) chunkMap)
            .regionium$tickRegionEntities(current.worldData());

        // Folia's ChunkMap tracker tick is part of the region tick. The
        // global ServerChunkCache maintenance pass must not run it, because
        // entity tracking state belongs to the ticking region.
        /*
         * Vanilla ChunkMap.tick() iterates the global entity tracker. That
         * tracker is not region-local yet, so executing it from a worker would
         * race the other regions. Region-local tracker ticking is a separate
         * lifecycle and must be implemented before this call is restored.
         */

        /*
         * This is the region-local equivalent of Folia's
         * ServerChunkCache.broadcastChangedChunks(). Block changes are queued
         * by blockChanged(), but the vanilla global ServerChunkCache queue is
         * not touched on a region thread. The owning region must flush its own
         * ChunkHolder update set here, otherwise block state changes are made
         * on the server but never sent to clients.
         */
        for (var holder : List.copyOf(current.worldData().chunkHoldersToBroadcast())) {
            var chunk = holder.getTickingChunk();
            if (chunk != null) {
                holder.broadcastChanges(chunk);
            }
        }
        current.worldData().chunkHoldersToBroadcast().clear();

        ci.cancel();
    }
}
