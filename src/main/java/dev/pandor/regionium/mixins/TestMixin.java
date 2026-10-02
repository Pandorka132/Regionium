package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the vanilla server tick without replacing it yet.
 *
 * <p>The vanilla tick remains authoritative. Regionium currently runs its
 * own region execution phase before vanilla performs the actual world tick.
 * Later Mixins will move narrowly selected world/entity work into these
 * regions.</p>
 */
@Mixin(MinecraftServer.class)
public final class TestMixin {
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void regionium$beforeTick(CallbackInfo ci) {
        Regionium.scheduler().tick((MinecraftServer) (Object) this);
    }
}
