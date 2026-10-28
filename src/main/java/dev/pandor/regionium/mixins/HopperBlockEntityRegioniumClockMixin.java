package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumTickOffsetAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityRegioniumClockMixin implements RegioniumTickOffsetAccess {
    @Override
    public void regionium$updateTicks(long fromTickOffset, long fromRedstoneTimeOffset) {
        if (this.tickedGameTime != Long.MIN_VALUE) {
            this.tickedGameTime += fromRedstoneTimeOffset;
        }
    }

    @Shadow private long tickedGameTime;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void regionium$initializeRegionClock(CallbackInfo ci) {
        this.tickedGameTime = Long.MIN_VALUE;
    }

    @Redirect(
        method = "pushItemsTick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getGameTime()J")
    )
    private static long regionium$useRegionClock(Level receiver, Level level, BlockPos pos, net.minecraft.world.level.block.state.BlockState state, HopperBlockEntity entity) {
        return RegioniumContext.redstoneTime(level, pos);
    }
}
