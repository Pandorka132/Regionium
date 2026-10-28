package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumTickOffsetAccess;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mirrors Folia's owner checks before exposing kill-credit entities. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityRegioniumKillCreditMixin implements RegioniumTickOffsetAccess {
    @Override
    public void regionium$updateTicks(long fromTickOffset, long fromRedstoneTimeOffset) {
        if (this.lastDamageStamp != Long.MIN_VALUE) {
            this.lastDamageStamp += fromRedstoneTimeOffset;
        }
    }

    @Shadow
    protected EntityReference<Player> lastHurtByPlayer;

    @Shadow
    private EntityReference<LivingEntity> lastHurtByMob;

    @Shadow private long lastDamageStamp;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void regionium$initializeDamageClock(CallbackInfo ci) {
        this.lastDamageStamp = Long.MIN_VALUE;
    }

    @Redirect(method = "hurtServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getGameTime()J"))
    private long regionium$damageClock(Level level) {
        LivingEntity self = (LivingEntity) (Object) this;
        return RegioniumContext.redstoneTime(level, self.blockPosition());
    }

    @Redirect(method = "getLastDamageSource(I)Lnet/minecraft/world/damagesource/DamageSource;", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getGameTime()J"))
    private long regionium$lastDamageClock(Level level) {
        LivingEntity self = (LivingEntity) (Object) this;
        return RegioniumContext.redstoneTime(level, self.blockPosition());
    }

    @Redirect(method = "handleDamageEvent", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getGameTime()J"))
    private long regionium$damageEventClock(Level level) {
        LivingEntity self = (LivingEntity) (Object) this;
        return RegioniumContext.redstoneTime(level, self.blockPosition());
    }

    @Inject(method = "getKillCredit", at = @At("HEAD"), cancellable = true)
    private void regionium$onlyReturnOwnedKillCredit(CallbackInfoReturnable<LivingEntity> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (lastHurtByPlayer != null) {
            Player player = lastHurtByPlayer.getEntity(self.level(), Player.class);
            if (RegioniumContext.isCurrentThreadFor(player)) {
                cir.setReturnValue(player);
                return;
            }
        }

        if (lastHurtByMob != null) {
            LivingEntity mob = lastHurtByMob.getEntity(self.level(), LivingEntity.class);
            if (RegioniumContext.isCurrentThreadFor(mob)) {
                cir.setReturnValue(mob);
                return;
            }
        }

        cir.setReturnValue(null);
    }
}
