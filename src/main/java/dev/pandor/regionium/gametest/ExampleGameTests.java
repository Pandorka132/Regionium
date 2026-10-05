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
            Identifier.fromNamespaceAndPath(Regionium.MOD_ID, NOOP_TEST_FUNCTION),
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

    @GameTest(maxTicks = 40)
    public void external_sanity_redstone_piston(GameTestHelper helper) {
        BlockPos piston = new BlockPos(2, 1, 2);
        BlockPos power = piston.relative(Direction.WEST);

        helper.setBlock(piston, Blocks.PISTON, Direction.EAST);
        helper.setBlock(power, Blocks.REDSTONE_BLOCK);

        helper.succeedWhen(() -> helper.assertBlockProperty(
            piston, BlockStateProperties.EXTENDED, true));
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
