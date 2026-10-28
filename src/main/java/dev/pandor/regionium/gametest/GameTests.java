package dev.pandor.regionium.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import dev.pandor.regionium.Regionium;

public final class GameTests {
    private static final String NOOP_TEST_FUNCTION = "noop";

    public GameTests() {
        Registry.register(
            BuiltInRegistries.TEST_FUNCTION,
            Identifier.fromNamespaceAndPath(Regionium.MOD_ID.toLowerCase(), NOOP_TEST_FUNCTION),
            GameTests::noop
        );
    }

    public static void noop(GameTestHelper helper) {
        helper.succeed();
    }

    @GameTest(maxTicks = 4800)
    public void endGatewayTransfersEntitiesAsynchronously(GameTestHelper helper) {
        var source = helper.getLevel();
        var end = source.getServer().getLevel(Level.END);
        if (end == null) {
            throw helper.assertionException("End dimension is unavailable in GameTestServer");
        }

        BlockPos gatewayPos = new BlockPos(10000, 80, 10000);
        var pigType = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "pig"));
        Entity pig = pigType.create(end, EntitySpawnReason.TRIGGERED);
        if (pig == null) {
            throw helper.assertionException("Could not create End gateway test entity");
        }
        pig.snapTo(gatewayPos.getX() + 0.5, gatewayPos.getY(), gatewayPos.getZ() + 0.5, 0.0F, 0.0F);
        var entityUuid = pig.getUUID();

        dev.pandor.regionium.Regionium.scheduler().withChunksAsync(end, gatewayPos, 2,
            net.minecraft.world.level.chunk.status.ChunkStatus.FULL, loaded -> {
                java.util.concurrent.CompletableFuture<Void> setup = new java.util.concurrent.CompletableFuture<>();
                dev.pandor.regionium.Regionium.scheduler().execute(end, gatewayPos, () -> {
                    try {
                        end.setBlockAndUpdate(gatewayPos, Blocks.END_GATEWAY.defaultBlockState());
                        if (!(end.getBlockEntity(gatewayPos) instanceof net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity gateway)) {
                            throw helper.assertionException("End gateway block entity was not created");
                        }
                        end.addFreshEntity(pig);
                        pig.setAsInsidePortal((Portal) Blocks.END_GATEWAY, gatewayPos);
                        setup.complete(null);
                    } catch (Throwable failure) {
                        setup.completeExceptionally(failure);
                    }
                });
                return setup;
            }
        );

        helper.startSequence()
            .thenExecuteFor(120, () -> regionium$touchPortal(end, pig, (Portal) Blocks.END_GATEWAY, gatewayPos))
            .thenWaitUntil(() -> {
                Entity transferred = end.getEntity(entityUuid);
                if (transferred == null || transferred == pig || transferred.isRemoved()) {
                    throw helper.assertionException("Waiting for asynchronous End gateway transfer");
                }
                if (transferred.distanceToSqr(gatewayPos.getX(), gatewayPos.getY(), gatewayPos.getZ()) < 1000.0) {
                    throw helper.assertionException("End gateway transfer did not move the entity to its exit");
                }
            })
            .thenSucceed();
    }

    @GameTest(maxTicks = 2400)
    public void endPortalTransfersEntitiesAsynchronously(GameTestHelper helper) {
        var source = helper.getLevel();
        BlockPos portalPos = helper.absolutePos(new BlockPos(2, 2, 6));
        source.setBlockAndUpdate(portalPos, Blocks.END_PORTAL.defaultBlockState());

        var pigType = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "pig"));
        Entity pig = pigType.create(source, EntitySpawnReason.TRIGGERED);
        if (pig == null) {
            throw helper.assertionException("Could not create End portal test entity");
        }
        pig.snapTo(portalPos.getX() + 0.5, portalPos.getY(), portalPos.getZ() + 0.5, 0.0F, 0.0F);
        source.addFreshEntity(pig);
        var entityUuid = pig.getUUID();
        var end = source.getServer().getLevel(Level.END);
        if (end == null) {
            throw helper.assertionException("End dimension is unavailable in GameTestServer");
        }

        dev.pandor.regionium.Regionium.scheduler().execute(source, portalPos, () -> {
            pig.setAsInsidePortal((Portal) Blocks.END_PORTAL, portalPos);
        });

        helper.startSequence()
            .thenExecuteFor(120, () -> regionium$touchPortal(source, pig, (Portal) Blocks.END_PORTAL, portalPos))
            .thenWaitUntil(() -> {
                Entity transferred = end.getEntity(entityUuid);
                if (transferred == null || transferred.isRemoved()) {
                    throw helper.assertionException("Waiting for asynchronous End portal transfer");
                }
                if (transferred.level() != end) {
                    throw helper.assertionException("End portal transfer left the entity in the wrong world");
                }
            })
            .thenSucceed();
    }

    @GameTest(maxTicks = 2400)
    public void endPortalReturnsNonPlayersToOverworldSpawn(GameTestHelper helper) {
        var overworld = helper.getLevel();
        var end = overworld.getServer().getLevel(Level.END);
        if (end == null) {
            throw helper.assertionException("End dimension is unavailable in GameTestServer");
        }

        BlockPos portalPos = new BlockPos(20000, 120, 20000);
        var pigType = BuiltInRegistries.ENTITY_TYPE.getValue(
            Identifier.fromNamespaceAndPath("minecraft", "pig"));
        java.util.concurrent.atomic.AtomicReference<java.util.UUID> entityUuid =
            new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> setupFailure =
            new java.util.concurrent.atomic.AtomicReference<>();

        dev.pandor.regionium.Regionium.scheduler().withChunksAsync(
            end, portalPos, 1, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, chunks -> {
                java.util.concurrent.CompletableFuture<Void> setup = new java.util.concurrent.CompletableFuture<>();
                dev.pandor.regionium.Regionium.scheduler().execute(end, portalPos, () -> {
                    try {
                        end.setBlockAndUpdate(portalPos, Blocks.END_PORTAL.defaultBlockState());
                        Entity pig = pigType.create(end, EntitySpawnReason.TRIGGERED);
                        if (pig == null) {
                            throw new IllegalStateException("Could not create End return-portal test entity");
                        }
                        pig.snapTo(portalPos.getX() + 0.5, portalPos.getY(), portalPos.getZ() + 0.5, 0.0F, 0.0F);
                        end.addFreshEntity(pig);
                        entityUuid.set(pig.getUUID());
                        pig.setAsInsidePortal((Portal) Blocks.END_PORTAL, portalPos);
                        setup.complete(null);
                    } catch (Throwable failure) {
                        setupFailure.set(failure);
                        setup.completeExceptionally(failure);
                    }
                });
                return setup;
            }
        ).whenComplete((ignored, failure) -> {
            if (failure != null) {
                setupFailure.set(failure);
            }
        });

        helper.startSequence()
            .thenExecuteFor(120, () -> {
                Entity current = entityUuid.get() == null ? null : end.getEntity(entityUuid.get());
                if (current != null) regionium$touchPortal(end, current, (Portal) Blocks.END_PORTAL, portalPos);
            })
            .thenWaitUntil(() -> {
                Throwable failure = setupFailure.get();
                if (failure != null) {
                    throw helper.assertionException("Could not prepare End return-portal test: " + failure);
                }
                java.util.UUID uuid = entityUuid.get();
                if (uuid == null) {
                    throw helper.assertionException("Waiting for End return-portal test setup");
                }
                Entity transferred = overworld.getEntity(uuid);
                if (transferred == null || transferred.isRemoved()) {
                    throw helper.assertionException("Waiting for asynchronous End-to-Overworld portal transfer");
                }
                if (transferred.level() != overworld) {
                    throw helper.assertionException("End portal returned the entity to the wrong dimension");
                }
            })
            .thenSucceed();
    }

    @GameTest(maxTicks = 2400)
    public void netherPortalReusesExistingDestinationPortalAsynchronously(GameTestHelper helper) {
        var source = helper.getLevel();
        // Keep this destination portal vertically separated from the other
        // portal tests: all tests share one world and nether coordinate scaling
        // would otherwise make their synthetic portals overlap.
        BlockPos sourcePortal = helper.absolutePos(new BlockPos(2, 116, 2));
        source.setBlockAndUpdate(sourcePortal, Blocks.NETHER_PORTAL.defaultBlockState()
            .setValue(NetherPortalBlock.AXIS, Direction.Axis.X));

        var nether = source.getServer().getLevel(Level.NETHER);
        if (nether == null) {
            throw helper.assertionException("Nether dimension is unavailable in GameTestServer");
        }

        double scale = net.minecraft.world.level.dimension.DimensionType.getTeleportationScale(
            source.dimensionType(), nether.dimensionType());
        BlockPos target = nether.getWorldBorder().clampToBounds(
            (sourcePortal.getX() + 0.5) * scale,
            sourcePortal.getY(),
            (sourcePortal.getZ() + 0.5) * scale
        ).offset(10, 0, 0);

        var pigType = BuiltInRegistries.ENTITY_TYPE.getValue(
            Identifier.fromNamespaceAndPath("minecraft", "pig"));
        java.util.concurrent.atomic.AtomicReference<java.util.UUID> entityUuid =
            new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> setupFailure =
            new java.util.concurrent.atomic.AtomicReference<>();

        dev.pandor.regionium.Regionium.scheduler().withChunksAsync(
            nether, target, 2, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, chunks -> {
                java.util.concurrent.CompletableFuture<Void> placed = new java.util.concurrent.CompletableFuture<>();
                dev.pandor.regionium.Regionium.scheduler().execute(nether, target, () -> {
                    try {
                        for (int dx = 0; dx < 2; dx++) {
                            for (int dy = 0; dy < 3; dy++) {
                                nether.setBlockAndUpdate(target.offset(dx, dy, 0),
                                    Blocks.NETHER_PORTAL.defaultBlockState()
                                        .setValue(NetherPortalBlock.AXIS, Direction.Axis.X));
                            }
                        }
                        placed.complete(null);
                    } catch (Throwable failure) {
                        placed.completeExceptionally(failure);
                    }
                });
                return placed;
            }
        ).whenComplete((ignored, failure) -> {
            if (failure != null) {
                setupFailure.set(failure);
                return;
            }
            dev.pandor.regionium.Regionium.scheduler().execute(source, sourcePortal, () -> {
                try {
                    Entity pig = pigType.create(source, EntitySpawnReason.TRIGGERED);
                    if (pig == null) {
                        throw new IllegalStateException("Could not create existing-portal test entity");
                    }
                    pig.snapTo(sourcePortal.getX() + 0.5, sourcePortal.getY(),
                        sourcePortal.getZ() + 0.5, 0.0F, 0.0F);
                    source.addFreshEntity(pig);
                    entityUuid.set(pig.getUUID());
                    pig.setAsInsidePortal((Portal) Blocks.NETHER_PORTAL, sourcePortal);
                } catch (Throwable failureToStart) {
                    setupFailure.set(failureToStart);
                }
            });
        });

        helper.startSequence()
            .thenExecuteFor(120, () -> {
                java.util.UUID uuid = entityUuid.get();
                Entity current = uuid == null ? null : source.getEntity(uuid);
                if (current != null) regionium$touchPortal(source, current, (Portal) Blocks.NETHER_PORTAL, sourcePortal);
            })
            .thenWaitUntil(() -> {
                Throwable failure = setupFailure.get();
                if (failure != null) {
                    throw helper.assertionException("Could not prepare existing Nether portal test: " + failure);
                }
                java.util.UUID uuid = entityUuid.get();
                if (uuid == null) {
                    throw helper.assertionException("Waiting for existing Nether portal test setup");
                }
                Entity transferred = nether.getEntity(uuid);
                if (transferred == null || transferred.isRemoved()) {
                    throw helper.assertionException("Waiting for asynchronous existing Nether portal transfer");
                }
                double distance = transferred.distanceToSqr(target.getX(), target.getY(), target.getZ());
                if (distance > 16.0) {
                    throw helper.assertionException(
                        "Entity did not use the existing destination portal; distance squared=" + distance);
                }
            })
            .thenSucceed();
    }

    @GameTest(maxTicks = 2400)
    public void netherPortalTransfersPassengerTreesAsynchronously(GameTestHelper helper) {
        var source = helper.getLevel();
        BlockPos portalPos = helper.absolutePos(new BlockPos(6, 2, 2));
        source.setBlockAndUpdate(portalPos, Blocks.NETHER_PORTAL.defaultBlockState()
            .setValue(NetherPortalBlock.AXIS, Direction.Axis.X));

        var boatType = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "boat"));
        var pigType = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "pig"));
        Entity boat = boatType.create(source, EntitySpawnReason.TRIGGERED);
        Entity pig = pigType.create(source, EntitySpawnReason.TRIGGERED);
        if (boat == null || pig == null) {
            throw helper.assertionException("Could not create portal passenger-tree test entities");
        }
        boat.snapTo(portalPos.getX() + 0.5, portalPos.getY(), portalPos.getZ() + 0.5, 0.0F, 0.0F);
        pig.snapTo(portalPos.getX() + 0.5, portalPos.getY(), portalPos.getZ() + 0.5, 0.0F, 0.0F);
        source.addFreshEntity(boat);
        source.addFreshEntity(pig);
        if (!pig.startRiding(boat, true, false)) {
            throw helper.assertionException("Could not attach the test pig to its boat");
        }

        var boatUuid = boat.getUUID();
        var pigUuid = pig.getUUID();
        var nether = source.getServer().getLevel(Level.NETHER);
        if (nether == null) {
            throw helper.assertionException("Nether dimension is unavailable in GameTestServer");
        }

        dev.pandor.regionium.Regionium.scheduler().execute(source, portalPos, () -> {
            boat.setAsInsidePortal((Portal) Blocks.NETHER_PORTAL, portalPos);
        });

        helper.startSequence()
            .thenExecuteFor(120, () -> regionium$touchPortal(source, boat, (Portal) Blocks.NETHER_PORTAL, portalPos))
            .thenWaitUntil(() -> {
                Entity transferredBoat = nether.getEntity(boatUuid);
                Entity transferredPig = nether.getEntity(pigUuid);
                if (transferredBoat == null || transferredPig == null) {
                    throw helper.assertionException("Waiting for asynchronous portal passenger-tree transfer");
                }
                if (transferredPig.getVehicle() != transferredBoat) {
                    throw helper.assertionException("Portal transfer did not preserve the passenger relationship");
                }
            })
            .thenSucceed();
    }

    @GameTest(maxTicks = 2400)
    public void netherPortalTransfersEntitiesAsynchronously(GameTestHelper helper) {
        var source = helper.getLevel();
        BlockPos portalPos = helper.absolutePos(new BlockPos(2, 2, 2));
        source.setBlockAndUpdate(portalPos, Blocks.NETHER_PORTAL.defaultBlockState()
            .setValue(NetherPortalBlock.AXIS, Direction.Axis.X));

        var pigType = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "pig"));
        Entity pig = pigType.create(source, EntitySpawnReason.TRIGGERED);
        if (pig == null) {
            throw helper.assertionException("Could not create portal test entity");
        }
        pig.snapTo(portalPos.getX() + 0.5, portalPos.getY(), portalPos.getZ() + 0.5, 0.0F, 0.0F);
        source.addFreshEntity(pig);
        var entityUuid = pig.getUUID();
        var nether = source.getServer().getLevel(Level.NETHER);
        if (nether == null) {
            throw helper.assertionException("Nether dimension is unavailable in GameTestServer");
        }

        dev.pandor.regionium.Regionium.scheduler().execute(source, portalPos, () -> {
            pig.setAsInsidePortal((Portal) Blocks.NETHER_PORTAL, portalPos);
        });

        helper.startSequence()
            .thenExecuteFor(120, () -> regionium$touchPortal(source, pig, (Portal) Blocks.NETHER_PORTAL, portalPos))
            .thenWaitUntil(() -> {
                Entity transferred = nether.getEntity(entityUuid);
                if (transferred == null || transferred.isRemoved()) {
                    throw helper.assertionException("Waiting for asynchronous nether portal transfer");
                }
                if (transferred.level() != nether) {
                    throw helper.assertionException("Portal transfer left the entity in the wrong world");
                }
            })
            .thenSucceed();
    }

    @GameTest(maxTicks = 1000)
    public void neighborUpdate(GameTestHelper helper) {
        BlockPos piston = new BlockPos(2, 1, 2);
        BlockPos power = piston.relative(Direction.WEST);

        Regionium.scheduler().registerLevel(helper.getLevel());
        Regionium.scheduler().refreshChunkLeases(helper.getLevel());
        Regionium.scheduler().execute(helper.getLevel(), helper.absolutePos(piston), () -> {
            helper.setBlock(piston, Blocks.PISTON, Direction.EAST);
            helper.setBlock(power, Blocks.REDSTONE_BLOCK);
        });

        BlockPos absolutePiston = helper.absolutePos(piston);
        helper.succeedWhen(() -> {
            // Do not let a failed region tick block the GameTest server thread
            // in getChunk(); poll the already-loaded chunk non-blockingly.
            var chunk = helper.getLevel().getChunkSource().getChunkNow(
                absolutePiston.getX() >> 4, absolutePiston.getZ() >> 4);
            if (chunk == null) {
                throw helper.assertionException("Piston chunk is not currently loaded");
            }
            var state = chunk.getBlockState(absolutePiston);
            if (!state.hasProperty(BlockStateProperties.EXTENDED)
                || !state.getValue(BlockStateProperties.EXTENDED)) {
                throw helper.assertionException("Piston did not extend: " + state);
            }
        });
    }

    @GameTest(maxTicks = 500)
    public void fallingSand(GameTestHelper helper) {
        BlockPos ground = new BlockPos(2, 1, 2);
        BlockPos sand = new BlockPos(2, 6, 2);
        BlockPos landing = ground.above();
        java.util.concurrent.atomic.AtomicBoolean observedFalling = new java.util.concurrent.atomic.AtomicBoolean();

        Regionium.scheduler().registerLevel(helper.getLevel());
        Regionium.scheduler().refreshChunkLeases(helper.getLevel());
        Regionium.scheduler().execute(helper.getLevel(), helper.absolutePos(sand), () -> {
            helper.setBlock(ground, Blocks.STONE);
            helper.setBlock(sand, Blocks.SAND);
        });

        helper.startSequence()
            .thenExecuteFor(20, () -> {
                var falling = BuiltInRegistries.ENTITY_TYPE.getValue(
                    Identifier.fromNamespaceAndPath("minecraft", "falling_block"));
                double startY = helper.absolutePos(sand).getY();
                if (helper.getEntities(falling, sand, 32.0).stream()
                    .anyMatch(entity -> entity.getY() < startY)) {
                    observedFalling.set(true);
                }
            })
            .thenIdle(100)
            .thenExecute(() -> {
                if (!observedFalling.get()) {
                    throw helper.assertionException(
                        "Falling sand never produced a downward-moving FallingBlockEntity");
                }
                var landingState = regionium$loadedBlockState(helper, landing);
                if (landingState == null || !landingState.is(Blocks.SAND)) {
                    throw helper.assertionException(
                        "Falling sand did not convert back into a sand block at "
                            + helper.absolutePos(landing) + ", state=" + landingState);
                }
            })
            .thenSucceed();
    }

    @GameTest(maxTicks = 1000)
    public void scheduledBlockTick(GameTestHelper helper) {
        BlockPos lamp = new BlockPos(2, 2, 2);
        var lit = Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, true);

        Regionium.scheduler().registerLevel(helper.getLevel());
        Regionium.scheduler().refreshChunkLeases(helper.getLevel());
        Regionium.scheduler().execute(helper.getLevel(), helper.absolutePos(lamp), () -> {
            helper.setBlock(lamp, lit);
            helper.getLevel().scheduleTick(helper.absolutePos(lamp), Blocks.REDSTONE_LAMP, 1);
        });

        helper.succeedWhen(() -> {
            var state = regionium$loadedBlockState(helper, lamp);
            if (state == null || !state.hasProperty(BlockStateProperties.LIT)
                || state.getValue(BlockStateProperties.LIT)) {
                throw helper.assertionException("Scheduled lamp tick has not extinguished the lamp: " + state);
            }
        });
    }

    /**
     * Baseline for the cross-region clock tests: prove the exact machine
     * oscillates when all of its blocks belong to one ordinary GameTest region.
     */
    @GameTest(maxTicks = 300)
    public void stickyPistonClockRunsFor200Ticks(GameTestHelper helper) {
        BlockPos piston = new BlockPos(2, 1, 2);
        BlockPos slime = piston.above();
        BlockPos redstone = slime.relative(Direction.SOUTH);
        BlockPos update = piston.relative(Direction.SOUTH);
        int[] previousState = {-1};
        int[] transitions = new int[1];

        Regionium.scheduler().registerLevel(helper.getLevel());
        Regionium.scheduler().refreshChunkLeases(helper.getLevel());
        Regionium.scheduler().execute(helper.getLevel(), helper.absolutePos(piston), () -> {
            helper.setBlock(piston, Blocks.STICKY_PISTON.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.UP));
            helper.setBlock(slime, Blocks.SLIME_BLOCK);
            helper.setBlock(redstone, Blocks.REDSTONE_BLOCK);
            // Match the repro exactly: the final neighbour update starts it.
            helper.setBlock(update, Blocks.STONE);
        });

        helper.startSequence()
            .thenIdle(10)
            .thenExecuteFor(200, () -> {
                int state = stickyPistonClockState(
                    helper.getLevel(), helper.absolutePos(piston));
                if (previousState[0] >= 0 && state != previousState[0]) transitions[0]++;
                previousState[0] = state;
            })
            .thenExecute(() -> {
                if (transitions[0] < 5) {
                    throw helper.assertionException(
                        "Single-region sticky-piston clock failed to oscillate for 200 ticks: "
                            + "transitions=" + transitions[0]
                            + ", piston=" + regionium$loadedBlockState(helper, piston)
                            + ", slime=" + regionium$loadedBlockState(helper, slime)
                            + ", redstone=" + regionium$loadedBlockState(helper, redstone));
                }
            })
            .thenSucceed();
    }

    private static net.minecraft.world.level.block.state.BlockState regionium$loadedBlockState(
        GameTestHelper helper, BlockPos relativePos
    ) {
        BlockPos pos = helper.absolutePos(relativePos);
        var chunk = helper.getLevel().getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockState(pos);
    }

    private static boolean regionium$willReceiveNormalTick(ServerLevel level, Entity entity) {
        var current = dev.pandor.regionium.core.RegioniumContext.currentRegion();
        if (current == null || current.level() != level
            || Regionium.scheduler().regionizer().owner(level, entity.chunkPosition().pack()) != current) {
            return false;
        }
        if (entity instanceof ServerPlayer player) {
            return current.worldData().players().contains(player) && player.connection != null;
        }
        long chunkKey = entity.chunkPosition().pack();
        var chunkMap = (dev.pandor.regionium.mixins.ChunkMapRegioniumVisibleAccessorMixin)
            (Object) level.getChunkSource().chunkMap;
        var holder = chunkMap.regionium$getVisibleChunkMap().get(chunkKey);
        return level.getChunkSource().chunkMap.getDistanceManager().inEntityTickingRange(chunkKey)
            && holder != null
            && holder.getEntityTickingChunkFuture()
                .getNow(net.minecraft.server.level.ChunkHolder.UNLOADED_LEVEL_CHUNK)
                .orElse(null) != null;
    }

    private static void regionium$touchPortal(ServerLevel level, Entity entity, Portal portal, BlockPos pos) {
        Regionium.scheduler().executeWhenOwned(level, pos, () -> {
            if (entity.level() != level || entity.isRemoved()
                || level.getEntity(entity.getUUID()) != entity
                || dev.pandor.regionium.core.RegioniumPortalDispatcher.isTransferPending(entity)) {
                return;
            }
            // Do not invoke portal handling until the source chunk is actually
            // FULL and the test portal exists. The region owns the section
            // before chunk promotion completes, but getBlockState must not
            // synchronously load that chunk from a region worker.
            var chunk = dev.pandor.regionium.core.RegioniumPortalChunkAccess.getLoadedChunk(
                level, pos.getX() >> 4, pos.getZ() >> 4,
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL);
            if (chunk == null || !(chunk.getBlockState(pos).getBlock() instanceof Portal actualPortal)) {
                return;
            }
            // Simulate portal contact before the entity's normal region tick.
            // GameTest-created entities that are not eligible for normal ticking
            // are driven directly, but only after their source chunk is loaded.
            entity.setAsInsidePortal(actualPortal, pos);
            if (!regionium$willReceiveNormalTick(level, entity)) {
                ((dev.pandor.regionium.mixins.EntityRegioniumPortalInvokerMixin) (Object) entity)
                    .regionium$invokeHandlePortal();
            }
        });
    }

    private static int stickyPistonClockState(
        net.minecraft.server.level.ServerLevel level,
        BlockPos piston
    ) {
        var chunk = level.getChunkSource().getChunkNow(piston.getX() >> 4, piston.getZ() >> 4);
        if (chunk == null) return -1;
        var state = chunk.getBlockState(piston);
        if (state.is(Blocks.MOVING_PISTON)) return 2;
        if (state.is(Blocks.STICKY_PISTON)) {
            return state.getValue(BlockStateProperties.EXTENDED) ? 1 : 0;
        }
        return 3;
    }

    // These sanity tests exercise the same GameTest world through Regionium's
    // regionized tick pipeline, while keeping the assertions focused on the
    // vanilla behavior being validated.

    @GameTest(maxTicks = 20)
    public void external_sanity_block(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        Regionium.scheduler().registerLevel(helper.getLevel());
        Regionium.scheduler().refreshChunkLeases(helper.getLevel());
        Regionium.scheduler().execute(helper.getLevel(), helper.absolutePos(pos),
            () -> helper.setBlock(pos, Blocks.DIAMOND_BLOCK));
        helper.succeedWhen(() -> {
            var state = regionium$loadedBlockState(helper, pos);
            if (state == null || !state.is(Blocks.DIAMOND_BLOCK)) {
                throw helper.assertionException("Diamond block not present; loaded state=" + state);
            }
        });
    }

    @GameTest(maxTicks = 40)
    public void external_sanity_entity(GameTestHelper helper) {
        BlockPos spawn = new BlockPos(2, 2, 2);
        var cow = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "cow"));
        java.util.concurrent.atomic.AtomicReference<Entity> spawned = new java.util.concurrent.atomic.AtomicReference<>();
        Regionium.scheduler().registerLevel(helper.getLevel());
        Regionium.scheduler().refreshChunkLeases(helper.getLevel());
        Regionium.scheduler().execute(helper.getLevel(), helper.absolutePos(spawn),
            () -> spawned.set(helper.spawn(cow, spawn)));
        helper.succeedWhen(() -> {
            Entity entity = spawned.get();
            if (entity == null || entity.isRemoved() || entity.level() != helper.getLevel()) {
                throw helper.assertionException("Cow was not registered in the owning region");
            }
        });
    }

    @GameTest(maxTicks = 80)
    public void external_sanity_redstone_piston(GameTestHelper helper) {
        BlockPos piston = new BlockPos(2, 1, 2);
        BlockPos head = piston.relative(Direction.EAST);
        BlockPos power = piston.relative(Direction.WEST);

        Regionium.scheduler().registerLevel(helper.getLevel());
        Regionium.scheduler().refreshChunkLeases(helper.getLevel());
        Regionium.scheduler().execute(helper.getLevel(), helper.absolutePos(piston), () -> {
            helper.setBlock(piston, Blocks.PISTON, Direction.EAST);
            helper.setBlock(power, Blocks.REDSTONE_BLOCK);
        });

        helper.startSequence()
            .thenIdle(10)
            .thenWaitUntil(() -> {
                var state = regionium$loadedBlockState(helper, piston);
                if (state == null || !state.hasProperty(BlockStateProperties.EXTENDED)
                    || !state.getValue(BlockStateProperties.EXTENDED)) {
                    throw helper.assertionException("Piston did not extend after packet-free redstone setup: " + state);
                }
            })
            .thenExecute(() -> Regionium.scheduler().execute(
                helper.getLevel(),
                helper.absolutePos(power),
                () -> helper.setBlock(power, Blocks.AIR)))
            .thenIdle(10)
            .thenWaitUntil(() -> {
                var state = regionium$loadedBlockState(helper, piston);
                if (state == null || !state.hasProperty(BlockStateProperties.EXTENDED)
                    || state.getValue(BlockStateProperties.EXTENDED)) {
                    throw helper.assertionException("Piston did not retract after power removal: " + state);
                }
                var absolutePiston = helper.absolutePos(piston);
                var chunk = helper.getLevel().getChunkSource().getChunkNow(
                    absolutePiston.getX() >> 4, absolutePiston.getZ() >> 4);
                if (chunk == null || chunk.getBlockEntity(absolutePiston) != null
                    || chunk.getBlockEntity(helper.absolutePos(head)) != null) {
                    throw helper.assertionException(
                        "Piston left a stale block entity after completing its extend/retract cycle");
                }
            })
            .thenSucceed();
    }

    @GameTest(maxTicks = 1000)
    public void external_sanity_scheduled_lamp(GameTestHelper helper) {
        BlockPos lamp = new BlockPos(2, 2, 2);
        Regionium.scheduler().registerLevel(helper.getLevel());
        Regionium.scheduler().refreshChunkLeases(helper.getLevel());
        Regionium.scheduler().execute(helper.getLevel(), helper.absolutePos(lamp), () -> {
            helper.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState()
                .setValue(BlockStateProperties.LIT, true));
            helper.getLevel().scheduleTick(helper.absolutePos(lamp), Blocks.REDSTONE_LAMP, 1);
        });
        helper.succeedWhen(() -> {
            var state = regionium$loadedBlockState(helper, lamp);
            if (state == null || !state.hasProperty(BlockStateProperties.LIT)
                || state.getValue(BlockStateProperties.LIT)) {
                throw helper.assertionException("Scheduled lamp tick has not extinguished the lamp: " + state);
            }
        });
    }

    @GameTest(maxTicks = 500)
    public void external_sanity_falling_block(GameTestHelper helper) {
        BlockPos ground = new BlockPos(2, 1, 2);
        BlockPos sand = new BlockPos(2, 6, 2);
        BlockPos landing = ground.above();
        java.util.concurrent.atomic.AtomicBoolean observedFalling = new java.util.concurrent.atomic.AtomicBoolean();

        Regionium.scheduler().registerLevel(helper.getLevel());
        Regionium.scheduler().refreshChunkLeases(helper.getLevel());
        Regionium.scheduler().execute(helper.getLevel(), helper.absolutePos(sand), () -> {
            helper.setBlock(ground, Blocks.STONE);
            helper.setBlock(sand, Blocks.SAND);
        });

        helper.startSequence()
            .thenExecuteFor(20, () -> {
                var falling = BuiltInRegistries.ENTITY_TYPE.getValue(
                    Identifier.fromNamespaceAndPath("minecraft", "falling_block"));
                double startY = helper.absolutePos(sand).getY();
                if (helper.getEntities(falling, sand, 32.0).stream()
                    .anyMatch(entity -> entity.getY() < startY)) {
                    observedFalling.set(true);
                }
            })
            .thenIdle(100)
            .thenExecute(() -> {
                if (!observedFalling.get()) {
                    throw helper.assertionException(
                        "Falling sand never produced a downward-moving FallingBlockEntity");
                }
                var landingState = regionium$loadedBlockState(helper, landing);
                if (landingState == null || !landingState.is(Blocks.SAND)) {
                    throw helper.assertionException(
                        "Falling sand did not convert back into a sand block at "
                            + helper.absolutePos(landing) + ", state=" + landingState);
                }
            })
            .thenSucceed();
    }

}
