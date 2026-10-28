package dev.pandor.regionium.core;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.access.ServerCommonPacketListenerRegioniumAccess;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Keeps vanilla's chunk packet construction on the region that owns the chunk.
 * Other packets for that player are held until the chunk batch is complete so
 * the client still receives chunk data before later block/entity updates.
 */
public final class RegioniumChunkSendBatch {
    private static final ThreadLocal<Batch> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> FAILURE_DISCONNECT =
        ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> FINISH_PACKET_BYPASS =
        ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Packet<?>> FLUSHING_PACKET = new ThreadLocal<>();

    private RegioniumChunkSendBatch() {}

    public static Batch begin() {
        Batch batch = new Batch(CURRENT.get());
        if (Boolean.TRUE.equals(FAILURE_DISCONNECT.get())) {
            batch.failed = true;
        }
        CURRENT.set(batch);
        return batch;
    }

    public static Batch current() {
        return CURRENT.get();
    }

    public static void end() {
        Batch batch = CURRENT.get();
        if (batch == null || batch.previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(batch.previous);
        }
    }

    public static boolean shouldBuffer(
        ServerCommonPacketListenerImpl listener,
        Packet<?> packet
    ) {
        if (packet instanceof ClientboundChunkBatchStartPacket) {
            Batch batch = CURRENT.get();
            if (batch != null && listener instanceof ServerGamePacketListenerImpl connection) {
                batch.onBatchStart(connection);
            }
            return false;
        }
        if (Boolean.TRUE.equals(FINISH_PACKET_BYPASS.get())
            || packet instanceof ClientboundLevelChunkWithLightPacket) {
            return false;
        }

        return OutboundGate.bufferIfActive(listener, packet, null);
    }

    public static boolean shouldBuffer(
        ServerCommonPacketListenerImpl listener,
        Packet<?> packet,
        @Nullable ChannelFutureListener callback
    ) {
        if (packet instanceof ClientboundChunkBatchStartPacket) {
            Batch batch = CURRENT.get();
            if (batch != null && listener instanceof ServerGamePacketListenerImpl connection) {
                batch.onBatchStart(connection);
            }
            return false;
        }
        if (Boolean.TRUE.equals(FINISH_PACKET_BYPASS.get())
            || packet instanceof ClientboundLevelChunkWithLightPacket) {
            return false;
        }

        return OutboundGate.bufferIfActive(listener, packet, callback);
    }

    private static void sendFinishAndRelease(
        ServerGamePacketListenerImpl connection,
        ClientboundChunkBatchFinishedPacket packet
    ) {
        FINISH_PACKET_BYPASS.set(true);
        try {
            connection.send(packet);
        } finally {
            FINISH_PACKET_BYPASS.remove();
        }
        OutboundGate.flush(connection);
    }

    private static void failBatch(ServerGamePacketListenerImpl connection, Throwable error) {
        Regionium.LOGGER.error(
            "Failed to serialize a chunk on its owning region; disconnecting {}",
            connection.player.getGameProfile().name(),
            error
        );
        OutboundGate.abort(connection);
        dispatchFailureDisconnect(connection);
    }

    private static void dispatchFailureDisconnect(ServerGamePacketListenerImpl connection) {
        ServerPlayer player = connection.player;
        Component reason = Component.literal("Failed to send chunk data safely");
        if (player.isRemoved() || !(player.level() instanceof ServerLevel level)) {
            dispatchFailureDisconnectOnServer(connection, player, reason);
            return;
        }

        Regionium.scheduler().executeWhenOwned(
            level,
            player.blockPosition(),
            () -> {
                if (player.connection != connection) {
                    return;
                }
                if (player.isRemoved() || player.level() != level) {
                    dispatchFailureDisconnectOnServer(connection, player, reason);
                    return;
                }
                if (!Regionium.scheduler().isCurrentRegionFor(level, player.blockPosition())) {
                    dispatchFailureDisconnect(connection);
                    return;
                }
                runFailureDisconnect(connection, reason);
            }
        );
    }

    private static void dispatchFailureDisconnectOnServer(
        ServerGamePacketListenerImpl connection,
        ServerPlayer player,
        Component reason
    ) {
        MinecraftServer server = Regionium.scheduler().server();
        if (server == null && player.level() instanceof ServerLevel level) {
            server = level.getServer();
        }
        if (server != null) {
            server.execute(() -> {
                if (player.connection == connection) {
                    runFailureDisconnect(connection, reason);
                }
            });
            return;
        }

        // During server teardown there may be no executor left. Closing the
        // transport is the remaining global cleanup path and is thread-safe.
        ((ServerCommonPacketListenerRegioniumAccess) connection)
            .regionium$getConnection().disconnect(reason);
    }

    private static void runFailureDisconnect(
        ServerGamePacketListenerImpl connection,
        Component reason
    ) {
        boolean wasDisconnecting = FAILURE_DISCONNECT.get();
        FAILURE_DISCONNECT.set(true);
        try {
            connection.disconnect(reason);
        } finally {
            if (wasDisconnecting) {
                FAILURE_DISCONNECT.set(true);
            } else {
                FAILURE_DISCONNECT.remove();
            }
        }
    }

    public static final class Batch {
        private final Batch previous;
        private int pending;
        private boolean asyncUsed;
        private boolean gateAcquired;
        private boolean startSeen;
        private boolean callInProgress = true;
        private boolean failed;
        private boolean finishSent;
        private ServerGamePacketListenerImpl connection;
        private ClientboundChunkBatchFinishedPacket finishPacket;

        private Batch(Batch previous) {
            this.previous = previous;
        }

        /** Reserve the per-connection batch slot before vanilla emits its start packet. */
        public synchronized boolean reserve(ServerGamePacketListenerImpl connection) {
            if (failed || finishSent || gateAcquired) {
                return false;
            }
            if (!OutboundGate.tryReserve(connection)) {
                return false;
            }
            this.connection = connection;
            this.gateAcquired = true;
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log(
                    "chunk-batch-reserved",
                    RegioniumChunkDebug.playerState(connection.player)
                );
            }
            return true;
        }

        private synchronized void onBatchStart(ServerGamePacketListenerImpl connection) {
            if (failed || finishSent) {
                return;
            }
            if (!gateAcquired) {
                // Defensive path for a vanilla call that bypasses the wrapper.
                OutboundGate.begin(connection);
                gateAcquired = true;
            }
            this.connection = connection;
            startSeen = true;
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log(
                    "chunk-batch-start",
                    RegioniumChunkDebug.playerState(connection.player)
                );
            }
        }

        public void failIfStarted(ServerGamePacketListenerImpl connection, Throwable error) {
            boolean incompleteStartedBatch;
            synchronized (this) {
                incompleteStartedBatch = startSeen && !finishSent && !failed;
            }
            if (incompleteStartedBatch) {
                fail(connection, error);
            } else {
                releaseIfNoBatchStarted();
            }
        }

        /**
         * Vanilla mutates batch quota and sender counters after emitting the
         * finish packet. Keep the reservation until the method itself returns,
         * even when every chunk was serialized synchronously.
         */
        public void onSendNextChunksReturned() {
            boolean missingFinish;
            ServerGamePacketListenerImpl target;
            synchronized (this) {
                callInProgress = false;
                target = connection;
                missingFinish = startSeen && finishPacket == null && !finishSent && !failed;
            }
            if (missingFinish && target != null) {
                fail(
                    target,
                    new IllegalStateException("Chunk batch started without a finish packet")
                );
                return;
            }
            tryFinish();
        }

        /** Release a reservation when vanilla had no chunks to send. */
        public void releaseIfNoBatchStarted() {
            ServerGamePacketListenerImpl target;
            synchronized (this) {
                if (!gateAcquired || startSeen || finishSent || failed) {
                    return;
                }
                finishSent = true;
                target = connection;
                gateAcquired = false;
            }
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log(
                    "chunk-batch-empty-reservation-released",
                    RegioniumChunkDebug.playerState(target.player)
                );
            }
            OutboundGate.flush(target);
        }

        public synchronized boolean registerAsync(ServerGamePacketListenerImpl connection) {
            if (failed || finishSent) {
                return false;
            }
            if (this.connection == null) {
                this.connection = connection;
            }
            pending++;
            if (!asyncUsed) {
                asyncUsed = true;
                if (!gateAcquired) {
                    OutboundGate.begin(connection);
                    gateAcquired = true;
                }
            }
            return true;
        }

        public boolean deferFinish(
            ServerGamePacketListenerImpl connection,
            ClientboundChunkBatchFinishedPacket packet
        ) {
            synchronized (this) {
                if (!gateAcquired) {
                    return false;
                }
                this.connection = connection;
                finishPacket = packet;
            }
            tryFinish();
            return true;
        }

        public void completeChunk() {
            synchronized (this) {
                if (pending > 0) {
                    pending--;
                }
            }
            tryFinish();
        }

        public void fail(ServerGamePacketListenerImpl connection, Throwable error) {
            synchronized (this) {
                if (failed) {
                    return;
                }
                failed = true;
                finishPacket = null;
            }
            failBatch(connection, error);
        }

        private void tryFinish() {
            ServerGamePacketListenerImpl target;
            ClientboundChunkBatchFinishedPacket packet;
            synchronized (this) {
                if (failed || finishSent || !gateAcquired || callInProgress || pending != 0 || finishPacket == null) {
                    return;
                }
                finishSent = true;
                target = connection;
                packet = finishPacket;
                finishPacket = null;
            }
            try {
                if (RegioniumChunkDebug.enabled()) {
                    RegioniumChunkDebug.log(
                        "chunk-batch-finish",
                        RegioniumChunkDebug.playerState(target.player),
                        "pending=0"
                    );
                }
                sendFinishAndRelease(target, packet);
            } catch (Throwable error) {
                fail(target, error);
            }
        }
    }

    private record PendingPacket(Packet<?> packet, @Nullable ChannelFutureListener callback) {}

    private static final class OutboundGate {
        private static final java.util.Map<ServerCommonPacketListenerImpl, OutboundGate> GATES =
            new java.util.WeakHashMap<>();

        private final Queue<PendingPacket> pendingPackets = new ArrayDeque<>();
        // The connection may only have one chunk batch being serialized at a
        // time because the protocol's start/finish packets carry no batch ID.
        // This counter also makes gate release explicit and safe on failure.
        private int activeBatches;
        // Packets are drained outside the monitor: listener.send() can re-enter
        // this gate through the packet-listener mixin, so sending while holding
        // the gate lock creates a lock-order deadlock with bufferIfActive().
        private boolean flushing;

        static void begin(ServerCommonPacketListenerImpl listener) {
            OutboundGate gate;
            synchronized (GATES) {
                gate = GATES.computeIfAbsent(listener, ignored -> new OutboundGate());
            }
            synchronized (gate) {
                gate.activeBatches++;
            }
        }

        static boolean tryReserve(ServerCommonPacketListenerImpl listener) {
            OutboundGate gate;
            synchronized (GATES) {
                gate = GATES.computeIfAbsent(listener, ignored -> new OutboundGate());
            }
            synchronized (gate) {
                // Do not let a new batch overtake packets from the previous
                // batch while its ordered outbound queue is being drained.
                if (gate.activeBatches != 0 || gate.flushing) {
                    return false;
                }
                gate.activeBatches = 1;
                return true;
            }
        }

        static boolean bufferIfActive(
            ServerCommonPacketListenerImpl listener,
            Packet<?> packet,
            @Nullable ChannelFutureListener callback
        ) {
            if (FLUSHING_PACKET.get() == packet) {
                return false;
            }
            OutboundGate gate;
            synchronized (GATES) {
                gate = GATES.get(listener);
            }
            if (gate == null) {
                return false;
            }
            synchronized (gate) {
                if (gate.activeBatches == 0 && !gate.flushing) {
                    return false;
                }
                gate.pendingPackets.add(new PendingPacket(packet, callback));
                return true;
            }
        }

        static void flush(ServerCommonPacketListenerImpl listener) {
            OutboundGate gate;
            synchronized (GATES) {
                gate = GATES.get(listener);
            }
            if (gate == null) {
                return;
            }
            synchronized (gate) {
                if (gate.activeBatches > 0) {
                    gate.activeBatches--;
                }
                if (gate.activeBatches != 0 || gate.flushing) {
                    return;
                }
                gate.flushing = true;
            }

            try {
                while (true) {
                    PendingPacket pending;
                    synchronized (gate) {
                        // A defensive begin() may have happened while flushing.
                        // Leave remaining packets queued until that batch releases.
                        if (gate.activeBatches != 0) {
                            gate.flushing = false;
                            return;
                        }
                        pending = gate.pendingPackets.poll();
                        if (pending == null) {
                            // This transition is atomic with bufferIfActive():
                            // a concurrent sender either queues before this
                            // check or observes flushing=false and sends after
                            // every earlier queued packet has been written.
                            gate.flushing = false;
                            return;
                        }
                    }
                    // Never invoke packet sending under the gate monitor. The
                    // packet-listener mixin may synchronously query this gate.
                    // Let this exact queued packet pass through instead of
                    // enqueueing it again while the gate is being drained.
                    FLUSHING_PACKET.set(pending.packet());
                    try {
                        listener.send(pending.packet(), pending.callback());
                    } finally {
                        FLUSHING_PACKET.remove();
                    }
                }
            } catch (RuntimeException | Error error) {
                // Do not leave a half-drained gate mapped to the connection:
                // subsequent chunk batches would otherwise inherit stale
                // packets and potentially remain blocked forever.
                abort(listener);
                throw error;
            }
        }

        static void abort(ServerCommonPacketListenerImpl listener) {
            OutboundGate gate;
            synchronized (GATES) {
                gate = GATES.remove(listener);
            }
            if (gate != null) {
                synchronized (gate) {
                    gate.activeBatches = 0;
                    gate.pendingPackets.clear();
                }
            }
        }
    }
}
