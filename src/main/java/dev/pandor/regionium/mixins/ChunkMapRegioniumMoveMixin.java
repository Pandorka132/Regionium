package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents vanilla's global ChunkMap.move() from mutating PlayerMap,
 * DistanceManager and entityMap from a region worker.
 *
 * Player spatial ownership is reconciled by RegioniumRegionizer after the
 * region tick. Chunk tracking will be made region-local separately; calling
 * the vanilla implementation here is unsafe because all of its mutable
 * indexes are global.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapRegioniumMoveMixin {
    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void regionium$skipGlobalMove(ServerPlayer player, CallbackInfo ci) {
        if (!RegioniumContext.isRegionThread()) {
            return;
        }

        RegioniumContext.currentRegion();
        ci.cancel();
    }
}
