package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumPortalChunkAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.saveddata.maps.MapBanner;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

@Mixin(MapItemSavedData.class)
public abstract class MapItemSavedDataRegioniumThreadSafetyMixin {
    @WrapMethod(method = "locked")
    private MapItemSavedData regionium$syncLockedCopy(Operation<MapItemSavedData> original) {
        synchronized (this) {
            return original.call();
        }
    }

    @WrapMethod(method = "scaled")
    private MapItemSavedData regionium$syncScaledCopy(Operation<MapItemSavedData> original) {
        synchronized (this) {
            return original.call();
        }
    }

    @WrapMethod(method = "tickCarriedBy")
    private void regionium$syncPlayerTick(Player player, ItemStack stack, @Nullable ItemFrame frame, Operation<Void> original) {
        if (RegioniumContext.currentRegion() != null && !RegioniumContext.isCurrentThreadFor((net.minecraft.world.entity.Entity) player)) {
            throw new IllegalStateException("Map player tick executed outside its owning region");
        }
        synchronized (this) {
            original.call(player, stack, frame);
        }
    }

    @Redirect(
        method = "toggleBanner",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/saveddata/maps/MapBanner;fromWorld(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/saveddata/maps/MapBanner;")
    )
    private MapBanner regionium$avoidForeignBannerRead(BlockGetter level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel && RegioniumContext.currentRegion() != null) {
            if (!RegioniumContext.ownsBlockNeighborhood(serverLevel, pos, 0)) {
                return null;
            }
            ChunkAccess chunk = RegioniumPortalChunkAccess.getLoadedChunk(
                serverLevel, pos.getX() >> 4, pos.getZ() >> 4,
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL
            );
            if (chunk == null) {
                return null;
            }
        }
        return MapBanner.fromWorld(level, pos);
    }

    @WrapMethod(method = "getUpdatePacket")
    private @Nullable Packet<?> regionium$syncUpdatePacket(MapId id, Player player, Operation<@Nullable Packet<?>> original) {
        synchronized (this) {
            return original.call(id, player);
        }
    }

    @WrapMethod(method = "getHoldingPlayer")
    private MapItemSavedData.HoldingPlayer regionium$syncGetHoldingPlayer(Player player, Operation<MapItemSavedData.HoldingPlayer> original) {
        synchronized (this) {
            return original.call(player);
        }
    }

    @WrapMethod(method = "toggleBanner")
    private boolean regionium$syncToggleBanner(LevelAccessor level, BlockPos pos, Operation<Boolean> original) {
        synchronized (this) {
            return original.call(level, pos);
        }
    }

    @WrapMethod(method = "checkBanners")
    private void regionium$syncCheckBanners(BlockGetter level, int x, int z, Operation<Void> original) {
        synchronized (this) {
            original.call(level, x, z);
        }
    }

    @WrapMethod(method = "removedFromFrame")
    private void regionium$syncRemoveFrame(BlockPos pos, int entityId, Operation<Void> original) {
        synchronized (this) {
            original.call(pos, entityId);
        }
    }

    @WrapMethod(method = "updateColor")
    private boolean regionium$syncUpdateColor(int x, int y, byte color, Operation<Boolean> original) {
        synchronized (this) {
            return original.call(x, y, color);
        }
    }

    @WrapMethod(method = "setColor")
    private void regionium$syncSetColor(int x, int y, byte color, Operation<Void> original) {
        synchronized (this) {
            original.call(x, y, color);
        }
    }

    @WrapMethod(method = "setColorsDirty")
    private void regionium$syncSetColorsDirty(int x, int y, Operation<Void> original) {
        synchronized (this) {
            original.call(x, y);
        }
    }

    @WrapMethod(method = "setDecorationsDirty")
    private void regionium$syncSetDecorationsDirty(Operation<Void> original) {
        synchronized (this) {
            original.call();
        }
    }

    @WrapMethod(method = "addClientSideDecorations")
    private void regionium$syncClientDecorations(List<MapDecoration> decorations, Operation<Void> original) {
        synchronized (this) {
            original.call(decorations);
        }
    }

    @WrapMethod(method = "isTrackedCountOverLimit")
    private boolean regionium$syncTrackedCount(int limit, Operation<Boolean> original) {
        synchronized (this) {
            return original.call(limit);
        }
    }
}
