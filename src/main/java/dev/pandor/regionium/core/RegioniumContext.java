package dev.pandor.regionium.core;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Execution context of the currently ticking region.
 *
 * RegionizedWorldData is derived from the current region; it is not a second
 * global map keyed by region.
 */
public final class RegioniumContext {
    private static final ThreadLocal<RegioniumRegion> CURRENT_REGION = new ThreadLocal<>();

    private RegioniumContext() {
    }

    public static void enter(RegioniumRegion region) {
        CURRENT_REGION.set(Objects.requireNonNull(region, "region"));
    }

    public static void enterWorld(RegioniumWorldData data) {
        RegioniumRegion current = requireRegionThread();
        if (current.worldData() != data) {
            throw new IllegalStateException("World data does not belong to current region");
        }
    }

    public static void exitWorld() {
        // World data is derived from the current region. There is no second
        // ThreadLocal world-data state to clear.
    }

    public static void exit() {
        CURRENT_REGION.remove();
    }

    public static @Nullable RegioniumWorldData currentWorldData() {
        RegioniumRegion region = CURRENT_REGION.get();
        return region == null ? null : region.worldData();
    }

    public static @Nullable RegioniumRegion currentRegion() {
        return CURRENT_REGION.get();
    }

    public static boolean isRegionThread() {
        return CURRENT_REGION.get() != null;
    }

    public static RegioniumRegion requireRegionThread() {
        RegioniumRegion region = CURRENT_REGION.get();
        if (region == null) {
            throw new IllegalStateException(
                "This operation requires a Regionium region context"
            );
        }
        return region;
    }

    public static void requireOwner(Object object) {
        RegioniumRegion current = requireRegionThread();
        RegioniumRegion owner = dev.pandor.regionium.Regionium.scheduler().ownerOf(object);
        if (owner != current) {
            throw new IllegalStateException(
                "Illegal cross-region access: current=" + current +
                    ", owner=" + owner +
                    ", object=" + object
            );
        }
    }
}
