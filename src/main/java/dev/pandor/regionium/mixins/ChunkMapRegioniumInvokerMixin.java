package dev.pandor.regionium.mixins;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface ChunkMapRegioniumInvokerMixin {
    @Invoker("getVisibleChunkIfPresent")
    @Nullable ChunkHolder regionium$getVisibleChunkIfPresent(long chunkKey);
}
