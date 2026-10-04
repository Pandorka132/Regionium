package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import dev.pandor.regionium.mixins.ServerLevelTickTimeInvokerMixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens and closes the global collection window. Regional tick timing is
 * owned by each RegioniumRegion and is not controlled by this mixin.
 */
@Mixin(MinecraftServer.class)
public abstract class RegioniumServerTickMixin {
    @Inject(method = "tickChildren", at = @At("HEAD"))
    private void regionium$beginServerTick(CallbackInfo ci) {
        Regionium.scheduler().beginServerTick();
        MinecraftServer server = (MinecraftServer) (Object) this;
        for (ServerLevel level : server.getAllLevels()) {
            Regionium.scheduler().registerLevel(level);
            // Refresh the vanilla simulation set before the independent
            // region clocks consume it. This is collection/bookkeeping only;
            // no region tick is released by this pass.
            Regionium.scheduler().refreshChunkLeases(level);
            // Keep ChunkMap/DistanceManager lifecycle maintenance on the
            // global server thread. The region tick only executes world work.
            level.getChunkSource().tick(() -> true, false);
        }
    }

    @Inject(method = "tickChildren", at = @At("TAIL"))
    private void regionium$applyTransfers(CallbackInfo ci) {
        Regionium.scheduler().applyPendingTransfers();
        Regionium.scheduler().finishServerTick();
    }
}
