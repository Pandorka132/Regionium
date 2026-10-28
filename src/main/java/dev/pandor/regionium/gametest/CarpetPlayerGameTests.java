package dev.pandor.regionium.gametest;

import dev.pandor.regionium.Regionium;
import net.minecraft.core.BlockPos;
import dev.pandor.regionium.core.RegioniumRegion;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class CarpetPlayerGameTests {
    private static final String DISTANT_A = "RegioniumDistantA";
    private static final String DISTANT_B = "RegioniumDistantB";
    private static final String TRANSFER_A = "RegioniumTransferA";
    private static final String TRANSFER_B = "RegioniumTransferB";
    private static final String TICK_A = "RegioniumTickA";
    private static final String TICK_B = "RegioniumTickB";
    private static final String SIM_RANGE_A = "RegioniumSimRangeA";
    private static final String SIM_RANGE_B = "RegioniumSimRangeB";

    private static final int DISTANT_BASE_X = 0;
    private static final int TRANSFER_BASE_X = 4096;
    private static final int TICK_BASE_X = 8192;
    private static final int FAR_OFFSET = 1280;

    @GameTest(maxTicks = 200)
    public void regionOwnershipIsSectionBasedAndEmptySectionsStayUnowned(GameTestHelper helper) {
        var level = helper.getLevel();
        var regionizer = Regionium.scheduler().regionizer();
        Regionium.scheduler().registerLevel(level);
        Regionium.scheduler().refreshChunkLeases(level);

        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;

        helper.startSequence()
            .thenWaitUntil(() -> {
                if (regionizer.owner(level, chunkX, chunkZ) == null) {
                    throw helper.assertionException("Waiting for the test chunk to acquire a region owner");
                }
            })
            .thenExecute(() -> {
                RegioniumRegion expected = regionizer.owner(level, chunkX, chunkZ);
                if (expected == null || regionizer.owner(level, chunkX + 1, chunkZ) != expected) {
                    throw helper.assertionException(
                        "Chunks in the same 8x8 region section must resolve to the same section owner");
                }

                // This section is deliberately far outside the test's loaded FULL holders.
                int emptySectionChunkX = chunkX + (8 * 1024);
                if (regionizer.owner(level, emptySectionChunkX, chunkZ) != null) {
                    throw helper.assertionException(
                        "An empty region section without a FULL holder must not retain an owner");
                }

                var visible = ((dev.pandor.regionium.mixins.ChunkMapRegioniumVisibleAccessorMixin)
                    (Object) level.getChunkSource().chunkMap).regionium$getVisibleChunkMap();
                for (var holder : visible.values()) {
                    if (net.minecraft.server.level.ChunkLevel.fullStatus(holder.getTicketLevel())
                            .isOrAfter(net.minecraft.server.level.FullChunkStatus.FULL)
                        && regionizer.owner(level, holder.getPos().pack()) == null) {
                        throw helper.assertionException(
                            "FULL chunk has no region owner: " + holder.getPos()
                                + ", ticketLevel=" + holder.getTicketLevel()
                        );
                    }
                }
            })
            .thenSucceed();
    }

    @GameTest(maxTicks = 1000)
    public void distantCarpetPlayersCreateSeparateRegions(GameTestHelper helper) {
        Regionium.scheduler().registerLevel(helper.getLevel());

        helper.startSequence()
            .thenExecute(() -> {
                killBot(helper, DISTANT_A);
                killBot(helper, DISTANT_B);
                runCommand(helper, "carpet allowSpawningOfflinePlayers true");
                spawnPairAt(helper, DISTANT_A, DISTANT_B, DISTANT_BASE_X);
            })
            .thenWaitUntil(() -> waitForPairOwners(helper, DISTANT_A, DISTANT_B))
            .thenIdle(20)
            .thenExecute(() -> {
                ServerPlayer a = requirePlayer(helper, DISTANT_A);
                ServerPlayer b = requirePlayer(helper, DISTANT_B);

                RegioniumRegion regionA = Regionium.scheduler().regionizer().regionFor(a);
                RegioniumRegion regionB = Regionium.scheduler().regionizer().regionFor(b);

                assertSeparateRegions(helper, a, b, regionA, regionB);

                if (!regionA.worldData().hasEntity(a) || !regionB.worldData().hasEntity(b)) {
                    throw helper.assertionException(
                        "Region player sets do not contain their player owners");
                }
            })
            .thenExecute(() -> cleanupPair(helper, DISTANT_A, DISTANT_B))
            .thenSucceed();
    }

    @GameTest(maxTicks = 800)
    public void regionTickSetsRespectSimulationDistance(GameTestHelper helper) {
        Regionium.scheduler().registerLevel(helper.getLevel());
        Regionium.scheduler().refreshChunkLeases(helper.getLevel());

        helper.startSequence()
            .thenExecute(() -> {
                killBot(helper, SIM_RANGE_A);
                killBot(helper, SIM_RANGE_B);
                runCommand(helper, "carpet allowSpawningOfflinePlayers true");
                spawnPairAt(helper, SIM_RANGE_A, SIM_RANGE_B, 28672);
            })
            .thenWaitUntil(() -> waitForPairOwners(helper, SIM_RANGE_A, SIM_RANGE_B))
            .thenIdle(40)
            .thenExecute(() -> {
                var level = helper.getLevel();
                var distanceManager = level.getChunkSource().chunkMap.getDistanceManager();
                int lazyChunks = 0;

                for (RegioniumRegion region : Regionium.scheduler().regionizer().allRegions()) {
                    if (region.level() != level) {
                        continue;
                    }

                    var data = region.worldData();
                    for (var chunk : data.chunks()) {
                        long chunkKey = chunk.getPos().pack();
                        boolean inBlockTickingRange = distanceManager.inBlockTickingRange(chunkKey);
                        boolean inEntityTickingRange = distanceManager.inEntityTickingRange(chunkKey);
                        boolean markedTicking = data.tickingChunks().contains(chunk);
                        boolean markedEntityTicking = data.entityTickingChunks().contains(chunk);

                        if (markedTicking != inBlockTickingRange) {
                            throw helper.assertionException(
                                "Region tick set disagrees with DistanceManager block-ticking range at "
                                    + chunk.getPos() + ": inBlockTickingRange=" + inBlockTickingRange
                                    + ", markedTicking=" + markedTicking
                            );
                        }
                        if (markedEntityTicking != inEntityTickingRange) {
                            throw helper.assertionException(
                                "Region entity-tick set disagrees with DistanceManager entity-ticking range at "
                                    + chunk.getPos() + ": inEntityTickingRange=" + inEntityTickingRange
                                    + ", markedEntityTicking=" + markedEntityTicking
                            );
                        }
                        if (inBlockTickingRange && !inEntityTickingRange) {
                            lazyChunks++;
                        }
                    }

                    for (var chunk : data.tickingChunks()) {
                        if (!distanceManager.inBlockTickingRange(chunk.getPos().pack())) {
                            throw helper.assertionException(
                                "Region ticking set contains chunk outside block-ticking range: " + chunk.getPos()
                            );
                        }
                    }

                    for (var chunk : data.entityTickingChunks()) {
                        if (!distanceManager.inEntityTickingRange(chunk.getPos().pack())) {
                            throw helper.assertionException(
                                "Region entity-ticking set contains chunk outside simulation range: "
                                    + chunk.getPos()
                            );
                        }
                    }
                }

                if (lazyChunks == 0) {
                    throw helper.assertionException(
                        "Test did not observe any block-ticking chunks outside the entity-ticking range"
                    );
                }
            })
            .thenExecute(() -> cleanupPair(helper, SIM_RANGE_A, SIM_RANGE_B))
            .thenSucceed();
    }

    @GameTest(maxTicks = 1200)
    public void carpetPlayerAutoTransfer(GameTestHelper helper) {
        Regionium.scheduler().registerLevel(helper.getLevel());
        Regionium.scheduler().refreshChunkLeases(helper.getLevel());

        helper.startSequence()
            .thenExecute(() -> {
                killBot(helper, TRANSFER_A);
                killBot(helper, TRANSFER_B);
                runCommand(helper, "carpet allowSpawningOfflinePlayers true");
                spawnPairAt(helper, TRANSFER_A, TRANSFER_B, TRANSFER_BASE_X);
            })
            .thenWaitUntil(() -> waitForPairOwners(helper, TRANSFER_A, TRANSFER_B))
            .thenIdle(20)
            .thenExecute(() -> {
                requirePlayer(helper, TRANSFER_A);
                requirePlayer(helper, TRANSFER_B);
            })
            .thenExecute(() -> {
                ServerPlayer a = requirePlayer(helper, TRANSFER_A);
                ServerPlayer b = requirePlayer(helper, TRANSFER_B);

                RegioniumRegion oldRegion = Regionium.scheduler().regionizer().regionFor(a);
                RegioniumRegion destinationRegion = Regionium.scheduler().regionizer().regionFor(b);

                assertSeparateRegions(helper, a, b, oldRegion, destinationRegion);

                a.teleportTo(
                    helper.getLevel(),
                    TRANSFER_BASE_X + FAR_OFFSET,
                    80.0,
                    0.0,
                    java.util.Set.of(),
                    a.getYRot(),
                    a.getXRot(),
                    true
                );
            })
            .thenIdle(60)
            .thenExecute(() -> {
                ServerPlayer a = requirePlayer(helper, TRANSFER_A);
                ServerPlayer b = requirePlayer(helper, TRANSFER_B);

                RegioniumRegion aRegion = Regionium.scheduler().regionizer().regionFor(a);
                RegioniumRegion bRegion = Regionium.scheduler().regionizer().regionFor(b);

                if (aRegion == null || bRegion == null) {
                    throw helper.assertionException("Transferred player lost its region owner");
                }

                if (aRegion != bRegion) {
                    throw helper.assertionException(
                        "Teleport did not put both players in the destination region: A="
                            + aRegion.id() + ", B=" + bRegion.id());
                }

                int ownerCount = 0;
                for (RegioniumRegion region : Regionium.scheduler().regionizer().allRegions()) {
                    if (region.worldData().hasEntity(a)) {
                        ownerCount++;
                    }
                }

                if (ownerCount != 1 || !aRegion.worldData().hasEntity(a)) {
                    throw helper.assertionException(
                        "Player has invalid region membership after transfer: count=" + ownerCount);
                }
            })
            .thenExecute(() -> cleanupPair(helper, TRANSFER_A, TRANSFER_B))
            .thenSucceed();
    }


    @GameTest(maxTicks = 1600)
    public void carpetPlayerNetherPortalTransferIsRegionThreaded(GameTestHelper helper) {
        var level = helper.getLevel();
        Regionium.scheduler().registerLevel(level);
        Regionium.scheduler().refreshChunkLeases(level);

        final String playerName = "RegioniumPortalPlayer";
        final String anchorName = "RegioniumPortalAnchor";
        final int baseX = 24580;
        final int anchorX = baseX + FAR_OFFSET;
        final int z = 4;
        var playerUuid = new java.util.concurrent.atomic.AtomicReference<java.util.UUID>();
        var portalPosition = new java.util.concurrent.atomic.AtomicReference<BlockPos>();
        var setupFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var nether = level.getServer().getLevel(Level.NETHER);
        if (nether == null) {
            throw helper.assertionException("Nether dimension is unavailable in GameTestServer");
        }

        helper.startSequence()
            .thenExecute(() -> {
                killBot(helper, playerName);
                killBot(helper, anchorName);
                runCommand(helper, "carpet allowSpawningOfflinePlayers true");
                runCommand(helper, "forceload add " + baseX + " " + z);
                runCommand(helper, "forceload add " + anchorX + " " + z);
                runCommand(helper, "player " + playerName + " spawn at " + baseX + " 80 " + z);
                runCommand(helper, "player " + anchorName + " spawn at " + anchorX + " 80 " + z);
            })
            .thenWaitUntil(() -> waitForPairOwners(helper, playerName, anchorName))
            .thenExecute(() -> {
                preparePlatform(helper, baseX, z);
                preparePlatform(helper, anchorX, z);
            })
            .thenWaitUntil(() -> waitForPlatforms(helper, baseX, z, anchorX, z))
            .thenExecute(() -> {
                teleportPlayer(helper, playerName, baseX, z);
                teleportPlayer(helper, anchorName, anchorX, z);
            })
            .thenIdle(20)
            .thenExecute(() -> {
                ServerPlayer player = requirePlayer(helper, playerName);
                BlockPos portalPos = player.blockPosition();
                playerUuid.set(player.getUUID());
                Regionium.scheduler().execute(level, portalPos, () -> {
                    try {
                        level.setBlockAndUpdate(portalPos, Blocks.NETHER_PORTAL.defaultBlockState()
                            .setValue(NetherPortalBlock.AXIS, Direction.Axis.X));
                        player.setPortalCooldown(0);
                        portalPosition.set(portalPos.immutable());
                    } catch (Throwable failure) {
                        setupFailure.set(failure);
                    }
                });
            })
            // Simulate vanilla's per-tick portal contact instead of calling
            // handlePortal 120 times in one region task. The real portal
            // processor advances once per tick and resets its inside flag.
            .thenExecuteFor(120, () -> {
                java.util.UUID uuid = playerUuid.get();
                BlockPos pos = portalPosition.get();
                if (uuid == null || pos == null) {
                    return;
                }
                Entity current = level.getEntity(uuid);
                if (!(current instanceof ServerPlayer player) || player.level() != level || player.isRemoved()) {
                    return;
                }
                Regionium.scheduler().executeWhenOwned(level, pos, () -> {
                    if (!player.isRemoved() && player.level() == level) {
                        if (player.level() != level || player.isRemoved()
                            || level.getEntity(player.getUUID()) != player) {
                            return;
                        }
                        var chunk = dev.pandor.regionium.core.RegioniumPortalChunkAccess.getLoadedChunk(
                            level, pos.getX() >> 4, pos.getZ() >> 4,
                            net.minecraft.world.level.chunk.status.ChunkStatus.FULL);
                        if (chunk == null || !(chunk.getBlockState(pos).getBlock() instanceof Portal actualPortal)) {
                            return;
                        }
                        player.setAsInsidePortal(actualPortal, pos);
                        // This fake player is not driven by the live network
                        // connection tick loop. Advance the portal processor once
                        // for this simulated contact tick, as vanilla would.
                        ((dev.pandor.regionium.mixins.EntityRegioniumPortalInvokerMixin) (Object) player)
                            .regionium$invokeHandlePortal();
                    }
                });
            })
            .thenWaitUntil(() -> {
                Throwable failure = setupFailure.get();
                if (failure != null) {
                    throw helper.assertionException("Could not start player Nether portal transfer: " + failure);
                }
                java.util.UUID uuid = playerUuid.get();
                if (uuid == null) {
                    throw helper.assertionException("Waiting for player Nether portal setup");
                }
                Entity transferred = nether.getEntity(uuid);
                if (!(transferred instanceof ServerPlayer player) || player.level() != nether) {
                    throw helper.assertionException("Waiting for asynchronous player Nether portal transfer");
                }
                if (player.getX() > 10000.0 || player.getX() < -10000.0) {
                    throw helper.assertionException("Nether portal transfer kept unscaled Overworld coordinates: " + player.position());
                }
                if (Regionium.scheduler().regionizer().regionFor(player) == null) {
                    throw helper.assertionException("Transferred player did not acquire a Nether region owner");
                }
            })
            .thenExecute(() -> {
                cleanupPair(helper, playerName, anchorName);
                runCommand(helper, "forceload remove " + baseX + " " + z);
                runCommand(helper, "forceload remove " + anchorX + " " + z);
            })
            .thenSucceed();
    }

    /**
     * Regression for failures that only appeared after several player transfers.
     * Three A -> B -> A cycles force repeated region merge/split operations while
     * block and fluid ticks are pending. A final real UseItemOn packet checks
     * the packet queue, block placement, neighbour propagation, and piston event.
     */
    @GameTest(maxTicks = 1800)
    public void repeatedTransfersPreserveTicksAndPacketWorldUpdates(GameTestHelper helper) {
        final String moverName = "RegioniumRepeatMove";
        final String anchorName = "RegioniumRepeatAnchor";
        final int baseX = 16384;
        final int destinationX = baseX + FAR_OFFSET;
        final BlockPos lampPos = new BlockPos(destinationX + 8, 80, 0);
        final BlockPos waterPos = new BlockPos(destinationX + 12, 80, 0);
        final BlockPos waterFlowPos = waterPos.east();
        final BlockPos pistonPos = new BlockPos(destinationX + 2, 80, 2);
        final BlockPos redstoneSupportPos = pistonPos.west();
        final BlockPos redstonePos = redstoneSupportPos;
        final RegioniumRegion[] initialOwners = new RegioniumRegion[2];

        Regionium.scheduler().registerLevel(helper.getLevel());
        Regionium.scheduler().refreshChunkLeases(helper.getLevel());

        helper.startSequence()
            .thenExecute(() -> {
                killBot(helper, moverName);
                killBot(helper, anchorName);
                runCommand(helper, "carpet allowSpawningOfflinePlayers true");
                spawnPairAt(helper, moverName, anchorName, baseX);
            })
            .thenWaitUntil(() -> waitForPairOwners(helper, moverName, anchorName))
            .thenExecute(() -> {
                preparePlatform(helper, baseX);
                preparePlatform(helper, destinationX);
            })
            .thenWaitUntil(() -> waitForPlatforms(helper, baseX, destinationX))
            .thenExecute(() -> {
                teleportPlayer(helper, moverName, baseX);
                teleportPlayer(helper, anchorName, destinationX);
            })
            .thenIdle(20)
            .thenExecute(() -> {
                ServerPlayer mover = requirePlayer(helper, moverName);
                ServerPlayer anchor = requirePlayer(helper, anchorName);
                initialOwners[0] = Regionium.scheduler().regionizer().regionFor(mover);
                initialOwners[1] = Regionium.scheduler().regionizer().regionFor(anchor);
                if (initialOwners[0] == null || initialOwners[1] == null || initialOwners[0] == initialOwners[1]) {
                    throw helper.assertionException("Transfer test players did not begin in distinct regions");
                }
                if (Regionium.scheduler().regionizer().regionFor(anchor) == null) {
                    throw helper.assertionException("Anchor has no region before tick setup");
                }
                Regionium.scheduler().execute(helper.getLevel(), lampPos, () -> {
                    var level = helper.getLevel();
                    level.setBlock(lampPos, Blocks.REDSTONE_LAMP.defaultBlockState()
                        .setValue(BlockStateProperties.LIT, true), 3);
                    level.scheduleTick(lampPos, Blocks.REDSTONE_LAMP, 500);
                    level.setBlock(waterPos.below(), Blocks.STONE.defaultBlockState(), 3);
                    level.setBlock(waterFlowPos.below(), Blocks.STONE.defaultBlockState(), 3);
                    level.setBlock(waterPos.north().below(), Blocks.STONE.defaultBlockState(), 3);
                    level.setBlock(waterPos.south().below(), Blocks.STONE.defaultBlockState(), 3);
                    level.setBlock(waterPos.west().below(), Blocks.STONE.defaultBlockState(), 3);
                    level.setBlock(waterPos, Blocks.WATER.defaultBlockState(), 3);
                    level.scheduleTick(waterPos, Fluids.WATER, 500);
                });
            })
            .thenExecute(() -> teleportPlayer(helper, moverName, destinationX))
            .thenWaitUntil(() -> waitForSameOwner(helper, moverName, anchorName))
            .thenIdle(120)
            .thenExecute(() -> teleportPlayer(helper, moverName, baseX))
            .thenWaitUntil(() -> waitForSeparateOwners(helper, moverName, anchorName))
            .thenIdle(8)
            .thenExecute(() -> assertRegionTopologyConsistent(helper))
            .thenExecute(() -> teleportPlayer(helper, moverName, destinationX))
            .thenWaitUntil(() -> waitForSameOwner(helper, moverName, anchorName))
            .thenIdle(8)
            .thenExecute(() -> teleportPlayer(helper, moverName, baseX))
            .thenWaitUntil(() -> waitForSeparateOwners(helper, moverName, anchorName))
            .thenIdle(8)
            .thenExecute(() -> assertRegionTopologyConsistent(helper))
            .thenExecute(() -> teleportPlayer(helper, moverName, destinationX))
            .thenWaitUntil(() -> waitForSameOwner(helper, moverName, anchorName))
            .thenIdle(8)
            .thenExecute(() -> teleportPlayer(helper, moverName, baseX))
            .thenWaitUntil(() -> waitForSeparateOwners(helper, moverName, anchorName))
            .thenExecute(() -> assertRegionTopologyConsistent(helper))
            .thenIdle(400)
            .thenExecute(() -> {
                if (helper.getLevel().getBlockState(lampPos).getValue(BlockStateProperties.LIT)) {
                    throw helper.assertionException("Scheduled block tick was lost/delayed after repeated transfers: "
                        + helper.getLevel().getBlockState(lampPos));
                }
                if (helper.getLevel().getFluidState(waterFlowPos).isEmpty()) {
                    throw helper.assertionException("Scheduled fluid tick did not spread water after repeated transfers: "
                        + "source=" + helper.getLevel().getFluidState(waterPos)
                        + ", adjacent=" + helper.getLevel().getFluidState(waterFlowPos));
                }
            })
            .thenExecute(() -> teleportPlayer(helper, moverName, destinationX))
            .thenWaitUntil(() -> waitForSameOwner(helper, moverName, anchorName))
            .thenExecute(() -> Regionium.scheduler().execute(helper.getLevel(), pistonPos, () -> {
                var level = helper.getLevel();
                level.setBlock(redstoneSupportPos.below(), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(pistonPos.below(), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(pistonPos, Blocks.PISTON.defaultBlockState()
                    .setValue(BlockStateProperties.FACING, Direction.EAST), 3);

                ServerPlayer mover = requirePlayer(helper, moverName);
                mover.setGameMode(GameType.CREATIVE);
                mover.getInventory().setSelectedSlot(0);
                mover.getInventory().setItem(0, new ItemStack(Items.REDSTONE_BLOCK));
                // Carpet fake connections do not receive the client's normal load-ack packet.
                // Put the listener into the same ready state a real client reaches after loading.
                for (int i = 0; i < 200 && !mover.connection.hasClientLoaded(); i++) {
                    mover.connection.tickClientLoadTimeout();
                }
                if (!mover.connection.hasClientLoaded()) {
                    throw helper.assertionException("Fake player's packet listener never reached client-loaded state");
                }
                var packetAccess = (dev.pandor.regionium.access.ServerPlayerRegioniumPacketAccess) (Object) mover;
                // Carpet's fake connection has no client to acknowledge the server teleport.
                // Send the same acknowledgement a real client sends before interaction.
                var listenerAccess = (dev.pandor.regionium.mixins.ServerGamePacketListenerRegioniumAccessorMixin)
                    (Object) mover.connection;
                packetAccess.regionium$schedulePacket(mover.connection, new ServerboundAcceptTeleportationPacket(
                    listenerAccess.regionium$getAwaitingTeleport(), mover.getX(), mover.getY(), mover.getZ(),
                    mover.getYRot(), mover.getXRot()
                ));
                packetAccess.regionium$schedulePacket(mover.connection, new ServerboundMovePlayerPacket.PosRot(
                    mover.getX(), mover.getY(), mover.getZ(), mover.getYRot(), mover.getXRot(), true, false
                ));
                packetAccess.regionium$schedulePacket(mover.connection, new ServerboundUseItemOnPacket(
                        InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(redstoneSupportPos.below()), Direction.UP,
                            redstoneSupportPos.below(), false),
                        0
                    ));
            }))
            .thenWaitUntil(() -> {
                if (!helper.getLevel().getBlockState(redstonePos).is(Blocks.REDSTONE_BLOCK)) {
                    ServerPlayer mover = requirePlayer(helper, moverName);
                    RegioniumRegion packetOwner = Regionium.scheduler().regionizer().regionFor(mover);
                    var processor = ((dev.pandor.regionium.access.ServerPlayerRegioniumPacketAccess) (Object) mover)
                        .regionium$getPacketProcessor();
                    boolean queued = ((dev.pandor.regionium.access.PacketProcessorRegioniumAccess) (Object) processor)
                        .regionium$hasPackets();
                    throw helper.assertionException("Queued ServerboundUseItemOnPacket did not place redstone: "
                        + helper.getLevel().getBlockState(redstonePos)
                        + ", owner=" + packetOwner
                        + ", ownerHasPlayer=" + (packetOwner != null && packetOwner.worldData().players().contains(mover))
                        + ", ownerHasEntity=" + (packetOwner != null && packetOwner.worldData().hasEntity(mover))
                        + ", queuedPackets=" + queued
                        + ", support=" + helper.getLevel().getBlockState(redstoneSupportPos.below())
                        + ", piston=" + helper.getLevel().getBlockState(pistonPos)
                        + ", hand=" + mover.getItemInHand(InteractionHand.MAIN_HAND)
                        + ", inRange=" + mover.isWithinBlockInteractionRange(redstoneSupportPos.below(), 1.0)
                        + ", moverPos=" + mover.position()
                        + ", target=" + redstoneSupportPos.below()
                        + ", clientLoaded=" + mover.connection.hasClientLoaded()
                        + ", connection=" + mover.connection);
                }
            })
            .thenIdle(5)
            .thenExecute(() -> {
                if (!helper.getLevel().getBlockState(pistonPos).getValue(BlockStateProperties.EXTENDED)) {
                    throw helper.assertionException(
                        "Block-placement packet placed redstone, but neighbour/block-event processing did not extend piston: "
                            + helper.getLevel().getBlockState(pistonPos)
                            + ", neighborSignal=" + helper.getLevel().hasNeighborSignal(pistonPos)
                            + ", redstone=" + helper.getLevel().getBlockState(redstonePos)
                            + ", region=" + Regionium.scheduler().regionizer().owner(helper.getLevel(), net.minecraft.world.level.ChunkPos.pack(pistonPos)));
                }
            })
            .thenExecute(() -> {
                ServerPlayer mover = requirePlayer(helper, moverName);
                RegioniumRegion owner = Regionium.scheduler().regionizer().regionFor(mover);
                if (owner == null || owner != Regionium.scheduler().regionizer().regionFor(
                    requirePlayer(helper, anchorName))) {
                    throw helper.assertionException("Packet test lost destination-region ownership");
                }
                long memberships = Regionium.scheduler().regionizer().allRegions().stream()
                    .filter(region -> region.worldData().hasEntity(mover)).count();
                if (memberships != 1) {
                    throw helper.assertionException("Invalid region membership after repeated transfers: " + memberships);
                }
                cleanupPair(helper, moverName, anchorName);
            })
            .thenSucceed();
    }

    @GameTest(maxTicks = 800)
    public void regionTickStressDoesNotFail(GameTestHelper helper) {
        Regionium.scheduler().registerLevel(helper.getLevel());
        Regionium.scheduler().refreshChunkLeases(helper.getLevel());

        final long[] failures = new long[2];
        final long[] ticks = new long[2];
        final RegioniumRegion[] regions = new RegioniumRegion[2];

        helper.startSequence()
            .thenExecute(() -> {
                killBot(helper, "RegioniumStressA");
                killBot(helper, "RegioniumStressB");
                runCommand(helper, "carpet allowSpawningOfflinePlayers true");
                runCommand(helper, "gamerule randomTickSpeed 1000");
                spawnPairAt(helper, "RegioniumStressA", "RegioniumStressB", 12288);
            })
            .thenWaitUntil(() -> waitForPairOwners(helper, "RegioniumStressA", "RegioniumStressB"))
            .thenIdle(100)
            .thenExecute(() -> {
                ServerPlayer a = requirePlayer(helper, "RegioniumStressA");
                ServerPlayer b = requirePlayer(helper, "RegioniumStressB");
                regions[0] = Regionium.scheduler().regionizer().regionFor(a);
                regions[1] = Regionium.scheduler().regionizer().regionFor(b);

                assertSeparateRegions(helper, a, b, regions[0], regions[1]);

                failures[0] = regions[0].tickFailures();
                failures[1] = regions[1].tickFailures();
                ticks[0] = regions[0].tickCount();
                ticks[1] = regions[1].tickCount();

                helper.getLevel().setBlock(
                    new BlockPos(12288, 80, 1),
                    Blocks.GRASS_BLOCK.defaultBlockState(),
                    3
                );
                helper.getLevel().setBlock(
                    new BlockPos(12289, 80, 1),
                    Blocks.DIRT.defaultBlockState(),
                    3
                );
            })
            .thenIdle(200)
            .thenExecute(() -> {
                if (regions[0].tickFailures() != failures[0]
                    || regions[1].tickFailures() != failures[1]) {
                    throw helper.assertionException(
                        "Region tick failures occurred during stress run: "
                            + "A=" + (regions[0].tickFailures() - failures[0])
                            + ", B=" + (regions[1].tickFailures() - failures[1]));
                }
                if (regions[0].tickCount() <= ticks[0]
                    || regions[1].tickCount() <= ticks[1]) {
                    throw helper.assertionException(
                        "Region stopped ticking during stress run");
                }
                runCommand(helper, "gamerule randomTickSpeed 3");
            })
            .thenExecute(() -> cleanupPair(helper, "RegioniumStressA", "RegioniumStressB"))
            .thenSucceed();
    }

    @GameTest(maxTicks = 1000)
    public void separateRegionsKeepTicking(GameTestHelper helper) {
        Regionium.scheduler().registerLevel(helper.getLevel());
        Regionium.scheduler().refreshChunkLeases(helper.getLevel());

        long[] beforeTicks = new long[2];
        String[] workerThreads = new String[2];

        helper.startSequence()
            .thenExecute(() -> {
                killBot(helper, TICK_A);
                killBot(helper, TICK_B);
                runCommand(helper, "carpet allowSpawningOfflinePlayers true");
                spawnPairAt(helper, TICK_A, TICK_B, TICK_BASE_X);
            })
            .thenWaitUntil(() -> waitForPairOwners(helper, TICK_A, TICK_B))
            .thenIdle(20)
            .thenExecute(() -> {
                requirePlayer(helper, TICK_A);
                requirePlayer(helper, TICK_B);
            })
            .thenExecute(() -> {
                ServerPlayer a = requirePlayer(helper, TICK_A);
                ServerPlayer b = requirePlayer(helper, TICK_B);

                RegioniumRegion regionA = Regionium.scheduler().regionizer().regionFor(a);
                RegioniumRegion regionB = Regionium.scheduler().regionizer().regionFor(b);

                assertSeparateRegions(helper, a, b, regionA, regionB);

                beforeTicks[0] = regionA.tickCount();
                beforeTicks[1] = regionB.tickCount();
                workerThreads[0] = regionA.lastWorkerThreadName();
                workerThreads[1] = regionB.lastWorkerThreadName();

                if (workerThreads[0].equals("<not ticked yet>")
                    || workerThreads[1].equals("<not ticked yet>")) {
                    throw helper.assertionException("A region never executed on a worker thread");
                }
            })
            .thenIdle(40)
            .thenExecute(() -> {
                ServerPlayer a = requirePlayer(helper, TICK_A);
                ServerPlayer b = requirePlayer(helper, TICK_B);
                RegioniumRegion regionA = Regionium.scheduler().regionizer().regionFor(a);
                RegioniumRegion regionB = Regionium.scheduler().regionizer().regionFor(b);

                if (regionA == null || regionB == null || regionA == regionB) {
                    throw helper.assertionException("Regions merged or lost ownership while ticking");
                }

                long deltaA = regionA.tickCount() - beforeTicks[0];
                long deltaB = regionB.tickCount() - beforeTicks[1];

                if (deltaA <= 0 || deltaB <= 0) {
                    throw helper.assertionException(
                        "One region stopped ticking: deltaA=" + deltaA + ", deltaB=" + deltaB);
                }

                // Worker-pool balancing may execute a region on a different
                // worker between ticks. The invariant is continued independent
                // ticking with valid Regionium worker execution, not fixed thread affinity.
                if (!regionA.lastWorkerThreadName().startsWith("Regionium-Worker-")
                    || !regionB.lastWorkerThreadName().startsWith("Regionium-Worker-")) {
                    throw helper.assertionException(
                        "A region stopped executing on the Regionium worker pool: A="
                            + regionA.lastWorkerThreadName() + ", B=" + regionB.lastWorkerThreadName());
                }

                long failuresA = regionA.tickFailures();
                long failuresB = regionB.tickFailures();
                if (failuresA != 0 || failuresB != 0) {
                    throw helper.assertionException(
                        "Region tick pipeline failed while both regions were active: "
                            + "A failures=" + failuresA + ", B failures=" + failuresB);
                }
            })
            .thenExecute(() -> cleanupPair(helper, TICK_A, TICK_B))
            .thenSucceed();
    }

    @GameTest(maxTicks = 100)
    public void scheduledTickTimeRebasePreservesDelay(GameTestHelper helper) {
        var level = helper.getLevel();
        Regionium.scheduler().registerLevel(level);
        Regionium.scheduler().refreshChunkLeases(level);

        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<String> failure = new java.util.concurrent.atomic.AtomicReference<>();

        helper.startSequence()
            .thenWaitUntil(() -> {
                if (Regionium.scheduler().regionizer().owner(
                    level, net.minecraft.world.level.ChunkPos.pack(pos)) == null) {
                    throw helper.assertionException("Scheduled-tick test chunk is not assigned to a region yet");
                }
            })
            .thenExecute(() -> {
                RegioniumRegion owner = Regionium.scheduler().regionizer().owner(
                    level, net.minecraft.world.level.ChunkPos.pack(pos));
                if (owner == null) {
                    throw helper.assertionException("Scheduled-tick test chunk has no region owner");
                }

                owner.execute(() -> {
                    try {
                        var chunk = level.getChunkAt(pos);
                        var ticks = ((dev.pandor.regionium.mixins.LevelChunkRegioniumTickAccessorMixin)
                            (Object) chunk).regionium$getBlockTicks();
                        long originalTrigger = owner.worldData().redstoneTime() + 100L;
                        ticks.schedule(new net.minecraft.world.ticks.ScheduledTick<>(
                            Blocks.STONE, pos, originalTrigger, net.minecraft.world.ticks.TickPriority.NORMAL,
                            owner.worldData().nextSubTickCount()
                        ));

                        dev.pandor.regionium.core.RegioniumWorldData.shiftScheduledTicks(chunk, 37L);
                        long expectedTrigger = originalTrigger + 37L;

                        // Deliberately desynchronize this test region from global gameTime:
                        // createTick must use the owner region's clock, not LevelData.gameTime.
                        for (int i = 0; i < 25; i++) {
                            owner.worldData().advanceRedstoneTime();
                        }
                        long expectedCreatedTrigger = owner.worldData().redstoneTime() + 13L;
                        var created = level.createTick(
                            pos.above(), Blocks.STONE, 13
                        );
                        if (created.triggerTick() != expectedCreatedTrigger) {
                            failure.set("New scheduled tick used the wrong clock: expected trigger="
                                + expectedCreatedTrigger + ", actual=" + created.triggerTick());
                        }
                        var shifted = ticks.getAll()
                            .filter(tick -> tick.pos().equals(pos) && tick.type() == Blocks.STONE)
                            .findFirst()
                            .orElse(null);
                        if (shifted == null || shifted.triggerTick() != expectedTrigger) {
                            failure.set("Scheduled tick delay changed during clock rebase: expected trigger="
                                + expectedTrigger + ", actual=" + shifted);
                        }
                    } catch (Throwable error) {
                        failure.set("Scheduled tick clock rebase threw " + error);
                    } finally {
                        finished.set(true);
                    }
                });
            })
            .thenWaitUntil(finished::get)
            .thenExecute(() -> {
                if (failure.get() != null) {
                    throw helper.assertionException(failure.get());
                }
            })
            .thenSucceed();
    }

    /**
     * A quasi-connectivity sticky-piston clock must keep oscillating while its
     * nearby chunks are repeatedly merged into and split out of another
     * player's region. Transfers are four game ticks apart, and the piston is
     * sampled every third game tick to verify its actual oscillation cadence.
     */
    @GameTest(maxTicks = 1600)
    public void stickyPistonClockSurvivesTwentyTransfers(GameTestHelper helper) {
        final String moverName = "RegioniumPistonMove";
        final String anchorName = "RegioniumPistonAnchor";
        // Anchor the machine in the GameTest structure's already-active chunk;
        // the mover remains far enough away to force real region split/merge.
        final BlockPos structureAnchor = helper.absolutePos(new BlockPos(8, 1, 8));
        final int baseX = structureAnchor.getX();
        final int baseZ = structureAnchor.getZ();
        final int destinationX = baseX + FAR_OFFSET;
        final BlockPos piston = new BlockPos(baseX + 2, 80, baseZ + 2);
        final BlockPos slime = piston.above();
        final BlockPos redstone = slime.relative(Direction.SOUTH);
        final BlockPos update = piston.relative(Direction.SOUTH);
        final int[] previousState = {-1};
        final int[] sampledTicks = {0};
        final int[] sampleBaseline = {-1};
        final int[] sampledChanges = {0};
        final int[] sampledStates = new int[30];
        final long[] sampledRegionTicks = new long[30];
        final RegioniumRegion[] ownerBeforeTransfer = {null};
        final int[] elapsedTicks = {0};
        final int[] transfers = {0};

        Regionium.scheduler().registerLevel(helper.getLevel());
        Regionium.scheduler().refreshChunkLeases(helper.getLevel());

        var sequence = helper.startSequence();
        sequence.thenExecute(() -> {
            killBot(helper, moverName);
            killBot(helper, anchorName);
            runCommand(helper, "carpet allowSpawningOfflinePlayers true");
            runCommand(helper, "forceload add " + baseX + " " + baseZ);
            runCommand(helper, "forceload add " + destinationX + " " + baseZ);
            runCommand(helper, "player " + anchorName + " spawn at " + baseX + " 80 " + baseZ);
            runCommand(helper, "player " + moverName + " spawn at " + destinationX + " 80 " + baseZ);
        });
        sequence.thenWaitUntil(() -> waitForPairOwners(helper, moverName, anchorName));
        sequence.thenWaitUntil(() -> {
            ServerPlayer mover = requirePlayer(helper, moverName);
            ServerPlayer anchor = requirePlayer(helper, anchorName);
            if (!Regionium.scheduler().arePlayerPhysicsChunksReady(helper.getLevel(), mover)
                || !Regionium.scheduler().arePlayerPhysicsChunksReady(helper.getLevel(), anchor)) {
                throw helper.assertionException("Waiting for all player-adjacent chunks to become FULL");
            }
        });
        sequence.thenExecute(() -> {
            preparePlatform(helper, baseX, baseZ);
            preparePlatform(helper, destinationX, baseZ);
        });
        sequence.thenWaitUntil(() -> waitForPlatforms(helper, baseX, baseZ, destinationX, baseZ));
        sequence.thenExecute(() -> {
            // Build the initial machine on the GameTest/server thread, then let
            // Regionium own every subsequent piston tick and transfer.
            var level = helper.getLevel();
            // GameTest worlds can be reused across runs; clear stale blocks so
            // the final setBlock really emits the neighbor update that starts
            // the quasi-connectivity clock.
            level.setBlock(piston, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(slime, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(redstone, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(update, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(piston.below(), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(piston.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(piston, Blocks.STICKY_PISTON.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.UP), 3);
            level.setBlock(slime, Blocks.SLIME_BLOCK.defaultBlockState(), 3);
            level.setBlock(redstone, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            // Do not place scaffolding under the redstone block: the intended
            // reproduction is exactly the piston, slime block, and redstone block.
        });
        sequence.thenExecute(() -> Regionium.scheduler().execute(helper.getLevel(), piston, () ->
            helper.getLevel().neighborChanged(piston, Blocks.STONE, null)));
        sequence.thenIdle(10);
        sequence.thenExecute(() -> {
            int state = stickyPistonClockState(helper.getLevel(), piston);
            if (state == 3 || state == 2) {
                throw helper.assertionException("Piston clock did not initialize: "
                    + helper.getLevel().getBlockState(piston));
            }
            previousState[0] = state;
        });
        // Align the cadence window to a real piston transition. Otherwise a
        // test window beginning immediately before the first pulse can compare
        // two samples from the same side of the transition and report a false
        // failure even though the clock is about to start oscillating.
        sequence.thenWaitUntil(() -> {
            int state = stickyPistonClockState(helper.getLevel(), piston);
            if (state == 3 || state == 2 || state == previousState[0]) {
                throw helper.assertionException("Waiting for first piston-clock transition: state="
                    + state + ", piston=" + helper.getLevel().getBlockState(piston));
            }
            previousState[0] = state;
        });
        sequence.thenExecute(() -> {
            sampledTicks[0] = 0;
            sampleBaseline[0] = -1;
            sampledChanges[0] = 0;
            elapsedTicks[0] = 0;
            transfers[0] = 0;
        });
        // Transfers are attempted exactly four game ticks apart. Verify the
        // previous transfer's actual owner change at the next four-tick boundary,
        // while sampling the piston once every three game ticks throughout.
        sequence.thenExecuteFor(81, () -> {
            int tick = elapsedTicks[0]++;
            if (tick % 3 == 0) {
                sampleStickyPistonClock(helper, piston, previousState, sampledTicks, sampleBaseline,
                    sampledChanges, sampledStates, sampledRegionTicks, transfers[0]);
            }
            if (tick % 4 == 0) {
                if (transfers[0] > 0) {
                    int completedTransfer = transfers[0];
                    assertTransferChangedOwner(helper, moverName, anchorName, ownerBeforeTransfer[0],
                        completedTransfer, (completedTransfer & 1) == 1);
                }
                if (transfers[0] < 20) {
                    int transferNumber = ++transfers[0];
                    int targetX = (transferNumber & 1) == 1 ? baseX : destinationX;
                    ownerBeforeTransfer[0] = Regionium.scheduler().regionizer().regionFor(
                        requirePlayer(helper, moverName));
                    if (ownerBeforeTransfer[0] == null) {
                        throw helper.assertionException("Mover has no region before transfer " + transferNumber);
                    }
                    teleportPlayer(helper, moverName, targetX, baseZ);
                }
            }
        });
        sequence.thenExecute(() -> {
            String failure = clockTransitionFailure(helper, piston, 20, sampledChanges[0],
                sampledTicks[0], sampledStates, sampledRegionTicks);
            if (failure != null) throw helper.assertionException(failure);
            if (transfers[0] != 20) {
                throw helper.assertionException("Expected 20 transfers, got " + transfers[0]);
            }
        });

        sequence.thenExecute(() -> {
            killBot(helper, moverName);
            killBot(helper, anchorName);
            runCommand(helper, "forceload remove " + baseX + " " + baseZ);
            runCommand(helper, "forceload remove " + destinationX + " " + baseZ);
        });
        sequence.thenSucceed();
    }

    private static void sampleStickyPistonClock(
        GameTestHelper helper,
        BlockPos piston,
        int[] previousState,
        int[] sampledTicks,
        int[] sampleBaseline,
        int[] sampledChanges,
        int[] sampledStates,
        long[] sampledRegionTicks,
        int transfer
    ) {
        int state = stickyPistonClockState(helper.getLevel(), piston);
        if (state == 3) {
            throw helper.assertionException("Piston clock left its expected state during transfer "
                + transfer + ": " + helper.getLevel().getBlockState(piston));
        }
        sampledStates[sampledTicks[0]] = state;
        var currentOwner = Regionium.scheduler().regionizer().owner(helper.getLevel(),
            net.minecraft.world.level.ChunkPos.pack(piston));
        sampledRegionTicks[sampledTicks[0]] = currentOwner == null ? -1L : currentOwner.tickCount();
        if (sampledTicks[0] == 0) {
            sampleBaseline[0] = state;
        } else {
            if (state == sampleBaseline[0]) {
                throw helper.assertionException("Quasi-connectivity piston did not change state between "
                    + "three-tick samples during transfer " + transfer + ": previous="
                    + sampleBaseline[0] + ", current=" + state + ", sample=" + sampledTicks[0]);
            }
            sampledChanges[0]++;
            sampleBaseline[0] = state;
        }
        previousState[0] = state;
        sampledTicks[0]++;
    }

    private static String clockTransitionFailure(
        GameTestHelper helper,
        BlockPos piston,
        int transfer,
        int sampledChanges,
        int sampledTicks,
        int[] sampledStates,
        long[] sampledRegionTicks
    ) {
        int expectedChanges = Math.max(0, sampledTicks - 1);
        if (sampledChanges != expectedChanges || sampledTicks < 20) {
            var level = helper.getLevel();
            var owner = Regionium.scheduler().regionizer().owner(level,
                net.minecraft.world.level.ChunkPos.pack(piston));
            return "Quasi-connectivity piston clock failed three-game-tick cadence during transfer sequence " + transfer
                    + ": changes=" + sampledChanges + "/" + expectedChanges
                    + ", sampledTicks=" + sampledTicks + ", trace="
                    + java.util.Arrays.toString(java.util.Arrays.copyOf(sampledStates, sampledTicks))
                    + ", regionTicks="
                    + java.util.Arrays.toString(java.util.Arrays.copyOf(sampledRegionTicks, sampledTicks))
                    + ", piston=" + level.getBlockState(piston)
                    + ", slime=" + level.getBlockState(piston.above())
                    + ", redstone=" + level.getBlockState(piston.above().relative(Direction.SOUTH))
                    + ", update=" + level.getBlockState(piston.relative(Direction.SOUTH))
                    + ", shouldTick=" + level.shouldTickBlocksAt(piston)
                    + ", owner=" + owner
                    + ", ownerHasChunk=" + (owner != null && owner.worldData().hasChunk(
                        net.minecraft.world.level.ChunkPos.pack(piston)));
        }
        return null;
    }

    private static void assertTransferChangedOwner(
        GameTestHelper helper,
        String moverName,
        String anchorName,
        RegioniumRegion previousOwner,
        int transfer,
        boolean shouldMerge
    ) {
        ServerPlayer mover = requirePlayer(helper, moverName);
        ServerPlayer anchor = requirePlayer(helper, anchorName);
        RegioniumRegion currentOwner = Regionium.scheduler().regionizer().regionFor(mover);
        RegioniumRegion anchorOwner = Regionium.scheduler().regionizer().regionFor(anchor);
        if (currentOwner == null || currentOwner == previousOwner
            || (shouldMerge ? currentOwner != anchorOwner : currentOwner == anchorOwner)) {
            throw helper.assertionException("Transfer " + transfer + " did not change the mover's region: "
                + "previous=" + previousOwner + ", current=" + currentOwner
                + ", anchor=" + anchorOwner + ", expectedMerge=" + shouldMerge
                + ", moverChunk=" + mover.chunkPosition() + ", anchorChunk=" + anchor.chunkPosition());
        }
    }

    private static int stickyPistonClockState(
        net.minecraft.server.level.ServerLevel level,
        BlockPos piston
    ) {
        var state = level.getBlockState(piston);
        if (state.is(Blocks.MOVING_PISTON)) {
            var blockEntity = level.getBlockEntity(piston);
            if (blockEntity instanceof net.minecraft.world.level.block.piston.PistonMovingBlockEntity moving
                && moving.isSourcePiston()) {
                // The source piston is represented by MOVING_PISTON during its
                // animation. Observe its logical direction, not that transient
                // rendering state, or a valid two-tick pulse looks like a stall.
                return moving.isExtending() ? 1 : 0;
            }
            return 2;
        }
        if (state.is(Blocks.STICKY_PISTON)) {
            return state.getValue(BlockStateProperties.EXTENDED) ? 1 : 0;
        }
        return 3;
    }

    private static void assertRegionTopologyConsistent(GameTestHelper helper) {
        var regionizer = Regionium.scheduler().regionizer();
        var active = regionizer.activeRegions(helper.getLevel());
        for (RegioniumRegion region : regionizer.regions(helper.getLevel())) {
            if (region.isRetired() || !active.contains(region)) {
                throw helper.assertionException(
                    "Region registry retained an inactive/retired region after transfer: id=" + region.id()
                        + ", retired=" + region.isRetired()
                        + ", active=" + active.contains(region)
                );
            }
        }
    }

    private static void preparePlatform(GameTestHelper helper, int x) {
        preparePlatform(helper, x, 0);
    }

    private static void preparePlatform(GameTestHelper helper, int x, int z) {
        Regionium.scheduler().execute(helper.getLevel(), new BlockPos(x, 80, z), () -> {
            // Keep setup scaffolding inside the player's owned chunk and avoid
            // neighbor propagation into chunks that this test region does not own.
            for (int dx = 0; dx < 8; dx++) {
                for (int dz = 0; dz <= 4; dz++) {
                    helper.getLevel().setBlock(
                        new BlockPos(x + dx, 79, z + dz),
                        Blocks.STONE.defaultBlockState(),
                        2
                    );
                }
            }
        });
    }

    private static void waitForPlatforms(GameTestHelper helper, int firstX, int secondX) {
        waitForPlatforms(helper, firstX, 0, secondX, 0);
    }

    private static void waitForPlatforms(
        GameTestHelper helper, int firstX, int firstZ, int secondX, int secondZ
    ) {
        if (!helper.getLevel().getBlockState(new BlockPos(firstX, 79, firstZ)).is(Blocks.STONE)
            || !helper.getLevel().getBlockState(new BlockPos(secondX, 79, secondZ)).is(Blocks.STONE)) {
            throw helper.assertionException("Waiting for transfer test platforms to be built");
        }
    }

    private static void teleportPlayer(GameTestHelper helper, String name, int x) {
        teleportPlayer(helper, name, x, 0);
    }

    private static void teleportPlayer(GameTestHelper helper, String name, int x, int z) {
        ServerPlayer player = requirePlayer(helper, name);
        player.teleportTo(
            helper.getLevel(), x, 80.0, z, java.util.Set.of(),
            player.getYRot(), player.getXRot(), true
        );
    }

    private static void waitForSameOwner(GameTestHelper helper, String first, String second) {
        ServerPlayer a = requirePlayer(helper, first);
        ServerPlayer b = requirePlayer(helper, second);
        RegioniumRegion ownerA = Regionium.scheduler().regionizer().regionFor(a);
        RegioniumRegion ownerB = Regionium.scheduler().regionizer().regionFor(b);
        if (ownerA == null || ownerB == null || ownerA != ownerB) {
            throw helper.assertionException(
                "Waiting for region merge: A=" + ownerA + ", B=" + ownerB
                    + ", A chunk=" + chunkLifecycle(helper.getLevel(), a.blockPosition())
                    + ", B chunk=" + chunkLifecycle(helper.getLevel(), b.blockPosition())
            );
        }
    }

    private static void waitForSeparateOwners(GameTestHelper helper, String first, String second) {
        ServerPlayer a = requirePlayer(helper, first);
        ServerPlayer b = requirePlayer(helper, second);
        RegioniumRegion ownerA = Regionium.scheduler().regionizer().regionFor(a);
        RegioniumRegion ownerB = Regionium.scheduler().regionizer().regionFor(b);
        if (ownerA == null || ownerB == null || ownerA == ownerB) {
            throw helper.assertionException(
                "Waiting for region split: A=" + ownerA + ", B=" + ownerB
                    + ", A chunk=" + chunkLifecycle(helper.getLevel(), a.blockPosition())
                    + ", B chunk=" + chunkLifecycle(helper.getLevel(), b.blockPosition())
            );
        }
    }

    private static void spawnPairAt(
        GameTestHelper helper,
        String first,
        String second,
        int baseX
    ) {
        runCommand(
            helper,
            "player " + first + " spawn at " + baseX + " 80 0"
        );
        runCommand(
            helper,
            "player " + second + " spawn at " + (baseX + FAR_OFFSET) + " 80 0"
        );
    }

    /**
     * Carpet fake players can be created before their destination chunks have
     * completed ticket promotion and Regionium topology publication. Do not
     * treat that asynchronous startup window as a failed region split; wait
     * for both players to acquire real owners, bounded by the GameTest timeout.
     */
    private static void waitForPairOwners(GameTestHelper helper, String first, String second) {
        ServerPlayer a = findPlayer(helper, first);
        ServerPlayer b = findPlayer(helper, second);
        if (a == null || b == null) {
            throw helper.assertionException(
                "Waiting for Carpet players to spawn: A=" + (a != null) + ", B=" + (b != null));
        }

        RegioniumRegion regionA = Regionium.scheduler().regionizer().regionFor(a);
        RegioniumRegion regionB = Regionium.scheduler().regionizer().regionFor(b);
        if (regionA == null || regionB == null) {
            throw helper.assertionException(
                "Waiting for Regionium chunk ownership: A=" + regionA + ", B=" + regionB
                    + ", A chunk=" + chunkLifecycle(helper.getLevel(), a.blockPosition())
                    + ", B chunk=" + chunkLifecycle(helper.getLevel(), b.blockPosition()));
        }
    }

    private static void assertSeparateRegions(
        GameTestHelper helper,
        ServerPlayer a,
        ServerPlayer b,
        RegioniumRegion regionA,
        RegioniumRegion regionB
    ) {
        if (regionA == null || regionB == null) {
            throw helper.assertionException(
                "A player has no Regionium owner: A=" + regionA + ", B=" + regionB
                    + ", A chunk=" + chunkLifecycle(helper.getLevel(), a.blockPosition())
                    + ", B chunk=" + chunkLifecycle(helper.getLevel(), b.blockPosition()));
        }

        if (regionA == regionB) {
            throw helper.assertionException(
                "Distant Carpet players were placed in the same region: region="
                    + regionA.id() + ", A=" + a.position() + ", B=" + b.position());
        }
    }

    private static String chunkLifecycle(
        net.minecraft.server.level.ServerLevel level,
        BlockPos pos
    ) {
        long key = net.minecraft.world.level.ChunkPos.pack(pos);
        var visible = ((dev.pandor.regionium.mixins.ChunkMapRegioniumVisibleAccessorMixin)
            (Object) level.getChunkSource().chunkMap).regionium$getVisibleChunkMap();
        var holder = visible.get(key);
        return "pos=" + pos + ", owner=" + Regionium.scheduler().regionizer().owner(level, key)
            + ", holder=" + (holder == null ? "null" : "ticket=" + holder.getTicketLevel()
                + "/ticking=" + (holder.getTickingChunk() != null)
                + "/entityTicking=" + (holder.getEntityTickingChunkFuture()
                    .getNow(net.minecraft.server.level.ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null) != null));
    }

    private static ServerPlayer requirePlayer(GameTestHelper helper, String name) {
        ServerPlayer player = findPlayer(helper, name);
        if (player == null) {
            throw helper.assertionException("Missing Carpet bot " + name);
        }
        return player;
    }

    private static ServerPlayer findPlayer(GameTestHelper helper, String name) {
        return helper.getLevel().players().stream()
            .filter(player -> name.equals(player.getGameProfile().name()))
            .findFirst()
            .orElse(null);
    }

    private static void cleanupPair(GameTestHelper helper, String first, String second) {
        killBot(helper, first);
        killBot(helper, second);
    }

    @GameTest(maxTicks = 1400)
    public void playerTransferRoundTripKeepsSingleOwner(GameTestHelper helper) {
        Regionium.scheduler().registerLevel(helper.getLevel());
        Regionium.scheduler().refreshChunkLeases(helper.getLevel());
        double[] originalA = new double[3];
        double[] originalB = new double[3];

        helper.startSequence()
            .thenExecute(() -> {
                killBot(helper, "RegioniumRoundTripA");
                killBot(helper, "RegioniumRoundTripB");
                runCommand(helper, "carpet allowSpawningOfflinePlayers true");
                runCommand(helper, "forceload add 16384 0");
                runCommand(helper, "forceload add 17664 0");
                spawnPairAt(helper, "RegioniumRoundTripA", "RegioniumRoundTripB", 16384);
            })
            .thenIdle(100)
            .thenExecute(() -> {
                ServerPlayer a = requirePlayer(helper, "RegioniumRoundTripA");
                ServerPlayer b = requirePlayer(helper, "RegioniumRoundTripB");
                assertSeparateRegions(helper, a, b,
                    Regionium.scheduler().regionizer().regionFor(a),
                    Regionium.scheduler().regionizer().regionFor(b));
                originalA[0] = a.getX(); originalA[1] = a.getY(); originalA[2] = a.getZ();
                originalB[0] = b.getX(); originalB[1] = b.getY(); originalB[2] = b.getZ();
            })
            .thenExecute(() -> teleportPlayerToOther(helper, "RegioniumRoundTripA", "RegioniumRoundTripB"))
            .thenIdle(80)
            .thenExecute(() -> assertSameRegionAndSingleMembership(helper, "RegioniumRoundTripA", "RegioniumRoundTripB"))
            .thenExecute(() -> teleportPlayerToCoordinates(
                helper, "RegioniumRoundTripA", originalA[0], originalA[1], originalA[2]))
            .thenIdle(80)
            .thenExecute(() -> {
                ServerPlayer a = requirePlayer(helper, "RegioniumRoundTripA");
                ServerPlayer b = requirePlayer(helper, "RegioniumRoundTripB");
                assertSeparateRegions(helper, a, b,
                    Regionium.scheduler().regionizer().regionFor(a),
                    Regionium.scheduler().regionizer().regionFor(b));
                assertNoTickFailures(helper);
            })
            .thenExecute(() -> {
                cleanupPair(helper, "RegioniumRoundTripA", "RegioniumRoundTripB");
                runCommand(helper, "forceload remove 16384 0");
                runCommand(helper, "forceload remove 17664 0");
            })
            .thenSucceed();
    }

    @GameTest(maxTicks = 1200)
    public void chunkLifecycleChurnDoesNotLeaveStaleOwnership(GameTestHelper helper) {
        Regionium.scheduler().registerLevel(helper.getLevel());
        Regionium.scheduler().refreshChunkLeases(helper.getLevel());

        helper.startSequence()
            .thenExecute(() -> {
                killBot(helper, "RegioniumChurnA");
                killBot(helper, "RegioniumChurnB");
                runCommand(helper, "carpet allowSpawningOfflinePlayers true");
                spawnPairAt(helper, "RegioniumChurnA", "RegioniumChurnB", 20480);
            })
            .thenWaitUntil(() -> waitForPairOwners(helper, "RegioniumChurnA", "RegioniumChurnB"))
            .thenIdle(20)
            .thenExecute(() -> {
                ServerPlayer a = requirePlayer(helper, "RegioniumChurnA");
                ServerPlayer b = requirePlayer(helper, "RegioniumChurnB");
                assertSeparateRegions(helper, a, b,
                    Regionium.scheduler().regionizer().regionFor(a),
                    Regionium.scheduler().regionizer().regionFor(b));
            })
            .thenExecute(() -> killBot(helper, "RegioniumChurnB"))
            .thenIdle(100)
            .thenExecute(() -> {
                ServerPlayer a = requirePlayer(helper, "RegioniumChurnA");
                RegioniumRegion region = Regionium.scheduler().regionizer().regionFor(a);
                if (region == null || region.tickFailures() != 0) {
                    throw helper.assertionException("Remaining region invalid after chunk unload churn");
                }
            })
            .thenExecute(() -> {
                runCommand(helper, "player RegioniumChurnB spawn at 21760 80 0");
                runCommand(helper, "forceload add 21760 0");
            })
            .thenWaitUntil(() -> waitForPairOwners(helper, "RegioniumChurnA", "RegioniumChurnB"))
            .thenIdle(20)
            .thenExecute(() -> {
                ServerPlayer a = requirePlayer(helper, "RegioniumChurnA");
                ServerPlayer b = requirePlayer(helper, "RegioniumChurnB");
                assertSeparateRegions(helper, a, b,
                    Regionium.scheduler().regionizer().regionFor(a),
                    Regionium.scheduler().regionizer().regionFor(b));
                assertNoTickFailures(helper);
            })
            .thenExecute(() -> {
                cleanupPair(helper, "RegioniumChurnA", "RegioniumChurnB");
                runCommand(helper, "forceload remove 21760 0");
            })
            .thenSucceed();
    }

    private static void teleportPlayerToCoordinates(
        GameTestHelper helper, String playerName, double x, double y, double z) {
        ServerPlayer player = requirePlayer(helper, playerName);
        player.teleportTo(helper.getLevel(), x, y, z,
            java.util.Set.of(), player.getYRot(), player.getXRot(), true);
    }

    private static void teleportPlayerToOther(GameTestHelper helper, String playerName, String targetName) {
        ServerPlayer player = requirePlayer(helper, playerName);
        ServerPlayer target = requirePlayer(helper, targetName);
        player.teleportTo(helper.getLevel(), target.getX(), target.getY(), target.getZ(),
            java.util.Set.of(), player.getYRot(), player.getXRot(), true);
    }

    private static void assertSameRegionAndSingleMembership(GameTestHelper helper, String first, String second) {
        ServerPlayer a = requirePlayer(helper, first);
        ServerPlayer b = requirePlayer(helper, second);
        RegioniumRegion aRegion = Regionium.scheduler().regionizer().regionFor(a);
        RegioniumRegion bRegion = Regionium.scheduler().regionizer().regionFor(b);
        if (aRegion == null || aRegion != bRegion) {
            throw helper.assertionException("Transferred players do not share destination region");
        }
        for (ServerPlayer player : new ServerPlayer[] {a, b}) {
            int memberships = 0;
            for (RegioniumRegion region : Regionium.scheduler().regionizer().allRegions()) {
                if (region.worldData().hasEntity(player)) memberships++;
            }
            if (memberships != 1) {
                throw helper.assertionException("Player has " + memberships + " region memberships after transfer");
            }
        }
    }

    private static void assertNoTickFailures(GameTestHelper helper) {
        for (RegioniumRegion region : Regionium.scheduler().regionizer().allRegions()) {
            if (region.tickFailures() != 0) {
                throw helper.assertionException("Region " + region.id() + " has tick failures: " + region.tickFailures());
            }
        }
    }

    private static void killBot(GameTestHelper helper, String name) {
        runCommand(helper, "player " + name + " kill");
    }

    private static void runCommand(GameTestHelper helper, String command) {
        MinecraftServer server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(
            server.createCommandSourceStack().withSuppressedOutput(),
            command
        );
    }
}
