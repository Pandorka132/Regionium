package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

@Mixin(RedstoneTorchBlock.class)
public abstract class RedstoneTorchBlockRegioniumMixin {
    @Redirect(
        method = "tick",
        at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;")
    )
    private Object regionium$getRegionToggleHistory(
        Map<?, ?> togglesByLevel,
        Object key,
        BlockState state,
        ServerLevel level,
        BlockPos pos,
        RandomSource random
    ) {
        RegioniumRegion owner = Regionium.scheduler().regionizer().owner(level, ChunkPos.containing(pos).pack());
        if (owner != null && RegioniumContext.currentRegion() == owner) {
            return owner.worldData().redstoneUpdateInfos();
        }
        return togglesByLevel.get(key);
    }

    @Redirect(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getGameTime()J")
    )
    private long regionium$useOwningRegionClock(
        ServerLevel level,
        BlockState state,
        ServerLevel serverLevel,
        BlockPos pos,
        RandomSource random
    ) {
        RegioniumRegion owner = Regionium.scheduler().regionizer().owner(serverLevel, ChunkPos.containing(pos).pack());
        if (owner != null && RegioniumContext.currentRegion() == owner) {
            return owner.worldData().redstoneTime();
        }
        return level.getGameTime();
    }

    @Inject(
        method = "isToggledTooFrequently(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Z)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void regionium$useRegionToggleHistory(
        Level level,
        BlockPos pos,
        boolean add,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        RegioniumRegion owner = Regionium.scheduler().regionizer().owner(serverLevel, ChunkPos.containing(pos).pack());
        if (owner == null || RegioniumContext.currentRegion() != owner) {
            return;
        }

        List<RedstoneTorchBlock.Toggle> toggles = owner.worldData().redstoneUpdateInfos();
        long now = owner.worldData().redstoneTime();
        while (!toggles.isEmpty()
            && now - ((RedstoneTorchToggleRegioniumAccessorMixin) (Object) toggles.get(0)).regionium$getWhen() > 60L) {
            toggles.remove(0);
        }

        if (add) {
            toggles.add(new RedstoneTorchBlock.Toggle(pos.immutable(), now));
        }

        int count = 0;
        for (RedstoneTorchBlock.Toggle toggle : toggles) {
            BlockPos togglePos = ((RedstoneTorchToggleRegioniumAccessorMixin) (Object) toggle).regionium$getPos();
            if (togglePos.equals(pos) && ++count >= RedstoneTorchBlock.MAX_RECENT_TOGGLES) {
                cir.setReturnValue(true);
                return;
            }
        }

        cir.setReturnValue(false);
    }
}
