package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import java.util.function.Predicate;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.server.commands.SetBlockCommand;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Mixin(SetBlockCommand.class)
public abstract class SetBlockCommandRegioniumMixin {
    @WrapMethod(method = "setBlock")
    private static int regionium$routeSetBlock(
        CommandSourceStack source,
        BlockPos pos,
        BlockInput block,
        SetBlockCommand.Mode mode,
        Predicate<BlockInWorld> predicate,
        boolean strict,
        Operation<Integer> original
    ) throws CommandSyntaxException {
        ServerLevel level = source.getLevel();
        if (RegioniumContext.currentRegion() != null
            && Regionium.scheduler().isCurrentRegionFor(level, pos)) {
            return original.call(source, pos, block, mode, predicate, strict);
        }

        CompletableFuture<Integer> operation = Regionium.scheduler().withChunksAsync(
            level,
            List.of(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4)),
            ChunkStatus.FULL,
            chunks -> {
                CompletableFuture<Integer> result = new CompletableFuture<>();
                Regionium.scheduler().executeWhenOwned(level, pos, () -> {
                    try {
                        result.complete(original.call(source, pos, block, mode, predicate, strict));
                    } catch (Throwable throwable) {
                        if (throwable instanceof CommandSyntaxException exception) {
                            source.sendFailure(Component.literal(exception.getMessage()));
                            result.complete(0);
                        } else {
                            result.completeExceptionally(throwable);
                            if (throwable instanceof RuntimeException runtimeException) {
                                throw runtimeException;
                            }
                            if (throwable instanceof Error error) {
                                throw error;
                            }
                            throw new RuntimeException(throwable);
                        }
                    }
                });
                return result;
            }
        );
        operation.exceptionally(error -> {
            source.sendFailure(Component.literal("Failed to execute setblock in the owning region: " + error.getMessage()));
            return 0;
        });
        return 1;
    }
}
