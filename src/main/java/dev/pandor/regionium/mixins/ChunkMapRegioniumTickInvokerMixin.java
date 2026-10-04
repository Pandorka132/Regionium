package dev.pandor.regionium.mixins;

import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.function.BooleanSupplier;

@Mixin(ChunkMap.class)
public interface ChunkMapRegioniumTickInvokerMixin {
    @Invoker("tick")
    void regionium$invokeTick(BooleanSupplier haveTime);
}
