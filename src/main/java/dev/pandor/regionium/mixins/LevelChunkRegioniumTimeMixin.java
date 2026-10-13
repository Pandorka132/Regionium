package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Uses the owning region's redstone clock when serializing pending chunk ticks. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkRegioniumTimeMixin {
    @ModifyVariable(method = "getTicksForSerialization", at = @At("HEAD"), argsOnly = true)
    private long regionium$serializationTime(long globalTime) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return globalTime;
        }

        var owner = Regionium.scheduler().regionizer().owner(level, chunk.getPos().pack());
        return owner == null ? globalTime : owner.worldData().redstoneTime();
    }
}
