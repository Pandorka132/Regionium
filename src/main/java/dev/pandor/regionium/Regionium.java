package dev.pandor.regionium;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            registerCommands(dispatcher)
        );
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("regionium")
                .executes(context -> {
                    MinecraftServer server = context.getSource().getServer();

                    context.getSource().sendSuccess(
                        () -> Component.literal("Regionium threads: " + SCHEDULER.regions().size()),
                        false
                    );

                    for (var region : SCHEDULER.regions()) {
                        var players = server.getPlayerList().getPlayers().stream()
                            .filter(player -> SCHEDULER.ownerOf(player) == region)
                            .toList();

                        if (!players.isEmpty()) {
                            String names = players.stream()
                                .map(ServerPlayer::getGameProfile)
                                .map(profile -> profile.name())
                                .reduce((a, b) -> a + ", " + b)
                                .orElse("");

                            String playerWord = players.size() == 1 ? "player" : "players";
                            context.getSource().sendSuccess(
                                () -> Component.literal(
                                    region.workerName()
                                        + " - "
                                        + players.size()
                                        + " "
                                        + playerWord
                                        + ": "
                                        + names
                                ),
                                false
                            );
                        }
                    }

                    return 1;
                })
                .then(
                    Commands.literal("transfer")
                        .then(
                            Commands.argument("id", IntegerArgumentType.integer(0, SCHEDULER.regions().size() - 1))
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    int destinationId = IntegerArgumentType.getInteger(context, "id");
                                    var destination = SCHEDULER.region(destinationId);

                                    /*
                                     * The player and its ServerLevel are one execution unit.
                                     * Moving only the player would make packet handlers run on
                                     * the destination worker while the world keeps ticking on
                                     * the source worker.
                                     */
                                    // ServerLevel is shared state in the regionized architecture;
                                    // only the player's execution ownership is migrated here.
                                    // The destination region must already be a valid region of this scheduler.
                                    SCHEDULER.requestTransfer(player, destination);
                                    SCHEDULER.applyPendingTransfers();

                                    context.getSource().sendSuccess(
                                        () -> Component.literal(
                                            "Transferred to Region "
                                                + destinationId
                                                + " ("
                                                + destination.workerName()
                                                + ")"
                                        ),
                                        false
                                    );
                                    return 1;
                                })
                        )
                )
        );
    }
}
