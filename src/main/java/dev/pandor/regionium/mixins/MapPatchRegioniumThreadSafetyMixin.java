package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "net.minecraft.world.level.saveddata.maps.MapItemSavedData$MapPatch")
abstract class MapPatchRegioniumThreadSafetyMixin {
    @WrapMethod(method = "applyToMap")
    private void regionium$syncMapPatch(MapItemSavedData map, Operation<Void> original) {
        synchronized (map) {
            original.call(map);
        }
    }
}
