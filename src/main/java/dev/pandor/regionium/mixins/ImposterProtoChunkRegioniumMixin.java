package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents world generation workers from reading live block entities. */
@Mixin(ImposterProtoChunk.class)
public abstract class ImposterProtoChunkRegioniumMixin {
    @Shadow @Final private boolean allowWrites;

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void regionium$guardWorldgenBlockEntityRead(
        BlockPos pos,
        CallbackInfoReturnable<BlockEntity> cir
    ) {
        MinecraftServer server = Regionium.scheduler().server();
        if (!this.allowWrites
            && !RegioniumContext.isRegionThread()
            && (server == null || !server.isSameThread())) {
            cir.setReturnValue(null);
        }
    }
}
