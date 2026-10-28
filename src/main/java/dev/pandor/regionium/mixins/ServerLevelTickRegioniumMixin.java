package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.Redirect;
import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.server.players.SleepStatus;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.core.BlockPos;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;

@Mixin(ServerLevel.class)
public abstract class ServerLevelTickRegioniumMixin {
@org.spongepowered.asm.mixin.Unique
    private static final ThreadLocal<Boolean> REGIONIUM$DIRECT_TICKS = ThreadLocal.withInitial(() -> false);

    /*
     * ServerLevel.tick() is intentionally allowed to execute on the global
     * server thread as well as region threads.
     *
     * Global execution owns world-wide maintenance (weather, border, raid
     * bookkeeping, entity-section persistence, and cache maintenance).
     * Region execution owns simulation and debug synchronization work. The wrappers make the distinction
     * explicit so global maintenance runs exactly once rather than once per
     * region.
     */

    @WrapOperation(
        method = "tick(Ljava/util/function/BooleanSupplier;)V",
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
        method = "tick(Ljava/util/function/BooleanSupplier;)V",
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
        method = "tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/players/SleepStatus;areEnoughSleeping(I)Z",
            ordinal = 0
        )
    )
    private boolean regionium$skipRegionSleepHandling(
        SleepStatus sleepStatus,
        int sleepPercentage,
        Operation<Boolean> original
    ) {
        return RegioniumContext.isRegionThread()
            ? false
            : original.call(sleepStatus, sleepPercentage);
    }

    @WrapOperation(
        method = "tick(Ljava/util/function/BooleanSupplier;)V",
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

    @WrapOperation(
        method = "tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/attribute/EnvironmentAttributeSystem;invalidateTickCache()V"
        )
    )
    private void regionium$globalEnvironmentAttributeCache(
        EnvironmentAttributeSystem attributes,
        Operation<Void> original
    ) {
        if (!RegioniumContext.isRegionThread()) {
            original.call(attributes);
        }
    }

    @Inject(method = "tickTime", at = @At("HEAD"), cancellable = true)
    private void regionium$regionTickTime(CallbackInfo ci) {
        if (!RegioniumContext.isRegionThread()) {
            return;
        }

        // The global server pass advances LevelData.gameTime once. A region
        // advances its own redstone clock independently, matching Folia's
        // RegionizedWorldData.getRedstoneGameTime().
        ServerLevel level = (ServerLevel) (Object) this;
        if (((ServerLevelRegioniumAccessorMixin) (Object) level).regionium$isTickTime()) {
            RegioniumContext.requireRegionThread().worldData().advanceRedstoneTime();
        }
        ci.cancel();
    }

    @WrapOperation(
        method = "tick(Ljava/util/function/BooleanSupplier;)V",
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
        // Raids.tick dispatches global counter maintenance or region-owned
        // raid simulation according to the current execution context.
        original.call(raids, self);
    }

    @WrapOperation(
        method = "tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/dimension/end/EnderDragonFight;tick()V"
        )
    )
    private void regionium$globalDragonFight(
        EnderDragonFight fight,
        Operation<Void> original
    ) {
        if (RegioniumContext.isRegionThread()) {
            ServerLevel level = (ServerLevel) (Object) this;
            BlockPos origin = ((EnderDragonFightRegioniumAccessorMixin) (Object) fight)
                .regionium$getOrigin();
            var owner = Regionium.scheduler().regionizer().owner(
                level,
                origin.getX() >> 4,
                origin.getZ() >> 4
            );
            if (owner == RegioniumContext.currentRegion()) {
                original.call(fight);
            }
        }
    }

    @WrapOperation(
        method = "tick(Ljava/util/function/BooleanSupplier;)V",
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
        method = "tick(Ljava/util/function/BooleanSupplier;)V",
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

    /**
     * Folia architecture: ServerLevel exposes the region-owned LevelTicks
     * directly. Vanilla scheduling code therefore needs no special-case
     * routing: every scheduleTick() call made by a region task naturally lands
     * in that region's queue.
     */
    @Inject(method = "getBlockTicks()Lnet/minecraft/world/ticks/LevelTicks;", at = @At("HEAD"), cancellable = true)
    private void regionium$getRegionBlockTicks(CallbackInfoReturnable<LevelTicks<net.minecraft.world.level.block.Block>> cir) {
        if (!RegioniumContext.isRegionThread()) return;
        var region = RegioniumContext.currentRegion();
        var level = (ServerLevel) (Object) this;
        if (region != null) {
            cir.setReturnValue(Regionium.scheduler().worldData(level).blockTicks(region));
        }
    }

    @Inject(method = "getFluidTicks()Lnet/minecraft/world/ticks/LevelTicks;", at = @At("HEAD"), cancellable = true)
    private void regionium$getRegionFluidTicks(CallbackInfoReturnable<LevelTicks<net.minecraft.world.level.material.Fluid>> cir) {
        if (!RegioniumContext.isRegionThread()) return;
        var region = RegioniumContext.currentRegion();
        var level = (ServerLevel) (Object) this;
        if (region != null) {
            cir.setReturnValue(Regionium.scheduler().worldData(level).fluidTicks(region));
        }
    }

    @Redirect(
        method = "tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/ticks/LevelTicks;tick(JILjava/util/function/BiConsumer;)V",
            ordinal = 0
        )
    )
    private void regionium$blockTicks(
        LevelTicks<?> ticks,
        long gameTime,
        int maxTicks,
        BiConsumer callback
    ) {
        if (!RegioniumContext.isRegionThread() || REGIONIUM$DIRECT_TICKS.get()) {
            ticks.tick(gameTime, maxTicks, callback);
            return;
        }
        ServerLevel level = (ServerLevel) (Object) this;
        try {
            REGIONIUM$DIRECT_TICKS.set(true);
            Regionium.scheduler().tickScheduledTicksRegionally(level, ticks, maxTicks, callback);
        } finally {
            REGIONIUM$DIRECT_TICKS.set(false);
        }
    }

    @Redirect(
        method = "tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/ticks/LevelTicks;tick(JILjava/util/function/BiConsumer;)V",
            ordinal = 1
        )
    )
    private void regionium$fluidTicks(
        LevelTicks<?> ticks,
        long gameTime,
        int maxTicks,
        BiConsumer callback
    ) {
        if (!RegioniumContext.isRegionThread() || REGIONIUM$DIRECT_TICKS.get()) {
            ticks.tick(gameTime, maxTicks, callback);
            return;
        }
        ServerLevel level = (ServerLevel) (Object) this;
        try {
            REGIONIUM$DIRECT_TICKS.set(true);
            Regionium.scheduler().tickScheduledTicksRegionally(level, ticks, maxTicks, callback);
        } finally {
            REGIONIUM$DIRECT_TICKS.set(false);
        }
    }

}
