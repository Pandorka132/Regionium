package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumChunkSendBatch;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumRegion;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderRegioniumMixin {
    @Inject(method = "sendNextChunks", at = @At("HEAD"))
    private void regionium$beginChunkBatch(ServerPlayer player, CallbackInfo ci) {
        RegioniumChunkSendBatch.begin();
    }

    @Inject(method = "sendNextChunks", at = @At("RETURN"))
    private void regionium$endChunkBatch(ServerPlayer player, CallbackInfo ci) {
        RegioniumChunkSendBatch.end();
    }

    @WrapOperation(
        method = "sendNextChunks",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/PlayerChunkSender;sendChunk(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;)V"
        )
    )
    private void regionium$sendChunkOnOwner(
        ServerGamePacketListenerImpl connection,
        ServerLevel level,
        LevelChunk chunk,
        Operation<Void> original
    ) {
        RegioniumChunkSendBatch.Batch batch = RegioniumChunkSendBatch.current();
        RegioniumRegion owner = Regionium.scheduler().regionizer()
            .owner(level, chunk.getPos().pack());

        if (batch == null || owner == null || owner.isRetired()
            || owner == RegioniumContext.currentRegion()) {
            original.call(connection, level, chunk);
            return;
        }

        batch.registerAsync(connection);
        regionium$dispatchChunkSend(owner, connection, level, chunk, original, batch);
    }

    @WrapOperation(
        method = "sendNextChunks",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V",
            ordinal = 1
        )
    )
    private void regionium$finishBatchAfterOwnedChunks(
        ServerGamePacketListenerImpl connection,
        Packet<?> packet,
        Operation<Void> original
    ) {
        if (packet instanceof ClientboundChunkBatchFinishedPacket finished) {
            RegioniumChunkSendBatch.Batch batch = RegioniumChunkSendBatch.current();
            if (batch != null && batch.deferFinish(connection, finished)) {
                return;
            }
        }
        original.call(connection, packet);
    }

    @Unique
    private static void regionium$dispatchChunkSend(
        RegioniumRegion expectedOwner,
        ServerGamePacketListenerImpl connection,
        ServerLevel level,
        LevelChunk chunk,
        Operation<Void> original,
        RegioniumChunkSendBatch.Batch batch
    ) {
        RegioniumRegion owner = Regionium.scheduler().regionizer()
            .owner(level, chunk.getPos().pack());

        if (owner == null || owner.isRetired() || owner == RegioniumContext.currentRegion()) {
            regionium$runChunkSend(connection, level, chunk, original, batch);
            return;
        }

        boolean accepted = owner.execute(() -> {
            RegioniumRegion actualOwner = Regionium.scheduler().regionizer()
                .owner(level, chunk.getPos().pack());
            if (actualOwner != owner) {
                regionium$dispatchChunkSend(
                    owner, connection, level, chunk, original, batch
                );
                return;
            }
            regionium$runChunkSend(connection, level, chunk, original, batch);
        });

        if (!accepted) {
            regionium$dispatchChunkSend(
                expectedOwner, connection, level, chunk, original, batch
            );
        }
    }

    @Unique
    private static void regionium$runChunkSend(
        ServerGamePacketListenerImpl connection,
        ServerLevel level,
        LevelChunk chunk,
        Operation<Void> original,
        RegioniumChunkSendBatch.Batch batch
    ) {
        try {
            original.call(connection, level, chunk);
        } catch (Throwable error) {
            batch.fail(connection, error);
        } finally {
            batch.completeChunk();
        }
    }
}
