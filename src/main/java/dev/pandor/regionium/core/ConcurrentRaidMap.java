package dev.pandor.regionium.core;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.world.entity.raid.Raid;
import org.jspecify.annotations.NonNull;

/**
 * Thread-safe fastutil-compatible raid table. Views are detached snapshots,
 * which makes vanilla's CODEC stream safe while owner regions mutate the map.
 */
public final class ConcurrentRaidMap extends Int2ObjectOpenHashMap<Raid> {
    @Override
    public synchronized Raid get(int key) {
        return super.get(key);
    }

    @Override
    public synchronized Raid put(int key, Raid value) {
        return super.put(key, value);
    }

    @Override
    public synchronized Raid remove(int key) {
        return super.remove(key);
    }

    @Override
    public synchronized boolean remove(Object key, Object value) {
        if (!(key instanceof Integer intKey) || super.get(intKey.intValue()) != value) {
            return false;
        }
        super.remove(intKey.intValue());
        return true;
    }

    @Override
    public synchronized boolean remove(int key, Object value) {
        if (super.get(key) != value) {
            return false;
        }
        super.remove(key);
        return true;
    }

    @Override
    public synchronized boolean containsValue(Object value) {
        return super.containsValue(value);
    }

    @Override
    public synchronized @NonNull ObjectCollection<Raid> values() {
        ObjectArrayList<Raid> snapshot = new ObjectArrayList<>(super.size());
        snapshot.addAll(super.values());
        return snapshot;
    }

    @Override
    public synchronized Int2ObjectMap.FastEntrySet<Raid> int2ObjectEntrySet() {
        EntrySnapshot snapshot = new EntrySnapshot();
        for (Int2ObjectMap.Entry<Raid> entry : super.int2ObjectEntrySet()) {
            snapshot.add(new Int2ObjectMap.Entry<>() {
                private final int key = entry.getIntKey();
                private final Raid value = entry.getValue();

                @Override public int getIntKey() { return this.key; }
                @Override public Raid getValue() { return this.value; }
                @Override public Raid setValue(Raid ignored) { throw new UnsupportedOperationException(); }
                @Override public boolean equals(Object other) {
                    return other instanceof java.util.Map.Entry<?, ?> pair
                        && pair.getKey() instanceof Integer otherKey && otherKey == this.key
                        && pair.getValue() == this.value;
                }
                @Override public int hashCode() { return Integer.hashCode(this.key) ^ System.identityHashCode(this.value); }
            });
        }
        return snapshot;
    }

    private static final class EntrySnapshot extends ObjectOpenHashSet<Int2ObjectMap.Entry<Raid>>
        implements Int2ObjectMap.FastEntrySet<Raid> {
        @Override
        public ObjectIterator<Int2ObjectMap.Entry<Raid>> fastIterator() {
            return this.iterator();
        }
    }
}
