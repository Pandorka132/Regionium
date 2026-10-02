package dev.pandor.regionium.core;

import java.util.Objects;

/**
 * A unit of work owned by a region.
 *
 * <p>Tasks submitted while a region is ticking are intentionally queued for
 * the next region tick. This gives cross-region communication a deterministic
 * tick boundary instead of allowing arbitrary re-entrant execution.</p>
 */
public record RegioniumTask(Runnable action) {
    public RegioniumTask {
        Objects.requireNonNull(action, "action");
    }

    public void run() {
        action.run();
    }
}
