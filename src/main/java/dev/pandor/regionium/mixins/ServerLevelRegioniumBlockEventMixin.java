package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelRegioniumBlockEventMixin {
    @Inject(method = "blockEvent", at = @At("HEAD"), cancellable = true)
    private void regionium$deferWorkerBlockEvent(
        BlockPos pos, Block block, int paramA, int paramB, CallbackInfo ci
    ) {
        if (!RegioniumContext.isRegionThread()) {
            return;
        }

        Regionium.scheduler().deferBlockEvent(
            (ServerLevel) (Object) this,
            new BlockEventData(pos, block, paramA, paramB)
        );
        ci.cancel();
    }
}
