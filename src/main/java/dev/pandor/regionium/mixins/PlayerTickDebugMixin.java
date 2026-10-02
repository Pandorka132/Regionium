package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerTickDebugMixin {
    @Unique
    private int regionium$debugTickCounter;

    @Inject(method = "tick", at = @At("HEAD"))
    private void regionium$debugTickThread(CallbackInfo ci) {
        if (++regionium$debugTickCounter % 20 == 0) {
            Regionium.LOGGER.info(
                "PLAYER TICK DEBUG: thread={}, regionThread={}",
                Thread.currentThread().getName(),
                Thread.currentThread().getName().startsWith("Regionium-Worker-")
            );
        }
    }
}
