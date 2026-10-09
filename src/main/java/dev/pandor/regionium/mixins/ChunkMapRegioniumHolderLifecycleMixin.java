package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Bridges vanilla ChunkHolder creation into Regionium's persistent topology. */
@Mixin(ChunkMap.class)
public abstract class ChunkMapRegioniumHolderLifecycleMixin {
    @Inject(method = "updateChunkScheduling", at = @At("RETURN"))
    private void regionium$holderCreated(
        long chunkKey,
        int oldTicketLevel,
        ChunkHolder oldHolder,
        int newTicketLevel,
        CallbackInfoReturnable<ChunkHolder> cir
    ) {
        ChunkHolder holder = cir.getReturnValue();
        if (holder == null) {
            return;
        }

        var level = ((ChunkMapRegioniumVisibleAccessorMixin) (Object) this).regionium$getLevel();
        if (net.minecraft.server.level.ChunkLevel.isLoaded(newTicketLevel)) {
            // ChunkHolder lifecycle is the authoritative render-distance topology.
            // Re-add even when the same holder is reused after an unload transition.
            Regionium.scheduler().regionizer().addChunkHolder(level, holder);
        } else {
            // Do not keep an unloaded holder as a bridge between otherwise
            // disconnected render-distance regions.
            Regionium.scheduler().regionizer().removeChunkHolder(level, holder.getPos().pack());
        }
    }
}
