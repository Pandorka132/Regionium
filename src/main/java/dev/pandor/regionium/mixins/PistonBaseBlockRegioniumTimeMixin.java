package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockRegioniumTimeMixin {
    @WrapOperation(
        method = "checkIfExtend",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getGameTime()J"
        )
    )
    private long regionium$comparePistonAtRegionTime(
        Level level,
        Operation<Long> original,
        Level ignoredLevel,
        BlockPos pos,
        BlockState state
    ) {
        if (level instanceof ServerLevel serverLevel) {
            RegioniumRegion owner = Regionium.scheduler().regionizer().owner(
                serverLevel, net.minecraft.world.level.ChunkPos.containing(pos).pack()
            );
            if (owner != null) {
                return owner.worldData().redstoneTime();
            }
        }
        return original.call(level);
    }
}
