package dev.pandor.regionium.mixins;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerPlayer.class)
public interface ServerPlayerRegioniumPortalInvokerMixin {
    @Invoker("triggerDimensionChangeTriggers")
    void regionium$triggerDimensionChangeTriggers(ServerLevel oldLevel);
}
