package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Removes a chunk from Regionium only when vanilla actually unloads it. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelRegioniumChunkLifecycleMixin {
    @Inject(method = "unload", at = @At("TAIL"))
    private void regionium$holderRemoved(LevelChunk chunk, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        Regionium.scheduler().regionizer().removeChunkHolder(level, chunk.getPos().pack());
    }
}
