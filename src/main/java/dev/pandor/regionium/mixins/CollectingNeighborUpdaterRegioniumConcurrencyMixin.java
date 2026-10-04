package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.redstone.NeighborUpdater;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Gives CollectingNeighborUpdater each execution thread's own mutable state.
 *
 * Vanilla stores stack/addedThisLayer/count directly on one Level-wide updater.
 * Regionium has multiple region workers entering that updater concurrently,
 * so sharing those fields corrupts the redstone state machine.
 */
@Mixin(CollectingNeighborUpdater.class)
public abstract class CollectingNeighborUpdaterRegioniumConcurrencyMixin {
    @Unique
    private final ThreadLocal<ArrayDeque<Object>> regionium$stack =
        ThreadLocal.withInitial(ArrayDeque::new);

    @Unique
    private final ThreadLocal<List<Object>> regionium$addedThisLayer =
        ThreadLocal.withInitial(ArrayList::new);

    @Unique
    private final ThreadLocal<int[]> regionium$count =
        ThreadLocal.withInitial(() -> new int[1]);

    @Unique
    private final ThreadLocal<int[]> regionium$runDepth =
        ThreadLocal.withInitial(() -> new int[1]);

    @Redirect(
        method = {"addAndRun", "runUpdates"},
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/world/level/redstone/CollectingNeighborUpdater;stack:Ljava/util/ArrayDeque;",
            opcode = Opcodes.GETFIELD
        )
    )
    private ArrayDeque regionium$getStack(CollectingNeighborUpdater self) {
        return regionium$stack.get();
    }

    @Redirect(
        method = {"addAndRun", "runUpdates"},
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/world/level/redstone/CollectingNeighborUpdater;addedThisLayer:Ljava/util/List;",
            opcode = Opcodes.GETFIELD
        )
    )
    private List regionium$getAddedThisLayer(CollectingNeighborUpdater self) {
        return regionium$addedThisLayer.get();
    }

    @Redirect(
        method = {"addAndRun", "runUpdates"},
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/world/level/redstone/CollectingNeighborUpdater;count:I",
            opcode = Opcodes.GETFIELD
        )
    )
    private int regionium$getCount(CollectingNeighborUpdater self) {
        return regionium$count.get()[0];
    }

    @Redirect(
        method = {"addAndRun", "runUpdates"},
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/world/level/redstone/CollectingNeighborUpdater;count:I",
            opcode = Opcodes.PUTFIELD
        )
    )
    private void regionium$setCount(CollectingNeighborUpdater self, int value) {
        regionium$count.get()[0] = value;
    }

    @Inject(method = "runUpdates", at = @At("HEAD"))
    private void regionium$enterRunUpdates(CallbackInfo ci) {
        regionium$runDepth.get()[0]++;
    }

    @Inject(method = "runUpdates", at = @At("RETURN"))
    private void regionium$cleanupThreadLocalState(CallbackInfo ci) {
        int[] depth = regionium$runDepth.get();
        depth[0]--;
        if (depth[0] > 0) {
            return;
        }

        regionium$stack.get().clear();
        regionium$addedThisLayer.get().clear();
        regionium$count.get()[0] = 0;
        depth[0] = 0;
    }
}

/**
 * Folia-style boundary guard: a region worker must never execute a neighbor
 * callback against a block owned by another region.
 */
@Mixin(NeighborUpdater.class)
interface RegioniumNeighborUpdaterGuardMixin {
    @Inject(
        method = "executeUpdate",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void regionium$guardExecuteUpdate(
        Level level,
        net.minecraft.world.level.block.state.BlockState state,
        BlockPos pos,
        net.minecraft.world.level.block.Block block,
        net.minecraft.world.level.redstone.Orientation orientation,
        boolean movedByPiston,
        CallbackInfo ci
    ) {
        if (level instanceof ServerLevel serverLevel
            && RegioniumContext.isRegionThread()
            && !Regionium.scheduler().isCurrentRegionFor(serverLevel, pos)) {
            ci.cancel();
        }
    }

    @Inject(
        method = "executeShapeUpdate",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void regionium$guardExecuteShapeUpdate(
        net.minecraft.world.level.LevelAccessor level,
        Direction direction,
        BlockPos pos,
        BlockPos neighborPos,
        net.minecraft.world.level.block.state.BlockState neighborState,
        int flags,
        int updateLimit,
        CallbackInfo ci
    ) {
        if (level instanceof ServerLevel serverLevel
            && RegioniumContext.isRegionThread()
            && (!Regionium.scheduler().isCurrentRegionFor(serverLevel, pos)
                || !Regionium.scheduler().isCurrentRegionFor(serverLevel, neighborPos))) {
            ci.cancel();
        }
    }
}

/**
 * Prevents MultiNeighborUpdate from reading a foreign region's live block
 * state before the final NeighborUpdater dispatch guard gets a chance to run.
 */
@Mixin(targets = "net.minecraft.world.level.redstone.CollectingNeighborUpdater$MultiNeighborUpdate")
abstract class RegioniumMultiNeighborUpdateMixin {
    @Shadow @Final private BlockPos sourcePos;
    @Shadow private int idx;
    @Shadow @Final private Direction skipDirection;

    @Inject(method = "runNext", at = @At("HEAD"), cancellable = true)
    private void regionium$guardNextNeighbor(
        Level level,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (!(level instanceof ServerLevel serverLevel) || !RegioniumContext.isRegionThread()) {
            return;
        }

        Direction[] order = NeighborUpdater.UPDATE_ORDER;
        if (idx >= order.length) {
            cir.setReturnValue(false);
            return;
        }

        BlockPos target = sourcePos.relative(order[idx]);
        if (Regionium.scheduler().isCurrentRegionFor(serverLevel, target)) {
            return;
        }

        idx++;
        if (idx < order.length && order[idx] == skipDirection) {
            idx++;
        }

        cir.setReturnValue(idx < order.length);
    }
}

@Mixin(targets = "net.minecraft.world.level.redstone.CollectingNeighborUpdater$ShapeUpdate")
abstract class RegioniumShapeNeighborUpdateMixin {
    @Shadow public abstract BlockPos neighborPos();

    @Inject(method = "runNext", at = @At("HEAD"), cancellable = true)
    private void regionium$guardShapeNeighbor(
        Level level,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (level instanceof ServerLevel serverLevel
            && RegioniumContext.isRegionThread()
            && !Regionium.scheduler().isCurrentRegionFor(serverLevel, neighborPos())) {
            cir.setReturnValue(false);
        }
    }
}

@Mixin(targets = "net.minecraft.world.level.redstone.CollectingNeighborUpdater$SimpleNeighborUpdate")
abstract class RegioniumSimpleNeighborUpdateMixin {
    @Shadow public abstract BlockPos pos();

    @Inject(method = "runNext", at = @At("HEAD"), cancellable = true)
    private void regionium$guardSimpleNeighbor(
        Level level,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (level instanceof ServerLevel serverLevel
            && RegioniumContext.isRegionThread()
            && !Regionium.scheduler().isCurrentRegionFor(serverLevel, pos())) {
            cir.setReturnValue(false);
        }
    }
}

@Mixin(targets = "net.minecraft.world.level.redstone.CollectingNeighborUpdater$FullNeighborUpdate")
abstract class RegioniumFullNeighborUpdateMixin {
    @Shadow public abstract BlockPos pos();

    @Inject(method = "runNext", at = @At("HEAD"), cancellable = true)
    private void regionium$guardFullNeighbor(
        Level level,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (level instanceof ServerLevel serverLevel
            && RegioniumContext.isRegionThread()
            && !Regionium.scheduler().isCurrentRegionFor(serverLevel, pos())) {
            cir.setReturnValue(false);
        }
    }
}
