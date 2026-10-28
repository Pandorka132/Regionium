package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.GoToPotentialJobSite;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.Optional;

@Mixin(GoToPotentialJobSite.class)
public abstract class GoToPotentialJobSiteRegioniumMixin {
    /**
     * @author Regionium
     * @reason A villager may release a POI owned by another region when its job-site behavior stops.
     */
    @Overwrite
    protected void stop(ServerLevel level, Villager body, long timestamp) {
        Optional<GlobalPos> potentialJobSite = body.getBrain().getMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
        potentialJobSite.ifPresent(globalPos -> {
            BlockPos pos = globalPos.pos();
            ServerLevel poiLevel = level.getServer().getLevel(globalPos.dimension());
            if (poiLevel != null) {
                Regionium.scheduler().executeWhenOwned(poiLevel, pos, () -> {
                    PoiManager manager = poiLevel.getPoiManager();
                    if (manager.exists(pos, ignored -> true)) {
                        manager.release(pos);
                    }
                    poiLevel.debugSynchronizers().updatePoi(pos);
                });
            }
        });
        body.getBrain().eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
    }
}
