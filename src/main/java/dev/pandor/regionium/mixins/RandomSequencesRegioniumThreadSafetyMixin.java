package dev.pandor.regionium.mixins;

import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.RandomSequence;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(RandomSequences.class)
public abstract class RandomSequencesRegioniumThreadSafetyMixin {
    @Shadow @Final @Mutable private Map<Identifier, RandomSequence> sequences;

    @Inject(method = {"<init>()V", "<init>(IZZLjava/util/Map;)V"}, at = @At("RETURN"))
    private void regionium$useConcurrentSequenceMap(CallbackInfo ci) {
        this.sequences = new ConcurrentHashMap<>(this.sequences);
    }
}

@Mixin(targets = "net.minecraft.world.RandomSequences$DirtyMarkingRandomSource")
abstract class RandomSequencesDirtySourceRegioniumThreadSafetyMixin {
    @Shadow @Final private RandomSource random;

    @Redirect(method = "fork", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;fork()Lnet/minecraft/util/RandomSource;"))
    private RandomSource regionium$fork(RandomSource source) {
        synchronized (source) { return source.fork(); }
    }

    @Redirect(method = "forkPositional", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;forkPositional()Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;"))
    private PositionalRandomFactory regionium$forkPositional(RandomSource source) {
        synchronized (source) { return source.forkPositional(); }
    }

    @Redirect(method = "setSeed", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;setSeed(J)V"))
    private void regionium$setSeed(RandomSource source, long seed) {
        synchronized (source) { source.setSeed(seed); }
    }

    @Redirect(method = "nextInt()I", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextInt()I"))
    private int regionium$nextInt(RandomSource source) {
        synchronized (source) { return source.nextInt(); }
    }

    @Redirect(method = "nextInt(I)I", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextInt(I)I"))
    private int regionium$nextIntBounded(RandomSource source, int bound) {
        synchronized (source) { return source.nextInt(bound); }
    }

    @Redirect(method = "nextLong()J", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextLong()J"))
    private long regionium$nextLong(RandomSource source) {
        synchronized (source) { return source.nextLong(); }
    }

    @Redirect(method = "nextBoolean()Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextBoolean()Z"))
    private boolean regionium$nextBoolean(RandomSource source) {
        synchronized (source) { return source.nextBoolean(); }
    }

    @Redirect(method = "nextFloat()F", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextFloat()F"))
    private float regionium$nextFloat(RandomSource source) {
        synchronized (source) { return source.nextFloat(); }
    }

    @Redirect(method = "nextDouble()D", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextDouble()D"))
    private double regionium$nextDouble(RandomSource source) {
        synchronized (source) { return source.nextDouble(); }
    }

    @Redirect(method = "nextGaussian()D", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextGaussian()D"))
    private double regionium$nextGaussian(RandomSource source) {
        synchronized (source) { return source.nextGaussian(); }
    }
}
