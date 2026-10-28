package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumRegion;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/** Uses only this region's players in PlayerSensor, matching Folia's local-player query. */
@Mixin(net.minecraft.world.entity.ai.sensing.PlayerSensor.class)
public abstract class PlayerSensorRegioniumLocalPlayersMixin {
    @WrapOperation(
        method = "doTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;players()Ljava/util/List;"
        )
    )
    private List<ServerPlayer> regionium$useLocalPlayers(
        ServerLevel level,
        Operation<List<ServerPlayer>> original
    ) {
        RegioniumRegion current = RegioniumContext.currentRegion();
        if (current != null && current.level() == level) {
            return current.worldData().players();
        }
        return original.call(level);
    }
}
