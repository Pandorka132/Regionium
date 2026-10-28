package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(DamageSource.class)
public abstract class DamageSourceRegioniumMixin {
    @Redirect(method = "getLocalizedDeathMessage", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getMainHandItem()Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack regionium$avoidForeignHeldItem(LivingEntity entity) {
        return RegioniumContext.isCurrentThreadFor(entity) ? entity.getMainHandItem() : ItemStack.EMPTY;
    }
}
