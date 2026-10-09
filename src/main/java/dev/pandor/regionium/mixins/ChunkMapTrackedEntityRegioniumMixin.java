package dev.pandor.regionium.mixins;

import dev.pandor.regionium.access.ChunkMapTrackedEntityRegioniumAccess;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class ChunkMapTrackedEntityRegioniumMixin implements ChunkMapTrackedEntityRegioniumAccess {
    @Shadow public abstract void updatePlayers(List<ServerPlayer> players);
    @Shadow private ServerEntity serverEntity;

    @Override
    public void regionium$sendChanges(List<ServerPlayer> players) {
        updatePlayers(players);
        serverEntity.sendChanges();
    }
}
