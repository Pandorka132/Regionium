package dev.pandor.regionium.gametest;

import dev.pandor.regionium.Regionium;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import dev.pandor.regionium.core.RegioniumRegion;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class CarpetPlayerGameTests {
    private static final String DISTANT_A = "RegioniumDistantA";
    private static final String DISTANT_B = "RegioniumDistantB";
    private static final String TRANSFER_A = "RegioniumTransferA";
    private static final String TRANSFER_B = "RegioniumTransferB";
    private static final String TICK_A = "RegioniumTickA";
    private static final String TICK_B = "RegioniumTickB";

    private static final int DISTANT_BASE_X = 0;
    private static final int TRANSFER_BASE_X = 4096;
    private static final int TICK_BASE_X = 8192;
    private static final int FAR_OFFSET = 1280;

    @GameTest(maxTicks = 800)
    public void distantCarpetPlayersCreateSeparateRegions(GameTestHelper helper) {
        Regionium.scheduler().registerLevel(helper.getLevel());
        Regionium.scheduler().refreshChunkLeases(helper.getLevel());

        helper.startSequence()
            .thenExecute(() -> {
                killBot(helper, DISTANT_A);
                killBot(helper, DISTANT_B);
                runCommand(helper, "carpet allowSpawningOfflinePlayers true");
                spawnPairAt(helper, DISTANT_A, DISTANT_B, DISTANT_BASE_X);
            })
            .thenIdle(100)
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
            .thenIdle(100)
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

    @GameTest(maxTicks = 500)
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
            .thenIdle(100)
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

    private static void assertSeparateRegions(
        GameTestHelper helper,
        ServerPlayer a,
        ServerPlayer b,
        RegioniumRegion regionA,
        RegioniumRegion regionB
    ) {
        if (regionA == null || regionB == null) {
            throw helper.assertionException(
                "A player has no Regionium owner: A=" + regionA + ", B=" + regionB);
        }

        if (regionA == regionB) {
            throw helper.assertionException(
                "Distant Carpet players were placed in the same region: region="
                    + regionA.id() + ", A=" + a.position() + ", B=" + b.position());
        }
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
            .thenExecute(() -> cleanupPair(helper, "RegioniumRoundTripA", "RegioniumRoundTripB"))
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
            .thenIdle(100)
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
            .thenExecute(() -> runCommand(helper, "player RegioniumChurnB spawn at 21760 80 0"))
            .thenIdle(100)
            .thenExecute(() -> {
                ServerPlayer a = requirePlayer(helper, "RegioniumChurnA");
                ServerPlayer b = requirePlayer(helper, "RegioniumChurnB");
                assertSeparateRegions(helper, a, b,
                    Regionium.scheduler().regionizer().regionFor(a),
                    Regionium.scheduler().regionizer().regionFor(b));
                assertNoTickFailures(helper);
            })
            .thenExecute(() -> cleanupPair(helper, "RegioniumChurnA", "RegioniumChurnB"))
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
