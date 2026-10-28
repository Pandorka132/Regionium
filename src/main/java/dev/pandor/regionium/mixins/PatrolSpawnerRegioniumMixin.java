package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

@Mixin(PatrolSpawner.class)
public abstract class PatrolSpawnerRegioniumMixin {
    @Shadow private int nextTick;

    @Redirect(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/PatrolSpawner;nextTick:I", opcode = org.objectweb.asm.Opcodes.GETFIELD))
    private int regionium$readLocalCountdown(PatrolSpawner self) {
        var region = RegioniumContext.currentRegion();
        return region == null ? this.nextTick : region.worldData().patrolSpawnerNextTick;
    }

    @Redirect(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/PatrolSpawner;nextTick:I", opcode = org.objectweb.asm.Opcodes.PUTFIELD))
    private void regionium$writeLocalCountdown(PatrolSpawner self, int value) {
        var region = RegioniumContext.currentRegion();
        if (region == null) this.nextTick = value;
        else region.worldData().patrolSpawnerNextTick = value;
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;players()Ljava/util/List;"))
    private List<ServerPlayer> regionium$useLocalPlayers(ServerLevel level) {
        var region = RegioniumContext.currentRegion();
        return region != null && region.level() == level ? region.worldData().players() : level.players();
    }
}
