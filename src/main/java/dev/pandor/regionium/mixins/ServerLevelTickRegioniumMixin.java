package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.Redirect;
import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.util.debug.LevelDebugSynchronizers;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BiConsumer;

@Mixin(ServerLevel.class)
public abstract class ServerLevelTickRegioniumMixin {

    /*
     * ServerLevel.tick() is intentionally allowed to execute on the global
     * server thread as well as region threads.
     *
     * Global execution owns world-wide maintenance (weather, border, raids,
     * entity-section persistence, debug/global bookkeeping). Region
     * execution owns simulation work. The wrappers below make the distinction
     * explicit so global maintenance runs exactly once rather than once per
     * region.
     */

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/border/WorldBorder;tick()V"
        )
    )
    private void regionium$globalWorldBorder(
        WorldBorder border,
        Operation<Void> original
    ) {
        if (!RegioniumContext.isRegionThread()) {
            original.call(border);
        }
    }

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;advanceWeatherCycle()V"
        )
    )
    private void regionium$globalWeather(
        ServerLevel self,
        Operation<Void> original
    ) {
        if (!RegioniumContext.isRegionThread()) {
            original.call(self);
        }
    }

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;wakeUpAllPlayers()V"
        )
    )
    private void regionium$globalWakePlayers(
        ServerLevel self,
        Operation<Void> original
    ) {
        if (!RegioniumContext.isRegionThread()) {
            original.call(self);
        }
    }

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;updateSkyBrightness()V"
        )
    )
    private void regionium$globalSkyBrightness(
        ServerLevel self,
        Operation<Void> original
    ) {
        if (!RegioniumContext.isRegionThread()) {
            original.call(self);
        }
    }

    @Inject(method = "tickTime", at = @At("HEAD"), cancellable = true)
    private void regionium$regionTickTime(CallbackInfo ci) {
        if (!RegioniumContext.isRegionThread()) {
            return;
        }

        ServerLevel level = (ServerLevel) (Object) this;
        var region = RegioniumContext.requireRegionThread();
        var data = Regionium.scheduler().worldData(level);
        data.setRedstoneTime(region, data.redstoneTime(region) + 1L);
        ci.cancel();
    }

    /**
     * LevelAccessor#createTick() uses getGameTime() when it creates scheduled
     * block/fluid ticks. On a region worker that must be the region's
     * redstone clock, not the shared ServerLevel clock.
     */
    public long getGameTime() {
        if (RegioniumContext.isRegionThread()) {
            var region = RegioniumContext.currentRegion();
            if (region != null) {
                ServerLevel level = (ServerLevel) (Object) this;
                return Regionium.scheduler().worldData(level).redstoneTime(region);
            }
        }

        return ((net.minecraft.world.level.storage.LevelData)
            ((ServerLevel) (Object) this).getLevelData()).getGameTime();
    }

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/raid/Raids;tick(Lnet/minecraft/server/level/ServerLevel;)V"
        )
    )
    private void regionium$globalRaids(
        Raids raids,
        ServerLevel self,
        Operation<Void> original
    ) {
        if (!RegioniumContext.isRegionThread()) {
            original.call(raids, self);
        }
    }

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/dimension/end/EnderDragonFight;tick()V"
        )
    )
    private void regionium$globalDragonFight(
        net.minecraft.world.level.dimension.end.EnderDragonFight fight,
        Operation<Void> original
    ) {
        if (!RegioniumContext.isRegionThread()) {
            original.call(fight);
        }
    }

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;tick()V"
        )
    )
    private void regionium$entityManagerGlobalMaintenance(
        net.minecraft.world.level.entity.PersistentEntitySectionManager<?> manager,
        Operation<Void> original
    ) {
        if (!RegioniumContext.isRegionThread()) {
            original.call(manager);
        }
    }

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/RandomState;garbageCollect()V"
        )
    )
    private void regionium$globalRandomStateGc(
        RandomState randomState,
        Operation<Void> original
    ) {
        if (!RegioniumContext.isRegionThread()) {
            original.call(randomState);
        }
    }

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/util/debug/LevelDebugSynchronizers;tick(Lnet/minecraft/util/debug/ServerDebugSubscribers;)V"
        )
    )
    private void regionium$globalDebugSynchronizers(
        LevelDebugSynchronizers synchronizers,
        net.minecraft.util.debug.ServerDebugSubscribers subscribers,
        Operation<Void> original
    ) {
        if (!RegioniumContext.isRegionThread()) {
            original.call(synchronizers, subscribers);
        }
    }

    /**
     * Folia architecture: ServerLevel exposes the region-owned LevelTicks
     * directly. Vanilla scheduling code therefore needs no special-case
     * routing: every scheduleTick() call made by a region task naturally lands
     * in that region's queue.
     */
    @Inject(method = "getBlockTicks", at = @At("HEAD"), cancellable = true)
    private void regionium$getRegionBlockTicks(CallbackInfoReturnable<LevelTicks<net.minecraft.world.level.block.Block>> cir) {
        if (!RegioniumContext.isRegionThread()) return;
        var region = RegioniumContext.currentRegion();
        var level = (ServerLevel) (Object) this;
        if (region != null) {
            cir.setReturnValue(Regionium.scheduler().worldData(level).blockTicks(region));
        }
    }

    @Inject(method = "getFluidTicks", at = @At("HEAD"), cancellable = true)
    private void regionium$getRegionFluidTicks(CallbackInfoReturnable<LevelTicks<net.minecraft.world.level.material.Fluid>> cir) {
        if (!RegioniumContext.isRegionThread()) return;
        var region = RegioniumContext.currentRegion();
        var level = (ServerLevel) (Object) this;
        if (region != null) {
            cir.setReturnValue(Regionium.scheduler().worldData(level).fluidTicks(region));
        }
    }

    /*
     * LevelTicks is shared world state. The region tick owns the callback,
     * but the global maintenance pass must never consume the same queue.
     */
    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/ticks/LevelTicks;tick(JILjava/util/function/BiConsumer;)V"
        )
    )
    private void regionium$regionLocalScheduledTicks(
        LevelTicks<?> ticks,
        long gameTime,
        int maxTicks,
        BiConsumer callback
    ) {
        ServerLevel level = (ServerLevel) (Object) this;

        if (!RegioniumContext.isRegionThread()) {
            return;
        }
        Regionium.scheduler().tickScheduledTicksRegionally(level, ticks, maxTicks, callback);
    }
}
