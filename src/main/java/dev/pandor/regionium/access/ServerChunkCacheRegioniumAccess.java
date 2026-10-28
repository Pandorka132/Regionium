package dev.pandor.regionium.access;

/** Internal access to ServerChunkCache's main-thread distance-manager update pass. */
public interface ServerChunkCacheRegioniumAccess {
    boolean regionium$runDistanceManagerUpdates();
}
