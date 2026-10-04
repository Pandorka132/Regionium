package dev.pandor.regionium.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.util.ClassInstanceMultiMap;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * Makes entity spatial queries safe while Regionium entity ticks run in parallel.
 *
 * Vanilla's ClassInstanceMultiMap stores entity references in plain ArrayLists.
 * EntitySectionStorage is shared by the whole ServerLevel, so a worker can
 * otherwise iterate a list while another thread adds/removes an entity.
 *
 * The lock is deliberately per ClassInstanceMultiMap instance, not global.
 * Different entity sections therefore remain independently concurrent.
 * Read operations take a short immutable snapshot; the entity tick itself does
 * not run while holding the lock.
 */
@Mixin(ClassInstanceMultiMap.class)
public abstract class ClassInstanceMultiMapRegioniumConcurrencyMixin<T> {
    @WrapMethod(method = "add")
    private boolean regionium$synchronizedAdd(T entity, Operation<Boolean> original) {
        synchronized (this) {
            return original.call(entity);
        }
    }

    @WrapMethod(method = "remove")
    private boolean regionium$synchronizedRemove(Object entity, Operation<Boolean> original) {
        synchronized (this) {
            return original.call(entity);
        }
    }

    @WrapMethod(method = "iterator")
    private Iterator<T> regionium$snapshotIterator(Operation<Iterator<T>> original) {
        synchronized (this) {
            Iterator<T> iterator = original.call();
            List<T> snapshot = new java.util.ArrayList<>();
            iterator.forEachRemaining(snapshot::add);
            return snapshot.iterator();
        }
    }

    @WrapMethod(method = "find")
    private <S> Collection<S> regionium$snapshotFind(
        Class<S> type,
        Operation<Collection<S>> original
    ) {
        synchronized (this) {
            return List.copyOf(original.call(type));
        }
    }
}
