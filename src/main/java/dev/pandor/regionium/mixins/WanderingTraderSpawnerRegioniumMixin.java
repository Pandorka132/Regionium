package dev.pandor.regionium.mixins;

import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.access.WanderingTraderDataRegioniumAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner;
import net.minecraft.world.level.saveddata.WanderingTraderData;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(WanderingTraderSpawner.class)
public abstract class WanderingTraderSpawnerRegioniumMixin {
    @Shadow @Final private SavedDataStorage savedDataStorage;
    @Shadow @Nullable private WanderingTraderData traderData;
    @Shadow private int tickDelay;

    @Redirect(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/npc/wanderingtrader/WanderingTraderSpawner;tickDelay:I", opcode = org.objectweb.asm.Opcodes.GETFIELD))
    private int regionium$readLocalTickDelay(WanderingTraderSpawner self) {
        var region = RegioniumContext.currentRegion();
        if (region == null) return this.tickDelay;
        return ((WanderingTraderDataRegioniumAccess) (Object) region.worldData().wanderingTraderData())
            .regionium$getTickDelay();
    }

    @Redirect(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/npc/wanderingtrader/WanderingTraderSpawner;tickDelay:I", opcode = org.objectweb.asm.Opcodes.PUTFIELD))
    private void regionium$writeLocalTickDelay(WanderingTraderSpawner self, int value) {
        var region = RegioniumContext.currentRegion();
        if (region == null) this.tickDelay = value;
        else ((WanderingTraderDataRegioniumAccess) (Object) region.worldData().wanderingTraderData())
            .regionium$setTickDelay(value);
    }

    @Redirect(method = "spawn", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getRandomPlayer()Lnet/minecraft/server/level/ServerPlayer;"))
    private ServerPlayer regionium$chooseLocalPlayer(ServerLevel level) {
        var region = RegioniumContext.currentRegion();
        if (region == null || region.level() != level) return level.getRandomPlayer();
        var players = region.worldData().players();
        return players.isEmpty() ? null : players.get(level.getRandom().nextInt(players.size()));
    }

    /**
     * @author Regionium
     * @reason Folia stores wandering-trader spawn state per region.
     */
    @Overwrite
    private WanderingTraderData getTraderData() {
        var region = RegioniumContext.currentRegion();
        if (region != null) return region.worldData().wanderingTraderData();
        if (this.traderData == null) {
            this.traderData = this.savedDataStorage.computeIfAbsent(WanderingTraderData.TYPE);
        }
        return this.traderData;
    }
}
