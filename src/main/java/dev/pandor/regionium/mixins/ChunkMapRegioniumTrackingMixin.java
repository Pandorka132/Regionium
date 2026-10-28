package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.access.ChunkMapRegioniumTrackingAccess;
import dev.pandor.regionium.access.ChunkMapTrackedEntityRegioniumAccess;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumChunkDebug;
import dev.pandor.regionium.core.RegioniumRegion;
import dev.pandor.regionium.core.RegioniumWorldData;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkMap.class)
public abstract class ChunkMapRegioniumTrackingMixin implements ChunkMapRegioniumTrackingAccess {
    @Shadow @Final private Int2ObjectMap<?> entityMap;

    @WrapMethod(method = "markChunkPendingToSend(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/ChunkPos;)V")
    private void regionium$routePendingChunkByPosition(
        ServerPlayer player,
        ChunkPos pos,
        Operation<Void> original
    ) {
        if (RegioniumChunkDebug.enabled()) {
            RegioniumChunkDebug.log("pending-position-request", RegioniumChunkDebug.playerState(player), "chunk=" + pos);
        }
        regionium$runOnPlayerOwner(player, () -> {
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log("pending-position-execute", RegioniumChunkDebug.playerState(player), "chunk=" + pos);
            }
            original.call(player, pos);
        });
    }

    @WrapMethod(method = "markChunkPendingToSend(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/chunk/LevelChunk;)V")
    private static void regionium$routePendingChunk(
        ServerPlayer player,
        LevelChunk chunk,
        Operation<Void> original
    ) {
        if (RegioniumChunkDebug.enabled()) {
            RegioniumChunkDebug.log("pending-chunk-request", RegioniumChunkDebug.playerState(player), "chunk=" + chunk.getPos());
        }
        regionium$runOnPlayerOwner(player, () -> {
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log("pending-chunk-execute", RegioniumChunkDebug.playerState(player), "chunk=" + chunk.getPos());
            }
            original.call(player, chunk);
        });
    }

    @WrapMethod(method = "dropChunk(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/ChunkPos;)V")
    private static void regionium$routeDroppedChunk(
        ServerPlayer player,
        ChunkPos pos,
        Operation<Void> original
    ) {
        if (RegioniumChunkDebug.enabled()) {
            RegioniumChunkDebug.log("drop-chunk-request", RegioniumChunkDebug.playerState(player), "chunk=" + pos);
        }
        regionium$runOnPlayerOwner(player, () -> {
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log("drop-chunk-execute", RegioniumChunkDebug.playerState(player), "chunk=" + pos);
            }
            original.call(player, pos);
        });
    }

    @Unique
    private static void regionium$runOnPlayerOwner(ServerPlayer player, Runnable operation) {
        if (player.isRemoved() || !(player.level() instanceof ServerLevel level) || player.connection == null) {
            return;
        }

        RegioniumRegion owner = Regionium.scheduler().regionizer()
            .owner(level, player.chunkPosition().pack());
        if (owner != null && !owner.isRetired() && owner == RegioniumContext.currentRegion()) {
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log("pending-mutation-inline", RegioniumChunkDebug.playerState(player), "owner=" + owner.id());
            }
            operation.run();
            return;
        }
        if (RegioniumChunkDebug.enabled()) {
            RegioniumChunkDebug.log("pending-mutation-dispatch", RegioniumChunkDebug.playerState(player), "targetRegion=" + (owner == null ? "none" : owner.id()));
        }
        regionium$dispatchPlayerMutation(player, level, operation);
    }

    @Unique
    private static void regionium$dispatchPlayerMutation(
        ServerPlayer player,
        ServerLevel level,
        Runnable operation
    ) {
        if (player.isRemoved() || player.level() != level || player.connection == null) {
            return;
        }
        RegioniumRegion owner = Regionium.scheduler().regionizer()
            .owner(level, player.chunkPosition().pack());
        if (owner == null || owner.isRetired()) {
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log("pending-mutation-no-owner-retry", RegioniumChunkDebug.playerState(player));
            }
            Regionium.scheduler().executeWhenOwned(
                level, player.blockPosition(),
                () -> regionium$dispatchPlayerMutation(player, level, operation)
            );
            return;
        }
        if (owner == RegioniumContext.currentRegion()) {
            operation.run();
            return;
        }
        // Queue by spatial ownership rather than directly on RegioniumRegion:
        // a region can retire after execute() accepts a task, stranding a
        // pending chunk mutation during a split/merge handoff.
        Regionium.scheduler().executeWhenOwned(level, player.blockPosition(), () -> {
            if (player.isRemoved() || player.level() != level || player.connection == null) {
                return;
            }
            RegioniumRegion actualOwner = Regionium.scheduler().regionizer()
                .owner(level, player.chunkPosition().pack());
            if (actualOwner != RegioniumContext.currentRegion()) {
                regionium$dispatchPlayerMutation(player, level, operation);
                return;
            }
            operation.run();
        });
    }

    @WrapOperation(
        method = "onChunkReadyToSend",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkTrackingView;contains(Lnet/minecraft/world/level/ChunkPos;)Z"
        )
    )
    private boolean regionium$deferReadyChunkTrackingCheck(
        ChunkTrackingView view,
        ChunkPos pos,
        Operation<Boolean> original
    ) {
        // onChunkReadyToSend runs on the global chunk thread, while Regionium
        // mutates each player's tracking view on that player's owning region.
        // Let the player-owner callback make the eligibility decision from
        // the current view instead of a potentially stale global-thread read.
        boolean vanillaResult = original.call(view, pos);
        if (RegioniumChunkDebug.enabled()) {
            RegioniumChunkDebug.log("ready-tracking-check", "chunk=" + pos, "vanillaContains=" + vanillaResult, "forcedPass=true");
        }
        return true;
    }

    @WrapOperation(
        method = "onChunkReadyToSend",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;markChunkPendingToSend(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/chunk/LevelChunk;)V"
        )
    )
    private void regionium$queueReadyChunkOnPlayerOwner(
        ServerPlayer player,
        LevelChunk chunk,
        Operation<Void> original
    ) {
        if (!(player.level() instanceof ServerLevel level)) {
            original.call(player, chunk);
            return;
        }

        RegioniumRegion owner = Regionium.scheduler().regionizer()
            .owner(level, player.chunkPosition().pack());
        RegioniumRegion current = RegioniumContext.currentRegion();
        if (RegioniumChunkDebug.enabled()) {
            RegioniumChunkDebug.log("chunk-ready-for-player", RegioniumChunkDebug.playerState(player), "chunk=" + chunk.getPos(), "owner=" + (owner == null ? "none" : owner.id()), "current=" + (current == null ? "none" : current.id()));
        }
        if (owner == null) {
            regionium$dispatchReadyChunk(player, chunk, level, null);
            return;
        }

        if (!owner.isRetired() && owner == current) {
            regionium$markReadyChunkIfStillTracked(player, chunk, level);
            return;
        }

        regionium$dispatchReadyChunk(player, chunk, level, owner);
    }

    @Unique
    private void regionium$dispatchReadyChunk(
        ServerPlayer player,
        LevelChunk chunk,
        ServerLevel level,
        RegioniumRegion expectedOwner
    ) {
        if (player.isRemoved() || player.level() != level) {
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log("ready-dispatch-aborted-player-state", RegioniumChunkDebug.playerState(player), "chunk=" + chunk.getPos());
            }
            return;
        }

        RegioniumRegion owner = Regionium.scheduler().regionizer()
            .owner(level, player.chunkPosition().pack());
        if (owner == null || owner.isRetired()) {
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log("ready-dispatch-no-owner-retry", RegioniumChunkDebug.playerState(player), "chunk=" + chunk.getPos(), "expected=" + (expectedOwner == null ? "none" : expectedOwner.id()));
            }
            Regionium.scheduler().executeWhenOwned(
                level,
                player.blockPosition(),
                () -> regionium$dispatchReadyChunk(player, chunk, level, expectedOwner)
            );
            return;
        }

        if (owner == RegioniumContext.currentRegion()) {
            regionium$markReadyChunkIfStillTracked(player, chunk, level);
            return;
        }

        if (RegioniumChunkDebug.enabled()) {
            RegioniumChunkDebug.log("ready-dispatch-submit", RegioniumChunkDebug.playerState(player), "chunk=" + chunk.getPos(), "targetRegion=" + owner.id());
        }
        // Region-owned task queues are not durable across a topology handoff.
        // Use the spatially keyed queue so a ready-chunk notification follows
        // the player if its region is split or merged before execution.
        Regionium.scheduler().executeWhenOwned(level, player.blockPosition(), () -> {
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log("ready-dispatch-run", RegioniumChunkDebug.playerState(player), "chunk=" + chunk.getPos(), "scheduledRegion=" + RegioniumContext.currentRegion().id());
            }
            if (player.isRemoved() || player.level() != level) {
                if (RegioniumChunkDebug.enabled()) {
                    RegioniumChunkDebug.log("ready-dispatch-run-aborted-player-state", RegioniumChunkDebug.playerState(player), "chunk=" + chunk.getPos());
                }
                return;
            }
            RegioniumRegion actualOwner = Regionium.scheduler().regionizer()
                .owner(level, player.chunkPosition().pack());
            if (actualOwner != RegioniumContext.currentRegion()) {
                if (RegioniumChunkDebug.enabled()) {
                    RegioniumChunkDebug.log("ready-dispatch-owner-changed", RegioniumChunkDebug.playerState(player), "chunk=" + chunk.getPos(), "scheduled=" + (RegioniumContext.currentRegion() == null ? "none" : RegioniumContext.currentRegion().id()), "actual=" + (actualOwner == null ? "none" : actualOwner.id()));
                }
                regionium$dispatchReadyChunk(player, chunk, level, actualOwner);
                return;
            }
            regionium$markReadyChunkIfStillTracked(player, chunk, level);
        });
    }

    @Unique
    private static void regionium$markReadyChunkIfStillTracked(
        ServerPlayer player,
        LevelChunk chunk,
        ServerLevel level
    ) {
        if (player.isRemoved() || player.level() != level || player.connection == null) {
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log("ready-chunk-dropped-player-invalid", RegioniumChunkDebug.playerState(player), "chunk=" + chunk.getPos());
            }
            return;
        }
        boolean tracked = player.getChunkTrackingView().contains(chunk.getPos());
        if (RegioniumChunkDebug.enabled()) {
            RegioniumChunkDebug.log("ready-chunk-owner-check", RegioniumChunkDebug.playerState(player), "chunk=" + chunk.getPos(), "tracked=" + tracked);
        }
        if (tracked) {
            player.connection.chunkSender.markChunkPendingToSend(chunk);
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log("ready-chunk-enqueued", RegioniumChunkDebug.playerState(player), "chunk=" + chunk.getPos());
            }
        } else {
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log("ready-chunk-not-tracked", RegioniumChunkDebug.playerState(player), "chunk=" + chunk.getPos());
            }
        }
    }

    @Override
    public void regionium$tickRegionEntities(RegioniumWorldData data) {
        // A tracked entity can be visible to players owned by other regions.
        // Gather stable region-local player snapshots instead of using the
        // mutable global ServerLevel.players() list from a worker thread. The
        // entity's owning region remains the only thread mutating its tracker.
        java.util.List<ServerPlayer> players = new java.util.ArrayList<>();
        for (RegioniumRegion region : Regionium.scheduler().regions()) {
            if (region.level() == data.level()) {
                players.addAll(java.util.List.copyOf(region.worldData().players()));
            }
        }

        for (Entity entity : data.entitySnapshot()) {
            if (entity.isRemoved() || entity.level() != data.level()) {
                continue;
            }
            Object tracked = entityMap.get(entity.getId());
            if (tracked instanceof ChunkMapTrackedEntityRegioniumAccess access) {
                access.regionium$sendChanges(players);
            }
        }
    }
}
