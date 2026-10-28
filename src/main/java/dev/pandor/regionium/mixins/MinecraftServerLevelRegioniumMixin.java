package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.access.RaidsRegioniumAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.BooleanSupplier;

/**
 * Removes the vanilla global ServerLevel simulation tick.
 *
 * Folia has no second global world tick: region schedulers own the complete
 * world simulation. The global server thread only performs chunk lifecycle
 * and other genuinely global maintenance.
 */
@Mixin(net.minecraft.server.MinecraftServer.class)
public abstract class MinecraftServerLevelRegioniumMixin {
    @WrapOperation(
        method = "tickChildren",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/PlayerChunkSender;sendNextChunks(Lnet/minecraft/server/level/ServerPlayer;)V"
        )
    )
    private void regionium$disableGlobalChunkSending(
        PlayerChunkSender sender,
        ServerPlayer player,
        Operation<Void> original
    ) {
        // Folia does not run the per-player chunk sender from the global
        // server loop. Regionium invokes it from the player's owning region.
    }

    @WrapOperation(
        method = "tickChildren",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;resumeFlushing()V"
        )
    )
    private void regionium$disableGlobalPlayerFlush(
        net.minecraft.server.network.ServerGamePacketListenerImpl connection,
        Operation<Void> original
    ) {
        // Folia disables the entire global per-player send loop, including
        // resumeFlushing(). Regionium suspends and resumes each player's flush
        // inside that player's region tick. Resuming it here races the region's
        // connection tick and breaks the per-region packet-flush boundary.
    }

    @WrapOperation(
        method = "tickChildren",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tick(Ljava/util/function/BooleanSupplier;)V"
        )
    )
    private void regionium$prepareLevelTick(
        ServerLevel level,
        BooleanSupplier haveTime,
        Operation<Void> original
    ) {
        // Keep Folia's global-world operation order: border/weather/sleep,
        // raid cadence, sky brightness, then global game/day time.
        Regionium.scheduler().tickGlobalWorldState(level);
        ((RaidsRegioniumAccess) level.getRaids()).regionium$globalTick();
        Regionium.scheduler().tickGlobalSkyBrightness(level);
        Regionium.scheduler().tickGlobalTime(level);

        // Folia's global region processes chunk lifecycle/ticket work even
        // when no simulation region is active yet. This is what allows
        // GameTest structures and newly requested chunks to become ticking
        // chunks before the owning region can execute them.
        level.getChunkSource().tick(haveTime, false);

        // Topology is event-driven: ChunkHolder add/remove/lifecycle callbacks
        // mark it dirty. Re-marking it every global tick forces rebalance() to
        // queue for the regionizer's global write lock even when no chunk or
        // region boundary changed. A single slow region then stalls every other
        // region behind the fair tick barrier.
        ((ServerLevelRegioniumAccessorMixin) (Object) level)
            .regionium$getEntityManager().tick();
        Regionium.scheduler().prepareChunkExecution(level);
        // Intentionally do not call original.call().
        //
        // RegioniumRegion.tickRegion() invokes ServerLevel.tick() from the
        // owning region context. Calling it here as well would create a
        // second simulation pass and would reintroduce shared-state races.
    }
}
