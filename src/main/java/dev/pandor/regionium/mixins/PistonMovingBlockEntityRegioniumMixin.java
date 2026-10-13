package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.access.PistonMovingBlockEntityRegioniumAccess;
import dev.pandor.regionium.core.RegioniumRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PistonMovingBlockEntity.class)
public abstract class PistonMovingBlockEntityRegioniumMixin implements PistonMovingBlockEntityRegioniumAccess {
    @Shadow
    private long lastTicked;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void regionium$initializeLastTicked(CallbackInfo ci) {
        this.lastTicked = Long.MIN_VALUE;
    }

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getGameTime()J"
        )
    )
    private static long regionium$useRegionRedstoneTime(
        Level level,
        Operation<Long> original,
        Level ignoredLevel,
        BlockPos pos,
        BlockState state,
        PistonMovingBlockEntity entity
    ) {
        if (level instanceof ServerLevel serverLevel) {
            RegioniumRegion owner = Regionium.scheduler().regionizer().owner(
                serverLevel, net.minecraft.world.level.ChunkPos.containing(pos).pack()
            );
            if (owner != null) {
                return owner.worldData().redstoneTime();
            }
        }
        return original.call(level);
    }

    @Override
    public void regionium$offsetLastTicked(long offset) {
        if (offset != 0L && this.lastTicked != Long.MIN_VALUE) {
            this.lastTicked += offset;
        }
    }
}
