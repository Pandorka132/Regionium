package dev.pandor.regionium.mixins;

import dev.pandor.regionium.access.ChunkMapRegioniumTrackingAccess;
import dev.pandor.regionium.access.ChunkMapTrackedEntityRegioniumAccess;
import dev.pandor.regionium.core.RegioniumWorldData;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ChunkMap.class)
public abstract class ChunkMapRegioniumTrackingMixin implements ChunkMapRegioniumTrackingAccess {
    @Shadow private Int2ObjectMap<?> entityMap;

    @Override
    public void regionium$tickRegionEntities(RegioniumWorldData data) {
        for (Entity entity : java.util.List.copyOf(data.entities())) {
            if (entity.isRemoved() || entity.level() != data.level()) {
                continue;
            }
            Object tracked = entityMap.get(entity.getId());
            if (tracked instanceof ChunkMapTrackedEntityRegioniumAccess access) {
                access.regionium$sendChanges(java.util.List.copyOf(data.players()));
            }
        }
    }
}
