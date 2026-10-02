package dev.pandor.regionium.core;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Tracks execution ownership without requiring Minecraft objects themselves
 * to become thread-safe.
 *
 * <p>Identity semantics are intentional: two different Minecraft objects that
 * happen to implement equal/hashCode must never share ownership.</p>
 */
public final class RegioniumOwnership {
    private final Map<Object, RegioniumRegion> owners = new IdentityHashMap<>();
    private final Map<Object, TransferState> transfers = new IdentityHashMap<>();

    public synchronized RegioniumRegion ownerOf(Object object) {
        return owners.get(object);
    }

    public synchronized boolean isOwnedBy(Object object, RegioniumRegion region) {
        return owners.get(object) == region;
    }

    public synchronized void assign(Object object, RegioniumRegion region) {
        if (transfers.containsKey(object)) {
            throw new IllegalStateException("Object is currently transferring");
        }

        RegioniumRegion previous = owners.put(object, region);
        if (previous != null && previous != region) {
            throw new IllegalStateException(
                "Object already belongs to " + previous + " and cannot be reassigned directly"
            );
        }
    }

    /**
     * Atomically changes ownership between tick boundaries.
     */
    public synchronized void transfer(Object object, RegioniumRegion destination) {
        RegioniumRegion source = owners.get(object);
        if (source == destination) {
            return;
        }

        if (source == null) {
            owners.put(object, destination);
            return;
        }

        if (transfers.put(object, TransferState.TRANSFERRING) != null) {
            throw new IllegalStateException("Object is already transferring");
        }

        try {
            owners.remove(object);
            owners.put(object, destination);
        } finally {
            transfers.remove(object);
        }
    }

    public synchronized boolean isTransferring(Object object) {
        return transfers.containsKey(object);
    }

    public synchronized void release(Object object) {
        if (transfers.containsKey(object)) {
            throw new IllegalStateException("Cannot release an object while it is transferring");
        }
        owners.remove(object);
    }

    private enum TransferState {
        TRANSFERRING
    }
}
