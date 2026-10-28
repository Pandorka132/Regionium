package dev.pandor.regionium.mixins;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MinecraftServer.class)
public interface MinecraftServerRegioniumShutdownInvokerMixin {
    @Invoker("stopServer")
    void regionium$invokeStopServer();
}
