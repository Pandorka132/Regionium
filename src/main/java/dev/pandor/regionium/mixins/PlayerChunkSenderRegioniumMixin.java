package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumChunkDebug;
import dev.pandor.regionium.core.RegioniumChunkSendBatch;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumRegion;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps chunk packets and batch completion ordered while chunk data is produced
 * on chunk-owner regions. Optional diagnostics expose queue progress and batch
 * acknowledgements when investigating client chunk-loading stalls.
 */
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderRegioniumMixin {
    @Shadow @Final private LongSet pendingChunks;
    @Shadow private int unacknowledgedBatches;
    @Shadow private float batchQuota;

    @WrapMethod(method = "sendNextChunks")
    private void regionium$serializeChunkBatch(
        ServerPlayer player,
        Operation<Void> original
    ) {
        // ChunkBatchStart/ChunkBatchFinished have no batch identifier. Reserve
        // the connection's ordered packet stream atomically before vanilla can
        // emit the start packet, so another region cannot start a batch in the
        // gap between an active-batch check and the first chunk packet.
        RegioniumChunkSendBatch.Batch batch = RegioniumChunkSendBatch.begin();
        if (player.connection != null && !batch.reserve(player.connection)) {
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log(
                    "chunk-sender-deferred-active-batch",
                    RegioniumChunkDebug.playerState(player)
                );
            }
            RegioniumChunkSendBatch.end();
            return;
        }

        try {
            original.call(player);
        } catch (RuntimeException | Error error) {
            if (player.connection != null) {
                batch.failIfStarted(player.connection, error);
            } else {
                batch.releaseIfNoBatchStarted();
            }
            throw error;
        } finally {
            try {
                batch.onSendNextChunksReturned();
                batch.releaseIfNoBatchStarted();
            } finally {
                RegioniumChunkSendBatch.end();
            }
        }
    }

    @Inject(method = "sendChunk", at = @At("HEAD"))
    private static void regionium$logChunkPacketCreation(
        ServerGamePacketListenerImpl listener,
        ServerLevel level,
        LevelChunk chunk,
        CallbackInfo ci
    ) {
        if (RegioniumChunkDebug.enabled() && listener != null && listener.player != null) {
            RegioniumChunkDebug.log(
                "chunk-packet-create",
                RegioniumChunkDebug.playerState(listener.player),
                "chunk=" + chunk.getPos(),
                "chunkLevel=" + level.dimension().identifier()
            );
        }
    }

    @Inject(method = "onChunkBatchReceivedByClient", at = @At("HEAD"))
    private void regionium$logChunkBatchAck(float desiredChunksPerTick, CallbackInfo ci) {
        if (RegioniumChunkDebug.enabled()) {
            RegioniumChunkDebug.log(
                "chunk-batch-ack",
                "thread=" + Thread.currentThread().getName(),
                "unacknowledgedBatchesBefore=" + this.unacknowledgedBatches,
                "desiredChunksPerTick=" + desiredChunksPerTick
            );
        }
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

        if (batch == null) {
            original.call(connection, level, chunk);
            return;
        }

        if (owner != null && !owner.isRetired()
            && owner == RegioniumContext.currentRegion()) {
            original.call(connection, level, chunk);
            return;
        }

        if (RegioniumChunkDebug.enabled()) {
            RegioniumRegion current = RegioniumContext.currentRegion();
            RegioniumChunkDebug.log(
                "chunk-send-owner-dispatch",
                RegioniumChunkDebug.playerState(connection.player),
                "chunk=" + chunk.getPos(),
                "chunkOwner=" + (owner == null ? "none" : owner.id()),
                "currentRegion=" + (current == null ? "none" : current.id())
            );
        }

        if (!batch.registerAsync(connection)) {
            return;
        }
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

        if (owner == null || owner.isRetired()) {
            // A topology handoff is not permission to serialize chunk data on
            // the caller's thread. Keep the operation pending until the chunk
            // has a published, live owner.
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log(
                    "chunk-send-owner-wait",
                    RegioniumChunkDebug.playerState(connection.player),
                    "chunk=" + chunk.getPos(),
                    "owner=" + (owner == null ? "none" : owner.id()),
                    "expectedOwner=" + (expectedOwner == null ? "none" : expectedOwner.id())
                );
            }
            BlockPos retryPosition = new BlockPos(
                chunk.getPos().getMinBlockX(),
                level.getMinY(),
                chunk.getPos().getMinBlockZ()
            );
            Regionium.scheduler().executeWhenOwned(
                level,
                retryPosition,
                () -> regionium$dispatchChunkSend(
                    expectedOwner, connection, level, chunk, original, batch
                )
            );
            return;
        }

        if (owner == RegioniumContext.currentRegion()) {
            regionium$runChunkSend(connection, level, chunk, original, batch);
            return;
        }

        // A direct owner.execute() task can be accepted and then lost when
        // that region is retired by a split/merge. Chunk packet work must follow
        // the chunk's spatial owner through the regionizer's handoff queue.
        BlockPos retryPosition = new BlockPos(
            chunk.getPos().getMinBlockX(),
            level.getMinY(),
            chunk.getPos().getMinBlockZ()
        );
        Regionium.scheduler().executeWhenOwned(level, retryPosition, () -> {
            RegioniumRegion actualOwner = Regionium.scheduler().regionizer()
                .owner(level, chunk.getPos().pack());
            if (RegioniumChunkDebug.enabled()) {
                RegioniumRegion current = RegioniumContext.currentRegion();
                RegioniumChunkDebug.log(
                    "chunk-send-owner-run",
                    RegioniumChunkDebug.playerState(connection.player),
                    "chunk=" + chunk.getPos(),
                    "chunkOwner=" + (actualOwner == null ? "none" : actualOwner.id()),
                    "currentRegion=" + (current == null ? "none" : current.id())
                );
            }
            if (actualOwner != RegioniumContext.currentRegion()) {
                regionium$dispatchChunkSend(
                    actualOwner, connection, level, chunk, original, batch
                );
                return;
            }
            regionium$runChunkSend(connection, level, chunk, original, batch);
        });
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
