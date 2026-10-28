package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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
        var level = ((ChunkMapRegioniumVisibleAccessorMixin) (Object) this).regionium$getLevel();
        var regionizer = Regionium.scheduler().regionizer();

        // Region topology follows FULL spatial leases, not generation-only
        // dependency holders. 26.3 may keep those holders alive after their
        // ticket level drops, and counting them can bridge distant regions.
        if (holder != null) {
            regionizer.addChunkHolder(level, holder);
        }
    }

    @Shadow @Final private ServerLevel level;

    @Shadow @Final
    private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;

    @Redirect(
        method = "lambda$scheduleUnload$0",
        at = @At(
            value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap;remove(JLjava/lang/Object;)Z"
        )
    )
    private boolean regionium$holderActuallyUnloaded(
        Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads,
        long chunkKey,
        Object expectedHolder
    ) {
        boolean removed = pendingUnloads.remove(chunkKey, expectedHolder);
        if (removed && expectedHolder instanceof ChunkHolder holder) {
            // In 26.3, a holder is first moved to pendingUnloads and may be
            // reused before its asynchronous unload task runs. Mirror Folia's
            // holder-delete lifecycle only when that pending unload actually
            // wins the race and removes this exact holder.
            Regionium.scheduler().regionizer().removeChunkHolder(this.level, holder);
        }
        return removed;
    }
}
