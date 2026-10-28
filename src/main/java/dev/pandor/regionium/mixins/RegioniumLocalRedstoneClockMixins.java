package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumTickOffsetAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.ConduitBlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(DaylightDetectorBlock.class)
abstract class DaylightDetectorRegioniumClockMixin {
    @Redirect(method = "tickEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getGameTime()J"))
    private static long regionium$clock(Level receiver, Level level, BlockPos pos, BlockState state, net.minecraft.world.level.block.entity.DaylightDetectorBlockEntity entity) {
        return RegioniumContext.redstoneTime(receiver, pos);
    }
}

@Mixin(HoneyBlock.class)
abstract class HoneyBlockRegioniumClockMixin {
    @Redirect(method = "maybeDoSlideAchievement", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getGameTime()J"))
    private long regionium$clock(Level receiver, Entity entity, BlockPos pos) {
        return RegioniumContext.redstoneTime(receiver, pos);
    }
}

@Mixin(LightningRodBlock.class)
abstract class LightningRodRegioniumClockMixin {
    @Redirect(method = "animateTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getGameTime()J"))
    private long regionium$clock(Level receiver, BlockState state, Level level, BlockPos pos, RandomSource random) {
        return RegioniumContext.redstoneTime(receiver, pos);
    }
}

@Mixin(BeaconBlockEntity.class)
abstract class BeaconBlockEntityRegioniumClockMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getGameTime()J"))
    private static long regionium$clock(Level receiver, Level level, BlockPos pos, BlockState state, BeaconBlockEntity entity) {
        return RegioniumContext.redstoneTime(receiver, pos);
    }
}

@Mixin(ConduitBlockEntity.class)
abstract class ConduitBlockEntityRegioniumClockMixin {
    @Redirect(method = "serverTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getGameTime()J"))
    private static long regionium$serverClock(Level receiver, Level level, BlockPos pos, BlockState state, ConduitBlockEntity entity) {
        return RegioniumContext.redstoneTime(receiver, pos);
    }

    @Redirect(method = "clientTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getGameTime()J"))
    private static long regionium$clientClock(Level receiver, Level level, BlockPos pos, BlockState state, ConduitBlockEntity entity) {
        return RegioniumContext.redstoneTime(receiver, pos);
    }
}

@Mixin(net.minecraft.world.level.block.piston.PistonBaseBlock.class)
abstract class PistonBaseBlockRegioniumClockMixin {
    @Redirect(
        method = "checkIfExtend",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getGameTime()J")
    )
    private long regionium$clock(Level receiver, Level level, BlockPos pos, BlockState state) {
        return RegioniumContext.redstoneTime(receiver, pos);
    }
}

@Mixin(PistonMovingBlockEntity.class)
abstract class PistonMovingBlockEntityRegioniumClockMixin implements RegioniumTickOffsetAccess {
    @Shadow private long lastTicked;

    @Inject(method = "<init>(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V", at = @At("RETURN"))
    private void regionium$initializeLastTicked(CallbackInfo ci) {
        this.lastTicked = Long.MIN_VALUE;
    }

    @Override
    public void regionium$updateTicks(long fromTickOffset, long fromRedstoneTimeOffset) {
        if (this.lastTicked != Long.MIN_VALUE) {
            this.lastTicked += fromRedstoneTimeOffset;
        }
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getGameTime()J"))
    private static long regionium$clock(Level receiver, Level level, BlockPos pos, BlockState state, PistonMovingBlockEntity entity) {
        return RegioniumContext.redstoneTime(receiver, pos);
    }
}
