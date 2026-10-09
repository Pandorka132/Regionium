package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes the mutable vanilla DistanceManager player/ticket state.
 *
 * Region ticks may synchronously reach ChunkMap.move() through the entity
 * lifecycle callback. Vanilla DistanceManager uses non-thread-safe fastutil
 * collections, so add/removePlayer must never overlap runAllUpdates().
 *
 * This is a narrow compatibility boundary; it does not defer callbacks or
 * block unrelated world/region work.
 */
@Mixin(DistanceManager.class)
public abstract class ChunkMapRegioniumDistanceManagerMixin {
    private static final ReentrantLock REGIONIUM_DISTANCE_LOCK = new ReentrantLock();

    @WrapMethod(method = "addPlayer")
    private void regionium$lockedAddPlayer(
        SectionPos pos,
        ServerPlayer player,
        Operation<Void> original
    ) {
        REGIONIUM_DISTANCE_LOCK.lock();
        try {
            original.call(pos, player);
        } finally {
            REGIONIUM_DISTANCE_LOCK.unlock();
        }
    }

    @WrapMethod(method = "removePlayer")
    private void regionium$lockedRemovePlayer(
        SectionPos pos,
        ServerPlayer player,
        Operation<Void> original
    ) {
        REGIONIUM_DISTANCE_LOCK.lock();
        try {
            original.call(pos, player);
        } finally {
            REGIONIUM_DISTANCE_LOCK.unlock();
        }
    }

    @WrapMethod(method = "runAllUpdates")
    private boolean regionium$lockedRunAllUpdates(
        ChunkMap scheduler,
        Operation<Boolean> original
    ) {
        REGIONIUM_DISTANCE_LOCK.lock();
        try {
            return original.call(scheduler);
        } finally {
            REGIONIUM_DISTANCE_LOCK.unlock();
        }
    }
}
