package dev.pandor.regionium.mixins;

import dev.pandor.regionium.access.MapIndexRegioniumThreadSafetyAccess;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapIndex;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicInteger;

@Mixin(MapIndex.class)
public abstract class MapIndexRegioniumThreadSafetyMixin implements MapIndexRegioniumThreadSafetyAccess {
    @Shadow private int lastMapId;

    @Unique private AtomicInteger regionium$lastMapId;

    @Unique
    @Override
    public AtomicInteger regionium$getAtomicMapId() {
        return this.regionium$lastMapId;
    }

    @Inject(method = "<init>(I)V", at = @At("RETURN"))
    private void regionium$initializeAtomicMapId(int lastMapId, CallbackInfo ci) {
        this.regionium$lastMapId = new AtomicInteger(this.lastMapId);
    }

    @Redirect(
        method = "lambda$static$1",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/world/level/saveddata/maps/MapIndex;lastMapId:I",
            opcode = Opcodes.GETFIELD
        )
    )
    private static int regionium$serializeAtomicMapId(MapIndex index) {
        return ((MapIndexRegioniumThreadSafetyAccess) (Object) index).regionium$getAtomicMapId().get();
    }

    @Overwrite
    public MapId getNextMapId() {
        MapId id = new MapId(this.regionium$lastMapId.incrementAndGet());
        ((MapIndex) (Object) this).setDirty();
        return id;
    }
}
