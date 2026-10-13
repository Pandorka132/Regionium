package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Builds scheduled ticks against the owning region's clock, not global game time. */
@Mixin(LevelAccessor.class)
public interface LevelAccessorRegioniumTickTimeMixin {
    @Inject(
        method = "createTick(Lnet/minecraft/core/BlockPos;Ljava/lang/Object;ILnet/minecraft/world/ticks/TickPriority;)Lnet/minecraft/world/ticks/ScheduledTick;",
        at = @At("HEAD"),
        cancellable = true
    )
    private <T> void regionium$createTickWithPriority(
        BlockPos pos,
        T type,
        int delay,
        TickPriority priority,
        CallbackInfoReturnable<ScheduledTick<T>> cir
    ) {
        RegioniumRegion clockOwner = regionium$clockOwner(pos);
        if (clockOwner != null) {
            cir.setReturnValue(new ScheduledTick<>(
                type, pos, clockOwner.worldData().redstoneTime() + delay,
                priority, clockOwner.worldData().nextSubTickCount()
            ));
        }
    }

    @Inject(
        method = "createTick(Lnet/minecraft/core/BlockPos;Ljava/lang/Object;I)Lnet/minecraft/world/ticks/ScheduledTick;",
        at = @At("HEAD"),
        cancellable = true
    )
    private <T> void regionium$createTick(
        BlockPos pos,
        T type,
        int delay,
        CallbackInfoReturnable<ScheduledTick<T>> cir
    ) {
        RegioniumRegion clockOwner = regionium$clockOwner(pos);
        if (clockOwner != null) {
            cir.setReturnValue(new ScheduledTick<>(
                type, pos, clockOwner.worldData().redstoneTime() + delay,
                TickPriority.NORMAL, clockOwner.worldData().nextSubTickCount()
            ));
        }
    }

    private RegioniumRegion regionium$clockOwner(BlockPos pos) {
        if (!((Object) this instanceof ServerLevel level)) {
            return null;
        }

        RegioniumRegion owner = Regionium.scheduler().regionizer().owner(level, ChunkPos.pack(pos));
        if (owner != null) {
            return owner;
        }

        RegioniumRegion current = RegioniumContext.currentRegion();
        return current != null && current.level() == level ? current : null;
    }
}
