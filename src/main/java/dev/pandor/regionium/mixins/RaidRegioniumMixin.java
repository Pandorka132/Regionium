package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumRegion;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.advancements.triggers.PlayerTrigger;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.List;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Raid.class)
public abstract class RaidRegioniumMixin {
    @WrapOperation(
        method = "tick(Lnet/minecraft/server/level/ServerLevel;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;addEffect(Lnet/minecraft/world/effect/MobEffectInstance;)Z"
        )
    )
    private boolean regionium$deferHeroReward(
        LivingEntity hero,
        MobEffectInstance effect,
        Operation<Boolean> original
    ) {
        // Raid.tick finds heroes through the global entity map, which is an
        // intentional shared-map exception. Apply the whole reward in the
        // hero's next owner-region task, after rechecking current entity state.
        ServerLevel sourceLevel = hero.level() instanceof ServerLevel level ? level : null;
        if (sourceLevel != null) {
            regionium$queueHeroReward(hero, sourceLevel, effect);
        }
        return false;
    }

@org.spongepowered.asm.mixin.Unique
    private void regionium$queueHeroReward(LivingEntity hero, ServerLevel sourceLevel, MobEffectInstance effect) {
        if (hero.level() != sourceLevel) {
            return;
        }

        Regionium.scheduler().executeWhenOwned(sourceLevel, hero.blockPosition(), () -> {
            if (hero.level() != sourceLevel) {
                return;
            }

            RegioniumRegion currentOwner = RegioniumContext.currentRegion();
            if (currentOwner == null || Regionium.scheduler().ownerOf(hero) != currentOwner) {
                // Requeue through the owned-task path so migration and a
                // rejected owner submission cannot drop the reward. Region
                // execute() only enqueues this retry; it does not run inline.
                regionium$queueHeroReward(hero, sourceLevel, effect);
                return;
            }

            if (hero.isRemoved() || !hero.isAlive() || hero.isSpectator()) {
                return;
            }

            ServerPlayer playerHero = hero instanceof ServerPlayer player ? player : null;
            if (playerHero != null && (playerHero.isRemoved() || playerHero.level() != sourceLevel
                || !playerHero.isAlive() || playerHero.isSpectator())) {
                return;
            }

            hero.addEffect(effect);
            if (playerHero != null) {
                playerHero.awardStat(net.minecraft.stats.Stats.RAID_WIN);
                net.minecraft.advancements.triggers.CriteriaTriggers.RAID_WIN.trigger(playerHero);
            }
        });
    }

    @Redirect(
        method = "tick(Lnet/minecraft/server/level/ServerLevel;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;awardStat(Lnet/minecraft/resources/Identifier;)V"
        )
    )
    private void regionium$suppressImmediateRaidWinStat(ServerPlayer player, Identifier stat) {
        // The RAID_WIN award is included in regionium$deferHeroReward.
    }

    @Redirect(
        method = "tick(Lnet/minecraft/server/level/ServerLevel;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/advancements/triggers/PlayerTrigger;trigger(Lnet/minecraft/server/level/ServerPlayer;)V"
        )
    )
    private void regionium$suppressImmediateRaidWinCriterion(PlayerTrigger trigger, ServerPlayer player) {
        // The RAID_WIN trigger is included in regionium$deferHeroReward.
    }

    @Redirect(method = "playSound(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;players()Ljava/util/List;"))
    private List<ServerPlayer> regionium$useLocalPlayers(ServerLevel level) {
        RegioniumRegion current = RegioniumContext.currentRegion();
        return current != null && current.level() == level ? current.worldData().players() : level.players();
    }

    @Inject(method = "lambda$validPlayer$0", at = @At("HEAD"), cancellable = true)
    private void regionium$requirePlayerOwner(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
        RegioniumRegion current = RegioniumContext.currentRegion();
        ServerLevel level = player.level();
        if (current != null && (current.level() != level
            || Regionium.scheduler().regionizer().owner(level, player.chunkPosition().x(), player.chunkPosition().z()) != current)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(
        method = "addWaveMob(Lnet/minecraft/server/level/ServerLevel;ILnet/minecraft/world/entity/raid/Raider;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void regionium$requireRaidOwner(
        ServerLevel level,
        int wave,
        Raider raider,
        CallbackInfoReturnable<Boolean> cir
    ) {
        RegioniumRegion current = RegioniumContext.currentRegion();
        if (current == null) {
            return;
        }

        BlockPos center = ((Raid) (Object) this).getCenter();
        if (center == null || current.level() != level || !Regionium.scheduler().regionizer().ownsChunkSquare(
            current, level, center.getX() >> 4, center.getZ() >> 4, 8
        )) {
            cir.setReturnValue(false);
        }
    }
}
