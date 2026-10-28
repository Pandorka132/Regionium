package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumRegion;
import dev.pandor.regionium.core.RegioniumPortalChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gameevent.GameEventDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GameEventDispatcher.class)
public abstract class GameEventDispatcherRegioniumOwnershipMixin {
    @Shadow @Final private ServerLevel level;

    @Redirect(
        method = "post",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerChunkCache;getChunkNow(II)Lnet/minecraft/world/level/chunk/LevelChunk;"
        )
    )
    private LevelChunk regionium$skipForeignRegionGameEventChunks(
        ServerChunkCache chunkSource,
        int chunkX,
        int chunkZ
    ) {
        RegioniumRegion current = RegioniumContext.currentRegion();
        if (current == null || current.level() != this.level) {
            return chunkSource.getChunkNow(chunkX, chunkZ);
        }

        // Folia checks a one-chunk radius before visiting listeners. Match the
        // same square, without loading chunks or delivering cross-region events.
        for (int x = chunkX - 1; x <= chunkX + 1; x++) {
            for (int z = chunkZ - 1; z <= chunkZ + 1; z++) {
                BlockPos probe = new BlockPos(x << 4, this.level.getMinY(), z << 4);
                if (!Regionium.scheduler().isCurrentRegionFor(this.level, probe)) {
                    return null;
                }
            }
        }

        return RegioniumPortalChunkAccess.getLoadedChunk(this.level, chunkX, chunkZ, ChunkStatus.FULL)
            instanceof LevelChunk chunk ? chunk : null;
    }
}
