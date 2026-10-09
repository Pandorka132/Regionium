package dev.pandor.regionium.mixins;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerLevel.class)
public interface ServerLevelRegioniumTimeAccessor {
    @Accessor("tickTime")
    boolean regionium$isTickTime();
}
