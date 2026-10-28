package dev.pandor.regionium.mixins;

import dev.pandor.regionium.access.StructureStartRegioniumAccess;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.storage.loot.functions.ExplorationMapFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ExplorationMapFunction.class)
public abstract class ExplorationMapFunctionRegioniumMixin {
    @Redirect(method = "claimStructureAt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/StructureStart;canBeReferenced()Z"))
    private boolean regionium$claimReferenceAtomically(StructureStart start) {
        return ((StructureStartRegioniumAccess) (Object) start).regionium$tryReference();
    }
}
