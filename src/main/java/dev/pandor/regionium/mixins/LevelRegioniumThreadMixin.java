package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.levelgen.ThreadSafeLegacyRandomSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Makes Level#getBlockEntity's vanilla thread check Regionium-aware. */
@Mixin(Level.class)
public abstract class LevelRegioniumThreadMixin {
    @Shadow @Final private Thread thread;
    @Shadow @Final @Mutable private RandomSource random;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void regionium$makeRandomThreadSafe(CallbackInfo ci) {
        this.random = new ThreadSafeLegacyRandomSource(System.nanoTime());
    }

    @WrapOperation(
        method = "getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;)Lnet/minecraft/world/level/chunk/ChunkAccess;"
        )
    )
    private ChunkAccess regionium$unwrapImposter(
        Level level,
        int x,
        int z,
        ChunkStatus status,
        Operation<ChunkAccess> original
    ) {
        ChunkAccess chunk = original.call(level, x, z, status);
        if (chunk instanceof ImposterProtoChunk imposter) {
            Regionium.LOGGER.trace("[MT] unwrapping ImposterProtoChunk at [{}, {}] on {}", x, z, Thread.currentThread().getName());
            return imposter.getWrapped();
        }
        return chunk;
    }

    @WrapOperation(
        method = "getBlockEntity",
        at = @At(value = "INVOKE", target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;")
    )
    private Thread regionium$logicalCurrentThread(Operation<Thread> original) {
        return RegioniumContext.isRegionThread() ? thread : original.call();
    }

    @Inject(method = "addBlockEntityTicker", at = @At("HEAD"), cancellable = true)
    private void regionium$queueTickerRegistration(
        net.minecraft.world.level.block.entity.TickingBlockEntity ticker,
        CallbackInfo ci
    ) {
        if (!((Object) this instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }

        var owner = Regionium.scheduler().regionizer().owner(
            level,
            net.minecraft.world.level.ChunkPos.containing(ticker.getPos()).pack()
        );
        if (owner == null) {
            // Chunk lifecycle can register its tickers before Regionium has
            // published an owner. Leave vanilla's wrapper intact; addChunk()
            // will adopt it from LevelChunk.tickersInLevel when ownership is
            // established.
            return;
        }

        var current = RegioniumContext.isRegionThread()
            ? RegioniumContext.currentRegion()
            : null;
        if (owner == current) {
            owner.worldData().addBlockEntityTicker(ticker);
            ci.cancel();
            return;
        }

        if (!owner.isRetired() && owner.execute(() -> {
            if (!ticker.isRemoved()
                && Regionium.scheduler().regionizer().owner(
                    level,
                    net.minecraft.world.level.ChunkPos.containing(ticker.getPos()).pack()
                ) == owner) {
                owner.worldData().addBlockEntityTicker(ticker);
            }
        })) {
            ci.cancel();
        }
    }
}
