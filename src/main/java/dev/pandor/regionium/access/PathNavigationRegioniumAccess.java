package dev.pandor.regionium.access;

import net.minecraft.world.level.Level;

/** Access to the Level stored by PathNavigation, including its subclasses. */
public interface PathNavigationRegioniumAccess {
    Level regionium$getLevel();
}
