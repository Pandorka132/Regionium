package dev.pandor.regionium.mixins;

import com.mojang.datafixers.kinds.IdF;
import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.YieldJobSite;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.behavior.declarative.MemoryAccessor;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(YieldJobSite.class)
public abstract class YieldJobSiteRegioniumMixin {
    @Inject(method = "lambda$create$2", at = @At("HEAD"), cancellable = true)
    private static void regionium$skipForeignPotentialJob(
        BehaviorBuilder.Instance<?> instance,
        MemoryAccessor<IdF.Mu, GlobalPos> potentialJob,
        MemoryAccessor<?, ?> nearestEntities,
        MemoryAccessor<?, ?> walkTarget,
        MemoryAccessor<?, ?> lookTarget,
        float speedModifier,
        ServerLevel level,
        Villager body,
        long timestamp,
        CallbackInfoReturnable<Boolean> cir
    ) {
        var region = RegioniumContext.currentRegion();
        if (region == null) return;
        GlobalPos pos = instance.get(potentialJob);
        if (!pos.dimension().equals(level.dimension())
            || !Regionium.scheduler().isCurrentRegionFor(level, pos.pos())) {
            cir.setReturnValue(true);
        }
    }
}
