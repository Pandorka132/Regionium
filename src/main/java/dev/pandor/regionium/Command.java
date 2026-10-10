package dev.pandor.regionium;

import com.mojang.brigadier.CommandDispatcher;
import dev.pandor.regionium.core.RegioniumScheduler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public class Command {
    private static final RegioniumScheduler SCHEDULER = Regionium.scheduler();

    static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                net.minecraft.commands.Commands.literal("regionium")
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
