package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.pandor.regionium.Regionium;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class ServerLevelTickRegioniumMixin {
    @WrapOperation(method = "tickChildren", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;tick(Ljava/util/function/BooleanSupplier;)V"))
    private void regionium$dispatchLevelTick(ServerLevel level, BooleanSupplier haveTime, Operation<Void> original) {
        Regionium.scheduler().executeEntityAndWait((MinecraftServer) (Object) this, level, () -> original.call(level, haveTime));
    }
}
