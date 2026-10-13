package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Removes a chunk from Regionium only when vanilla actually unloads it. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelRegioniumChunkLifecycleMixin {
    @Redirect(
        method = "startTickingChunk",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunk;unpackTicks(J)V"
        )
    )
    private void regionium$unpackTicksUsingRegionClock(LevelChunk chunk, long ignoredGlobalTime) {
        ServerLevel level = (ServerLevel) (Object) this;
        var owner = Regionium.scheduler().regionizer().owner(level, chunk.getPos().pack());
        long time = owner == null ? level.getGameTime() : owner.worldData().redstoneTime();
        chunk.unpackTicks(time);
    }

    @Inject(method = "unload", at = @At("TAIL"))
    private void regionium$holderRemoved(LevelChunk chunk, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        Regionium.scheduler().regionizer().removeChunkHolder(level, chunk.getPos().pack());
    }
}
