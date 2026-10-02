package dev.pandor.regionium.mixins;

import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Temporarily moves a ServerChunkCache's vanilla main-thread identity to the
 * Regionium worker that exclusively owns the enclosing ServerLevel tick.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheThreadMixin implements dev.pandor.regionium.ServerChunkCacheThreadAccess {
    @Mutable
    @Shadow
    @Final
    private Thread mainThread;

    @Unique
    public Thread regionium$getMainThread() {
        return mainThread;
    }

    @Unique
    public void regionium$setMainThread(Thread thread) {
        mainThread = thread;
    }
}
