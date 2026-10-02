package dev.pandor.regionium.core;

import org.jetbrains.annotations.Nullable;

/**
 * Describes which Regionium execution context the current thread is in.
 *
 * <p>Regionium deliberately uses ownership instead of making the entire
 * Minecraft object graph thread-safe. A region-owned object may only be
 * mutated by its owning region during a tick.</p>
 */
public final class RegioniumContext {
    private static final ThreadLocal<RegioniumRegion> CURRENT_REGION = new ThreadLocal<>();

    private RegioniumContext() {
    }

    public static void enter(RegioniumRegion region) {
        CURRENT_REGION.set(region);
    }

    public static void exit() {
        CURRENT_REGION.remove();
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
            throw new IllegalStateException("This operation requires a Regionium region thread");
        }
        return region;
    }
}
