
package dev.pandor.regionium.core;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Describes which Regionium execution context the current thread is in.
 *
 * <p>Regionium deliberately uses ownership instead of making the entire
 * Minecraft object graph thread-safe. A region-owned object may only be
 * mutated by its owning region during a tick.</p>
 */
public final class RegioniumContext {
    private static final ThreadLocal<RegioniumRegion> CURRENT_REGION = new ThreadLocal<>();
    private static final ThreadLocal<RegioniumWorldData> CURRENT_WORLD_DATA = new ThreadLocal<>();

    private RegioniumContext() {
    }

    public static void enter(RegioniumRegion region) {
        CURRENT_REGION.set(Objects.requireNonNull(region, "region"));
        CURRENT_WORLD_DATA.remove();
    }

    public static void enterWorld(RegioniumWorldData data) {
        CURRENT_WORLD_DATA.set(Objects.requireNonNull(data, "data"));
    }

    public static void exitWorld() {
        CURRENT_WORLD_DATA.remove();
    }

    public static void exit() {
        CURRENT_WORLD_DATA.remove();
        CURRENT_REGION.remove();
    }

    public static @Nullable RegioniumWorldData currentWorldData() {
        return CURRENT_WORLD_DATA.get();
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

    /**
     * Verifies that the current worker is the execution owner of an object.
     *
     * <p>This is intended for debug guards around Minecraft operations that
     * must never be performed by another region.</p>
     */
    public static void requireOwner(RegioniumOwnership ownership, Object object) {
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(object, "object");

        RegioniumRegion current = requireRegionThread();
        if (!ownership.isOwnedBy(object, current)) {
            RegioniumRegion owner = ownership.ownerOf(object);
            throw new IllegalStateException(
                "Illegal cross-region access: current=" + current
                    + ", owner=" + owner
                    + ", object=" + object
            );
        }
    }
}
