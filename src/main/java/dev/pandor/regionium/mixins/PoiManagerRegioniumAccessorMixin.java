package dev.pandor.regionium.mixins;

import net.minecraft.world.entity.ai.village.poi.PoiManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PoiManager.class)
public interface PoiManagerRegioniumAccessorMixin {
    @Accessor("distanceTracker")
    Object regionium$getDistanceTracker();
}
