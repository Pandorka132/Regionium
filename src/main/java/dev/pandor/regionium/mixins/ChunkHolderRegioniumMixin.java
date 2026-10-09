package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.Executor;

/** Marks region topology dirty whenever a chunk holder crosses a ticking state. */
@Mixin(ChunkHolder.class)
public abstract class ChunkHolderRegioniumMixin {
    @Inject(
        method = "updateFutures",
        at = @At("TAIL")
    )
    private void regionium$chunkTickStateChanged(
        ChunkMap scheduler,
        Executor mainThreadExecutor,
        CallbackInfo ci
    ) {
        var level = ((ChunkMapRegioniumVisibleAccessorMixin) (Object) scheduler).regionium$getLevel();
        Regionium.scheduler().regionizer().updateChunkLifecycle(
            level,
            (ChunkHolder) (Object) this
        );
    }
}
