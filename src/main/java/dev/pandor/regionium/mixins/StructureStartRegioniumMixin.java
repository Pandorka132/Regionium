package dev.pandor.regionium.mixins;

import dev.pandor.regionium.access.StructureStartRegioniumAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.objectweb.asm.Opcodes;

import java.util.concurrent.atomic.AtomicInteger;

@Mixin(StructureStart.class)
public abstract class StructureStartRegioniumMixin implements StructureStartRegioniumAccess {
    @Unique private AtomicInteger regionium$references;
    @Shadow private int references;
    @Shadow protected abstract int getMaxReferences();

    @Inject(method = "<init>", at = @At("TAIL"))
    private void regionium$initializeAtomicReferences(Structure structure, ChunkPos chunkPos, int references, PiecesContainer pieceContainer, CallbackInfo ci) {
        this.regionium$references = new AtomicInteger(this.references);
    }

    @Redirect(method = "createTag", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/StructureStart;references:I", opcode = Opcodes.GETFIELD))
    private int regionium$serializeAtomicReferences(StructureStart self) {
        return this.regionium$references.get();
    }

    @Unique
    public boolean tryReference() {
        for (int current = this.regionium$references.get();; current = this.regionium$references.get()) {
            if (current >= this.getMaxReferences()) return false;
            if (this.regionium$references.compareAndSet(current, current + 1)) return true;
        }
    }

    @Override
    @Unique
    public boolean regionium$tryReference() {
        return this.tryReference();
    }

    /** @author Regionium @reason Read the reference count atomically without reserving a reference. */
    @Overwrite
    public boolean canBeReferenced() {
        return this.regionium$references.get() < this.getMaxReferences();
    }

    /** @author Regionium @reason Preserve vanilla's increment semantics with an atomic reference count. */
    @Overwrite
    public void addReference() {
        this.regionium$references.incrementAndGet();
    }

    /** @author Regionium @reason Read the atomic structure reference count. */
    @Overwrite
    public int getReferences() {
        return this.regionium$references.get();
    }
}
