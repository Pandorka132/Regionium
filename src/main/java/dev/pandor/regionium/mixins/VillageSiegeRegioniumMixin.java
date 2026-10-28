package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.village.VillageSiege;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.Coerce;

@Mixin(VillageSiege.class)
public abstract class VillageSiegeRegioniumMixin {
    @Unique
    private static final RegioniumWorldData.VillageSiegeState REGIONIUM$GLOBAL_STATE = new RegioniumWorldData.VillageSiegeState();

    @Inject(method = "tick", at = @At("HEAD"))
    private void regionium$resetForeignSiege(ServerLevel level, boolean spawnEnemies, CallbackInfo ci) {
        var region = RegioniumContext.currentRegion();
        if (region == null || region.level() != level) return;
        RegioniumWorldData.VillageSiegeState state = region.worldData().villageSiegeState();
        Object siegeState = state.siegeState == null ? REGIONIUM$GLOBAL_STATE.siegeState : state.siegeState;
        if (siegeState instanceof Enum<?> value && !value.name().equals("SIEGE_DONE")
            && !Regionium.scheduler().regionizer().ownsChunkSquare(
                region, level, state.spawnX >> 4, state.spawnZ >> 4, 8
            )) {
            state.siegeState = REGIONIUM$GLOBAL_STATE.siegeState;
            state.hasSetupSiege = false;
            state.zombiesToSpawn = 0;
            state.nextSpawnTime = 0;
        }
    }

    @Redirect(method = "tryToSetupSiege", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;players()Ljava/util/List;"))
    private List<ServerPlayer> regionium$localPlayers(ServerLevel level) {
        var region = RegioniumContext.currentRegion();
        return region != null && region.level() == level ? region.worldData().players() : level.players();
    }

    @Redirect(method = "<init>", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/ai/village/VillageSiege;siegeState:Lnet/minecraft/world/entity/ai/village/VillageSiege$State;", opcode = Opcodes.PUTFIELD))
    private void regionium$captureInitialState(VillageSiege self, @Coerce Object value) {
        REGIONIUM$GLOBAL_STATE.siegeState = value;
    }

    @Redirect(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/ai/village/VillageSiege;hasSetupSiege:Z", opcode = Opcodes.GETFIELD))
    private boolean regionium$getHasSetupSiege(VillageSiege self) {
        RegioniumWorldData.VillageSiegeState state = regionium$state();
        return state.hasSetupSiege;
    }

    @Redirect(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/ai/village/VillageSiege;hasSetupSiege:Z", opcode = Opcodes.PUTFIELD))
    private void regionium$setHasSetupSiege(VillageSiege self, boolean value) {
        RegioniumWorldData.VillageSiegeState state = regionium$state();
        state.hasSetupSiege = value;
    }

    @Redirect(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/ai/village/VillageSiege;siegeState:Lnet/minecraft/world/entity/ai/village/VillageSiege$State;", opcode = Opcodes.GETFIELD))
    private @Coerce Object regionium$getSiegeState(VillageSiege self) {
        RegioniumWorldData.VillageSiegeState state = regionium$state();
        return state.siegeState == null ? REGIONIUM$GLOBAL_STATE.siegeState : state.siegeState;
    }

    @Redirect(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/ai/village/VillageSiege;siegeState:Lnet/minecraft/world/entity/ai/village/VillageSiege$State;", opcode = Opcodes.PUTFIELD))
    private void regionium$setSiegeState(VillageSiege self, @Coerce Object value) {
        RegioniumWorldData.VillageSiegeState state = regionium$state();
        state.siegeState = value;
    }

    @Redirect(method = {"tick", "tryToSetupSiege"}, at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/ai/village/VillageSiege;zombiesToSpawn:I", opcode = Opcodes.GETFIELD))
    private int regionium$getZombiesToSpawn(VillageSiege self) {
        RegioniumWorldData.VillageSiegeState state = regionium$state();
        return state == null ? 0 : state.zombiesToSpawn;
    }

    @Redirect(method = {"tick", "tryToSetupSiege"}, at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/ai/village/VillageSiege;zombiesToSpawn:I", opcode = Opcodes.PUTFIELD))
    private void regionium$setZombiesToSpawn(VillageSiege self, int value) {
        RegioniumWorldData.VillageSiegeState state = regionium$state();
        if (state != null) state.zombiesToSpawn = value;
    }

    @Redirect(method = {"tick", "tryToSetupSiege"}, at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/ai/village/VillageSiege;nextSpawnTime:I", opcode = Opcodes.GETFIELD))
    private int regionium$getNextSpawnTime(VillageSiege self) {
        RegioniumWorldData.VillageSiegeState state = regionium$state();
        return state == null ? 0 : state.nextSpawnTime;
    }

    @Redirect(method = {"tick", "tryToSetupSiege"}, at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/ai/village/VillageSiege;nextSpawnTime:I", opcode = Opcodes.PUTFIELD))
    private void regionium$setNextSpawnTime(VillageSiege self, int value) {
        RegioniumWorldData.VillageSiegeState state = regionium$state();
        if (state != null) state.nextSpawnTime = value;
    }

    @Redirect(method = {"tryToSetupSiege", "trySpawn"}, at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/ai/village/VillageSiege;spawnX:I", opcode = Opcodes.GETFIELD))
    private int regionium$getSpawnX(VillageSiege self) {
        RegioniumWorldData.VillageSiegeState state = regionium$state();
        return state == null ? 0 : state.spawnX;
    }

    @Redirect(method = {"tryToSetupSiege", "trySpawn"}, at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/ai/village/VillageSiege;spawnX:I", opcode = Opcodes.PUTFIELD))
    private void regionium$setSpawnX(VillageSiege self, int value) {
        RegioniumWorldData.VillageSiegeState state = regionium$state();
        if (state != null) state.spawnX = value;
    }

    @Redirect(method = {"tryToSetupSiege", "trySpawn"}, at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/ai/village/VillageSiege;spawnY:I", opcode = Opcodes.GETFIELD))
    private int regionium$getSpawnY(VillageSiege self) {
        RegioniumWorldData.VillageSiegeState state = regionium$state();
        return state == null ? 0 : state.spawnY;
    }

    @Redirect(method = {"tryToSetupSiege", "trySpawn"}, at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/ai/village/VillageSiege;spawnY:I", opcode = Opcodes.PUTFIELD))
    private void regionium$setSpawnY(VillageSiege self, int value) {
        RegioniumWorldData.VillageSiegeState state = regionium$state();
        if (state != null) state.spawnY = value;
    }

    @Redirect(method = {"tryToSetupSiege", "trySpawn"}, at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/ai/village/VillageSiege;spawnZ:I", opcode = Opcodes.GETFIELD))
    private int regionium$getSpawnZ(VillageSiege self) {
        RegioniumWorldData.VillageSiegeState state = regionium$state();
        return state == null ? 0 : state.spawnZ;
    }

    @Redirect(method = {"tryToSetupSiege", "trySpawn"}, at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/ai/village/VillageSiege;spawnZ:I", opcode = Opcodes.PUTFIELD))
    private void regionium$setSpawnZ(VillageSiege self, int value) {
        regionium$state().spawnZ = value;
    }

    @Unique
    private static RegioniumWorldData.VillageSiegeState regionium$state() {
        var region = RegioniumContext.currentRegion();
        return region == null ? REGIONIUM$GLOBAL_STATE : region.worldData().villageSiegeState();
    }
}
