package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LevelTicks is not thread-safe. Region workers may schedule the next tick while
 * executing a chunk/entity/block-entity callback, so those writes are collected
 * and committed by the server thread immediately before the next LevelTicks.tick().
 *
 * <p>This preserves vanilla queue ownership without dropping redstone schedules
 * or concurrently mutating the tick container.</p>
 */
@Mixin(LevelTicks.class)
public abstract class LevelTicksRegioniumScheduleMixin<T> {
    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void regionium$deferWorkerSchedule(ScheduledTick<T> tick, CallbackInfo ci) {
        if (!dev.pandor.regionium.core.RegioniumContext.isRegionThread()) {
            return;
        }

        Regionium.scheduler().deferScheduledTick(this, tick);
        ci.cancel();
    }
}
