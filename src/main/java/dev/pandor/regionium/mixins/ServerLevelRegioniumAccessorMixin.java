package dev.pandor.regionium.mixins;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.server.players.SleepStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerLevel.class)
public interface ServerLevelRegioniumAccessorMixin {
    @Accessor("blockTicks") LevelTicks<Block> regionium$getBlockTicks();
    @Accessor("fluidTicks") LevelTicks<Fluid> regionium$getFluidTicks();
    @Accessor("sleepStatus") SleepStatus regionium$getSleepStatus();

    @Accessor("tickTime") boolean regionium$isTickTime();
    @Accessor("entityManager") PersistentEntitySectionManager<Entity> regionium$getEntityManager();

    @org.spongepowered.asm.mixin.gen.Invoker("advanceWeatherCycle")
    void regionium$advanceWeatherCycle();

    @org.spongepowered.asm.mixin.gen.Invoker("tickTime")
    void regionium$tickTime();

    @org.spongepowered.asm.mixin.gen.Invoker("wakeUpAllPlayers")
    void regionium$wakeUpAllPlayers();
}
