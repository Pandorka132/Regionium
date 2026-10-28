package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.players.StoredUserList;
import org.spongepowered.asm.mixin.Mixin;

import java.io.IOException;

@Mixin(StoredUserList.class)
public abstract class StoredUserListRegioniumThreadSafetyMixin {
    @WrapMethod(method = "save")
    private void regionium$syncSave(Operation<Void> original) throws IOException {
        synchronized (this) {
            original.call();
        }
    }

    @WrapMethod(method = "load")
    private void regionium$syncLoad(Operation<Void> original) throws IOException {
        synchronized (this) {
            original.call();
        }
    }
}
