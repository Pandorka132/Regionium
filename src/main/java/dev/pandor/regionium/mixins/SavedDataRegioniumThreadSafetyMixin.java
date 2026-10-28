package dev.pandor.regionium.mixins;

import net.minecraft.world.level.saveddata.SavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SavedData.class)
public abstract class SavedDataRegioniumThreadSafetyMixin {
    @Shadow private boolean dirty;

    /**
     * Folia makes the dirty flag visible between region workers and the save
     * thread. Synchronizing every access gives the same visibility guarantee
     * without relying on a field-modifier rewrite.
     */
    @Overwrite
    public void setDirty() {
        this.setDirty(true);
    }

    @Overwrite
    public void setDirty(boolean dirty) {
        synchronized (this) {
            this.dirty = dirty;
        }
    }

    @Overwrite
    public boolean isDirty() {
        synchronized (this) {
            return this.dirty;
        }
    }
}
