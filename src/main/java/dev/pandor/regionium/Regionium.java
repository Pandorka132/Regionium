package dev.pandor.regionium;

import dev.pandor.regionium.core.RegioniumScheduler;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Regionium implements ModInitializer {
    public static final String MOD_ID = "regionium";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final RegioniumScheduler SCHEDULER = new RegioniumScheduler();

    public static RegioniumScheduler scheduler() {
        return SCHEDULER;
    }

    @Override
    public void onInitialize() {
        LOGGER.info(
            "Regionium initialized with {} execution regions",
            SCHEDULER.regions().size()
        );
    }
}
