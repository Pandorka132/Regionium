package dev.pandor.regionium.mixins;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Makes Level's vanilla thread-affinity follow the Regionium worker while a
 * ServerLevel is executing there. Vanilla uses this guard in APIs such as
 * Level#getBlockEntity(), so leaving it bound to the server thread makes
 * worker-side piston/redstone logic observe missing block entities.
 */
@Mixin(Level.class)
public abstract class LevelThreadMixin implements dev.pandor.regionium.LevelThreadAccess {
    @Mutable
    @Shadow
    @Final
    private Thread thread;

    @Unique
    public Thread regionium$getThread() {
        return thread;
    }

    @Unique
    public void regionium$setThread(Thread thread) {
        this.thread = thread;
    }
}
