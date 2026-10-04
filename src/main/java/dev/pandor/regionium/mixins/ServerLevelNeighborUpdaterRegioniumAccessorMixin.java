package dev.pandor.regionium.mixins;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Level.class)
public interface ServerLevelNeighborUpdaterRegioniumAccessorMixin {
    @Accessor("neighborUpdater")
    CollectingNeighborUpdater regionium$getNeighborUpdater();
}
