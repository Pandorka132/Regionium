package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import net.minecraft.server.commands.SummonCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Mixin(SummonCommand.class)
public abstract class SummonCommandRegioniumMixin {
@org.spongepowered.asm.mixin.Unique
    private static final ConcurrentMap<Entity, Runnable> PENDING_FINALIZE = new ConcurrentHashMap<>();
    @Redirect(
        method = "createEntity",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Mob;finalizeSpawn(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/DifficultyInstance;Lnet/minecraft/world/entity/EntitySpawnReason;Lnet/minecraft/world/entity/SpawnGroupData;)Lnet/minecraft/world/entity/SpawnGroupData;"
        )
    )
    private static SpawnGroupData regionium$finalizeSpawnOnOwner(
        Mob mob,
        ServerLevelAccessor level,
        DifficultyInstance difficulty,
        EntitySpawnReason reason,
        SpawnGroupData spawnData
    ) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return mob.finalizeSpawn(level, difficulty, reason, spawnData);
        }
        if (RegioniumContext.currentRegion() != null
            && Regionium.scheduler().isCurrentRegionFor(serverLevel, mob.blockPosition())) {
            return mob.finalizeSpawn(level, difficulty, reason, spawnData);
        }
        PENDING_FINALIZE.put(mob, () -> mob.finalizeSpawn(level, difficulty, reason, spawnData));
        return spawnData;
    }

    @Redirect(
        method = "createEntity",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tryAddFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)Z"
        )
    )
    private static boolean regionium$addEntityOnOwner(ServerLevel level, Entity entity) {
        if (RegioniumContext.currentRegion() != null
            && Regionium.scheduler().isCurrentRegionFor(level, entity.blockPosition())) {
            return level.tryAddFreshEntityWithPassengers(entity);
        }

        CompletableFuture<Boolean> spawn = Regionium.scheduler().withChunksAsync(
            level,
            List.of(new ChunkPos(entity.blockPosition().getX() >> 4, entity.blockPosition().getZ() >> 4)),
            ChunkStatus.FULL,
            chunks -> {
                CompletableFuture<Boolean> result = new CompletableFuture<>();
                Regionium.scheduler().executeWhenOwned(level, entity.blockPosition(), () -> {
                    Runnable finalize = PENDING_FINALIZE.remove(entity);
                    if (finalize != null) {
                        finalize.run();
                    }
                    result.complete(level.tryAddFreshEntityWithPassengers(entity));
                });
                return result;
            }
        );
        spawn.exceptionally(error -> {
            PENDING_FINALIZE.remove(entity);
            Regionium.LOGGER.error("Failed to spawn command entity in its owning region", error);
            return false;
        });
        // Folia queues entity insertion and reports the command as accepted;
        // insertion itself remains confined to the owning region.
        return true;
    }
}
