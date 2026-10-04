package dev.pandor.regionium.mixins;

import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CollectingNeighborUpdater.class)
public interface CollectingNeighborUpdaterRegioniumAccessorMixin {
    @Accessor("maxChainedNeighborUpdates")
    int regionium$getMaxChainedNeighborUpdates();
}
