package dev.pandor.regionium.mixins;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MinecraftServer.class)
public interface MinecraftServerTaskAccess {
    @Invoker("pollTask")
    boolean regionium$pollTask();
}
