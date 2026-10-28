package dev.pandor.regionium.mixins;

import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;

import java.util.Map;
import java.util.Optional;
import java.util.HashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import net.minecraft.nbt.NbtOps;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(SavedDataStorage.class)
public abstract class SavedDataStorageRegioniumThreadSafetyMixin {
    @Shadow @Final @Mutable private Map<SavedDataType<?>, Optional<SavedData>> cache;

    @Shadow
    private <T extends SavedData> T readSavedData(SavedDataType<T> type) {
        throw new AssertionError();
    }

    @Shadow @Final private HolderLookup.Provider registries;

    @Shadow
    private <T extends SavedData> CompoundTag encodeUnchecked(SavedDataType<T> type, SavedData data, RegistryOps<Tag> ops) {
        throw new AssertionError();
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void regionium$useConcurrentSavedDataCache(CallbackInfo ci) {
        this.cache = new ConcurrentHashMap<>(this.cache);
    }

    @Overwrite
    private Map<SavedDataType<?>, CompoundTag> collectDirtyTagsToSave() {
        Map<SavedDataType<?>, CompoundTag> tagsToSave = new Object2ObjectArrayMap<>();
        RegistryOps<Tag> ops = this.registries.createSerializationContext(NbtOps.INSTANCE);
        this.cache.forEach((type, optional) -> optional.filter(SavedData::isDirty).ifPresent(data -> {
            synchronized (data) {
                if (data.isDirty()) {
                    tagsToSave.put(type, this.regionium$encodeUnchecked(type, data, ops));
                    data.setDirty(false);
                }
            }
        }));
        return tagsToSave;
    }

    @SuppressWarnings("unchecked")
@org.spongepowered.asm.mixin.Unique
    private CompoundTag regionium$encodeUnchecked(SavedDataType<?> type, SavedData data, RegistryOps<Tag> ops) {
        return this.encodeUnchecked((SavedDataType<SavedData>) type, data, ops);
    }

    @Overwrite
    public <T extends SavedData> T get(SavedDataType<T> type) {
        Optional<SavedData> value = this.cache.computeIfAbsent(
            type,
            key -> Optional.ofNullable(this.readSavedData((SavedDataType<T>) key))
        );
        return (T) value.orElse(null);
    }

    @Overwrite
    public <T extends SavedData> T computeIfAbsent(SavedDataType<T> type) {
        Optional<SavedData> value = this.cache.compute(type, (key, current) -> {
            if (current != null && current.isPresent()) {
                return current;
            }

            if (current == null) {
                SavedData loaded = this.readSavedData((SavedDataType<T>) key);
                if (loaded != null) {
                    return Optional.of(loaded);
                }
            }

            T created = type.constructor().get();
            created.setDirty();
            return Optional.of(created);
        });
        return (T) value.orElse(null);
    }

    @Overwrite
    public <T extends SavedData> void set(SavedDataType<T> type, T data) {
        data.setDirty();
        this.cache.put(type, Optional.of(data));
    }
}
