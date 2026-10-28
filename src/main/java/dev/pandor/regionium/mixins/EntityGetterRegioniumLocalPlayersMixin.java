package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumWorldData;
import net.minecraft.server.level.ServerEntityGetter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

@Mixin(EntityGetter.class)
interface EntityGetterRegioniumLocalPlayersMixin {
    @Redirect(method = "getNearestPlayer*", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/EntityGetter;players()Ljava/util/List;"))
    private List<? extends Player> regionium$nearestPlayerLocalView(EntityGetter getter) {
        return regionium$players(getter);
    }

    @Redirect(method = "hasNearbyAlivePlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/EntityGetter;players()Ljava/util/List;"))
    private List<? extends Player> regionium$nearbyAlivePlayerLocalView(EntityGetter getter) {
        return regionium$players(getter);
    }

    @Redirect(method = "getPlayerByUUID", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/EntityGetter;players()Ljava/util/List;"))
    private List<? extends Player> regionium$playerByUuidLocalView(EntityGetter getter) {
        return regionium$players(getter);
    }

    @Unique
    private static List<? extends Player> regionium$players(EntityGetter getter) {
        RegioniumWorldData data = RegioniumContext.currentWorldData();
        if (data != null && getter instanceof ServerLevel level && data.level() == level) {
            return data.players();
        }
        return getter.players();
    }
}

@Mixin(ServerEntityGetter.class)
interface ServerEntityGetterRegioniumLocalPlayersMixin {
    @Redirect(method = "getNearestPlayer*", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerEntityGetter;players()Ljava/util/List;"))
    private List<? extends Player> regionium$nearestPlayerLocalView(ServerEntityGetter getter) {
        return regionium$players(getter);
    }

    @Redirect(method = "getNearbyPlayers", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerEntityGetter;players()Ljava/util/List;"))
    private List<? extends Player> regionium$nearbyPlayersLocalView(ServerEntityGetter getter) {
        return regionium$players(getter);
    }

    @Unique
    private static List<? extends Player> regionium$players(ServerEntityGetter getter) {
        RegioniumWorldData data = RegioniumContext.currentWorldData();
        if (data != null && getter.getLevel() == data.level()) {
            return data.players();
        }
        return getter.players();
    }
}
