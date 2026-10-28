package dev.pandor.regionium.access;

/** Access to the vanilla singleton flag used while evaluating redstone signals. */
public interface RedstoneWireBlockRegioniumAccess {
    boolean regionium$getShouldSignal();

    void regionium$setShouldSignal(boolean shouldSignal);
}
