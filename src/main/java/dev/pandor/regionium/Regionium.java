package dev.pandor.regionium;

import com.mojang.brigadier.CommandDispatcher;
import dev.pandor.regionium.core.RegioniumScheduler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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
            "Regionium initialized with {} worker capacity",
            SCHEDULER.workerCount()
        );

        CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, environment) -> registerCommands(dispatcher)
        );
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("regionium")
                .executes(context -> {
                    MinecraftServer server = context.getSource().getServer();

                    context.getSource().sendSuccess(
                        () -> Component.literal(
                            "Active spatial regions: " + SCHEDULER.regions().size()
                        ),
                        false
                    );

                    context.getSource().sendSuccess(
                        () -> Component.literal(
                            "Scheduler workers: " + SCHEDULER.workerCount()
                        ),
                        false
                    );

                    ServerPlayer sourcePlayer = context.getSource().getPlayer();
                    if (sourcePlayer != null) {
                        var owner = SCHEDULER.ownerOf(sourcePlayer);
                        if (owner != null) {
                            context.getSource().sendSuccess(
                                () -> Component.literal(
                                    "Current region: " + owner.id() +
                                        " (last scheduler thread: " + owner.lastWorkerThreadName() +
                                        ", ticking=" + owner.isTicking() + ")"
                                ),
                                false
                            );
                        }
                    }

                    for (var region : SCHEDULER.regions()) {
                        int players = region.worldData().players().size();
                        int entities = region.worldData().entities().size();
                        int chunks = region.worldData().chunks().size();

                        if (players == 0 && entities == 0 && chunks == 0) {
                            continue;
                        }

                        context.getSource().sendSuccess(
                            () -> Component.literal(
                                "Region " + region.id() +
                                    " - chunks=" + chunks +
                                    ", entities=" + entities +
                                    ", players=" + players
                            ),
                            false
                        );
                    }

                    return 1;
                })
        );
    }
}
