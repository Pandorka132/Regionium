package dev.pandor.regionium.access;

import java.util.concurrent.atomic.AtomicInteger;

/** Exposes the atomic map-ID counter added to Minecraft's MapIndex by Regionium. */
public interface MapIndexRegioniumThreadSafetyAccess {
    AtomicInteger regionium$getAtomicMapId();
}
