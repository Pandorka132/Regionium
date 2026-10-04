package dev.pandor.regionium.mixins;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheRegioniumAccessorMixin {
    @Accessor("chunkHoldersToBroadcast")
    Set<ChunkHolder> regionium$getChunkHoldersToBroadcast();
}
