package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens and closes the global collection window.
 * Regional tick timing is owned by each RegioniumRegion and is not controlled
 * by this mixin.
 */
@Mixin(MinecraftServer.class)
public abstract class RegioniumServerTickMixin {
    @Inject(method = "tickChildren", at = @At("HEAD"))
    private void regionium$beginServerTick(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        Regionium.scheduler().beginServerTick(server);

        /*
         * ServerLevel.tickChildren is wrapped by MinecraftServerLevelRegioniumMixin.
         * That wrapper is the single global ChunkSource.tick(..., false) call for
         * this server tick, immediately before region topology reconciliation.
         *
         * Do not tick ChunkSource here as well: doing so advances the global
         * chunk/ticket lifecycle twice per server tick and makes chunk promotion
         * and generation compete with the region schedulers.
         */
        for (ServerLevel level : server.getAllLevels()) {
            Regionium.scheduler().registerLevel(level);
            Regionium.scheduler().regionizer().rebalance(level);
        }
    }

    @Inject(method = "tickChildren", at = @At("TAIL"))
    private void regionium$applyTransfers(CallbackInfo ci) {
        Regionium.scheduler().applyPendingTransfers();
        Regionium.scheduler().finishServerTick();
    }
}
