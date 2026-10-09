package dev.pandor.regionium.access;

import net.minecraft.server.level.ServerPlayer;
import java.util.List;

public interface ChunkMapTrackedEntityRegioniumAccess {
    void regionium$sendChanges(List<ServerPlayer> players);
}
