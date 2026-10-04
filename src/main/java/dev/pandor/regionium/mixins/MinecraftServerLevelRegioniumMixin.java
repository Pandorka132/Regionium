package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.pandor.regionium.Regionium;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.BooleanSupplier;

/** Maintains the per-level chunk ownership snapshot around the vanilla level tick. */
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
        Regionium.scheduler().prepareChunkExecution(level);
        // ServerLevel.tick() now releases its regional tick plans asynchronously
        // at its own TAIL. Do not refresh leases here after the tick: that would
        // inspect/mutate lease state while region workers are still executing.
        original.call(level, haveTime);
    }
}
