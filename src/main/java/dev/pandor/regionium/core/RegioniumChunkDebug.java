package dev.pandor.regionium.core;

import dev.pandor.regionium.Regionium;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/** Opt-in diagnostics for the complete player chunk-tracking/send pipeline. */
public final class RegioniumChunkDebug {
    private static final boolean ENABLED = Boolean.getBoolean("regionium.chunkDebug");

    private RegioniumChunkDebug() {}

    public static boolean enabled() {
        return ENABLED;
    }

    public static void log(String event, Object... details) {
        if (!ENABLED) {
            return;
        }
        RegioniumRegion current = RegioniumContext.currentRegion();
        Regionium.LOGGER.info(
            "[CHUNK-DEBUG] event={} thread={} currentRegion={} details={}",
            event,
            Thread.currentThread().getName(),
            current == null ? "global/none" : current.id(),
            java.util.Arrays.deepToString(details)
        );
    }

    public static String playerState(ServerPlayer player) {
        if (player == null) {
            return "player=null";
        }
        String dimension = player.level() instanceof ServerLevel level
            ? level.dimension().identifier().toString() : String.valueOf(player.level());
        ChunkPos pos = player.chunkPosition();
        RegioniumRegion owner = player.level() instanceof ServerLevel level
            ? Regionium.scheduler().regionizer().owner(level, pos.pack()) : null;
        return "player=" + player.getGameProfile().name()
            + ",dimension=" + dimension
            + ",chunk=" + pos.x() + "," + pos.z()
            + ",owner=" + (owner == null ? "none" : owner.id())
            + ",removed=" + player.isRemoved()
            + ",connection=" + (player.connection != null);
    }
}
