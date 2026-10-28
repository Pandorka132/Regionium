package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

@Mixin(HappyGhast.class)
public abstract class HappyGhastRegioniumLocalPlayersMixin {
    @Redirect(method = "scanPlayerAboveGhast", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;players()Ljava/util/List;"))
    private List<? extends Player> regionium$useLocalPlayers(Level level) {
        RegioniumWorldData data = RegioniumContext.currentWorldData();
        if (level instanceof ServerLevel serverLevel && data != null && data.level() == serverLevel) {
            return data.players();
        }
        return level.players();
    }
}
