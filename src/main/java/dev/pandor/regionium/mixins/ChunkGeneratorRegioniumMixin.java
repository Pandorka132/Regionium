package dev.pandor.regionium.mixins;

import dev.pandor.regionium.access.StructureStartRegioniumAccess;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorRegioniumMixin {
    @Redirect(method = "tryAddReference", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/StructureStart;canBeReferenced()Z"))
    private static boolean regionium$claimReferenceAtomically(StructureStart start) {
        return ((StructureStartRegioniumAccess) (Object) start).regionium$tryReference();
    }
}
