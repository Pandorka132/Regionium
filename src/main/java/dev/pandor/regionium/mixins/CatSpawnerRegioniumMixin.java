package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.CatSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

@Mixin(CatSpawner.class)
public abstract class CatSpawnerRegioniumMixin {
    @Shadow private int nextTick;

    @Redirect(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/npc/CatSpawner;nextTick:I", opcode = org.objectweb.asm.Opcodes.GETFIELD))
    private int regionium$readLocalCountdown(CatSpawner self) {
        var region = RegioniumContext.currentRegion();
        return region == null ? this.nextTick : region.worldData().catSpawnerNextTick;
    }

    @Redirect(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/npc/CatSpawner;nextTick:I", opcode = org.objectweb.asm.Opcodes.PUTFIELD))
    private void regionium$writeLocalCountdown(CatSpawner self, int value) {
        var region = RegioniumContext.currentRegion();
        if (region == null) this.nextTick = value;
        else region.worldData().catSpawnerNextTick = value;
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getRandomPlayer()Lnet/minecraft/server/level/ServerPlayer;"))
    private ServerPlayer regionium$chooseLocalPlayer(ServerLevel level) {
        var region = RegioniumContext.currentRegion();
        if (region == null || region.level() != level) return level.getRandomPlayer();
        List<ServerPlayer> players = region.worldData().players();
        return players.isEmpty() ? null : players.get(level.getRandom().nextInt(players.size()));
    }
}
