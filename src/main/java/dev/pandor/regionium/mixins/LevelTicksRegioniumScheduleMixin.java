package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumWorldData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Routes worker-generated scheduled ticks into the current Folia-style region. */
@Mixin(LevelTicks.class)
public abstract class LevelTicksRegioniumScheduleMixin<T> {
    private static final ThreadLocal<Boolean> REGIONIUM$ROUTING = ThreadLocal.withInitial(() -> false);

    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void regionium$routeSchedule(ScheduledTick<T> tick, CallbackInfo ci) {
        if (REGIONIUM$ROUTING.get()) return;

        var scheduler = dev.pandor.regionium.Regionium.scheduler();
        net.minecraft.server.level.ServerLevel level = scheduler.levelForVanillaTicks(this);
        if (level == null) return;

        try {
            REGIONIUM$ROUTING.set(true);
            if (scheduler.routeScheduledTick(level, tick)) {
                ci.cancel();
            }
        } finally {
            REGIONIUM$ROUTING.set(false);
        }
    }
}
