package dev.pandor.regionium;

public interface LevelThreadAccess {
    Thread regionium$getThread();
    void regionium$setThread(Thread thread);
}
