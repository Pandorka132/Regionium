package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.access.RaidsRegioniumAccess;
import dev.pandor.regionium.core.ConcurrentRaidMap;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.SavedData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

@Mixin(Raids.class)
public abstract class RaidsRegioniumMixin extends SavedData implements RaidsRegioniumAccess {
    @Shadow @Final @Mutable private Int2ObjectMap<Raid> raidMap;
    @Shadow private int nextId;
    @Shadow private int tick;
    @Unique private final AtomicInteger regionium$nextAvailableId = new AtomicInteger(1);

    @Unique
    @Override
    public int regionium$getNextAvailableRaidId() {
        return this.regionium$nextAvailableId.get();
    }

    protected RaidsRegioniumMixin() {
        super();
    }

    @Inject(method = "<init>()V", at = @At("TAIL"))
    private void regionium$initializeDefaultConstructor(CallbackInfo ci) {
        this.regionium$initializeRaidState();
    }

    @Inject(method = "<init>(Ljava/util/List;II)V", at = @At("TAIL"))
    private void regionium$initializeLoadedConstructor(List<?> raids, int nextId, int tick, CallbackInfo ci) {
        this.regionium$initializeRaidState();
    }

    @Unique
    private void regionium$initializeRaidState() {
        Int2ObjectMap<Raid> threadSafeMap = new ConcurrentRaidMap();
        synchronized (this.raidMap) {
            threadSafeMap.putAll(this.raidMap);
        }
        this.raidMap = threadSafeMap;
        this.regionium$nextAvailableId.set(this.nextId);
    }

    /**
     * Allocate IDs atomically because separate region owners may create raids
     * in parallel. Keep vanilla's serialized nextId field in sync for CODEC.
     */
    @Overwrite
    private int getUniqueId() {
        int id = this.regionium$nextAvailableId.incrementAndGet();
        // SavedData's codec still reads vanilla's serialized field. Keep it at
        // the maximum allocated ID even when separate owners allocate at once.
        synchronized (this) {
            this.nextId = Math.max(this.nextId, id);
        }
        return id;
    }

    /**
     * CODEC's next_id getter reads the vanilla int field directly. Region
     * owners can allocate while SavedData is being encoded on the global
     * thread, so serialize the atomic source of truth instead of that racy
     * mirror field.
     */
    @Redirect(
        method = "lambda$static$2(Lnet/minecraft/world/entity/raid/Raids;)Ljava/lang/Integer;",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/world/entity/raid/Raids;nextId:I",
            opcode = Opcodes.GETFIELD
        )
    )
    private static int regionium$serializeCurrentNextId(Raids raids) {
        return ((RaidsRegioniumAccess) (Object) raids).regionium$getNextAvailableRaidId();
    }

    /**
     * Folia splits the world-global counter/dirty cadence from region raid
     * simulation. Take a stable map snapshot before ticking raids so ownership
     * regions can run concurrently with map insertions and removals.
     */
    @Overwrite
    public void tick(ServerLevel level) {
        if (!RegioniumContext.isRegionThread()) {
            regionium$globalTick();
            return;
        }

        List<Raid> raids = new ArrayList<>(this.raidMap.values());
        RegioniumRegion current = RegioniumContext.requireRegionThread();
        for (Raid raid : raids) {
            if (!regionium$ownsRaid(current, level, raid)) {
                continue;
            }
            if (!level.getGameRules().get(GameRules.RAIDS)) {
                raid.stop();
            }
            if (raid.isStopped()) {
                OptionalInt id = this.getId(raid);
                if (id.isPresent()) {
                    this.raidMap.remove(id.getAsInt(), raid);
                    this.setDirty();
                }
            } else {
                raid.tick(level);
            }
        }
    }

    /** Folia's SavedData clock and periodic dirtying run once globally. */
    @Override
    @Unique
    public void regionium$globalTick() {
        this.tick++;
        if (this.tick % 200 == 0) {
            this.setDirty();
        }
    }

    @Overwrite
    public OptionalInt getId(Raid raid) {
        for (Int2ObjectMap.Entry<Raid> entry : this.raidMap.int2ObjectEntrySet()) {
            if (entry.getValue() == raid) {
                return OptionalInt.of(entry.getIntKey());
            }
        }
        return OptionalInt.empty();
    }

    @Overwrite
    public Raid getNearbyRaid(BlockPos pos, int maxDistSqr) {
        RegioniumRegion current = RegioniumContext.currentRegion();
        return regionium$getNearbyRaid(current == null ? null : current.level(), pos, maxDistSqr);
    }

    @Override
    @Unique
    public Raid regionium$getNearbyRaid(ServerLevel level, BlockPos pos, int maxDistSqr) {
        RegioniumRegion current = RegioniumContext.currentRegion();
        Raid closest = null;
        double closestDistanceSqr = maxDistSqr;
        List<Raid> raids = new ArrayList<>(this.raidMap.values());
        for (Raid raid : raids) {
            if (current != null && (level == null || !regionium$ownsRaid(current, level, raid))) {
                continue;
            }
            double distance = raid.getCenter().distSqr(pos);
            if (raid.isActive() && distance < closestDistanceSqr) {
                closest = raid;
                closestDistanceSqr = distance;
            }
        }
        return closest;
    }

    @Unique
    private static boolean regionium$ownsRaid(RegioniumRegion current, ServerLevel level, Raid raid) {
        BlockPos center = raid.getCenter();
        if (center == null || current.level() != level) {
            return false;
        }
        return Regionium.scheduler().regionizer().ownsChunkSquare(
            current, level, center.getX() >> 4, center.getZ() >> 4, 8
        );
    }

    @Inject(method = "createOrExtendRaid", at = @At("HEAD"), cancellable = true)
    private void regionium$requireRaidCreationOwner(
        ServerPlayer player,
        BlockPos raidPosition,
        CallbackInfoReturnable<Raid> cir
    ) {
        RegioniumRegion current = RegioniumContext.currentRegion();
        if (current == null) {
            return;
        }
        ServerLevel level = player.level();
        if (current.level() != level
            || Regionium.scheduler().regionizer().owner(level, player.chunkPosition().x(), player.chunkPosition().z()) != current
            || !Regionium.scheduler().regionizer().ownsChunkSquare(
                current, level, player.chunkPosition().x(), player.chunkPosition().z(), 8
            )
            || !Regionium.scheduler().regionizer().ownsChunkSquare(
                current, level, raidPosition.getX() >> 4, raidPosition.getZ() >> 4, 8
            )) {
            cir.setReturnValue(null);
        }
    }

}
