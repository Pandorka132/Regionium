package dev.pandor.regionium.mixins;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.jetbrains.annotations.Nullable;

@Mixin(ChunkMap.class)
public interface ChunkMapRegioniumVisibleAccessorMixin {
    @Accessor("visibleChunkMap")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> regionium$getVisibleChunkMap();

    @Accessor("level")
    net.minecraft.server.level.ServerLevel regionium$getLevel();

    @Invoker("getVisibleChunkIfPresent")
    @Nullable ChunkHolder regionium$getVisibleChunkIfPresent(long chunkKey);
}
