package dev.pandor.regionium.mixins;

import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(StructureManager.class)
public abstract class StructureManagerRegioniumMixin {
    @Shadow @Final private StructureCheck structureCheck;

    /** @author Regionium @reason The caller must claim the start atomically before updating the serialized reference check. */
    @Overwrite
    public void addReference(StructureStart start) {
        this.structureCheck.incrementReference(start.getChunkPos(), start.getStructure());
    }
}
