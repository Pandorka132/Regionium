package dev.pandor.regionium.core;

import dev.pandor.regionium.Regionium;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
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
    private static final ThreadLocal<Boolean> FINISH_PACKET_BYPASS =
        ThreadLocal.withInitial(() -> false);

    private RegioniumChunkSendBatch() {}

    public static Batch begin() {
        Batch batch = new Batch();
        CURRENT.set(batch);
        return batch;
    }

    public static Batch current() {
        return CURRENT.get();
    }

    public static void end() {
        CURRENT.remove();
    }

    public static boolean shouldBuffer(
        ServerCommonPacketListenerImpl listener,
        Packet<?> packet
    ) {
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
        connection.disconnect(Component.literal("Failed to send chunk data safely"));
    }

    public static final class Batch {
        private int pending;
        private boolean asyncUsed;
        private boolean failed;
        private boolean finishSent;
        private ServerGamePacketListenerImpl connection;
        private ClientboundChunkBatchFinishedPacket finishPacket;

        public synchronized void registerAsync(ServerGamePacketListenerImpl connection) {
            if (failed) {
                return;
            }
            if (this.connection == null) {
                this.connection = connection;
            }
            pending++;
            if (!asyncUsed) {
                asyncUsed = true;
                OutboundGate.begin(connection);
            }
        }

        public boolean deferFinish(
            ServerGamePacketListenerImpl connection,
            ClientboundChunkBatchFinishedPacket packet
        ) {
            synchronized (this) {
                if (!asyncUsed) {
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
                failed = true;
                finishPacket = null;
            }
            failBatch(connection, error);
        }

        private void tryFinish() {
            ServerGamePacketListenerImpl target;
            ClientboundChunkBatchFinishedPacket packet;
            synchronized (this) {
                if (failed || finishSent || !asyncUsed || pending != 0 || finishPacket == null) {
                    return;
                }
                finishSent = true;
                target = connection;
                packet = finishPacket;
                finishPacket = null;
            }
            sendFinishAndRelease(target, packet);
        }
    }

    private record PendingPacket(Packet<?> packet, @Nullable ChannelFutureListener callback) {}

    private static final class OutboundGate {
        private static final java.util.Map<ServerCommonPacketListenerImpl, OutboundGate> GATES =
            new java.util.WeakHashMap<>();

        private final Queue<PendingPacket> pendingPackets = new ArrayDeque<>();
        private boolean active = true;

        static void begin(ServerCommonPacketListenerImpl listener) {
            OutboundGate gate;
            synchronized (GATES) {
                gate = GATES.computeIfAbsent(listener, ignored -> new OutboundGate());
            }
            synchronized (gate) {
                gate.active = true;
            }
        }

        static boolean bufferIfActive(
            ServerCommonPacketListenerImpl listener,
            Packet<?> packet,
            @Nullable ChannelFutureListener callback
        ) {
            OutboundGate gate;
            synchronized (GATES) {
                gate = GATES.get(listener);
            }
            if (gate == null) {
                return false;
            }
            synchronized (gate) {
                if (!gate.active) {
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
                gate.active = false;
                PendingPacket pending;
                while ((pending = gate.pendingPackets.poll()) != null) {
                    listener.send(pending.packet(), pending.callback());
                }
            }
        }

        static void abort(ServerCommonPacketListenerImpl listener) {
            OutboundGate gate;
            synchronized (GATES) {
                gate = GATES.remove(listener);
            }
            if (gate != null) {
                synchronized (gate) {
                    gate.active = false;
                    gate.pendingPackets.clear();
                }
            }
        }
    }
}
