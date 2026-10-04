package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheRegioniumMixin {
    @Shadow private ServerLevel level;
    @Shadow private ChunkMap chunkMap;

    /**
     * ServerLevel.tick() is the region tick body now. Its ChunkSource must
     * therefore not run global ChunkMap maintenance or a second global tick.
     *
     * The global server thread separately calls ServerChunkCache.tick(...,
     * false) for ticket/unload maintenance.
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void regionium$regionLocalChunkTick(
        java.util.function.BooleanSupplier haveTime,
        boolean tickChunks,
        CallbackInfo ci
    ) {
        if (!RegioniumContext.isRegionThread()) {
            // ServerLevel.tick() performs a true tickChunks call, but the
            // global server maintenance pass already invokes tick(false).
            // Prevent the global world tick from running a second chunk tick.
            if (tickChunks) {
                ci.cancel();
            }
            return;
        }

        var current = RegioniumContext.currentRegion();
        if (current == null) {
            ci.cancel();
            return;
        }

        if (tickChunks && level.tickRateManager().runsNormally() && !level.isDebug()) {
            int tickSpeed = level.getGameRules().get(
                net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED
            );

            chunkMap.forEachBlockTickingChunk(chunk -> {
                long packed = chunk.getPos().pack();
                if (Regionium.scheduler().chunkLeases().owner(level, packed) == current) {
                    Regionium.scheduler().beginChunkExecution(level, packed);
                    try {
                        level.tickChunk(chunk, tickSpeed);
                    } finally {
                        Regionium.scheduler().endChunkExecution(level, packed);
                    }
                }
            });
        }

        ci.cancel();
    }
}
