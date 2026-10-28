package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumWorldData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Region workers must not query vanilla's globally mutable EntitySectionStorage.
 * Folia uses a region-owned entity lookup; Regionium retains the global vanilla
 * entity map for global discovery, but performs ticking-region queries against
 * the owning RegionizedWorldData entity set.
 */
@Mixin(Level.class)
public abstract class LevelRegioniumEntityQueriesMixin {
    @Inject(
        method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;Ljava/util/List;I)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private <T extends Entity> void regionium$localTypedEntityQuery(
        EntityTypeTest<Entity, T> type,
        AABB bounds,
        Predicate<? super T> selector,
        List<? super T> output,
        int maxResults,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci
    ) {
        if (!((Object) this instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        RegioniumWorldData data = RegioniumContext.currentWorldData();
        if (data == null || data.level() != level) {
            return;
        }

        for (Entity entity : data.entitySnapshot()) {
            if (entity.isRemoved() || !bounds.intersects(entity.getBoundingBox())) {
                continue;
            }
            T typedEntity = type.tryCast(entity);
            if (typedEntity != null && selector.test(typedEntity)) {
                output.add(typedEntity);
                if (output.size() >= maxResults) {
                    ci.cancel();
                    return;
                }
            }

            if (entity instanceof EnderDragon dragon) {
                for (EnderDragonPart part : dragon.getSubEntities()) {
                    T typedPart = type.tryCast(part);
                    if (typedPart != null && selector.test(typedPart)) {
                        output.add(typedPart);
                        if (output.size() >= maxResults) {
                            ci.cancel();
                            return;
                        }
                    }
                }
            }
        }
        ci.cancel();
    }

    @Inject(
        method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void regionium$localEntityQuery(
        Entity except,
        AABB bounds,
        Predicate<? super Entity> selector,
        CallbackInfoReturnable<List<Entity>> cir
    ) {
        if (!((Object) this instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        RegioniumWorldData data = RegioniumContext.currentWorldData();
        if (data == null || data.level() != level) {
            return;
        }

        List<Entity> result = new ArrayList<>();
        for (Entity entity : data.entitySnapshot()) {
            if (entity != except && !entity.isRemoved()
                && bounds.intersects(entity.getBoundingBox()) && selector.test(entity)) {
                result.add(entity);
            }
        }
        for (EnderDragonPart part : level.dragonParts()) {
            if (part != except && part.parentMob != except
                && selector.test(part) && bounds.intersects(part.getBoundingBox())) {
                result.add(part);
            }
        }
        cir.setReturnValue(result);
    }
}
