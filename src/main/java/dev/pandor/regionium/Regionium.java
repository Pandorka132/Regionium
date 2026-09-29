package dev.pandor.regionium;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*? if fabric {*/
import net.fabricmc.api.ModInitializer;
/*?}*/

/*? if forge {*/
/*import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
*//*?}*/

/*? if neoforge {*/
/*import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
*//*?}*/

/*? if neoforge {*/
/*@Mod(Regionium.MOD_ID)
*//*?}*/
/*? if forge {*/
/*@Mod(Regionium.MOD_ID)
*//*?}*/
public class Regionium /*? if fabric {*/ implements ModInitializer /*?}*/ {
    public static final String MOD_ID = "regionium";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /*? if forge {*/
    /*public Regionium(FMLJavaModLoadingContext context) {
        LOGGER.info("Hello Forge world!");
    }
    *//*?}*/

    /*? if neoforge {*/
    /*public Regionium(IEventBus modEventBus) {
        LOGGER.info("Hello NeoForge world!");
    }
    *//*?}*/

    /*? if fabric {*/
    @Override
    public void onInitialize() {
        LOGGER.info("Hello Fabric world!");
    }
    /*?}*/
}
