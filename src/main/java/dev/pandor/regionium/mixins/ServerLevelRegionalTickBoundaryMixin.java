package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Closes the collection side of a ServerLevel tick.
 *
 * <p>ServerLevel.tick() intentionally remains on the Minecraft server thread
 * for global/non-regional work such as weather, time, world-border state,
 * sleep handling, raids, debug synchronizers and entity-manager bookkeeping.
 * Regionium only captures the actual regional tick callbacks while that method
 * runs. Nothing is submitted to a worker until this TAIL is reached.</p>
 *
 * <p>At this point every regional phase has been appended in vanilla order:
 * scheduled ticks, chunk tick work, block events, entities and block entities.
 * Each region therefore receives one coherent FIFO tick plan. There is no
 * cross-region wait.</p>
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelRegionalTickBoundaryMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void regionium$legacyBoundary(CallbackInfo ci) {
        // Intentionally empty. Regional ticks are no longer released from
        // ServerLevel.tick(). The source is kept for historical/debug builds,
        // but the mixin is disabled in regionium.mixins.json.
    }
}
