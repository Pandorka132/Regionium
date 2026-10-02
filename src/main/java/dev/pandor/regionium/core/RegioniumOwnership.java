
package dev.pandor.regionium.core;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Tracks execution ownership without requiring Minecraft objects themselves
 * to become thread-safe.
 *
 * <p>Identity semantics are intentional: two different Minecraft objects that
 * happen to implement equal/hashCode must never share ownership.</p>
 *
 * <p>Direct ownership changes are controlled by the scheduler. Callers should
 * normally use scheduler assignment/transfer APIs so changes happen at safe
 * tick boundaries.</p>
 */
public final class RegioniumOwnership {
    private final Map<Object, RegioniumRegion> owners = new IdentityHashMap<>();

    public synchronized RegioniumRegion ownerOf(Object object) {
        Objects.requireNonNull(object, "object");
        return owners.get(object);
    }

    public synchronized boolean isOwnedBy(Object object, RegioniumRegion region) {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(region, "region");
        return owners.get(object) == region;
    }

    synchronized void assign(Object object, RegioniumRegion region) {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(region, "region");

        RegioniumRegion previous = owners.get(object);
        if (previous != null && previous != region) {
            throw new IllegalStateException(
                "Object already belongs to " + previous + " and cannot be reassigned directly"
            );
        }

        owners.put(object, region);
    }

    synchronized void transfer(Object object, RegioniumRegion destination) {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(destination, "destination");

        RegioniumRegion source = owners.get(object);
        if (source == destination) {
            return;
        }

        owners.put(object, destination);
    }

    synchronized void release(Object object) {
        Objects.requireNonNull(object, "object");
        owners.remove(object);
    }
}
