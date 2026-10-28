package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerRegioniumMixin {
    @Redirect(method = "createState", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getAllEntities()Ljava/lang/Iterable;"))
    private static Iterable<Entity> regionium$useRegionEntities(ServerLevel level) {
        var region = RegioniumContext.currentRegion();
        if (region == null || region.level() != level) return level.getAllEntities();
        return region.worldData().entities();
    }
}
