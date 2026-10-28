package dev.pandor.regionium.core;

import net.minecraft.network.Connection;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Bridges region-side disconnect handling to the global connection registry. */
public final class RegioniumConnectionCleanup {
    private static final Set<Connection> PENDING = ConcurrentHashMap.newKeySet();

    private RegioniumConnectionCleanup() {
    }

    public static void enqueue(Connection connection) {
        PENDING.add(connection);
    }

    public static boolean isPending(Connection connection) {
        return PENDING.contains(connection);
    }

    public static void complete(Connection connection) {
        PENDING.remove(connection);
    }
}
