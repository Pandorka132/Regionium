package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerLevelPlayerTickMixin {
    /*
     * ServerPlayer.doTick() is part of the normal EntityTickList traversal.
     * ServerLevelEntityTickRegioniumMixin already moves that traversal to the
     * owning region. Re-dispatching doTick here would recurse through the
     * mailbox and, worse, used a hard-coded region id.
     *
     * Keep this mixin as a compatibility hook, but deliberately do not
     * intercept the vanilla player tick.
     */
}
