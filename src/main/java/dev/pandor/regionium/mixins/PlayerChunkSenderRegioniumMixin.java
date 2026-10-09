package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Folia-style chunk-send ownership.
 *
 * Vanilla's PlayerChunkSender is driven from the global server thread, but
 * constructing ClientboundLevelChunkWithLightPacket reads LevelChunk and its
 * PalettedContainers. Regionium mutates those same objects on region threads.
 *
 * Folia keeps chunk access on the correct region/chunk execution context
 * instead of letting the global thread directly serialize live region state.
 * We mirror that rule: the global thread chooses what to send, while the
 * owning region constructs the packet and performs the send.
 */
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderRegioniumMixin {
    @Inject(
        method = "sendChunk(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void regionium$sendOnOwningRegion(
        ServerGamePacketListenerImpl connection,
        ServerLevel level,
        LevelChunk chunk,
        CallbackInfo ci
    ) {
        if (dev.pandor.regionium.core.RegioniumContext.isRegionThread()) {
            return;
        }

        var owner = Regionium.scheduler().regionizer()
            .owner(level, chunk.getPos().pack());

        if (owner == null) {
            return;
        }

        ci.cancel();

        owner.execute(() -> {
            if (chunk.isEmpty()) {
                return;
            }

            connection.send(new ClientboundLevelChunkWithLightPacket(
                chunk,
                level.getLightEngine(),
                null,
                null
            ));

            if (net.minecraft.SharedConstants.DEBUG_VERBOSE_SERVER_EVENTS) {
                Regionium.LOGGER.trace("SEN {}", chunk.getPos());
            }

            level.debugSynchronizers().startTrackingChunk(
                connection.player,
                chunk.getPos()
            );
        });
    }
}
