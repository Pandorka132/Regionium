package dev.pandor.regionium;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.api.ModInitializer;

public class Regionium implements ModInitializer {
    public static final String MOD_ID = "regionium";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Hello world!");
    }
}
