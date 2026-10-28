package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumPortalDispatcher;
import dev.pandor.regionium.core.RegioniumRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mirrors Folia's region ownership checks at the concrete vanilla interaction
 * points. Packet processing remains on the player's region; targets must also
 * belong to that region before vanilla mutates them.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerRegioniumOwnershipMixin {
    @Inject(method = "handlePlayerAction", at = @At("RETURN"))
    private void regionium$synchronizeOffhandSwap(
        ServerboundPlayerActionPacket packet,
        CallbackInfo ci
    ) {
        if (packet.getAction() != ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND
            || !RegioniumContext.isRegionThread()) {
            return;
        }

        ServerGamePacketListenerImpl listener = (ServerGamePacketListenerImpl) (Object) this;
        var player = listener.player;
        if (!player.isSpectator()
            && Regionium.scheduler().isOwnedByCurrentRegion(player)) {
            // Vanilla normally sends the changed inventory slots from the
            // player's next tick. Regionium can legitimately defer that tick
            // while ownership is being reconciled, so synchronize the swap
            // immediately on the same region that handled the incoming packet.
            player.containerMenu.broadcastChanges();
        }
    }

    @Inject(method = "tickPlayer", at = @At("HEAD"), cancellable = true)
    private void regionium$waitForPlayerPhysicsChunks(CallbackInfoReturnable<Boolean> cir) {
        ServerGamePacketListenerImpl listener = (ServerGamePacketListenerImpl) (Object) this;
        var player = listener.player;
        if (RegioniumPortalDispatcher.isTransferPending(player)) {
            // Folia detaches the entity tree before asynchronous destination
            // discovery. Regionium's transfer pipeline may still be preparing
            // chunks, so do not continue ticking the player while it is pending;
            // otherwise its portal cooldown can expire and stale portal contact
            // can start another dimension transfer before the first completes.
            cir.setReturnValue(false);
            return;
        }
        if (!player.isRemoved() && player.level() instanceof ServerLevel level
            && !Regionium.scheduler().arePlayerPhysicsChunksReady(level, player)) {
            // Keep the network connection ticking, but do not run player physics
            // against a chunk whose FULL state is not ready yet. The readiness
            // check requests missing chunks through the normal async ticket path.
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "tickPlayer", at = @At("RETURN"))
    private void regionium$handlePlayerPortalAfterTick(CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }
        ServerGamePacketListenerImpl listener = (ServerGamePacketListenerImpl) (Object) this;
        var player = listener.player;
        // A cancellable HEAD injection still reaches this RETURN injection.
        // Explicitly gate it as well, or portal processing continues during an
        // in-flight transfer even though tickPlayer itself was skipped.
        if (RegioniumPortalDispatcher.isTransferPending(player)
            || player.isRemoved() || !(player.level() instanceof ServerLevel level)
            || !Regionium.scheduler().arePlayerPhysicsChunksReady(level, player)) {
            return;
        }

        // Run only after all of tickPlayer's movement validation and position
        // reconciliation. Injecting immediately after doTick() lets the rest of
        // tickPlayer snap the player back to its pre-portal position while the
        // asynchronous dimension transfer is already starting.
        BlockPos position = player.blockPosition();
        var owner = Regionium.scheduler().regionizer().owner(level, player.chunkPosition().pack());
        if (RegioniumContext.isRegionThread() && owner != null
            && owner == RegioniumContext.currentRegion()) {
            ((EntityRegioniumPortalInvokerMixin) (Object) player).regionium$invokeHandlePortal();
            return;
        }

        // Connection ticks may also run outside the player's region. In that
        // case, do not touch PortalProcessor off-owner; schedule its post-tick
        // step on the owning region instead.
        Regionium.scheduler().executeWhenOwned(level, position, () -> {
            if (!player.isRemoved() && player.level() == level
                && Regionium.scheduler().regionizer().owner(level, player.chunkPosition().pack())
                    == RegioniumContext.currentRegion()) {
                ((EntityRegioniumPortalInvokerMixin) (Object) player).regionium$invokeHandlePortal();
            }
        });
    }

    @WrapOperation(
        method = "handleAttack",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getEntityOrPart(I)Lnet/minecraft/world/entity/Entity;"
        )
    )
    private Entity regionium$attackOnlyOwnedEntity(
        ServerLevel level,
        int entityId,
        Operation<Entity> original
    ) {
        return regionium$ownedTarget(level, entityId, original);
    }

    @WrapOperation(
        method = "handleInteract",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getEntityOrPart(I)Lnet/minecraft/world/entity/Entity;"
        )
    )
    private Entity regionium$interactOnlyOwnedEntity(
        ServerLevel level,
        int entityId,
        Operation<Entity> original
    ) {
        return regionium$ownedTarget(level, entityId, original);
    }

@org.spongepowered.asm.mixin.Unique
    private static Entity regionium$ownedTarget(
        ServerLevel level,
        int entityId,
        Operation<Entity> original
    ) {
        Entity target = original.call(level, entityId);
        if (target == null || !RegioniumContext.isRegionThread()) {
            return target;
        }

        RegioniumRegion current = RegioniumContext.currentRegion();
        return current != null && Regionium.scheduler().ownerOf(target) == current
            ? target
            : null;
    }

    @Inject(
        method = "handlePlayerAction",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayerGameMode;handleBlockBreakAction(Lnet/minecraft/core/BlockPos;Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket$Action;Lnet/minecraft/core/Direction;II)V",
            shift = At.Shift.BEFORE
        ),
        cancellable = true
    )
    private void regionium$rejectBlockBreakOutsideOwnedArea(
        ServerboundPlayerActionPacket packet,
        CallbackInfo ci
    ) {
        if (!RegioniumContext.isRegionThread()) {
            return;
        }

        RegioniumRegion current = RegioniumContext.currentRegion();
        ServerGamePacketListenerImpl listener = (ServerGamePacketListenerImpl) (Object) this;
        BlockPos pos = packet.getPos();
        ServerLevel level = listener.player.level();
        if (current == null || !Regionium.scheduler().regionizer().ownsChunkSquare(
            current, level, pos.getX() >> 4, pos.getZ() >> 4, 8
        )) {
            listener.ackBlockChangesUpTo(packet.getSequence());
            ci.cancel();
        }
    }

    @Inject(
        method = "handleUseItemOn",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;ackBlockChangesUpTo(I)V",
            shift = At.Shift.AFTER,
            ordinal = 0
        ),
        cancellable = true
    )
    private void regionium$rejectBlockUseOutsideOwnedArea(
        ServerboundUseItemOnPacket packet,
        CallbackInfo ci
    ) {
        if (!RegioniumContext.isRegionThread()) {
            return;
        }

        RegioniumRegion current = RegioniumContext.currentRegion();
        ServerGamePacketListenerImpl listener = (ServerGamePacketListenerImpl) (Object) this;
        BlockPos pos = packet.hitResult().getBlockPos();
        ServerLevel level = listener.player.level();
        if (current == null || !Regionium.scheduler().regionizer().ownsChunkSquare(
            current, level, pos.getX() >> 4, pos.getZ() >> 4, 8
        )) {
            ci.cancel();
        }
    }
}
