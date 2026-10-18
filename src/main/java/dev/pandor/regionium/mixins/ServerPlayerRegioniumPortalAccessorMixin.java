package dev.pandor.regionium.mixins;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerPlayer.class)
public interface ServerPlayerRegioniumPortalAccessorMixin {
    @Accessor("isChangingDimension")
    void regionium$setChangingDimension(boolean changing);

    @Accessor("enteredNetherPosition")
    void regionium$setEnteredNetherPosition(Vec3 position);

    @Accessor("lastSentExp")
    void regionium$setLastSentExperience(int experience);

    @Accessor("lastSentHealth")
    void regionium$setLastSentHealth(float health);

    @Accessor("lastSentFood")
    void regionium$setLastSentFood(int food);

}
