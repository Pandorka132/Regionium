package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Raider.class)
public abstract class RaiderRegioniumMixin {
    @Redirect(
        method = "aiStep",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getGameTime()J")
    )
    private long regionium$useRegionClockForJoinCheck(Level level) {
        var current = RegioniumContext.currentRegion();
        if (current != null && current.level() == level) {
            return current.worldData().redstoneTime();
        }
        return level.getGameTime();
    }
}
