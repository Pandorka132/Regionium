package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import org.spongepowered.asm.mixin.Mixin;

import java.util.function.BooleanSupplier;

@Mixin(PoiManager.class)
public abstract class PoiManagerRegioniumMixin {
    @WrapMethod(method = "sectionsToVillage")
    private int regionium$lockDistanceRead(SectionPos pos, Operation<Integer> original) {
        synchronized (this) {
            return original.call(pos);
        }
    }

    @WrapMethod(method = "tick")
    private void regionium$lockDistanceTick(BooleanSupplier haveTime, Operation<Void> original) {
        synchronized (this) {
            original.call(haveTime);
        }
    }

    @WrapMethod(method = "setDirty")
    private void regionium$lockDistanceDirty(long sectionPos, Operation<Void> original) {
        synchronized (this) {
            original.call(sectionPos);
        }
    }

    @WrapMethod(method = "onSectionLoad")
    private void regionium$lockDistanceSectionLoad(long sectionPos, Operation<Void> original) {
        synchronized (this) {
            original.call(sectionPos);
        }
    }
}
