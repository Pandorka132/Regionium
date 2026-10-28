package dev.pandor.regionium.access;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;

public interface RaidsRegioniumAccess {
    Raid regionium$getNearbyRaid(ServerLevel level, BlockPos pos, int maxDistSqr);
    void regionium$globalTick();
    int regionium$getNextAvailableRaidId();
}
