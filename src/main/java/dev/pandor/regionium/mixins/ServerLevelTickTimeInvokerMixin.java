package dev.pandor.regionium.mixins;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerLevel.class)
public interface ServerLevelTickTimeInvokerMixin {
    @Invoker("tickTime")
    void regionium$invokeTickTime();
}
