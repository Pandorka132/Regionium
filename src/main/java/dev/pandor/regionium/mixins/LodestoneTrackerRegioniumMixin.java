package dev.pandor.regionium.mixins;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumPortalChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.Optional;

@Mixin(LodestoneTracker.class)
public abstract class LodestoneTrackerRegioniumMixin {
    @Overwrite
    public LodestoneTracker tick(ServerLevel level) {
        LodestoneTracker self = (LodestoneTracker) (Object) this;
        if (self.tracked() && self.target().isPresent()) {
            var target = self.target().get();
            if (target.dimension() != level.dimension()) {
                return self;
            }

            BlockPos pos = target.pos();
            ChunkAccess chunk = RegioniumContext.isRegionThread()
                ? RegioniumPortalChunkAccess.getLoadedChunk(level, pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL)
                : level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            return level.isInWorldBounds(pos)
                && (chunk == null || chunk.getBlockState(pos).getBlock() == Blocks.LODESTONE)
                ? self
                : new LodestoneTracker(Optional.empty(), true);
        }
        return self;
    }
}
