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
        if (net.minecraft.server.level.ChunkLevel.fullStatus(newTicketLevel)
            .isOrAfter(net.minecraft.server.level.FullChunkStatus.FULL)) {
            // Only FULL chunk leases belong to spatial topology. ChunkLevel.isLoaded()
            // also includes generation-only dependency holders outside this area.
            // Re-add when the same holder is promoted after a lower ticket level.
            Regionium.scheduler().regionizer().addChunkHolder(level, holder);
        } else {
            // A retained generation holder is not a leased world chunk and must
            // not bridge otherwise disconnected regions.
            Regionium.scheduler().regionizer().removeChunkHolder(level, holder.getPos().pack());
        }
    }
}
