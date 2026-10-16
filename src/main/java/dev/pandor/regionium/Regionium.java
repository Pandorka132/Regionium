package dev.pandor.regionium;

import dev.pandor.regionium.core.RegioniumScheduler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Regionium implements ModInitializer {
    public static final String MOD_ID = "Regionium";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final RegioniumScheduler SCHEDULER = new RegioniumScheduler();

    public static RegioniumScheduler scheduler() {
        return SCHEDULER;
    }

    @Override
    public void onInitialize() {
        LOGGER.trace(
            "Regionium initialized with {} workers",
            SCHEDULER.workerCount()
        );

        CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, environment) -> Command.registerCommands(dispatcher)
        );

        DebugOverlayServer.registerEvents();
        ServerLifecycleEvents.SERVER_STARTED.register(DebugOverlayServer::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> DebugOverlayServer.stop());
    }
}
