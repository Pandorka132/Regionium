package dev.pandor.regionium.mixins;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityRegioniumPortalInvokerMixin {
    @Invoker("handlePortal")
    void regionium$invokeHandlePortal();
}
