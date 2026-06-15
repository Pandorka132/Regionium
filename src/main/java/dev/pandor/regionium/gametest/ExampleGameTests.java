package dev.pandor.regionium.gametest;

import dev.pandor.regionium.Regionium;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;

/*? if fabric {*/
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
/*?}*/

/*? if forgeLike {*/
/*import net.minecraft.core.registries.Registries;
*//*?}*/

/*? if forge {*/
/*import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegisterEvent;
*//*?}*/

/*? if neoforge {*/
/*
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;
*//*?}*/

/*? if forge {*/
/*@Mod.EventBusSubscriber(modid = Regionium.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
*//*?}*/

/*? if neoforge {*/
/*@EventBusSubscriber(modid = Regionium.MOD_ID)
*//*?}*/
public final class ExampleGameTests {
    private static final String NOOP_TEST_FUNCTION = "noop";

    /*? if fabric {*/
    public ExampleGameTests() {
        Registry.register(
            BuiltInRegistries.TEST_FUNCTION,
            Identifier.fromNamespaceAndPath(Regionium.MOD_ID, NOOP_TEST_FUNCTION),
            ExampleGameTests::noop
        );
    }
    /*?}*/

    /*? if forgeLike {*/
    /*@SubscribeEvent
    public static void registerTestFunctions(RegisterEvent event) {
        Identifier noopFunctionId = Identifier.fromNamespaceAndPath(Regionium.MOD_ID, NOOP_TEST_FUNCTION);
        event.register(Registries.TEST_FUNCTION, noopFunctionId, () -> ExampleGameTests::noop);
    }
    *//*?}*/

    public static void noop(GameTestHelper context) {
        context.succeed();
    }
}
