package dev.pandor.regionium.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import dev.pandor.regionium.Regionium;
import net.minecraft.world.entity.EntityType;

public final class ExampleGameTests {
    private static final String NOOP_TEST_FUNCTION = "noop";

    public ExampleGameTests() {
        Registry.register(
            BuiltInRegistries.TEST_FUNCTION,
            Identifier.fromNamespaceAndPath(Regionium.MOD_ID.toLowerCase(), NOOP_TEST_FUNCTION),
            ExampleGameTests::noop
        );
    }

    public static void noop(GameTestHelper helper) {
        helper.succeed();
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
