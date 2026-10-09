package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerListRegioniumLifecycleMixin {
    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void regionium$registerPlayer(
        net.minecraft.network.Connection connection,
        ServerPlayer player,
        net.minecraft.server.network.CommonListenerCookie cookie,
        CallbackInfo ci
    ) {
        // PlayerList is the actual gameplay-session lifecycle boundary. The
        // ServerLevel.addFreshEntity hook can run before ChunkMap/region
        // ownership exists, so a player must be registered again once the
        // connection has entered the PLAY state.
        Regionium.scheduler().trackEntity(player);
    }
}
