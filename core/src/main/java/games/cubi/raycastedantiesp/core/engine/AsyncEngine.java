package games.cubi.raycastedantiesp.core.engine;

import games.cubi.locatables.api.ImmutableSpatial;
import games.cubi.locatables.api.Locatable;
import games.cubi.locatables.implementations.ImmutableSpatialImpl;
import games.cubi.logs.Logger;
import games.cubi.raycastedantiesp.core.config.ConfigManager;
import games.cubi.raycastedantiesp.core.config.DebugConfig;
import games.cubi.raycastedantiesp.core.config.raycast.ChunkSectionConfig;
import games.cubi.raycastedantiesp.core.config.raycast.EntityConfig;
import games.cubi.raycastedantiesp.core.config.raycast.HideBelowYConfig;
import games.cubi.raycastedantiesp.core.config.raycast.PlayerConfig;
import games.cubi.raycastedantiesp.core.config.raycast.TileEntityConfig;
import games.cubi.raycastedantiesp.core.tracked.NettyEntity;
import games.cubi.raycastedantiesp.core.tracked.TrackedChunkSection;
import games.cubi.raycastedantiesp.core.players.PlayerData;
import games.cubi.raycastedantiesp.core.players.PlayerRegistry;
import games.cubi.raycastedantiesp.core.raycast.ChunkSectionLosProbe;
import games.cubi.raycastedantiesp.core.raycast.ChunkSectionVisibilityUtil;
import games.cubi.raycastedantiesp.core.raycast.ParticleSpawner;
import games.cubi.raycastedantiesp.core.raycast.RaycastUtil;
import games.cubi.raycastedantiesp.core.utils.PrimitiveIntArrayList;
import games.cubi.raycastedantiesp.core.view.BlockView;
import games.cubi.raycastedantiesp.core.view.EntityView;
import games.cubi.raycastedantiesp.core.view.chunks.ChunkSectionStore;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

public abstract class AsyncEngine implements Engine {
    private static final long SLOW_TICK_NANOS = 40 * 1_000_000L;
    //Literally just magic numbers I made by keyboard mashing
    private static final int TICK_IDLE = 1872;
    private static final int TICK_PENDING = -129;
    private static final int TICK_RUNNING = 34892;
    /** Cap HIDE sections painted per player when broad debug is on (closest first). */
    private static final int SECTION_DEBUG_MAX_SECTIONS_PER_TICK = 320;
    /** Clear-path particle every N steps along a debug ray (occluders always drawn). */
    private static final int SECTION_DEBUG_CLEAR_STRIDE = 10;
    /** Spawn section debug particles every N ticks (not every tick). */
    private static final int SECTION_DEBUG_TICK_INTERVAL = 5;

    private final ConfigManager config;
    private final ParticleSpawner particleSpawner;
    private final IntSupplier currentTickSupplier;
    private final AtomicInteger tickThreadsRunning = new AtomicInteger(0);
    private final AtomicInteger runningTick = new AtomicInteger(-1);
    private final AtomicInteger tickState = new AtomicInteger(TICK_IDLE);
    private final AtomicLong tickNanos = new AtomicLong(0);
    private final AsyncRunner asyncRunner;
    private final TimingStatsSelector timingStatsSelector = new TimingStatsSelector();
    private Consumer<PlayerData> hideBelowYRecheckListener = null;

    public AsyncEngine(ConfigManager config, ParticleSpawner particleSpawner, IntSupplier currentTickSupplier, AsyncRunner asyncRunner) {
        this.config = config;
        this.particleSpawner = particleSpawner;
        this.currentTickSupplier = currentTickSupplier;
        this.asyncRunner = asyncRunner;
    }

    public void setHideBelowYRecheckListener(Consumer<PlayerData> listener) {
        this.hideBelowYRecheckListener = listener;
    }

    /**
     * Reserves the next engine tick before it is handed to the async scheduler.
     *
     * @return true if the caller now owns the only pending tick slot; false if an existing pending
     * or running tick should cover this attempt.
     */
    public boolean markTickRunning() {
        if (tickState.compareAndSet(TICK_IDLE, TICK_PENDING)) {
            return true;
        }
        recordSkippedTick(-1, System.nanoTime());
        return false;
    }

    /**
     * Releases a pending tick reservation when async scheduling fails before {@link #tick(int, long)}
     * can claim it.
     */
    public void cancelPendingTickReservation() {
        if (!tickState.compareAndSet(TICK_PENDING, TICK_IDLE)) {
            Logger.warning("Attempted to cancel a pending tick reservation, but the tick was no longer pending.", 5, AsyncEngine.class);
        }
    }

    /**
     * Runs the tick which was reserved using {@link #markTickRunning} and, if the engine falls behind the server clock, keeps the
     * same worker on the latest tick instead of yielding back to the scheduler.
     *
     * @param scheduledTick the server tick captured before async handoff.
     * @param scheduledNanos the time captured before async handoff, used to separate queue delay from engine work.
     */
    @Override
    public void tick(int scheduledTick, long scheduledNanos) {
        boolean runTick = true;
        boolean expectPending = true;
        int startTick = currentTickSupplier.getAsInt();
        while (runTick) {
            if (!startTickState(scheduledTick, expectPending)) {
                return;
            }
            if (!dispatchTick(scheduledTick, startTick, scheduledNanos)) {
                return;
            }

            int latestTick = currentTickSupplier.getAsInt();
            if (latestTick > startTick) {
                // If running behind, don't yield the thread, just run the next tick immediately
                Logger.warning("Tick thread completed tick #" + startTick + " after tick #" + latestTick + " had already begun. Starting next tick immediately instead of yielding thread to scheduler. This is probably safe to ignore but may suggest that your server is overloaded.", 5);
                startTick = latestTick;
                scheduledNanos = System.nanoTime();
                scheduledTick = latestTick;

                if (tickState.get() == TICK_RUNNING) {
                    // This means that there is already a tick running, so we should exit
                    Logger.info("Tick finished behind but another thread had already begun ticking the next tick.", 5, AsyncEngine.class);
                    return;
                }
                expectPending = false; //Next call to startTickState should not expect to see status set to pending
            }
            else runTick = false;
        }
    }

    /**
     * Claims worker capacity and either runs work immediately or schedules worker batches.
     *
     * @return true if at least one sub-tick worker accepted responsibility for completing the tick
     * lifecycle; false if this attempt was skipped or cleaned up during setup.
     */
    private boolean dispatchTick(int scheduledTick, int startTick, long scheduledNanos) {
        int threads = config.getEngineConfig().asyncConfig().asyncProcessingThreads();
        if (threads < 1) threads = 1;

        DebugConfig debugConfig = config.getDebugConfig();
        TimingStats tickTimingStats = timingStats(debugConfig);
        boolean recordTimings = tickTimingStats != TimingStatsNoOp.INSTANCE;

        long startNanos = System.nanoTime();

        int tickAlreadyRunning = runningTick.get();
        // First guard for the current tick already being processed (can happen if the previous tick ran overtime and thus didn't yield the thread)
        if (scheduledTick == tickAlreadyRunning) {
            logAggregateReport(tickTimingStats.recordSkipped(threads, startNanos));
            Logger.info("RaycastedAntiESP is already processing this tick; skipping duplicate same-tick attempt."
                    + " scheduledTick=" + scheduledTick
                    + " currentServerTick=" + startTick
                    + " currentRunningTick=" + tickAlreadyRunning, 6, AsyncEngine.class);
            finishTickState();
            return false;
        }

        int runningThreads = tickThreadsRunning.compareAndExchange(0, threads);
        // Now guard for previous tick still running
        if (runningThreads != 0) {
            long queueNanos = Math.max(0, startNanos - scheduledNanos);
            Logger.warning("RaycastedAntiESP is still ticking from the last tick! Skipping this tick to avoid concurrent modification issues."
                    + " scheduledTick=" + scheduledTick
                    + " currentServerTick=" + currentTickSupplier.getAsInt()
                    + " currentRunningTick=" + tickAlreadyRunning
                    + " runningThreads=" + runningThreads
                    + " timeSpentInQueue=" + TickTimingFormatter.formatMillis(queueNanos) + "ms", 5, AsyncEngine.class);
            logAggregateReport(tickTimingStats.recordSkipped(threads, startNanos));
            finishTickState();
            return false;
        }

        TickTimings timings = null;
        boolean handedOffToSubTick = false;
        boolean claimedRunningTick = false;
        try {
            tickNanos.set(startNanos);

            final int currentTick = startTick;
            runningTick.set(currentTick);
            claimedRunningTick = true;
            Collection<PlayerData> allPlayers = PlayerRegistry.getInstance().getAllPlayerData();

            EntityConfig entityConfig = config.getEntityConfig();
            PlayerConfig playerConfig = config.getPlayerConfig();
            TileEntityConfig tileEntityConfig = config.getTileEntityConfig();
            ChunkSectionConfig chunkSectionConfig = config.getChunkSectionConfig();
            if (recordTimings) {
                timings = new TickTimings(scheduledTick, scheduledNanos, currentTick, startNanos, threads, allPlayers.size());
            }
            /*
            Logger.debug("Tick #" + currentTick);
            if (currentTick % 1200 == 0) {
                Logger.debug("Printing player data");
                for (PlayerData playerData : allPlayers) {
                    Logger.debug("Player " + playerData.getPlayerUUID() + " location=" + playerData.ownLocation());
                    Logger.debug("EntityView:" + playerData.entityView().getStringDataForDebugging());
                    Logger.debug("PlayerView:" + playerData.playerView().getStringDataForDebugging());
                }
            }*/

            // If only one thread is configured, just use the current async thread to avoid the overhead of scheduling tasks and context switching.
            if (threads == 1) {
                handedOffToSubTick = true;
                subTick(new ArrayList<>(allPlayers), entityConfig, playerConfig, tileEntityConfig, chunkSectionConfig, debugConfig, currentTick, timings, tickTimingStats);
                return true;
            }

            List<List<PlayerData>> batches = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                batches.add(new ArrayList<>());
            }

            int index = 0;
            for (PlayerData playerData : allPlayers) {
                batches.get(index++ % threads).add(playerData);
            }

            TickTimings tickTimings = timings; // needs to be effectively-final for lambda
            int scheduledBatches = 0;
            try {
                for (List<PlayerData> batch : batches) {
                    asyncRunner.runNow(() -> subTick(batch, entityConfig, playerConfig, tileEntityConfig, chunkSectionConfig, debugConfig, currentTick, tickTimings, tickTimingStats));
                    scheduledBatches++;
                }
                handedOffToSubTick = true;
            }
            finally {
                if (scheduledBatches < threads) {
                    tickThreadsRunning.addAndGet(-(threads - scheduledBatches));
                    handedOffToSubTick = scheduledBatches > 0;
                }
            }
            return handedOffToSubTick;
        }
        finally {
            if (!handedOffToSubTick) {
                tickThreadsRunning.set(0);
                if (claimedRunningTick) {
                    finaliseTick(startTick);
                } else {
                    finishTickState();
                }
                Logger.error("An error occurred during tick scheduling before handing off to sub-tick processing. Resetting tickThreadsRunning to 0 to avoid deadlock. Current tick: " + currentTickSupplier.getAsInt(), 2, AsyncEngine.class);
            }
        }
    }

    /**
     * Processes one worker batch and lets the final batch publish timing data and release the tick reservation.
     *
     * @param timingStats the timing sink selected when this tick started, so config changes during
     * worker execution do not split one tick across sinks.
     */
    private void subTick(List<PlayerData> batch, EntityConfig entityConfig, PlayerConfig playerConfig, TileEntityConfig tileEntityConfig, ChunkSectionConfig chunkSectionConfig, DebugConfig debugConfig, int currentTick, TickTimings timings, TimingStats timingStats) {
        TickTimingBatch batchTimings = timings == null ? TickTimingBatchNoOp.INSTANCE : new TickTimingBatch();
        long batchStartNanos = batchTimings.startBatch();
        try {
            processTickForPlayers(batch, entityConfig, playerConfig, tileEntityConfig, chunkSectionConfig, debugConfig, currentTick, batchTimings);
        }
        finally {
            if (timings != null) {
                timings.recordBatch(batchTimings, batchTimings.elapsedSince(batchStartNanos));
            }
            int threadsRemaining = tickThreadsRunning.decrementAndGet();
            if (threadsRemaining < 0) {
                Logger.error("tickThreadsRunning went below 0! This should never happen. Resetting to 0 to avoid further issues.", 2, AsyncEngine.class);
                tickThreadsRunning.set(0);
                finaliseTick(currentTick);
            }
            if (threadsRemaining == 0) {
                try {
                    long completionNanos = System.nanoTime();
                    if (timings != null) {
                        TickTimingSnapshot snapshot = timings.snapshot(currentTickSupplier.getAsInt(), completionNanos);
                        String aggregateReport = timingStats.recordCompleted(snapshot, completionNanos);
                        if (snapshot.wallNanos() > SLOW_TICK_NANOS) {
                            Logger.warning(snapshot.toSlowTickMessage(), 5, AsyncEngine.class);
                        }
                        logAggregateReport(aggregateReport);
                    } else {
                        long elapsedNanos = completionNanos - tickNanos.get();
                        if (elapsedNanos > SLOW_TICK_NANOS) {
                            Logger.warning("Tick completed in " + (elapsedNanos / 1_000_000.0) + " ms. If you see this warning frequently, consider reducing the raycasting load by adjusting the configuration.", 5, AsyncEngine.class);
                        }
                    }
                } finally {
                    finaliseTick(currentTick);
                }
            }
        }
    }

    /**
     * Moves the reserved tick into the running state. Catch-up iterations may start from idle
     * because they are created by the current worker instead of by the server tick event.
     *
     * @return true if this thread now owns the running state; false if another pending or running
     * tick should take precedence.
     */
    private boolean startTickState(int scheduledTick, boolean expectPending) {
        if (expectPending) {
            if (tickState.compareAndSet(TICK_PENDING, TICK_RUNNING)) {
                return true;
            }
            if (tickState.compareAndSet(TICK_IDLE, TICK_RUNNING)) {
                // This means that this tick was dispatched but not marked as such, or was unmarked at some point.
                Logger.warning("Tick " + scheduledTick + " was dispatched but not marked as pending. This suggests a race condition. Please report this on our GitHub or Discord.", 5, AsyncEngine.class);
                return true;
            }
        } else if (tickState.compareAndSet(TICK_IDLE, TICK_RUNNING)) {
            return true;
        }

        // This means there is already a tick pending or running, so we should exit.
        recordSkippedTick(-1, System.nanoTime());
        Logger.info("Tick " + scheduledTick + " is pending but another tick is already pending or running. Exiting now.", 6, AsyncEngine.class);
        return false;
    }

    /**
     * Releases ownership for the tick that completed, without clearing a newer running tick that
     * may have already taken over.
     */
    private void finaliseTick(int currentTick) {
        runningTick.compareAndSet(currentTick, -1);
        finishTickState();
    }

    /**
     * Returns the reservation state to idle after the active tick has stopped touching shared player visibility data.
     */
    private void finishTickState() {
        if (!tickState.compareAndSet(TICK_RUNNING, TICK_IDLE)) {
            // This should never happen as it means the tick was marked as completed before processing was completed
            Logger.warning("tickState was not running when completing tick! This should never happen. Please report this on our GitHub or Discord.", 5, AsyncEngine.class);
        }
    }

    private void processTickForPlayers(List<PlayerData> playerDataList, EntityConfig entityConfig, PlayerConfig playerConfig, TileEntityConfig tileEntityConfig,
                                       ChunkSectionConfig chunkSectionConfig, DebugConfig debugConfig, int currentTick, TickTimingBatch timings) {
        boolean debugParticles = debugConfig.showDebugParticles();
        int chunkSectionDebugVerticalDown = debugConfig.chunkSectionDebugVerticalDown();

        for (PlayerData playerData : playerDataList) {
            if (!playerData.isConnected()) {
                continue;
            }
            playerData.nettyData().markPendingPostSpawnTasksForEviction();
            if (playerData.hasBypassPermission()) {
                timings.incrementBypassSkippedPlayers();
                continue;
            }

            BlockView blockView = playerData.blockView();

            Locatable playerLocation = playerData.ownLocation();
            if (playerLocation == null || playerLocation.world() == null) {
                timings.incrementNullLocationSkippedPlayers();
                continue;
            }
            int worldEpoch = playerData.tryAcquireWorldEpochFor(playerLocation.world());
            if (worldEpoch == PlayerData.INVALID_WORLD_EPOCH) {
                if (tileEntityConfig.enabled()) timings.incrementTileWorldSkipped();
                if (chunkSectionConfig.enabled()) timings.incrementSectionWorldSkipped();
                continue;
            }
            timings.incrementProcessedPlayers();

            if (entityConfig.enabled()) {
                long sectionStartNanos = timings.startEntitySection();
                checkEntities(playerData, playerLocation, entityConfig, debugParticles, blockView, currentTick, worldEpoch, timings);
                timings.finishEntitySection(sectionStartNanos);
            }
            if (playerConfig.enabled()) {
                long sectionStartNanos = timings.startPlayerSection();
                checkPlayers(playerData, playerLocation, playerConfig, debugParticles, blockView, currentTick, worldEpoch, timings);
                timings.finishPlayerSection(sectionStartNanos);
            }
            if (tileEntityConfig.enabled()) {
                long sectionStartNanos = timings.startTileSection();
                checkTileEntities(playerData, playerLocation, tileEntityConfig, debugParticles, blockView, currentTick, worldEpoch, timings);
                timings.finishTileSection(sectionStartNanos);
            }
            if (chunkSectionConfig.enabled()) {
                long sectionStartNanos = timings.startChunkSectionSection();
                // Section ray visuals are per-player (toggle / test command), not global debug.particles.
                boolean broadSectionDebug = playerData.chunkSectionRayDebug();
                boolean focusedSectionDebug = playerData.hasChunkSectionRayDebugTarget();
                checkChunkSections(
                        playerData,
                        playerLocation,
                        chunkSectionConfig,
                        broadSectionDebug,
                        focusedSectionDebug,
                        chunkSectionDebugVerticalDown,
                        blockView,
                        currentTick,
                        worldEpoch,
                        timings
                );
                timings.finishChunkSectionSection(sectionStartNanos);
            }
            HideBelowYConfig hideBelowYConfig = config.getHideBelowYConfig();
            if (hideBelowYConfig != null && hideBelowYConfig.enabled() && hideBelowYRecheckListener != null) {
                if (currentTick % hideBelowYConfig.recheckIntervalTicks() == 0) {
                    hideBelowYRecheckListener.accept(playerData);
                }
            }
        }
    }

    private void checkEntities(PlayerData player, Locatable playerLocation, EntityConfig entityConfig, boolean debugParticles, BlockView blockView, int currentTick, int worldEpoch, TickTimingBatch timings) {
        EntityView<?> entityView = player.entityView();
        HideBelowYConfig hideBelowYConfig = config.getHideBelowYConfig();

        int checked = entityView.forEachNeedingRecheckEntity(entityConfig.getVisibleRecheckIntervalTicks(), currentTick, !(timings instanceof TickTimingBatchNoOp), worldEpoch, entity -> {
            boolean wasVisible = entity.visible();
            if (attachedToSelf(player, entityView, entity, currentTick, worldEpoch)) {
                return;
            }
            if (hideBelowYConfig != null && hideBelowYConfig.enabled() && hideBelowYConfig.shouldAutoHideBlock(playerLocation.y(), (int) Math.floor(entity.y()))) {
                entityView.setVisibility(entity, false, currentTick, worldEpoch);
                return;
            }
            ImmutableSpatial entityLocation = entity.getOffsetPosition();
            if (entityLocation == null) {
                timings.incrementEntityNullTargets();
                Logger.debug("checkEntities skipped-null-location viewer=" + player.getPlayerUUID()
                        + " target=" + entity.entityUUID()
                        + " wasVisible=" + wasVisible
                        + " tick=" + currentTick);
                return;
            }
            timings.incrementEntityRaycasts();
            boolean canSee = RaycastUtil.raycast(playerLocation, entityLocation, entityConfig.getMaxOccludingCount(), entityConfig.getAlwaysShowRadius(), entityConfig.getRaycastRadius(), debugParticles, blockView, 1, particleSpawner);
            entityView.setVisibility(entity, canSee, currentTick, worldEpoch);
        });
        timings.addEntityChecked(checked);
    }

    private void checkPlayers(PlayerData player, Locatable playerLocation, PlayerConfig playerConfig, boolean debugParticles, BlockView blockView, int currentTick, int worldEpoch, TickTimingBatch timings) {
        EntityView<?> playerView = player.playerView();
        HideBelowYConfig hideBelowYConfig = config.getHideBelowYConfig();

        int checked = playerView.forEachNeedingRecheckEntity(playerConfig.getVisibleRecheckIntervalTicks(), currentTick, !(timings instanceof TickTimingBatchNoOp), worldEpoch, otherPlayer -> {
            boolean wasVisible = otherPlayer.visible();
            if (attachedToSelf(player, playerView, otherPlayer, currentTick, worldEpoch)) {
                return;
            }
            if (hideBelowYConfig != null && hideBelowYConfig.enabled() && hideBelowYConfig.shouldAutoHideBlock(playerLocation.y(), (int) Math.floor(otherPlayer.y()))) {
                playerView.setVisibility(otherPlayer, false, currentTick, worldEpoch);
                return;
            }
            ImmutableSpatial otherPlayerLocation = otherPlayer.getOffsetPosition();
            if (otherPlayerLocation == null) {
                timings.incrementPlayerNullTargets();
                Logger.debug("checkPlayers skipped-null-location viewer=" + player.getPlayerUUID()
                        + " target=" + otherPlayer.entityUUID()
                        + " wasVisible=" + wasVisible
                        + " tick=" + currentTick);
                return;
            }
            timings.incrementPlayerRaycasts();
            boolean canSee = RaycastUtil.raycast(playerLocation, otherPlayerLocation, playerConfig.getMaxOccludingCount(), playerConfig.getAlwaysShowRadius(), playerConfig.getRaycastRadius(), debugParticles, blockView, 1, particleSpawner);
            playerView.setVisibility(otherPlayer, canSee, currentTick, worldEpoch);
        });
        timings.addPlayerChecked(checked);
    }

    private boolean attachedToSelf(PlayerData player, EntityView<?> view, NettyEntity<?,?> entity, int currentTick, int worldEpoch) {
        int selfEntityID = player.nettyData().getSelfEntityID();
        if (!player.nettyData().isSelfEntityID(entity.leashingEntity())
                && !player.nettyData().isSelfEntityID(entity.vehicleID())
                && !PrimitiveIntArrayList.contains(entity.passengerIDs(), selfEntityID)) {
            return false;
        }
        view.setVisibility(entity, true, currentTick, worldEpoch);
        return true;
    }

    private void checkTileEntities(PlayerData player, Locatable playerLocation, TileEntityConfig tileEntityConfig, boolean debugParticles, BlockView blockView, int currentTick, int worldEpoch, TickTimingBatch timings) {
        long modeToken = blockView.tileEntityCheckModeToken();
        HideBelowYConfig hideBelowYConfig = config.getHideBelowYConfig();

        int checked = blockView.updateVisibilityForEachNeedingRecheck(tileEntityConfig.getVisibleRecheckIntervalTicks(), currentTick, modeToken, worldEpoch, tileEntityLocation -> {
            if (hideBelowYConfig != null && hideBelowYConfig.enabled() && hideBelowYConfig.shouldAutoHideBlock(playerLocation.y(), tileEntityLocation.blockY())) {
                return BlockView.VisibilityResolver.HIDE;
            }
            if (playerLocation.distanceSquared(tileEntityLocation) > (double) tileEntityConfig.getRaycastRadius() * tileEntityConfig.getRaycastRadius()) {
                timings.incrementTileRadiusSkipped();
                return BlockView.VisibilityResolver.HIDE;
            }
            timings.incrementTileRaycasts();
            boolean canSee = RaycastUtil.raycast(playerLocation, tileEntityLocation, tileEntityConfig.getMaxOccludingCount() + 1, tileEntityConfig.getAlwaysShowRadius(), tileEntityConfig.getRaycastRadius(), debugParticles, blockView, 1, particleSpawner);
            return canSee ? BlockView.VisibilityResolver.SHOW : BlockView.VisibilityResolver.HIDE;
        });
        timings.addTileChecked(checked);
    }

    private void checkChunkSections(PlayerData player, Locatable playerLocation, ChunkSectionConfig chunkSectionConfig,
                                    boolean broadDebug, boolean focusedDebug, int debugVerticalDown, BlockView blockView,
                                    int currentTick, int worldEpoch, TickTimingBatch timings) {
        long modeToken = blockView.chunkSectionCheckModeToken();
        if (!blockView.isCurrentEnabledChunkSectionMode(modeToken)) {
            return;
        }
        UUID world = playerLocation.world();
        int viewerChunkX = playerLocation.blockX() >> 4;
        int viewerSectionY = playerLocation.blockY() >> 4;
        int viewerChunkZ = playerLocation.blockZ() >> 4;
        int alwaysShowRadius = chunkSectionConfig.alwaysShowRadiusChunks();
        int alwaysShowVerticalDown = chunkSectionConfig.alwaysShowVerticalDown();
        int alwaysShowVerticalUp = chunkSectionConfig.alwaysShowVerticalUp();
        int raycastRadius = chunkSectionConfig.raycastRadiusChunks();
        int raycastRadiusBlocks = raycastRadius * 16;
        int hideDelayTicks = chunkSectionConfig.hideDelayTicks();
        boolean neighborPadding = chunkSectionConfig.neighborPadding();
        int visibleRecheckTicks = chunkSectionConfig.visibleRecheckIntervalTicks();
        int hiddenRecheckTicks = chunkSectionConfig.hiddenRecheckIntervalTicks();
        boolean paintDebugThisTick = (broadDebug || focusedDebug)
                && Math.floorMod(currentTick, SECTION_DEBUG_TICK_INTERVAL) == 0;

        blockView.refreshDirtySectionVisConnectivity();

        HideBelowYConfig hideBelowYConfig = config.getHideBelowYConfig();

        LongOpenHashSet reachable = new LongOpenHashSet();
        LongOpenHashSet frontier = new LongOpenHashSet();
        ChunkSectionLosProbe.collectFrontierFromEye(
                playerLocation, raycastRadius, blockView, reachable, frontier
        );

        LongOpenHashSet wantShow = new LongOpenHashSet();
        LongOpenHashSet evaluatedThisTick = new LongOpenHashSet();
        int[] checked = {0};
        // Broad debug: collect HIDE candidates, then paint closest (avoids hash-order lottery).
        List<TrackedChunkSection> debugHideCandidates = broadDebug && !focusedDebug && paintDebugThisTick
                ? new ArrayList<>()
                : null;

        List<TrackedChunkSection> sectionsToEvaluate = new ArrayList<>();
        blockView.forEachTrackedChunkSection(sectionsToEvaluate::add);

        if (chunkSectionConfig.directionalOcclusionCulling()) {
            sectionsToEvaluate.sort(Comparator.comparingInt(section ->
                    ChunkSectionVisibilityUtil.chebyshevSectionDistance(
                            viewerChunkX, viewerSectionY, viewerChunkZ,
                            section.chunkX(), section.sectionY(), section.chunkZ()
                    )
            ));
        }

        LongOpenHashSet occludedSections = chunkSectionConfig.directionalOcclusionCulling()
                ? new LongOpenHashSet()
                : null;

        for (TrackedChunkSection section : sectionsToEvaluate) {
            checked[0]++;
            int chebyshev = ChunkSectionVisibilityUtil.chebyshevSectionDistance(
                    viewerChunkX, viewerSectionY, viewerChunkZ,
                    section.chunkX(), section.sectionY(), section.chunkZ()
            );
            if (chebyshev > raycastRadius) {
                timings.incrementSectionRadiusSkipped();
                continue;
            }
            long key = ChunkSectionStore.packChunkCoords(section.chunkX(), section.sectionY(), section.chunkZ());
            if (hideBelowYConfig != null && hideBelowYConfig.enabled()
                    && hideBelowYConfig.shouldAutoHideSection(playerLocation.y(), section.sectionY())) {
                timings.incrementSectionRadiusSkipped();
                evaluatedThisTick.add(key);
                if (occludedSections != null) {
                    occludedSections.add(key);
                }
                continue;
            }
            if (ChunkSectionVisibilityUtil.isWithinAlwaysShow(
                    viewerChunkX, viewerSectionY, viewerChunkZ,
                    section.chunkX(), section.sectionY(), section.chunkZ(),
                    alwaysShowRadius, alwaysShowVerticalDown, alwaysShowVerticalUp)
                    || ChunkSectionVisibilityUtil.isEyeInsideSection(playerLocation, section)) {
                wantShow.add(key);
                evaluatedThisTick.add(key);
                continue;
            }

            int lastChecked = section.lastChecked();
            // Sticky SHOW: avoid re-raycasting every tick (major server cost / client thrash source).
            if (section.visible()
                    && lastChecked != TrackedChunkSection.NEVER_CHECKED
                    && visibleRecheckTicks > 0
                    && currentTick - lastChecked < visibleRecheckTicks) {
                wantShow.add(key);
                continue;
            }
            // Sticky HIDE: skip re-probing recently culled sections.
            if (!section.visible()
                    && lastChecked != TrackedChunkSection.NEVER_CHECKED
                    && hiddenRecheckTicks > 0
                    && currentTick - lastChecked < hiddenRecheckTicks) {
                if (occludedSections != null) {
                    occludedSections.add(key);
                }
                continue;
            }

            // VisGraph frontier: reachable open paths + face-adjacent shell (SOLID rock faces).
            if (!frontier.contains(key)) {
                evaluatedThisTick.add(key);
                if (occludedSections != null) {
                    occludedSections.add(key);
                }
                continue;
            }

            if (occludedSections != null && ChunkSectionLosProbe.isSectionOccludedByShadow(
                    playerLocation, section.chunkX(), section.sectionY(), section.chunkZ(), occludedSections)) {
                evaluatedThisTick.add(key);
                occludedSections.add(key);
                if (debugHideCandidates != null && section.sectionY() >= viewerSectionY - debugVerticalDown) {
                    debugHideCandidates.add(section);
                }
                continue;
            }

            evaluatedThisTick.add(key);
            timings.incrementSectionRaycasts();
            if (ChunkSectionLosProbe.canSeeByFacingFaces(
                    playerLocation,
                    section.chunkX(),
                    section.sectionY(),
                    section.chunkZ(),
                    chunkSectionConfig.maxOccludingCount(),
                    raycastRadiusBlocks,
                    blockView
            )) {
                wantShow.add(key);
            } else {
                if (occludedSections != null) {
                    occludedSections.add(key);
                }
                if (debugHideCandidates != null
                        && section.sectionY() >= viewerSectionY - debugVerticalDown) {
                    debugHideCandidates.add(section);
                }
            }
        }

        if (focusedDebug && paintDebugThisTick) {
            long target = player.chunkSectionRayDebugTarget();
            ChunkSectionLosProbe.paintSectionDebugRays(
                    playerLocation,
                    ChunkSectionStore.unpackChunkX(target),
                    ChunkSectionStore.unpackSectionY(target),
                    ChunkSectionStore.unpackChunkZ(target),
                    chunkSectionConfig.maxOccludingCount(),
                    raycastRadiusBlocks,
                    blockView,
                    particleSpawner,
                    player.getPlayerUUID(),
                    SECTION_DEBUG_CLEAR_STRIDE
            );
        } else if (debugHideCandidates != null && !debugHideCandidates.isEmpty()) {
            paintClosestSectionHideRayDebug(
                    player,
                    playerLocation,
                    debugHideCandidates,
                    chunkSectionConfig.maxOccludingCount(),
                    raycastRadiusBlocks,
                    blockView
            );
        }

        int preemptiveReveal = chunkSectionConfig.preemptiveNeighborReveal();
        LongOpenHashSet clientShow = wantShow;
        if ((neighborPadding || preemptiveReveal > 0) && !wantShow.isEmpty()) {
            clientShow = new LongOpenHashSet(wantShow);
            int r = Math.max(preemptiveReveal > 0 ? preemptiveReveal : 0, neighborPadding ? 1 : 0);
            for (long key : wantShow) {
                int cx = ChunkSectionStore.unpackChunkX(key);
                int sy = ChunkSectionStore.unpackSectionY(key);
                int cz = ChunkSectionStore.unpackChunkZ(key);
                for (int dx = -r; dx <= r; dx++) {
                    for (int dy = -r; dy <= r; dy++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) {
                                continue;
                            }
                            TrackedChunkSection neighbor = blockView.getTrackedChunkSection(world, cx + dx, sy + dy, cz + dz);
                            if (neighbor != null) {
                                if (preemptiveReveal > 0 || neighbor.visible()) {
                                    clientShow.add(ChunkSectionStore.packChunkCoords(cx + dx, sy + dy, cz + dz));
                                }
                            }
                        }
                    }
                }
            }
        }

        LongOpenHashSet finalClientShow = clientShow;
        LongOpenHashSet authoritativeShow = wantShow;
        blockView.forEachTrackedChunkSection(section -> {
            long key = ChunkSectionStore.packChunkCoords(section.chunkX(), section.sectionY(), section.chunkZ());
            boolean inWantShow = authoritativeShow.contains(key);
            boolean inClientShow = finalClientShow.contains(key);
            if (inWantShow) {
                section.setWantHiddenSinceTick(TrackedChunkSection.NOT_WANTING_HIDE);
                if (!section.visible()) {
                    blockView.applyChunkSectionVisibilityDecision(section, true, currentTick, modeToken, worldEpoch);
                } else if (evaluatedThisTick.contains(key)) {
                    // Advance recheck clock only after a real evaluation, not sticky reuse.
                    section.setLastChecked(currentTick);
                }
                return;
            }
            // Advance clock on real HIDE evaluations (VisGraph auto-hide / raycast blocked) so sticky HIDE works.
            if (evaluatedThisTick.contains(key)) {
                section.setLastChecked(currentTick);
            }
            // Neighbor-padding keep-alive must not reset the hide timer — otherwise a visible surface
            // shell next to any still-shown cave/always-show section never culls (cave ESP).
            if (inClientShow) {
                if (section.wantHiddenSinceTick() == TrackedChunkSection.NOT_WANTING_HIDE) {
                    section.setWantHiddenSinceTick(currentTick);
                }
                if (section.visible()
                        && ChunkSectionVisibilityUtil.hideDelayElapsed(section.wantHiddenSinceTick(), currentTick, hideDelayTicks)) {
                    blockView.applyChunkSectionVisibilityDecision(section, false, currentTick, modeToken, worldEpoch);
                }
                return;
            }
            if (section.wantHiddenSinceTick() == TrackedChunkSection.NOT_WANTING_HIDE) {
                section.setWantHiddenSinceTick(currentTick);
            }
            if (section.visible()
                    && ChunkSectionVisibilityUtil.hideDelayElapsed(section.wantHiddenSinceTick(), currentTick, hideDelayTicks)) {
                blockView.applyChunkSectionVisibilityDecision(section, false, currentTick, modeToken, worldEpoch);
            }
        });
        timings.addSectionChecked(checked[0]);
    }

    /**
     * Paints HIDE rays for the closest sections first (face-center samples, strided clear steps).
     */
    private void paintClosestSectionHideRayDebug(
            PlayerData player,
            Locatable eye,
            List<TrackedChunkSection> hideCandidates,
            int maxOccludingCount,
            int maxRaycastRadiusBlocks,
            BlockView blockView
    ) {
        hideCandidates.sort((a, b) -> Double.compare(
                sectionCenterDistSq(eye, a),
                sectionCenterDistSq(eye, b)
        ));
        UUID viewer = player.getPlayerUUID();
        int painted = 0;
        for (TrackedChunkSection section : hideCandidates) {
            if (painted >= SECTION_DEBUG_MAX_SECTIONS_PER_TICK) {
                break;
            }
            List<ImmutableSpatialImpl> centers = ChunkSectionVisibilityUtil.sampleFaceCentersForFacingFaces(
                    eye.x(), eye.y(), eye.z(),
                    section.chunkX(), section.sectionY(), section.chunkZ()
            );
            if (centers.isEmpty()) {
                continue;
            }
            painted++;
            for (ImmutableSpatialImpl sample : centers) {
                particleSpawner.spawnParticleAt(eye.world(), sample, ParticleSpawner.Colour.SECTION_HIDE_SAMPLE, viewer);
                RaycastUtil.raycastUntilSectionEntry(
                        eye,
                        sample,
                        section.chunkX(),
                        section.sectionY(),
                        section.chunkZ(),
                        maxOccludingCount,
                        0,
                        maxRaycastRadiusBlocks,
                        true,
                        blockView,
                        1,
                        particleSpawner,
                        viewer,
                        ParticleSpawner.Colour.SECTION_HIDE_STEP,
                        ParticleSpawner.Colour.SECTION_HIDE_OCCLUDER,
                        ParticleSpawner.Colour.SECTION_SHOW_ENTRY,
                        SECTION_DEBUG_CLEAR_STRIDE
                );
            }
        }
    }

    private static double sectionCenterDistSq(Locatable eye, TrackedChunkSection section) {
        double cx = (section.chunkX() << 4) + 8.0;
        double cy = (section.sectionY() << 4) + 8.0;
        double cz = (section.chunkZ() << 4) + 8.0;
        double dx = cx - eye.x();
        double dy = cy - eye.y();
        double dz = cz - eye.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static void logAggregateReport(String aggregateReport) {
        if (aggregateReport != null) {
            Logger.info(aggregateReport, 4, AsyncEngine.class);
        }
    }

    /**
     * Counts a skipped tick against the currently configured timing sink.
     */
    private void recordSkippedTick(int threads, long nowNanos) {
        logAggregateReport(timingStats().recordSkipped(threads, nowNanos));
    }

    /**
     * Selects the timing sink from the current debug config.
     *
     * @return a collecting {@link TimingStats} while timing diagnostics are enabled, otherwise the
     * no-op singleton.
     */
    private TimingStats timingStats() {
        return timingStats(config.getDebugConfig());
    }

    /**
     * Keeps disabled timing diagnostics allocation-free and starts a fresh collection window when
     * diagnostics are re-enabled.
     *
     * @return the timing sink that should be used for a newly starting tick or skip record.
     */
    private TimingStats timingStats(DebugConfig debugConfig) {
        return timingStatsSelector.select(debugConfig.recordTimings());
    }
}
