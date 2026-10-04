package dev.pandor.regionium.mixins;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(ServerLevel.class)
public interface ServerLevelRegioniumBlockEventAccessorMixin {
    @Accessor("blockEvents")
    ObjectLinkedOpenHashSet<BlockEventData> regionium$getBlockEvents();

    @Accessor("blockEventsToReschedule")
    List<BlockEventData> regionium$getBlockEventsToReschedule();
}
