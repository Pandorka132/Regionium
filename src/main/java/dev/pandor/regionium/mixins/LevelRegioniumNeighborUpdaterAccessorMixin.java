package dev.pandor.regionium.mixins;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.redstone.NeighborUpdater;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Level.class)
public interface LevelRegioniumNeighborUpdaterAccessorMixin {
    @Accessor("neighborUpdater")
    NeighborUpdater regionium$getVanillaNeighborUpdater();
}
