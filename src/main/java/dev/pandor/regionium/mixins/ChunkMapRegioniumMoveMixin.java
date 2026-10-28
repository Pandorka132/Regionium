package dev.pandor.regionium.mixins;

import dev.pandor.regionium.Regionium;
import dev.pandor.regionium.core.RegioniumContext;
import dev.pandor.regionium.core.RegioniumChunkDebug;
import dev.pandor.regionium.core.RegioniumRegion;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.PlayerMap;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Unique;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Keeps vanilla's global ChunkMap indexes serialized while moving the
 * player-owned chunk tracking view lifecycle onto that player's region.
 * The global Entity and Player maps remain the source of truth; only the
 * execution ownership of the mutable per-player tracking state is changed.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapRegioniumMoveMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @org.spongepowered.asm.mixin.Final private PlayerMap playerMap;

    @Unique
    private final Map<ServerPlayer, Long> regionium$trackingViewVersions =
        Collections.synchronizedMap(new WeakHashMap<>());

    @Unique
    private final ThreadLocal<ServerPlayer> regionium$validatedTrackingPlayer = new ThreadLocal<>();

    @Unique
    private final ThreadLocal<ChunkTrackingView> regionium$validatedTrackingView = new ThreadLocal<>();

    @Unique
    private long regionium$nextTrackingViewVersion(ServerPlayer player) {
        synchronized (this.regionium$trackingViewVersions) {
            long next = this.regionium$trackingViewVersions.getOrDefault(player, 0L) + 1L;
            this.regionium$trackingViewVersions.put(player, next);
            return next;
        }
    }

    @Unique
    private boolean regionium$isLatestTrackingView(ServerPlayer player, long version) {
        synchronized (this.regionium$trackingViewVersions) {
            return this.regionium$trackingViewVersions.getOrDefault(player, 0L) == version;
        }
    }

    @Unique
    private boolean regionium$applyTrackingViewIfLatest(
        ServerPlayer player,
        ChunkTrackingView view,
        long version
    ) {
        synchronized (this.regionium$trackingViewVersions) {
            if (player.isRemoved() || player.level() != this.level
                || this.regionium$trackingViewVersions.getOrDefault(player, 0L) != version) {
                if (RegioniumChunkDebug.enabled()) {
                    RegioniumChunkDebug.log(
                        "tracking-view-run-stale",
                        RegioniumChunkDebug.playerState(player),
                        "version=" + version,
                        "latestVersionChanged=true"
                    );
                }
                return false;
            }

            ServerPlayer previousPlayer = this.regionium$validatedTrackingPlayer.get();
            ChunkTrackingView previousView = this.regionium$validatedTrackingView.get();
            this.regionium$validatedTrackingPlayer.set(player);
            this.regionium$validatedTrackingView.set(view);
            try {
                // Keep version publication and application atomic. Otherwise a
                // newer request can arrive between the final version check and
                // this call, allowing this older captured view to win.
                this.regionium$invokeApplyChunkTrackingView(player, view);
                return true;
            } finally {
                if (previousPlayer == null) {
                    this.regionium$validatedTrackingPlayer.remove();
                } else {
                    this.regionium$validatedTrackingPlayer.set(previousPlayer);
                }
                if (previousView == null) {
                    this.regionium$validatedTrackingView.remove();
                } else {
                    this.regionium$validatedTrackingView.set(previousView);
                }
            }
        }
    }

    @Invoker("skipPlayer")
    protected abstract boolean regionium$invokeSkipPlayer(ServerPlayer player);

    @Invoker("updatePlayerPos")
    protected abstract void regionium$invokeUpdatePlayerPos(ServerPlayer player);

    @Invoker("updateChunkTracking")
    protected abstract void regionium$invokeUpdateChunkTracking(ServerPlayer player);

    @Invoker("applyChunkTrackingView")
    protected abstract void regionium$invokeApplyChunkTrackingView(
        ServerPlayer player,
        ChunkTrackingView view
    );

    @Inject(method = "updateChunkTracking", at = @At("HEAD"), cancellable = true)
    private void regionium$routeChunkTrackingUpdateToPlayerOwner(
        ServerPlayer player,
        CallbackInfo ci
    ) {
        if (player.level() != this.level || player.isRemoved()) {
            return;
        }
        RegioniumRegion owner = this.regionium$playerOwner(player);
        if (RegioniumChunkDebug.enabled()) {
            RegioniumChunkDebug.log("tracking-update-request", RegioniumChunkDebug.playerState(player), "owner=" + (owner == null ? "none" : owner.id()));
        }
        if (owner != null && !owner.isRetired() && owner == RegioniumContext.currentRegion()) {
            return;
        }

        ci.cancel();
        this.regionium$dispatchPeriodicTrackingUpdate(player);
    }

    @Unique
    private void regionium$dispatchPeriodicTrackingUpdate(ServerPlayer player) {
        if (RegioniumChunkDebug.enabled()) {
            RegioniumChunkDebug.log("periodic-tracking-enter", RegioniumChunkDebug.playerState(player));
        }
        if (player.isRemoved() || player.level() != this.level) {
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log("periodic-tracking-abort-player-invalid", RegioniumChunkDebug.playerState(player));
            }
            return;
        }
        RegioniumRegion owner = this.regionium$playerOwner(player);
        if (owner == null || owner.isRetired()) {
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log("periodic-tracking-no-owner-retry", RegioniumChunkDebug.playerState(player), "owner=" + (owner == null ? "none" : owner.id()), "retired=" + (owner != null && owner.isRetired()));
            }
            Regionium.scheduler().executeWhenOwned(
                this.level,
                player.blockPosition(),
                () -> this.regionium$dispatchPeriodicTrackingUpdate(player)
            );
            return;
        }
        if (owner == RegioniumContext.currentRegion()) {
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log("periodic-tracking-update-inline-begin", RegioniumChunkDebug.playerState(player), "owner=" + owner.id());
            }
            this.regionium$invokeUpdateChunkTracking(player);
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log("periodic-tracking-update-inline-end", RegioniumChunkDebug.playerState(player), "owner=" + owner.id());
            }
            return;
        }
        if (RegioniumChunkDebug.enabled()) {
            RegioniumChunkDebug.log("periodic-tracking-submit", RegioniumChunkDebug.playerState(player), "targetRegion=" + owner.id());
        }
        // Spatially keyed work survives a split/merge; a direct RegioniumRegion
        // task can be accepted and then stranded when that region is retired.
        Regionium.scheduler().executeWhenOwned(this.level, player.blockPosition(), () -> {
            if (RegioniumChunkDebug.enabled()) {
                RegioniumRegion currentOwner = RegioniumContext.currentRegion();
                RegioniumChunkDebug.log("periodic-tracking-run", RegioniumChunkDebug.playerState(player), "scheduledRegion=" + (currentOwner == null ? "none" : currentOwner.id()));
            }
            if (player.isRemoved() || player.level() != this.level) {
                if (RegioniumChunkDebug.enabled()) {
                    RegioniumChunkDebug.log("periodic-tracking-run-abort-player-invalid", RegioniumChunkDebug.playerState(player));
                }
                return;
            }
            RegioniumRegion actualOwner = this.regionium$playerOwner(player);
            if (actualOwner != RegioniumContext.currentRegion()) {
                if (RegioniumChunkDebug.enabled()) {
                    RegioniumRegion currentOwner = RegioniumContext.currentRegion();
                    RegioniumChunkDebug.log("periodic-tracking-owner-changed", RegioniumChunkDebug.playerState(player), "scheduled=" + (currentOwner == null ? "none" : currentOwner.id()), "actual=" + (actualOwner == null ? "none" : actualOwner.id()));
                }
                this.regionium$dispatchPeriodicTrackingUpdate(player);
                return;
            }
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log("periodic-tracking-update-run-begin", RegioniumChunkDebug.playerState(player), "owner=" + (actualOwner == null ? "none" : actualOwner.id()));
            }
            this.regionium$invokeUpdateChunkTracking(player);
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log("periodic-tracking-update-run-end", RegioniumChunkDebug.playerState(player), "owner=" + (actualOwner == null ? "none" : actualOwner.id()));
            }
        });
    }

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void regionium$skipGlobalMove(ServerPlayer player, CallbackInfo ci) {
        // Region workers must not mutate ChunkMap's global PlayerMap or
        // DistanceManager. Defer that part to the server-thread pass.
        ci.cancel();
        if (RegioniumContext.isRegionThread()) {
            Regionium.scheduler().queueGlobalPlayerMove(player);
            return;
        }

        // Vanilla ChunkMap.move() begins by iterating every entry in the global
        // entityMap and mutating each TrackedEntity.seenBy set. That is unsafe
        // when entity trackers are concurrently updated by region workers.
        // Keep the global PlayerMap/DistanceManager maintenance, but route the
        // player's chunk view through updateChunkTracking(), which dispatches
        // its mutable PlayerChunkSender state to the owning region. The global
        // entityMap itself remains intact and globally queryable.
        SectionPos oldSection = player.getLastSectionPos();
        SectionPos newSection = SectionPos.of(player);
        if (RegioniumChunkDebug.enabled()) {
            RegioniumChunkDebug.log("global-player-move", RegioniumChunkDebug.playerState(player), "oldSection=" + oldSection, "newSection=" + newSection);
        }

        // PlayerMap and DistanceManager remain global vanilla indexes in
        // Regionium. Serialize their movement update; keep the per-player view
        // update on this player's owning region.
        synchronized (this) {
            boolean wasIgnored = this.playerMap.ignored(player);
            boolean ignored = this.regionium$invokeSkipPlayer(player);
            boolean positionChanged = oldSection.asLong() != newSection.asLong();
            if (!positionChanged && wasIgnored == ignored) {
                return;
            }

            this.regionium$invokeUpdatePlayerPos(player);
            if (!wasIgnored) {
                ((ChunkMap) (Object) this).getDistanceManager().removePlayer(oldSection, player);
            }
            if (!ignored) {
                ((ChunkMap) (Object) this).getDistanceManager().addPlayer(newSection, player);
            }
            if (!wasIgnored && ignored) {
                this.playerMap.ignorePlayer(player);
            } else if (wasIgnored && !ignored) {
                this.playerMap.unIgnorePlayer(player);
            }
        }

        this.regionium$invokeUpdateChunkTracking(player);
    }

    @Inject(method = "applyChunkTrackingView", at = @At("HEAD"), cancellable = true)
    private void regionium$applyTrackingViewOnPlayerOwner(
        ServerPlayer player,
        ChunkTrackingView view,
        CallbackInfo ci
    ) {
        if (this.regionium$validatedTrackingPlayer.get() == player
            && this.regionium$validatedTrackingView.get() == view) {
            // This exact view has already passed the version check while the
            // version lock is held by regionium$applyTrackingViewIfLatest().
            return;
        }
        if (player.level() != this.level || player.isRemoved()) {
            return;
        }

        // applyChunkTrackingView can be requested by global distance-manager
        // work and then queued onto the player region. If the player moves again
        // before that task executes, an older captured view must not overwrite
        // the newer view and strand distant chunks outside the client's view.
        long version = this.regionium$nextTrackingViewVersion(player);
        RegioniumRegion owner = this.regionium$playerOwner(player);
        if (RegioniumChunkDebug.enabled()) {
            RegioniumChunkDebug.log("tracking-view-request", RegioniumChunkDebug.playerState(player), "version=" + version, "owner=" + (owner == null ? "none" : owner.id()), "current=" + (RegioniumContext.currentRegion() == null ? "none" : RegioniumContext.currentRegion().id()));
        }
        RegioniumRegion current = RegioniumContext.currentRegion();
        if (owner == null) {
            // A missing published owner is a lifecycle gap, not permission to
            // mutate PlayerChunkSender from the global thread. Queue the view
            // until the player's chunk owner is published.
            ci.cancel();
            this.regionium$dispatchTrackingView(player, view, null, version);
            return;
        }

        if (!owner.isRetired() && owner == current) {
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log("tracking-view-applied-inline", RegioniumChunkDebug.playerState(player), "version=" + version, "owner=" + owner.id());
            }
            return;
        }

        ci.cancel();
        if (RegioniumChunkDebug.enabled()) {
            RegioniumChunkDebug.log("tracking-view-cancel-and-dispatch", RegioniumChunkDebug.playerState(player), "version=" + version, "targetRegion=" + owner.id());
        }
        this.regionium$dispatchTrackingView(player, view, owner, version);
    }

@org.spongepowered.asm.mixin.Unique
    private RegioniumRegion regionium$playerOwner(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        return Regionium.scheduler().regionizer().owner(
            serverLevel,
            ChunkPos.pack(player.chunkPosition().x(), player.chunkPosition().z())
        );
    }

@org.spongepowered.asm.mixin.Unique
    private void regionium$dispatchTrackingView(
        ServerPlayer player,
        ChunkTrackingView view,
        RegioniumRegion expectedOwner,
        long version
    ) {
        if (player.isRemoved() || player.level() != this.level
            || !this.regionium$isLatestTrackingView(player, version)) {
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log("tracking-view-dispatch-stale-or-invalid", RegioniumChunkDebug.playerState(player), "version=" + version, "latest=" + this.regionium$isLatestTrackingView(player, version));
            }
            return;
        }

        RegioniumRegion owner = this.regionium$playerOwner(player);
        if (owner == null || owner.isRetired()) {
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log("tracking-view-no-owner-retry", RegioniumChunkDebug.playerState(player), "version=" + version);
            }
            Regionium.scheduler().executeWhenOwned(
                this.level,
                player.blockPosition(),
                () -> this.regionium$dispatchTrackingView(player, view, expectedOwner, version)
            );
            return;
        }

        if (owner == RegioniumContext.currentRegion()) {
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log("tracking-view-apply-dispatched-inline", RegioniumChunkDebug.playerState(player), "version=" + version, "owner=" + owner.id());
            }
            this.regionium$applyTrackingViewIfLatest(player, view, version);
            return;
        }

        if (RegioniumChunkDebug.enabled()) {
            RegioniumChunkDebug.log("tracking-view-submit", RegioniumChunkDebug.playerState(player), "version=" + version, "targetRegion=" + owner.id());
        }
        // Queue against the player's spatial key so this callback migrates
        // with the player if topology changes before it executes.
        Regionium.scheduler().executeWhenOwned(this.level, player.blockPosition(), () -> {
            RegioniumRegion scheduledOwner = RegioniumContext.currentRegion();
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log("tracking-view-run", RegioniumChunkDebug.playerState(player), "version=" + version, "scheduledRegion=" + (scheduledOwner == null ? "none" : scheduledOwner.id()));
            }
            if (player.isRemoved() || player.level() != this.level) {
                if (RegioniumChunkDebug.enabled()) {
                    RegioniumChunkDebug.log("tracking-view-run-invalid-player", RegioniumChunkDebug.playerState(player), "version=" + version);
                }
                return;
            }
            if (!this.regionium$isLatestTrackingView(player, version)) {
                if (RegioniumChunkDebug.enabled()) {
                    RegioniumChunkDebug.log(
                        "tracking-view-run-stale",
                        RegioniumChunkDebug.playerState(player),
                        "version=" + version,
                        "latestVersionChanged=true"
                    );
                }
                return;
            }
            RegioniumRegion actualOwner = this.regionium$playerOwner(player);
            if (actualOwner != scheduledOwner) {
                if (RegioniumChunkDebug.enabled()) {
                    RegioniumChunkDebug.log("tracking-view-owner-changed", RegioniumChunkDebug.playerState(player), "version=" + version, "scheduled=" + (scheduledOwner == null ? "none" : scheduledOwner.id()), "actual=" + (actualOwner == null ? "none" : actualOwner.id()));
                }
                this.regionium$dispatchTrackingView(player, view, actualOwner, version);
                return;
            }
            if (RegioniumChunkDebug.enabled()) {
                RegioniumChunkDebug.log("tracking-view-apply-execute", RegioniumChunkDebug.playerState(player), "version=" + version, "owner=" + (actualOwner == null ? "none" : actualOwner.id()));
            }
            this.regionium$applyTrackingViewIfLatest(player, view, version);
        });
    }
}
