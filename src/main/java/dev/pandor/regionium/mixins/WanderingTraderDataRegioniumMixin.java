package dev.pandor.regionium.mixins;

import dev.pandor.regionium.access.WanderingTraderDataRegioniumAccess;
import net.minecraft.world.level.saveddata.WanderingTraderData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(WanderingTraderData.class)
public abstract class WanderingTraderDataRegioniumMixin implements WanderingTraderDataRegioniumAccess {
    @Unique private int regionium$tickDelay = 1200;

    @Override
    public int regionium$getTickDelay() {
        return this.regionium$tickDelay;
    }

    @Override
    public void regionium$setTickDelay(int value) {
        this.regionium$tickDelay = value;
    }
}
