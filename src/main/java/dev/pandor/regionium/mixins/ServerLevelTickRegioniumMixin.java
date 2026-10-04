package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
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

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tickTime()V"
        )
    )
    private void regionium$globalizeTime(
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

    /*
     * LevelTicks is shared world state. The region tick owns the callback,
     * but the global maintenance pass must never consume the same queue.
     */
    @WrapOperation(
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
        BiConsumer callback,
        Operation<Void> original
    ) {
        ServerLevel level = (ServerLevel) (Object) this;
        Regionium.scheduler().flushScheduledTickWrites(ticks);

        if (!RegioniumContext.isRegionThread()) {
            // Do not consume the shared queue from the global maintenance
            // pass. Every callback is dispatched by a region clock.
            return;
        }

        Regionium.scheduler().tickScheduledTicksRegionally(
            level,
            ticks,
            gameTime,
            maxTicks,
            callback
        );
    }
}
