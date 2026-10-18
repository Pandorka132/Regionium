package dev.pandor.regionium.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.entity.Entity;
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
                        ((dev.pandor.regionium.mixins.EntityRegioniumPortalInvokerMixin) (Object) pig)
                            .regionium$invokeHandlePortal();
                        setup.complete(null);
                    } catch (Throwable failure) {
                        setup.completeExceptionally(failure);
                    }
                });
                return setup;
            }
        );

        helper.startSequence()
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
            ((dev.pandor.regionium.mixins.EntityRegioniumPortalInvokerMixin) (Object) pig)
                .regionium$invokeHandlePortal();
        });

        helper.startSequence()
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
                        ((dev.pandor.regionium.mixins.EntityRegioniumPortalInvokerMixin) (Object) pig)
                            .regionium$invokeHandlePortal();
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
                    ((dev.pandor.regionium.mixins.EntityRegioniumPortalInvokerMixin) (Object) pig)
                        .regionium$invokeHandlePortal();
                } catch (Throwable failureToStart) {
                    setupFailure.set(failureToStart);
                }
            });
        });

        helper.startSequence()
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
            ((dev.pandor.regionium.mixins.EntityRegioniumPortalInvokerMixin) (Object) boat)
                .regionium$invokeHandlePortal();
        });

        helper.startSequence()
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
            ((dev.pandor.regionium.mixins.EntityRegioniumPortalInvokerMixin) (Object) pig)
                .regionium$invokeHandlePortal();
        });

        helper.startSequence()
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

        helper.succeedWhen(() -> helper.assertBlockProperty(
            piston, BlockStateProperties.EXTENDED, true));
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
                if (!helper.getBlockState(landing).is(Blocks.SAND)) {
                    throw helper.assertionException(
                        "Falling sand did not convert back into a sand block at "
                            + helper.absolutePos(landing) + ", state=" + helper.getBlockState(landing));
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

        helper.succeedWhen(() -> helper.assertBlockProperty(
            lamp, BlockStateProperties.LIT, false));
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
                            + ", piston=" + helper.getBlockState(piston)
                            + ", slime=" + helper.getBlockState(slime)
                            + ", redstone=" + helper.getBlockState(redstone));
                }
            })
            .thenSucceed();
    }

    private static int stickyPistonClockState(
        net.minecraft.server.level.ServerLevel level,
        BlockPos piston
    ) {
        var state = level.getBlockState(piston);
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
        helper.setBlock(pos, Blocks.DIAMOND_BLOCK);
        helper.assertBlockPresent(Blocks.DIAMOND_BLOCK, pos);
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void external_sanity_entity(GameTestHelper helper) {
        BlockPos spawn = new BlockPos(2, 2, 2);
        var cow = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "cow"));
        helper.spawn(cow, spawn);
        helper.succeedWhen(() -> helper.assertEntityPresent(cow));
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
            .thenExecute(() -> helper.assertBlockProperty(
                piston, BlockStateProperties.EXTENDED, true))
            .thenExecute(() -> Regionium.scheduler().execute(
                helper.getLevel(),
                helper.absolutePos(power),
                () -> helper.setBlock(power, Blocks.AIR)))
            .thenIdle(10)
            .thenExecute(() -> {
                helper.assertBlockProperty(piston, BlockStateProperties.EXTENDED, false);
                if (helper.getLevel().getBlockEntity(helper.absolutePos(piston)) != null
                    || helper.getLevel().getBlockEntity(helper.absolutePos(head)) != null) {
                    throw helper.assertionException(
                        "Piston left a stale block entity after completing its extend/retract cycle");
                }
            })
            .thenSucceed();
    }

    @GameTest(maxTicks = 1000)
    public void external_sanity_scheduled_lamp(GameTestHelper helper) {
        BlockPos lamp = new BlockPos(2, 2, 2);
        helper.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState()
            .setValue(BlockStateProperties.LIT, true));
        helper.getLevel().scheduleTick(helper.absolutePos(lamp), Blocks.REDSTONE_LAMP, 1);
        helper.succeedWhen(() -> helper.assertBlockProperty(
            lamp, BlockStateProperties.LIT, false));
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
                if (!helper.getBlockState(landing).is(Blocks.SAND)) {
                    throw helper.assertionException(
                        "Falling sand did not convert back into a sand block at "
                            + helper.absolutePos(landing) + ", state=" + helper.getBlockState(landing));
                }
            })
            .thenSucceed();
    }

}
