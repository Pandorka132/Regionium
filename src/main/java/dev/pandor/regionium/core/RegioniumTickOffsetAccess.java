package dev.pandor.regionium.core;

/** Implemented by tick-bearing vanilla objects whose timestamps are region-clock based. */
public interface RegioniumTickOffsetAccess {
    default void regionium$updateTicks(long fromTickOffset, long fromRedstoneTimeOffset) {
    }
}
