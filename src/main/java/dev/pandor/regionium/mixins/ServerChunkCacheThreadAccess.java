package dev.pandor.regionium;

public interface ServerChunkCacheThreadAccess {
    Thread regionium$getMainThread();

    void regionium$setMainThread(Thread thread);
}
