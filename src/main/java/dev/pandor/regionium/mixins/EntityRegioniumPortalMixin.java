package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumPortalChunkAccess;
import dev.pandor.regionium.core.RegioniumPortalDispatcher;
import dev.pandor.regionium.core.RegioniumScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.BlockUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.feature.EndGatewayFeature;
import net.minecraft.data.worldgen.features.EndFeatures;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.feature.EndPlatformFeature;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Folia-style asynchronous portal pipeline for Regionium.
 *
 * Portal destination discovery and chunk preparation happen asynchronously.
 * Entity state is only detached on its owning region and destination entities
 * are only inserted on the destination region. No global tick pause and no
 * synchronous cross-region chunk loading are used.
 */
@Mixin(Entity.class)
public abstract class EntityRegioniumPortalMixin {
    @Shadow
    private PortalProcessor portalProcess;

    @Shadow
    private void processPortalCooldown() {
        throw new AssertionError();
    }

    @Shadow
    protected abstract void removeAfterChangingDimensions();

    @Shadow
    public abstract void restoreFrom(Entity oldEntity);

    @Shadow
    public abstract void unsetRemoved();

    @Unique
    private void regionium$unsetRemoved() {
        this.unsetRemoved();
    }

    @Inject(method = "handlePortal", at = @At("HEAD"), cancellable = true)
    private void regionium$handlePortalAsync(CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        if (!(entity.level() instanceof ServerLevel source)) {
            return;
        }

        // Vanilla's getPortalDestination() is synchronous and can load POI/chunk
        // data owned by unrelated regions. Never allow that path on a server entity.
        ci.cancel();
        if (!RegioniumContext.isRegionThread()) {
            return;
        }
        this.processPortalCooldown();
        PortalProcessor processor = this.portalProcess;
        if (processor == null) {
            return;
        }

        boolean portalReady = processor.processPortalTeleportation(source, entity, entity.canUsePortal(false));
        if (!portalReady) {
            if (processor.hasExpired()) {
                this.portalProcess = null;
            }
            return;
        }

        entity.setPortalCooldown();
        if (entity.isRemoved() || entity.isPassenger() || !RegioniumPortalDispatcher.beginTransfer(entity)) {
            return;
        }

        BlockPos entry = processor.getEntryPosition().immutable();
        BlockState state = source.getBlockState(entry);
        if (!(state.getBlock() instanceof Portal portal)
            || !RegioniumPortalDispatcher.invokePortalAsync(source, entity, portal, entry)) {
            RegioniumPortalDispatcher.endTransfer(entity);
        }
    }

    @Unique
    public void regionium$exitEndCredits(ServerPlayer player) {
        RegioniumPortalDispatcher.endTransfer(player);
        player.showEndCredits();
    }

    @Unique
    public boolean regionium$startPortalTransfer(
        Entity entity, ServerLevel source, ServerLevel expectedDestination,
        Portal portal, BlockPos entry, BlockState sourcePortalState
    ) {
        // Keep the source chunk FULL and region-owned until the asynchronous
        // transfer has either completed or restored the original entity tree.
        Regionium.scheduler().withChunksAsync(source, entry, 0, ChunkStatus.FULL, sourceChunks -> {
            CompletableFuture<Void> operation = new CompletableFuture<>();
            try {
                Regionium.scheduler().executeWhenOwned(source, entry, () -> {
                    if (entity.level() != source || entity.isRemoved()) {
                        RegioniumPortalDispatcher.endTransfer(entity);
                        operation.complete(null);
                        return;
                    }

                    RegioniumPortalTransfer transfer;
                    try {
                        transfer = this.regionium$detachForPortalTransfer(entity, source, expectedDestination);
                    } catch (Throwable failure) {
                        RegioniumPortalDispatcher.endTransfer(entity);
                        operation.completeExceptionally(failure);
                        return;
                    }
                    if (transfer == null) {
                        RegioniumPortalDispatcher.endTransfer(entity);
                        operation.complete(null);
                        return;
                    }

                    try {
                        CompletableFuture<TeleportTransition> transition;
                        if (portal instanceof NetherPortalBlock) {
                            transition = regionium$findNetherDestination(entity, source, entry, sourcePortalState);
                        } else if (portal instanceof EndPortalBlock) {
                            transition = regionium$findEndDestination(entity, source);
                        } else {
                            transition = regionium$findOtherPortalDestination(entity, source, portal, entry);
                        }

                        transition.whenComplete((target, failure) -> {
                            if (failure != null) {
                                Regionium.LOGGER.error("Asynchronous portal destination lookup failed for {}", entity, failure);
                                transfer.restore("portal destination lookup failed").whenComplete((ignored, restoreFailure) -> {
                                    if (restoreFailure != null) {
                                        failure.addSuppressed(restoreFailure);
                                    }
                                    operation.completeExceptionally(failure);
                                });
                                return;
                            }
                            if (target == null) {
                                transfer.restore("portal destination lookup returned no destination")
                                    .whenComplete((ignored, restoreFailure) -> {
                                        if (restoreFailure != null) {
                                            operation.completeExceptionally(restoreFailure);
                                        } else {
                                            operation.complete(null);
                                        }
                                    });
                                return;
                            }
    
                            ServerLevel destination = target.newLevel();
                            BlockPos destinationPos = BlockPos.containing(target.position());
                            Regionium.scheduler().withChunksAsync(
                                destination, destinationPos, 2, ChunkStatus.FULL,
                                chunks -> {
                                    return transfer.complete(target);
                                }
                            ).whenComplete((ignored, placementFailure) -> {
                                if (placementFailure == null) {
                                    operation.complete(null);
                                    return;
                                }
                                Regionium.LOGGER.error(
                                    "Could not complete destination placement for portal transfer of {}",
                                    entity, placementFailure
                                );
                                transfer.restore("destination chunk preparation or placement failed")
                                    .whenComplete((restored, restoreFailure) -> {
                                        if (restoreFailure != null) {
                                            placementFailure.addSuppressed(restoreFailure);
                                        }
                                        operation.completeExceptionally(placementFailure);
                                    });
                            });
                        });
                    } catch (Throwable failure) {
                        transfer.restore("portal destination pipeline setup failed").whenComplete((ignored, restoreFailure) -> {
                            if (restoreFailure != null) {
                                failure.addSuppressed(restoreFailure);
                            }
                            operation.completeExceptionally(failure);
                        });
                    }
                });
            } catch (Throwable failure) {
                operation.completeExceptionally(failure);
            }
            return operation;
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                RegioniumPortalDispatcher.endTransfer(entity);
                Regionium.LOGGER.error("Could not retain the source region during portal transfer for {}", entity, failure);
            }
        });
        return true;
    }

    @Unique
    private CompletableFuture<TeleportTransition> regionium$findNetherDestination(
        Entity entity, ServerLevel source, BlockPos portalEntry, BlockState sourcePortalState
    ) {
        ResourceKey<Level> destinationKey = source.dimension() == Level.NETHER ? Level.OVERWORLD : Level.NETHER;
        ServerLevel destination = source.getServer().getLevel(destinationKey);
        if (destination == null) {
            return CompletableFuture.completedFuture(null);
        }

        boolean toNether = destination.dimension() == Level.NETHER;
        double scale = net.minecraft.world.level.dimension.DimensionType.getTeleportationScale(
            source.dimensionType(), destination.dimensionType()
        );
        BlockPos target = destination.getWorldBorder().clampToBounds(
            entity.getX() * scale, entity.getY(), entity.getZ() * scale
        );
        Direction.Axis sourceAxis = sourcePortalState.getOptionalValue(BlockStateProperties.HORIZONTAL_AXIS)
            .orElse(Direction.Axis.X);
        BlockUtil.FoundRectangle sourceRectangle = BlockUtil.getLargestRectangleAround(
            portalEntry, sourceAxis, 21, Direction.Axis.Y, 21,
            pos -> source.getBlockState(pos) == sourcePortalState
        );
        Vec3 relativePosition = entity.getRelativePortalPosition(sourceAxis, sourceRectangle);
        int searchRadius = toNether ? 16 : 128;
        int searchChunkRadius = Math.floorDiv(searchRadius, 16) + 1;

        return Regionium.scheduler().withChunksAsync(destination, target, searchChunkRadius, ChunkStatus.EMPTY, loaded ->
            RegioniumPortalChunkAccess.findClosestPortalPositionAsync(
                destination, target, toNether, destination.getWorldBorder()
            ).thenComposeAsync(found -> {
                if (found.isPresent()) {
                    BlockPos foundPos = found.get();
                    return Regionium.scheduler().withChunksAsync(destination, foundPos, 2, ChunkStatus.FULL, portalChunks -> {
                        CompletableFuture<TeleportTransition> built = new CompletableFuture<>();
                        Regionium.scheduler().executeWhenOwned(destination, foundPos, () -> {
                            try {
                                built.complete(regionium$makeNetherTransition(
                                    entity, destination, foundPos, sourceAxis, relativePosition
                                ));
                            } catch (Throwable failure) {
                                built.completeExceptionally(failure);
                            }
                        });
                        return built;
                    });
                }
                if (entity.isSpectator()) {
                    return CompletableFuture.completedFuture(null);
                }

                return Regionium.scheduler().withChunksAsync(destination, target, 2, ChunkStatus.FULL, createChunks -> {
                    CompletableFuture<TeleportTransition> created = new CompletableFuture<>();
                    Regionium.scheduler().executeWhenOwned(destination, target, () -> {
                        try {
                            TeleportTransition result = destination.getPortalForcer().createPortal(target, sourceAxis)
                                .map(rectangle -> regionium$makeNetherTransition(
                                    entity, destination, rectangle.minCorner, sourceAxis, relativePosition
                                ))
                                .orElseGet(() -> new TeleportTransition(
                                    destination, Vec3.atCenterOf(target), Vec3.ZERO, 90.0F, 0.0F,
                                    TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET)
                                ));
                            created.complete(result);
                        } catch (Throwable failure) {
                            created.completeExceptionally(failure);
                        }
                    });
                    return created;
                });
            })
        );
    }

    @Unique
    private static BlockState regionium$getPreparedBlockState(ServerLevel level, BlockPos pos) {
        var chunk = RegioniumPortalChunkAccess.getLoadedChunk(
            level, pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL
        );
        if (chunk == null) {
            throw new IllegalStateException(
                "Portal rectangle inspection reached an unprepared chunk at " + pos + " in " + level.dimension()
            );
        }
        return chunk.getBlockState(pos);
    }

    @Unique
    private TeleportTransition regionium$makeNetherTransition(
        Entity entity, ServerLevel destination, BlockPos portalPos,
        Direction.Axis sourceAxis, Vec3 relativePosition
    ) {
        BlockState portalState = regionium$getPreparedBlockState(destination, portalPos);
        Direction.Axis destinationAxis = portalState.getOptionalValue(BlockStateProperties.HORIZONTAL_AXIS)
            .orElse(Direction.Axis.X);
        BlockUtil.FoundRectangle rectangle = BlockUtil.getLargestRectangleAround(
            portalPos, destinationAxis, 21, Direction.Axis.Y, 21,
            pos -> regionium$getPreparedBlockState(destination, pos) == portalState
        );
        return NetherPortalBlockRegioniumInvokerMixin.regionium$createDimensionTransition(
            destination, rectangle, sourceAxis, relativePosition, entity,
            TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET)
        );
    }

    @Unique
    private CompletableFuture<TeleportTransition> regionium$findEndDestination(Entity entity, ServerLevel source) {
        if (source.dimension() == Level.END) {
            if (entity instanceof ServerPlayer player) {
                return regionium$findPlayerEndExitDestination(player, source);
            }

            ServerLevel destination = source.getServer().getLevel(Level.OVERWORLD);
            if (destination == null) {
                return CompletableFuture.completedFuture(null);
            }
            var respawnData = destination.getRespawnData();
            BlockPos spawn = respawnData.pos();
            return Regionium.scheduler().withChunksAsync(destination, spawn, 1, ChunkStatus.FULL, loaded -> {
                CompletableFuture<TeleportTransition> result = new CompletableFuture<>();
                Regionium.scheduler().executeWhenOwned(destination, spawn, () -> {
                    try {
                        BlockPos adjusted = entity.adjustSpawnLocation(destination, spawn);
                        result.complete(new TeleportTransition(
                            destination, Vec3.atBottomCenterOf(adjusted), Vec3.ZERO,
                            respawnData.yaw(), respawnData.pitch(),
                            false, false, Relative.union(Relative.DELTA, Relative.ROTATION),
                            TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET)
                        ));
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                });
                return result;
            });
        }

        ServerLevel destination = source.getServer().getLevel(Level.END);
        if (destination == null) {
            return CompletableFuture.completedFuture(null);
        }

        BlockPos target = ServerLevel.END_SPAWN_POINT;
        return Regionium.scheduler().withChunksAsync(destination, target, 2, ChunkStatus.FULL, loaded -> {
            CompletableFuture<TeleportTransition> result = new CompletableFuture<>();
            Regionium.scheduler().executeWhenOwned(destination, target, () -> {
                try {
                    EndPlatformFeature.createEndPlatform(destination, target.below(), true);
                    Vec3 spawn = Vec3.atBottomCenterOf(target);
                    if (entity instanceof ServerPlayer) {
                        spawn = spawn.subtract(0.0, 1.0, 0.0);
                    }
                    result.complete(new TeleportTransition(
                        destination, spawn, Vec3.ZERO, Direction.WEST.toYRot(), 0.0F,
                        false, false, Relative.union(Relative.DELTA, Set.of(Relative.X_ROT)),
                        TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET)
                    ));
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result;
        });
    }

    @Unique
    private CompletableFuture<TeleportTransition> regionium$findPlayerEndExitDestination(
        ServerPlayer player, ServerLevel source
    ) {
        ServerPlayer.RespawnConfig respawnConfig = player.getRespawnConfig();
        if (respawnConfig == null) {
            return regionium$findDefaultRespawnTransition(player, source.getServer().findRespawnDimension(), false);
        }

        ServerLevel respawnLevel = source.getServer().getLevel(respawnConfig.respawnData().dimension());
        if (respawnLevel == null) {
            return regionium$findDefaultRespawnTransition(player, source.getServer().findRespawnDimension(), true);
        }

        BlockPos respawnPos = respawnConfig.respawnData().pos();
        return Regionium.scheduler().withChunksAsync(respawnLevel, respawnPos, 1, ChunkStatus.FULL, loaded -> {
            CompletableFuture<TeleportTransition> result = new CompletableFuture<>();
            Regionium.scheduler().executeWhenOwned(respawnLevel, respawnPos, () -> {
                try {
                    BlockState state = respawnLevel.getBlockState(respawnPos);
                    boolean valid = false;
                    if (state.getBlock() instanceof RespawnAnchorBlock) {
                        valid = (respawnConfig.forced()
                            || state.getValue(RespawnAnchorBlock.CHARGE) > 0)
                            && RespawnAnchorBlock.canSetSpawn(respawnLevel, respawnPos)
                            && RespawnAnchorBlock.findStandUpPosition(
                                player.getType(), respawnLevel, respawnPos
                            ).isPresent();
                    } else if (state.getBlock() instanceof AbstractBedBlock bed) {
                        valid = bed.getBedRule(respawnLevel, respawnPos).canSetSpawn(respawnLevel)
                            && AbstractBedBlock.findStandUpPosition(
                                player.getType(), respawnLevel, respawnPos,
                                state.getValue(AbstractBedBlock.FACING), respawnConfig.respawnData().yaw()
                            ).isPresent();
                    }

                    if (!valid && respawnConfig.forced()) {
                        BlockState top = respawnLevel.getBlockState(respawnPos.above());
                        valid = state.getBlock().isPossibleToRespawnInThis(state)
                            && top.getBlock().isPossibleToRespawnInThis(top);
                    }

                    if (valid) {
                        result.complete(player.findRespawnPositionAndUseSpawnBlock(
                            false, TeleportTransition.DO_NOTHING
                        ));
                    } else {
                        regionium$findDefaultRespawnTransition(
                            player, source.getServer().findRespawnDimension(), true
                        ).whenComplete((transition, failure) -> {
                            if (failure != null) result.completeExceptionally(failure);
                            else result.complete(transition);
                        });
                    }
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result;
        });
    }

    @Unique
    private CompletableFuture<TeleportTransition> regionium$findDefaultRespawnTransition(
        Entity entity, ServerLevel destination, boolean missingRespawnBlock
    ) {
        BlockPos spawn = destination.getRespawnData().pos();
        return Regionium.scheduler().withChunksAsync(destination, spawn, 1, ChunkStatus.FULL, loaded -> {
            CompletableFuture<TeleportTransition> result = new CompletableFuture<>();
            Regionium.scheduler().executeWhenOwned(destination, spawn, () -> {
                try {
                    BlockPos adjusted = entity.adjustSpawnLocation(destination, spawn);
                    var respawnData = destination.getRespawnData();
                    result.complete(new TeleportTransition(
                        destination, Vec3.atBottomCenterOf(adjusted), Vec3.ZERO,
                        respawnData.yaw(), respawnData.pitch(),
                        missingRespawnBlock, false, Set.of(), TeleportTransition.DO_NOTHING
                    ));
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result;
        });
    }

    @Unique
    private CompletableFuture<TeleportTransition> regionium$findOtherPortalDestination(
        Entity entity, ServerLevel source, Portal portal, BlockPos entry
    ) {
        // End gateways compute their target from the gateway block entity. Load
        // the source neighborhood first; their target area is prepared below
        // before the transfer is queued to its destination region.
        if (portal instanceof EndGatewayBlock) {
            return regionium$findGatewayDestination(entity, source, entry);
        }
        return CompletableFuture.completedFuture(null);
    }


    @Unique
    private CompletableFuture<TeleportTransition> regionium$findGatewayDestination(
        Entity entity, ServerLevel source, BlockPos entry
    ) {
        if (!(source.getBlockEntity(entry) instanceof TheEndGatewayBlockEntity gateway)) {
            return CompletableFuture.completedFuture(null);
        }

        var gatewayAccess = (TheEndGatewayBlockEntityRegioniumAccessorMixin) (Object) gateway;
        BlockPos existingExit = gatewayAccess.regionium$getExitPortal();
        boolean exactTeleport = gatewayAccess.regionium$isExactTeleport();
        if (existingExit != null) {
            return Regionium.scheduler().withChunksAsync(source, existingExit, 2, ChunkStatus.FULL, loaded -> {
                CompletableFuture<TeleportTransition> result = new CompletableFuture<>();
                Regionium.scheduler().executeWhenOwned(source, existingExit, () -> {
                    try {
                        BlockPos target = exactTeleport ? existingExit
                            : TheEndGatewayBlockEntityRegioniumInvokerMixin.regionium$findExitPosition(source, existingExit);
                        result.complete(regionium$gatewayTransition(entity, source, target));
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                });
                return result;
            });
        }
        if (source.dimension() != Level.END) {
            return CompletableFuture.completedFuture(null);
        }

        return Regionium.scheduler().withChunksAsync(source, entry, 0, ChunkStatus.FULL, gatewayChunk ->
            regionium$findGatewayExitPositionAsync(source, entry).thenComposeAsync(exitPosition ->
                Regionium.scheduler().withChunksAsync(source, exitPosition, 2, ChunkStatus.FULL, exitChunks -> {
                    CompletableFuture<TeleportTransition> result = new CompletableFuture<>();
                    Regionium.scheduler().executeWhenOwned(source, exitPosition, () -> {
                        try {
                            TheEndGatewayBlockEntityRegioniumInvokerMixin.regionium$spawnGatewayPortal(
                                source, exitPosition, EndGatewayFeature.knownExit(entry, false)
                            );
                            BlockPos finalPosition = TheEndGatewayBlockEntityRegioniumInvokerMixin
                                .regionium$findExitPosition(source, exitPosition);
                            Regionium.scheduler().executeWhenOwned(source, entry, () -> {
                                try {
                                    gateway.setExitPosition(exitPosition, false);
                                    result.complete(regionium$gatewayTransition(entity, source, finalPosition));
                                } catch (Throwable failure) {
                                    result.completeExceptionally(failure);
                                }
                            });
                        } catch (Throwable failure) {
                            result.completeExceptionally(failure);
                        }
                    });
                    return result;
                })
            )
        );
    }

    @Unique
    private CompletableFuture<BlockPos> regionium$findGatewayExitPositionAsync(ServerLevel level, BlockPos gatewayPos) {
        Vec3 direction = new Vec3(gatewayPos.getX(), 0.0, gatewayPos.getZ()).normalize();
        return regionium$searchGatewayCandidate(level, direction, direction.scale(1024.0), false, 16)
            .thenComposeAsync(candidate -> {
                BlockPos candidatePos = BlockPos.containing(candidate.x, 75.0, candidate.z);
                return Regionium.scheduler().withChunksAsync(level, candidatePos, 0, ChunkStatus.FULL, loaded -> {
                    CompletableFuture<BlockPos> candidateResult = new CompletableFuture<>();
                    Regionium.scheduler().executeWhenOwned(level, candidatePos, () -> {
                        try {
                            LevelChunk chunk = (LevelChunk) loaded.get(0);
                            BlockPos validSpawn = TheEndGatewayBlockEntityRegioniumInvokerMixin
                                .regionium$findValidSpawnInChunk(chunk);
                            if (validSpawn != null) {
                                candidateResult.complete(validSpawn);
                                return;
                            }

                            BlockPos islandPos = BlockPos.containing(candidate.x + 0.5, 75.0, candidate.z + 0.5);
                            Regionium.scheduler().withChunksAsync(level, islandPos, 2, ChunkStatus.FULL, islandChunks -> {
                                CompletableFuture<BlockPos> islandResult = new CompletableFuture<>();
                                Regionium.scheduler().executeWhenOwned(level, islandPos, () -> {
                                    try {
                                        level.registryAccess().lookup(Registries.FEATURE)
                                            .flatMap(registry -> registry.get(EndFeatures.END_ISLAND))
                                            .ifPresent(feature -> feature.value().place(
                                                level, level.getChunkSource().getGenerator(),
                                                RandomSource.create(islandPos.asLong()), islandPos
                                            ));
                                        islandResult.complete(islandPos);
                                    } catch (Throwable failure) {
                                        islandResult.completeExceptionally(failure);
                                    }
                                });
                                return islandResult;
                            }).whenComplete((island, failure) -> {
                                if (failure != null) candidateResult.completeExceptionally(failure);
                                else candidateResult.complete(island);
                            });
                        } catch (Throwable failure) {
                            candidateResult.completeExceptionally(failure);
                        }
                    });
                    return candidateResult;
                });
            })
            .thenComposeAsync(basePosition -> Regionium.scheduler().withChunksAsync(
                level, basePosition, 2, ChunkStatus.FULL, terrainChunks -> {
                    CompletableFuture<BlockPos> tallest = new CompletableFuture<>();
                    Regionium.scheduler().executeWhenOwned(level, basePosition, () -> {
                        try {
                            tallest.complete(TheEndGatewayBlockEntityRegioniumInvokerMixin
                                .regionium$findTallestBlock(level, basePosition, 16, true).above(10));
                        } catch (Throwable failure) {
                            tallest.completeExceptionally(failure);
                        }
                    });
                    return tallest;
                }
            ));
    }

    @Unique
    private CompletableFuture<Vec3> regionium$searchGatewayCandidate(
        ServerLevel level, Vec3 direction, Vec3 current, boolean forward, int remaining
    ) {
        BlockPos candidate = BlockPos.containing(current.x, 75.0, current.z);
        return Regionium.scheduler().withChunksAsync(level, candidate, 0, ChunkStatus.FULL, loaded ->
            CompletableFuture.completedFuture(loaded.get(0).getHighestFilledSectionIndex() == -1)
        ).thenComposeAsync(empty -> {
            int nextRemaining = remaining;
            boolean nextForward = forward;
            if (forward != empty) {
                nextRemaining = 0;
            }
            if (nextRemaining-- <= 0) {
                if (forward) {
                    return CompletableFuture.completedFuture(current);
                }
                nextForward = true;
                nextRemaining = 16;
            }
            Vec3 next = current.add(direction.scale(nextForward ? 16.0 : -16.0));
            return regionium$searchGatewayCandidate(level, direction, next, nextForward, nextRemaining);
        });
    }

    @Unique
    private TeleportTransition regionium$gatewayTransition(Entity entity, ServerLevel level, BlockPos target) {
        if (entity instanceof ThrownEnderpearl) {
            return new TeleportTransition(level, Vec3.atBottomCenterOf(target), Vec3.ZERO, 0.0F, 0.0F,
                Set.of(), TeleportTransition.PLACE_PORTAL_TICKET);
        }
        return new TeleportTransition(level, Vec3.atBottomCenterOf(target), Vec3.ZERO, 0.0F, 0.0F,
            Relative.union(Relative.DELTA, Relative.ROTATION), TeleportTransition.PLACE_PORTAL_TICKET);
    }

    @Unique
    private RegioniumPortalTransfer regionium$detachForPortalTransfer(
        Entity root, ServerLevel source, ServerLevel destination
    ) {
        List<Entity> originals = new ArrayList<>();
        List<Entity> queue = new ArrayList<>();
        queue.add(root);
        for (int index = 0; index < queue.size(); index++) {
            Entity current = queue.get(index);
            if (current.isRemoved() || current.level() != source) {
                return null;
            }
            originals.add(current);
            queue.addAll(List.copyOf(current.getPassengers()));
        }

        Map<Entity, Entity> parentByPassenger = new IdentityHashMap<>();
        for (Entity original : originals) {
            for (Entity passenger : List.copyOf(original.getPassengers())) {
                parentByPassenger.put(passenger, original);
            }
        }

        for (Entity original : originals) {
            original.ejectPassengers();
            if (original.isPassenger()) {
                original.removeVehicle();
            }
        }

        Map<Entity, Entity> replacements = new IdentityHashMap<>();
        for (Entity original : originals) {
            Entity replacement;
            if (original instanceof ServerPlayer) {
                replacement = original;
            } else {
                replacement = original.getType().create(destination, EntitySpawnReason.DIMENSION_TRAVEL);
                if (replacement == null) {
                    this.regionium$restorePassengerTree(parentByPassenger);
                    return null;
                }
                replacement.restoreFrom(original);
            }
            replacements.put(original, replacement);
        }

        // Detach/remove the source tree before starting asynchronous chunk work.
        // This prevents the old region from ticking entities while their portal
        // destination is being searched or loaded.
        for (Entity original : originals) {
            if (original instanceof ServerPlayer player) {
                player.stopUsingItem();
                if (source.dimension() != destination.dimension()) {
                    ServerPlayerRegioniumPortalAccessorMixin access =
                        (ServerPlayerRegioniumPortalAccessorMixin) (Object) player;
                    access.regionium$setChangingDimension(true);
                    if (source.dimension() == Level.OVERWORLD && destination.dimension() == Level.NETHER) {
                        access.regionium$setEnteredNetherPosition(player.position());
                    }
                }
                source.removePlayerImmediately(player, Entity.RemovalReason.CHANGED_DIMENSION);
            } else {
                ((EntityRegioniumPortalMixin) (Object) original).removeAfterChangingDimensions();
            }
        }

        return new RegioniumPortalTransfer(root, source, destination, originals, replacements, parentByPassenger);
    }

    @Unique
    private void regionium$restorePassengerTree(Map<Entity, Entity> parentByPassenger) {
        for (Map.Entry<Entity, Entity> entry : parentByPassenger.entrySet()) {
            entry.getKey().startRiding(entry.getValue(), true, false);
        }
    }

    @Unique
    private static final class RegioniumPortalTransfer {
        private final Entity root;
        private final ServerLevel source;
        private final ServerLevel destination;
        private final List<Entity> originals;
        private final Map<Entity, Entity> replacements;
        private final Map<Entity, Entity> parentByPassenger;
        private final BlockPos sourcePosition;
        private final Map<Entity, PositionMoveRotation> initialMoves = new IdentityHashMap<>();
        private final Set<ServerPlayer> playersRespawned = Collections.newSetFromMap(
            new IdentityHashMap<ServerPlayer, Boolean>()
        );
        private final Set<ServerPlayer> playersTeleported = Collections.newSetFromMap(
            new IdentityHashMap<ServerPlayer, Boolean>()
        );
        private final PositionMoveRotation initialRootMove;
        private volatile boolean finished;
        private CompletableFuture<Void> restoreFuture;

        private RegioniumPortalTransfer(
            Entity root,
            ServerLevel source,
            ServerLevel destination,
            List<Entity> originals,
            Map<Entity, Entity> replacements,
            Map<Entity, Entity> parentByPassenger
        ) {
            this.root = root;
            this.source = source;
            this.destination = destination;
            this.originals = List.copyOf(originals);
            this.replacements = replacements;
            this.parentByPassenger = parentByPassenger;
            this.sourcePosition = root.blockPosition();
            for (Entity original : originals) {
                this.initialMoves.put(original, new PositionMoveRotation(
                    original.position(), original.getDeltaMovement(), original.getYRot(), original.getXRot()
                ));
            }
            this.initialRootMove = this.initialMoves.get(root);
        }

        private CompletableFuture<Void> complete(TeleportTransition transition) {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            PositionMoveRotation rootMove = PositionMoveRotation.calculateAbsolute(
                initialRootMove, PositionMoveRotation.of(transition), transition.relatives()
            );
            BlockPos destinationPosition = BlockPos.containing(rootMove.position());
            try {
                // Keep the entity tree detached and marked removed while waiting
                // for the destination owner. Do not expose a player whose level
                // says Nether but whose position still belongs to the Overworld:
                // the connection tick can otherwise run it in the wrong region.
                Regionium.scheduler().executeWhenOwned(destination, destinationPosition, () -> {
                    try {
                        for (Entity original : originals) {
                            Entity replacement = replacements.get(original);
                            PositionMoveRotation originalMove = initialMoves.get(original);
                            Vec3 position;
                            float yaw;
                            float pitch;
                            if (original == root) {
                                position = rootMove.position();
                                yaw = rootMove.yRot();
                                pitch = rootMove.xRot();
                            } else {
                                Vec3 offset = originalMove.position().subtract(initialRootMove.position());
                                position = rootMove.position().add(
                                    transition.relatives().contains(Relative.X) ? 0.0 : offset.x,
                                    transition.relatives().contains(Relative.Y) ? 0.0 : offset.y,
                                    transition.relatives().contains(Relative.Z) ? 0.0 : offset.z
                                );
                                yaw = rootMove.yRot() + (transition.relatives().contains(Relative.Y_ROT)
                                    ? 0.0F : originalMove.yRot() - initialRootMove.yRot());
                                pitch = rootMove.xRot() + (transition.relatives().contains(Relative.X_ROT)
                                    ? 0.0F : originalMove.xRot() - initialRootMove.xRot());
                            }
                            // Match Folia's transformForAsyncTeleport ordering: switch a player
                            // to the destination level before changing its coordinates. setPosRaw
                            // then queues entity-section reconciliation against the correct level;
                            // snapTo on ServerPlayer is a connection-facing operation and must not
                            // be used as the intermediate cross-dimension transform.
                            if (replacement instanceof ServerPlayer player) {
                                player.setServerLevel(destination);
                            }
                            replacement.setPosRaw(position.x, position.y, position.z);
                            replacement.setYRot(yaw);
                            replacement.setYHeadRot(yaw);
                            replacement.setXRot(pitch);
                            replacement.setDeltaMovement(rootMove.deltaMovement());
                            replacement.setPortalCooldown();
                            replacement.setOldPosAndRot();
                            ((EntityRegioniumPortalStateAccessorMixin) (Object) replacement)
                                .regionium$setMainSupportingBlockPos(java.util.Optional.empty());
                            ((EntityRegioniumPortalStateAccessorMixin) (Object) replacement)
                                .regionium$getMovementThisTick().clear();
                        }
                        this.place(transition, completion);
                    } catch (Throwable failure) {
                        Regionium.LOGGER.error("Could not place portal transfer in destination region for {}", root, failure);
                        this.restore("destination region placement failed").whenComplete((ignored, restoreFailure) -> {
                            if (restoreFailure != null) failure.addSuppressed(restoreFailure);
                            completion.completeExceptionally(failure);
                        });
                    }
                });
            } catch (Throwable failure) {
                Regionium.LOGGER.error("Could not schedule destination region for portal transfer of {}", root, failure);
                this.restore("destination region scheduling failed").whenComplete((ignored, restoreFailure) -> {
                    if (restoreFailure != null) failure.addSuppressed(restoreFailure);
                    completion.completeExceptionally(failure);
                });
            }
            return completion;
        }

        private void place(TeleportTransition transition, CompletableFuture<Void> completion) {
            if (finished) {
                completion.completeExceptionally(new IllegalStateException("Portal transfer was already finalized"));
                return;
            }
            try {
                for (Entity original : originals) {
                    Entity replacement = replacements.get(original);
                    if (replacement instanceof ServerPlayer player) {
                        ((EntityRegioniumPortalMixin) (Object) player).regionium$unsetRemoved();
                        if (transition.missingRespawnBlock()) {
                            player.connection.send(new ClientboundGameEventPacket(
                                ClientboundGameEventPacket.NO_RESPAWN_BLOCK_AVAILABLE, 0.0F
                            ));
                        }
                        if (source.dimension() != destination.dimension()) {
                            player.connection.send(new ClientboundRespawnPacket(player.createCommonSpawnInfo(destination), (byte) 3));
                            playersRespawned.add(player);
                            var data = destination.getLevelData();
                            player.connection.send(new ClientboundChangeDifficultyPacket(data.getDifficulty(), data.isDifficultyLocked()));
                        }
                        PositionMoveRotation playerMove = new PositionMoveRotation(
                            player.position(), player.getDeltaMovement(), player.getYRot(), player.getXRot()
                        );
                        player.connection.teleport(playerMove, Collections.emptySet());
                        player.connection.resetPosition();
                        playersTeleported.add(player);
                    }
                    destination.addDuringTeleport(replacement);
                }

                for (Map.Entry<Entity, Entity> entry : parentByPassenger.entrySet()) {
                    Entity passenger = replacements.get(entry.getKey());
                    Entity vehicle = replacements.get(entry.getValue());
                    if (passenger != null && vehicle != null) {
                        passenger.startRiding(vehicle, true, false);
                    }
                }

                for (Entity original : originals) {
                    Entity replacement = replacements.get(original);
                    if (replacement instanceof ServerPlayer player) {
                        if (source.dimension() != destination.dimension()) {
                            destination.getServer().getPlayerList().sendPlayerPermissionLevel(player);
                            player.connection.send(new ClientboundPlayerAbilitiesPacket(player.getAbilities()));
                            destination.getServer().getPlayerList().sendLevelInfo(player, destination);
                            destination.getServer().getPlayerList().sendAllPlayerInfo(player);
                            destination.getServer().getPlayerList().sendActivePlayerEffects(player);
                            player.sendPostEffects();
                            ((ServerPlayerRegioniumPortalAccessorMixin) (Object) player).regionium$setLastSentExperience(-1);
                            ((ServerPlayerRegioniumPortalAccessorMixin) (Object) player).regionium$setLastSentHealth(-1.0F);
                            ((ServerPlayerRegioniumPortalAccessorMixin) (Object) player).regionium$setLastSentFood(-1);
                            ((ServerPlayerRegioniumPortalInvokerMixin) (Object) player)
                                .regionium$triggerDimensionChangeTriggers(source);
                        }
                    }
                }

                transition.postTeleportTransition().onTransition(replacements.get(root));
                if (source.dimension() != destination.dimension()) {
                    for (Entity replacement : replacements.values()) {
                        if (replacement instanceof ServerPlayer player) {
                            ((ServerPlayerRegioniumPortalAccessorMixin) (Object) player)
                                .regionium$setChangingDimension(false);
                        }
                    }
                }
                finished = true;
                RegioniumPortalDispatcher.endTransfer(root);
                completion.complete(null);
            } catch (Throwable failure) {
                Regionium.LOGGER.error("Portal destination placement failed for {}", root, failure);
                for (Entity replacement : replacements.values()) {
                    if (destination.getEntity(replacement.getUUID()) == replacement) {
                        if (replacement instanceof ServerPlayer player) {
                            destination.removePlayerImmediately(player, Entity.RemovalReason.CHANGED_DIMENSION);
                        } else {
                            ((EntityRegioniumPortalMixin) (Object) replacement).removeAfterChangingDimensions();
                        }
                    }
                }
                this.restore("destination placement failed").whenComplete((ignored, restoreFailure) -> {
                    if (restoreFailure != null) failure.addSuppressed(restoreFailure);
                    completion.completeExceptionally(failure);
                });
            }
        }

        private synchronized CompletableFuture<Void> restore(String reason) {
            if (finished) {
                return CompletableFuture.completedFuture(null);
            }
            if (restoreFuture != null) {
                return restoreFuture;
            }
            restoreFuture = new CompletableFuture<>();
            try {
                Regionium.scheduler().executeWhenOwned(source, sourcePosition, () -> {
                    if (finished) {
                        restoreFuture.complete(null);
                        return;
                    }
                    try {
                        for (Entity original : originals) {
                            PositionMoveRotation originalMove = initialMoves.get(original);
                            if (original instanceof ServerPlayer player) {
                                player.setServerLevel(source);
                            }
                            original.setPosRaw(
                                originalMove.position().x, originalMove.position().y, originalMove.position().z
                            );
                            original.setYRot(originalMove.yRot());
                            original.setYHeadRot(originalMove.yRot());
                            original.setXRot(originalMove.xRot());
                            original.setDeltaMovement(originalMove.deltaMovement());
                            original.setOldPosAndRot();
                            ((EntityRegioniumPortalStateAccessorMixin) (Object) original)
                                .regionium$setMainSupportingBlockPos(java.util.Optional.empty());
                            ((EntityRegioniumPortalStateAccessorMixin) (Object) original)
                                .regionium$getMovementThisTick().clear();
                            if (original instanceof ServerPlayer player) {
                                if (source.dimension() != destination.dimension()) {
                                    player.hasChangedDimension();
                                    ((ServerPlayerRegioniumPortalAccessorMixin) (Object) player)
                                        .regionium$setChangingDimension(false);
                                }
                                if (playersRespawned.contains(player)) {
                                    player.connection.send(new ClientboundRespawnPacket(player.createCommonSpawnInfo(source), (byte) 3));
                                    var data = source.getLevelData();
                                    player.connection.send(new ClientboundChangeDifficultyPacket(data.getDifficulty(), data.isDifficultyLocked()));
                                }
                                if (playersRespawned.contains(player) || playersTeleported.contains(player)) {
                                    player.connection.teleport(originalMove, Collections.emptySet());
                                    player.connection.resetPosition();
                                }
                            }
                            ((EntityRegioniumPortalMixin) (Object) original).regionium$unsetRemoved();
                            source.addDuringTeleport(original);
                        }
                        for (Map.Entry<Entity, Entity> entry : parentByPassenger.entrySet()) {
                            entry.getKey().startRiding(entry.getValue(), true, false);
                        }
                        finished = true;
                        RegioniumPortalDispatcher.endTransfer(root);
                        Regionium.LOGGER.warn("Restored {} after {}", root, reason);
                        restoreFuture.complete(null);
                    } catch (Throwable failure) {
                        Regionium.LOGGER.error("Failed to restore entity tree after {}: {}", reason, root, failure);
                        finished = true;
                        RegioniumPortalDispatcher.endTransfer(root);
                        restoreFuture.completeExceptionally(failure);
                    }
                });
            } catch (Throwable failure) {
                Regionium.LOGGER.error("Could not schedule restoration after {} for {}", reason, root, failure);
                finished = true;
                RegioniumPortalDispatcher.endTransfer(root);
                restoreFuture.completeExceptionally(failure);
            }
            return restoreFuture;
        }
    }

}
