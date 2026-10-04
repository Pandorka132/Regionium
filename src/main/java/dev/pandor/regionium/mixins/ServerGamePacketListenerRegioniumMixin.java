package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves world-mutating player packet handling onto the player's Regionium
 * owner. Vanilla receives these packets on the server/network thread, while
 * the corresponding ServerLevel tick is owned by a Regionium worker.
 *
 * The handler is queued onto that owner instead of synchronously waiting:
 * waiting here could overlap a packet task with an already-running world tick
 * and corrupt vanilla thread-affine state.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerRegioniumMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void regionium$handleMovePlayer(
        net.minecraft.network.protocol.game.ServerboundMovePlayerPacket packet,
        CallbackInfo ci
    ) {
        regionium$queueIfOffRegion(ci, () ->
            ((ServerGamePacketListenerImpl) (Object) this).handleMovePlayer(packet));
    }

    @Inject(method = "handleClientTickEnd", at = @At("HEAD"), cancellable = true)
    private void regionium$handleClientTickEnd(
        net.minecraft.network.protocol.game.ServerboundClientTickEndPacket packet,
        CallbackInfo ci
    ) {
        regionium$queueIfOffRegion(ci, () ->
            ((ServerGamePacketListenerImpl) (Object) this).handleClientTickEnd(packet));
    }

    @Inject(method = "handlePlayerInput", at = @At("HEAD"), cancellable = true)
    private void regionium$handlePlayerInput(
        net.minecraft.network.protocol.game.ServerboundPlayerInputPacket packet,
        CallbackInfo ci
    ) {
        regionium$queueIfOffRegion(ci, () ->
            ((ServerGamePacketListenerImpl) (Object) this).handlePlayerInput(packet));
    }

    @Inject(method = "handleMoveVehicle", at = @At("HEAD"), cancellable = true)
    private void regionium$handleMoveVehicle(
        net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket packet,
        CallbackInfo ci
    ) {
        regionium$queueIfOffRegion(ci, () ->
            ((ServerGamePacketListenerImpl) (Object) this).handleMoveVehicle(packet));
    }

    @Inject(method = "handlePaddleBoat", at = @At("HEAD"), cancellable = true)
    private void regionium$handlePaddleBoat(
        net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket packet,
        CallbackInfo ci
    ) {
        regionium$queueIfOffRegion(ci, () ->
            ((ServerGamePacketListenerImpl) (Object) this).handlePaddleBoat(packet));
    }

    @Inject(method = "handlePlayerCommand", at = @At("HEAD"), cancellable = true)
    private void regionium$handlePlayerCommand(
        net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket packet,
        CallbackInfo ci
    ) {
        regionium$queueIfOffRegion(ci, () ->
            ((ServerGamePacketListenerImpl) (Object) this).handlePlayerCommand(packet));
    }

    @Inject(method = "handleSpectatorAction", at = @At("HEAD"), cancellable = true)
    private void regionium$handleSpectatorAction(
        net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket packet,
        CallbackInfo ci
    ) {
        regionium$queueIfOffRegion(ci, () ->
            ((ServerGamePacketListenerImpl) (Object) this).handleSpectatorAction(packet));
    }

    @Inject(method = "handleClientCommand", at = @At("HEAD"), cancellable = true)
    private void regionium$handleClientCommand(
        net.minecraft.network.protocol.game.ServerboundClientCommandPacket packet,
        CallbackInfo ci
    ) {
        regionium$queueIfOffRegion(ci, () ->
            ((ServerGamePacketListenerImpl) (Object) this).handleClientCommand(packet));
    }

    @Inject(method = "handlePlayerAbilities", at = @At("HEAD"), cancellable = true)
    private void regionium$handlePlayerAbilities(
        net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket packet,
        CallbackInfo ci
    ) {
        regionium$queueIfOffRegion(ci, () ->
            ((ServerGamePacketListenerImpl) (Object) this).handlePlayerAbilities(packet));
    }

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void regionium$handlePlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (!regionium$onRegionThread()) {
            ci.cancel();
            Regionium.scheduler().executeEntity(
                player,
                () -> ((ServerGamePacketListenerImpl) (Object) this).handlePlayerAction(packet)
            );
        }
    }

    @Inject(method = "handleUseItemOn", at = @At("HEAD"), cancellable = true)
    private void regionium$handleUseItemOn(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (!regionium$onRegionThread()) {
            ci.cancel();
            Regionium.scheduler().executeEntity(
                player,
                () -> ((ServerGamePacketListenerImpl) (Object) this).handleUseItemOn(packet)
            );
        }
    }

    @Inject(method = "handleUseItem", at = @At("HEAD"), cancellable = true)
    private void regionium$handleUseItem(ServerboundUseItemPacket packet, CallbackInfo ci) {
        if (!regionium$onRegionThread()) {
            ci.cancel();
            Regionium.scheduler().executeEntity(
                player,
                () -> ((ServerGamePacketListenerImpl) (Object) this).handleUseItem(packet)
            );
        }
    }

    @Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
    private void regionium$handleInteract(ServerboundInteractPacket packet, CallbackInfo ci) {
        if (!regionium$onRegionThread()) {
            ci.cancel();
            Regionium.scheduler().executeEntity(
                player,
                () -> ((ServerGamePacketListenerImpl) (Object) this).handleInteract(packet)
            );
        }
    }

    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void regionium$handleContainerClick(
        net.minecraft.network.protocol.game.ServerboundContainerClickPacket packet,
        CallbackInfo ci
    ) {
        regionium$queueIfOffRegion(ci, () ->
            ((ServerGamePacketListenerImpl) (Object) this).handleContainerClick(packet));
    }

    @Inject(method = "handleContainerClose", at = @At("HEAD"), cancellable = true)
    private void regionium$handleContainerClose(
        net.minecraft.network.protocol.game.ServerboundContainerClosePacket packet,
        CallbackInfo ci
    ) {
        regionium$queueIfOffRegion(ci, () ->
            ((ServerGamePacketListenerImpl) (Object) this).handleContainerClose(packet));
    }

    @Inject(method = "handleContainerButtonClick", at = @At("HEAD"), cancellable = true)
    private void regionium$handleContainerButtonClick(
        net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket packet,
        CallbackInfo ci
    ) {
        regionium$queueIfOffRegion(ci, () ->
            ((ServerGamePacketListenerImpl) (Object) this).handleContainerButtonClick(packet));
    }

    @Inject(method = "handleSetCarriedItem", at = @At("HEAD"), cancellable = true)
    private void regionium$handleSetCarriedItem(
        net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket packet,
        CallbackInfo ci
    ) {
        regionium$queueIfOffRegion(ci, () ->
            ((ServerGamePacketListenerImpl) (Object) this).handleSetCarriedItem(packet));
    }

    @Inject(method = "handleSetCreativeModeSlot", at = @At("HEAD"), cancellable = true)
    private void regionium$handleSetCreativeModeSlot(
        net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket packet,
        CallbackInfo ci
    ) {
        regionium$queueIfOffRegion(ci, () ->
            ((ServerGamePacketListenerImpl) (Object) this).handleSetCreativeModeSlot(packet));
    }

    @Inject(method = "handlePlaceRecipe", at = @At("HEAD"), cancellable = true)
    private void regionium$handlePlaceRecipe(
        net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket packet,
        CallbackInfo ci
    ) {
        regionium$queueIfOffRegion(ci, () ->
            ((ServerGamePacketListenerImpl) (Object) this).handlePlaceRecipe(packet));
    }

    @Inject(method = "handleSelectTrade", at = @At("HEAD"), cancellable = true)
    private void regionium$handleSelectTrade(
        net.minecraft.network.protocol.game.ServerboundSelectTradePacket packet,
        CallbackInfo ci
    ) {
        regionium$queueIfOffRegion(ci, () ->
            ((ServerGamePacketListenerImpl) (Object) this).handleSelectTrade(packet));
    }

    @Inject(method = "handleRenameItem", at = @At("HEAD"), cancellable = true)
    private void regionium$handleRenameItem(
        net.minecraft.network.protocol.game.ServerboundRenameItemPacket packet,
        CallbackInfo ci
    ) {
        regionium$queueIfOffRegion(ci, () ->
            ((ServerGamePacketListenerImpl) (Object) this).handleRenameItem(packet));
    }

    @Inject(method = "handleEditBook", at = @At("HEAD"), cancellable = true)
    private void regionium$handleEditBook(
        net.minecraft.network.protocol.game.ServerboundEditBookPacket packet,
        CallbackInfo ci
    ) {
        regionium$queueIfOffRegion(ci, () ->
            ((ServerGamePacketListenerImpl) (Object) this).handleEditBook(packet));
    }

    private void regionium$queueIfOffRegion(CallbackInfo ci, Runnable action) {
        if (!regionium$onRegionThread()) {
            ci.cancel();
            Regionium.scheduler().executeEntity(player, action);
        }
    }

    private boolean regionium$onRegionThread() {
        return dev.pandor.regionium.core.RegioniumContext.isRegionThread()
            && Regionium.scheduler().isOwnedByCurrentRegion(player);
    }
}
