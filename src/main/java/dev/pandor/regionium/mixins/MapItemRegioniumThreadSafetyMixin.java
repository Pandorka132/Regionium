package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(MapItem.class)
public abstract class MapItemRegioniumThreadSafetyMixin {
    @WrapMethod(method = "update")
    private void regionium$syncMapUpdate(Level level, Entity player, MapItemSavedData data, Operation<Void> original) {
        synchronized (data) {
            original.call(level, player, data);
        }
    }
}
