package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.pandor.regionium.Regionium;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.BooleanSupplier;

/**
 * Removes the vanilla global ServerLevel simulation tick.
 *
 * Folia has no second global world tick: region schedulers own the complete
 * world simulation. The global server thread only performs chunk lifecycle
 * and other genuinely global maintenance.
 */
@Mixin(net.minecraft.server.MinecraftServer.class)
public abstract class MinecraftServerLevelRegioniumMixin {
    @WrapOperation(
        method = "tickChildren",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tick(Ljava/util/function/BooleanSupplier;)V"
        )
    )
    private void regionium$prepareLevelTick(
        ServerLevel level,
        BooleanSupplier haveTime,
        Operation<Void> original
    ) {
        // Global world time is advanced once here, exactly like Folia's
        // global-region tick. The region-local ServerLevel.tickTime() only
        // advances its redstone clock.
        Regionium.scheduler().tickGlobalTime(level);

        // Folia's global region processes chunk lifecycle/ticket work even
        // when no simulation region is active yet. This is what allows
        // GameTest structures and newly requested chunks to become ticking
        // chunks before the owning region can execute them.
        level.getChunkSource().tick(haveTime, false);

        Regionium.scheduler().regionizer().markTopologyDirty(level);
        ((ServerLevelEntityManagerAccessorMixin) (Object) level)
            .regionium$getEntityManager().tick();
        Regionium.scheduler().prepareChunkExecution(level);
        // Intentionally do not call original.call().
        //
        // RegioniumRegion.tickRegion() invokes ServerLevel.tick() from the
        // owning region context. Calling it here as well would create a
        // second simulation pass and would reintroduce shared-state races.
    }
}
