package games.cubi.raycastedantiesp.packetevents.viewcontrollers;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.HeightmapType;
import com.github.retrooper.packetevents.protocol.world.chunk.LightData;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.PaletteType;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.SingletonPalette;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import games.cubi.locatables.api.BlockSpatial;
import games.cubi.locatables.api.Locatable;
import games.cubi.locatables.implementations.ImmutableBlockSpatialImpl;
import games.cubi.locatables.implementations.MutableBlockSpatialImpl;
import games.cubi.logs.Logger;
import games.cubi.raycastedantiesp.core.chunks.BlockChunkData;
import games.cubi.raycastedantiesp.core.chunks.BlockInfoResolver;
import games.cubi.raycastedantiesp.core.chunks.ChunkData;
import games.cubi.raycastedantiesp.core.config.ConfigManager;
import games.cubi.raycastedantiesp.core.config.raycast.ChunkSectionConfig;
import games.cubi.raycastedantiesp.core.config.raycast.HideBelowYConfig;
import games.cubi.raycastedantiesp.core.config.raycast.TileEntityConfig;
import games.cubi.raycastedantiesp.core.raycast.ChunkSectionVisibilityUtil;
import games.cubi.raycastedantiesp.core.tracked.NettyChunkSection;
import games.cubi.raycastedantiesp.core.tracked.NettyTileEntity;
import games.cubi.raycastedantiesp.core.tracked.TrackedChunkSection;
import games.cubi.raycastedantiesp.core.tracked.TrackedTileEntity;
import games.cubi.raycastedantiesp.core.players.PlayerData;
import games.cubi.raycastedantiesp.core.players.PlayerRegistry;
import games.cubi.raycastedantiesp.core.view.BlockView;
import games.cubi.raycastedantiesp.core.view.BlockViewTransition;
import games.cubi.raycastedantiesp.core.view.ChunkSectionViewTransition;
import games.cubi.raycastedantiesp.packetevents.replaydata.PacketEventsTileEntityReplayData;
import games.cubi.raycastedantiesp.packetevents.viewcontrollers.chunkparser.BlockChunkParser;
import games.cubi.raycastedantiesp.packetevents.viewcontrollers.chunkparser.CachedWireColumn;
import games.cubi.raycastedantiesp.packetevents.viewcontrollers.chunkparser.ChunkParser;
import games.cubi.raycastedantiesp.packetevents.viewcontrollers.chunkparser.ChunkSectionParseContext;
import games.cubi.raycastedantiesp.packetevents.viewcontrollers.chunkparser.NonMutatingBlockChunkParser;
import games.cubi.raycastedantiesp.packetevents.viewcontrollers.chunkparser.NonMutatingOcclusionChunkParser;
import games.cubi.raycastedantiesp.packetevents.viewcontrollers.chunkparser.OcclusionChunkParser;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

public abstract class PacketEventsBlockViewController implements PacketListener {
    private static PacketEventsBlockViewController instance;
    private final BlockInfoResolver blockInfoResolver;
    private final ChunkParser mutatingChunkParser;
    private final ChunkParser nonMutatingChunkParser;
    private final IntSupplier currentTickSupplier;
    private final PacketEventsCommonViewController common;
    private final Map<UUID, Long2ObjectOpenHashMap<CachedWireColumn>> wireColumnsByViewer = new ConcurrentHashMap<>();
    private final Map<UUID, Double> lastEvaluatedPlayerY = new ConcurrentHashMap<>();
    private final Map<UUID, Long2ObjectOpenHashMap<CachedWireColumn>> pendingHideBelowYUpdates = new ConcurrentHashMap<>();
    private static final int MAX_OUTER_CHUNKS_PER_FLUSH = 24;
    private static final int IMMEDIATE_INNER_RADIUS_SQ = 4;
    private TileEntityConfig tileEntityConfig = null;
    private ChunkSectionConfig chunkSectionConfig = null;
    private HideBelowYConfig hideBelowYConfig = null;
    private int hideOnSpawnDistanceSquared = 0;

    public static PacketEventsBlockViewController get() {
        return instance;
    }

    protected PacketEventsBlockViewController(BlockInfoResolver blockInfoResolver, boolean trackAllBlocks,
            IntSupplier currentTickSupplier) {
        instance = this;
        this.blockInfoResolver = blockInfoResolver;
        this.currentTickSupplier = currentTickSupplier;
        common = PacketEventsCommonViewController.get(currentTickSupplier);
        if (trackAllBlocks) {
            mutatingChunkParser = new BlockChunkParser(blockInfoResolver, this::getHiddenBlockId);
            nonMutatingChunkParser = new NonMutatingBlockChunkParser(blockInfoResolver, this::getHiddenBlockId);
        } else {
            mutatingChunkParser = new OcclusionChunkParser(blockInfoResolver, this::getHiddenBlockId);
            nonMutatingChunkParser = new NonMutatingOcclusionChunkParser(blockInfoResolver, this::getHiddenBlockId);
        }
    }

    protected abstract int getHiddenBlockId(int blockY);

    protected void ensureOwnLocation(PlayerData playerData, UUID viewerUUID) {
    }

    public void removeViewer(UUID viewerUUID) {
        if (viewerUUID != null) {
            wireColumnsByViewer.remove(viewerUUID);
            lastEvaluatedPlayerY.remove(viewerUUID);
            pendingHideBelowYUpdates.remove(viewerUUID);
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        UUID viewerUUID = event.getUser().getUUID();
        if (viewerUUID == null) {
            return;
        }

        ConfigManager config = ConfigManager.get();
        if (config.getTileEntityConfig() != tileEntityConfig) {
            tileEntityConfig = config.getTileEntityConfig();
            hideOnSpawnDistanceSquared = tileEntityConfig.hideOnSpawnDistance()
                    * tileEntityConfig.hideOnSpawnDistance();
        }
        if (config.getChunkSectionConfig() != chunkSectionConfig) {
            chunkSectionConfig = config.getChunkSectionConfig();
        }
        if (config.getHideBelowYConfig() != hideBelowYConfig) {
            hideBelowYConfig = config.getHideBelowYConfig();
        }

        PlayerData playerData = PlayerRegistry.getInstance().getPlayerData(viewerUUID);
        if (playerData == null) {
            return;
        }
        ensureOwnLocation(playerData, viewerUUID);

        UUID world = common.resolvePacketWorld(playerData, event.getUser());
        int currentTick = currentTickSupplier.getAsInt();
        boolean tileChecksEnabled = tileEntityConfig.enabled();
        boolean sectionChecksEnabled = chunkSectionConfig.enabled();
        playerData.blockView().applyTileEntityCheckMode(tileChecksEnabled, currentTick);
        playerData.blockView().applyChunkSectionCheckMode(sectionChecksEnabled, currentTick);

        handleBlockPackets(event, event.getUser(), viewerUUID, playerData, world, currentTick, tileChecksEnabled,
                sectionChecksEnabled);

        if (playerData.blockView().hasPendingTransitions()) {
            processTileEntityTransitions(event.getUser(), playerData);
        }
        if (playerData.blockView().hasPendingSectionTransitions()) {
            processChunkSectionTransitions(event.getUser(), playerData);
        }
    }

    private void handleBlockPackets(PacketSendEvent event, User viewer, UUID viewerUUID, PlayerData playerData,
            UUID world, int currentTick, boolean tileChecksEnabled, boolean sectionChecksEnabled) {
        if (world == null) {
            return;
        }

        BlockView blockView = playerData.blockView();

        if (event.getPacketType() == PacketType.Play.Server.UNLOAD_CHUNK) {
            WrapperPlayServerUnloadChunk packet = new WrapperPlayServerUnloadChunk(event);
            removeChunk(viewerUUID, packet, blockView, world);
        } else if (event.getPacketType() == PacketType.Play.Server.BLOCK_CHANGE) {
            WrapperPlayServerBlockChange packet = new WrapperPlayServerBlockChange(event);
            handleSingleBlockChange(event, viewer, playerData, world, packet, tileChecksEnabled, sectionChecksEnabled);
        } else if (event.getPacketType() == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
            WrapperPlayServerMultiBlockChange packet = new WrapperPlayServerMultiBlockChange(event);
            handleMultiBlockChange(event, blockView, world, packet, playerData.ownLocation(), tileChecksEnabled,
                    sectionChecksEnabled);
        } else if (event.getPacketType() == PacketType.Play.Server.BLOCK_ENTITY_DATA) {
            WrapperPlayServerBlockEntityData packet = new WrapperPlayServerBlockEntityData(event);
            ImmutableBlockSpatialImpl position = new ImmutableBlockSpatialImpl(packet.getPosition().getX(),
                    packet.getPosition().getY(), packet.getPosition().getZ());
            boolean hideBelowYActive = hideBelowYConfig != null && hideBelowYConfig.enabled()
                    && playerData.ownLocation() != null;
            boolean autoHideByY = hideBelowYActive
                    && hideBelowYConfig.shouldAutoHideBlock(playerData.ownLocation().y(), position.blockY());
            if (autoHideByY || (sectionChecksEnabled && !blockView.isChunkSectionVisible(world, position.chunkX(),
                    position.chunkY(), position.chunkZ()))) {
                event.setCancelled(true);
                return;
            }
            TrackedTileEntity<PacketEventsTileEntityReplayData> tileEntity = getTrackedTileEntity(blockView, world,
                    position);
            if (tileEntity == null) {
                // This can be triggered by things such as virtual signs
                Logger.warning(
                        "Received standalone block entity data for an uncached tile entity. Location: " + world + " "
                                + position.blockX() + "," + position.blockY() + "," + position.blockZ() + ". Data:"
                                + packet.getBlockEntityType().getName() + packet.getNBT(),
                        7, PacketEventsBlockViewController.class);
                return;
            }
            ensureTileReplayData(tileEntity).setBlockEntityData(packet.getBlockEntityType(), packet.getNBT());
            if (tileChecksEnabled && !blockView.isVisible(world, position, currentTick)) {
                event.setCancelled(true);
                sendHiddenBlock(viewer, position);
            }
        } else if (event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
            WrapperPlayServerChunkData packet = new WrapperPlayServerChunkData(event);
            // Tile mutation uses the mutating parser. Section-only mode keeps tiles
            // non-mutating but still redacts
            // sections via sectionContext inside AbstractChunkParser.
            ChunkParser parser = tileChecksEnabled ? mutatingChunkParser : nonMutatingChunkParser;
            int minimumSectionY = playerData.nettyData().getCurrentWorldMinHeight() >> 4;
            boolean hideBelowYEnabled = hideBelowYConfig != null && hideBelowYConfig.enabled();
            ChunkSectionParseContext sectionContext = (sectionChecksEnabled || hideBelowYEnabled)
                    ? new ChunkSectionParseContext(
                            playerData.ownLocation(),
                            chunkSectionConfig != null ? chunkSectionConfig.alwaysShowRadiusChunks() : 1,
                            chunkSectionConfig != null ? chunkSectionConfig.alwaysShowVerticalDown() : 1,
                            chunkSectionConfig != null ? chunkSectionConfig.alwaysShowVerticalUp() : 12,
                            chunkSectionConfig == null || chunkSectionConfig.hideAsAir(),
                            hideBelowYConfig,
                            sectionChecksEnabled)
                    : null;
            Column column = packet.getColumn();
            @Nullable
            Column result = parser.parse(blockView, world, column, minimumSectionY, sectionContext);
            if (result != null) {
                packet.setColumn(result);
                event.markForReEncode(true);
                column = result;
            }
            cacheWireColumn(viewerUUID, column, packet.getLightData(), minimumSectionY,
                    sectionContext == null ? null : sectionContext.unhiddenSections());

        } else if (event.getPacketType() == PacketType.Play.Server.MAP_CHUNK_BULK) {
            WrapperPlayServerChunkDataBulk packet = new WrapperPlayServerChunkDataBulk(event);
            throw new RuntimeException(
                    "I didn't think this packet existed. Please report this to the developer with details on how to reproduce it so it can be implemented");
        }
    }

    private void removeChunk(UUID viewerUUID, WrapperPlayServerUnloadChunk packet, BlockView blockView, UUID world) {
        blockView.removeChunk(world, packet.getChunkX(), packet.getChunkZ());
        Long2ObjectOpenHashMap<CachedWireColumn> columns = wireColumnsByViewer.get(viewerUUID);
        if (columns != null) {
            columns.remove(packColumnKey(packet.getChunkX(), packet.getChunkZ()));
        }
    }

    private void cacheWireColumn(UUID viewerUUID, Column column, LightData lightData, int minimumSectionY,
            BaseChunk[] unhiddenSections) {
        if (viewerUUID == null || column == null) {
            return;
        }
        BaseChunk[] sections = column.getChunks();
        if (sections == null) {
            return;
        }
        DataPalette[] biomes = new DataPalette[sections.length];
        BaseChunk[] finalUnhidden = new BaseChunk[sections.length];
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] instanceof Chunk_v1_18 chunk) {
                biomes[i] = chunk.getBiomeData();
            }
            if (unhiddenSections != null && i < unhiddenSections.length && unhiddenSections[i] != null) {
                finalUnhidden[i] = unhiddenSections[i];
            } else {
                finalUnhidden[i] = sections[i];
            }
        }
        LightData lightClone = prepareLightForResend(lightData);
        NBTCompound heightMapsNbt = column.hasHeightMaps() ? column.getHeightMaps() : null;
        Map<HeightmapType, long[]> heightmapsMap = column.getHeightmaps();
        CachedWireColumn cached = new CachedWireColumn(
                column.getX(),
                column.getZ(),
                minimumSectionY,
                biomes,
                finalUnhidden,
                lightClone,
                heightMapsNbt,
                heightmapsMap,
                column.getTileEntities());
        wireColumnsByViewer
                .computeIfAbsent(viewerUUID, ignored -> new Long2ObjectOpenHashMap<>())
                .put(packColumnKey(column.getX(), column.getZ()), cached);
    }

    private static long packColumnKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    private CachedWireColumn getCachedWireColumn(UUID viewerUUID, int chunkX, int chunkZ) {
        if (viewerUUID == null)
            return null;
        Long2ObjectOpenHashMap<CachedWireColumn> columns = wireColumnsByViewer.get(viewerUUID);
        return columns == null ? null : columns.get(packColumnKey(chunkX, chunkZ));
    }

    private void handleMultiBlockChange(PacketSendEvent event, BlockView blockView, UUID world,
            WrapperPlayServerMultiBlockChange packet, Locatable playerLocation, boolean tileChecksEnabled,
            boolean sectionChecksEnabled) {
        MutableBlockSpatialImpl key = new MutableBlockSpatialImpl(0, 0, 0);
        boolean hideBelowYActive = hideBelowYConfig != null && hideBelowYConfig.enabled() && playerLocation != null;
        User viewer = event.getUser();
        UUID viewerUUID = viewer == null ? null : viewer.getUUID();
        for (WrapperPlayServerMultiBlockChange.EncodedBlock change : packet.getBlocks()) {
            char blockID = (char) change.getBlockId();
            boolean tileEntity = blockInfoResolver.isTileEntity(blockID);
            blockView.upsertBlock(world, change.getX(), change.getY(), change.getZ(), blockID);
            key.setBlockPosition(change.getX(), change.getY(), change.getZ());
            if (viewerUUID != null) {
                CachedWireColumn cached = getCachedWireColumn(viewerUUID, key.chunkX(), key.chunkZ());
                if (cached != null) {
                    int secIdx = key.chunkY() - cached.minimumSectionY();
                    BaseChunk unhidden = cached.unhiddenSection(secIdx);
                    if (unhidden instanceof Chunk_v1_18 chunkSection) {
                        chunkSection.set(key.blockX() & 15, key.blockY() & 15, key.blockZ() & 15, blockID);
                    }
                }
            }
            boolean autoHideByY = hideBelowYActive
                    && hideBelowYConfig.shouldAutoHideBlock(playerLocation.y(), key.blockY());
            boolean sectionHidden = sectionChecksEnabled
                    && !blockView.isChunkSectionVisible(world, key.chunkX(), key.chunkY(), key.chunkZ());
            if (autoHideByY || sectionHidden) {
                if (sectionChecksEnabled) {
                    ensureTrackedSection(blockView, world, key, playerLocation, sectionChecksEnabled);
                }
                change.setBlockId(sectionHiddenWireBlockId(key.blockY()));
                event.markForReEncode(true);
                if (tileEntity) {
                    blockView.updateOrInsertTileEntity(world, key, blockID, false);
                } else {
                    blockView.removeTileEntity(world, key);
                }
                continue;
            }
            if (tileEntity) {
                boolean visibleIfNew = !tileChecksEnabled || visibleIfNew(key, playerLocation, world);
                TrackedTileEntity<?> state = blockView.updateOrInsertTileEntity(world, key, blockID, visibleIfNew);
                if (!tileChecksEnabled) {
                    blockView.recordOutboundTileEntityVisibility(state, true);
                } else if (state != null && !state.visible()) {
                    change.setBlockId(getHiddenBlockId(key.blockY()));
                    event.markForReEncode(true);
                }
            } else {
                blockView.removeTileEntity(world, key);
            }
        }
    }

    private void processTileEntityTransitions(User viewer, PlayerData playerData) {
        BlockView blockView = playerData.blockView();
        int worldEpoch = playerData.acquireWorldEpoch();
        for (BlockViewTransition transition : blockView.drainTransitions()) {
            BlockSpatial location = transition.tileEntity();
            TrackedTileEntity<PacketEventsTileEntityReplayData> state = resolveCurrentTransitionState(transition,
                    worldEpoch);
            if (state == null || state.blockID() == 0) {
                continue;
            }
            Locatable ownLocation = playerData.ownLocation();
            UUID viewerWorld = ownLocation == null ? null : ownLocation.world();
            if (chunkSectionConfig != null && chunkSectionConfig.enabled()
                    && viewerWorld != null
                    && !blockView.isChunkSectionVisible(viewerWorld, location.chunkX(), location.chunkY(),
                            location.chunkZ())) {
                // Terrain section is still hidden; tile SHOW would leak through air. Skip until
                // the section is shown.
                if (transition.type() == BlockViewTransition.Type.SHOW) {
                    state.setLastChecked(TrackedTileEntity.NEVER_CHECKED);
                    continue;
                }
            }
            switch (transition.type()) {
                case HIDE -> {
                    if (!blockView.isCurrentEnabledTileEntityMode(transition.modeToken())) {
                        state.setVisible(true);
                        state.setLastChecked(TrackedTileEntity.NEVER_CHECKED);
                        continue;
                    }
                    viewer.writePacketSilently(new WrapperPlayServerBlockChange(
                            new Vector3i(location.blockX(), location.blockY(), location.blockZ()),
                            getHiddenBlockId(location.blockY())));
                }
                case SHOW -> {
                    viewer.writePacketSilently(new WrapperPlayServerBlockChange(
                            new Vector3i(location.blockX(), location.blockY(), location.blockZ()),
                            state.blockID()));
                    PacketEventsTileEntityReplayData replayData = ensureTileReplayData(state);
                    if (replayData.blockEntityType() != null && replayData.nbt() != null) {
                        viewer.writePacketSilently(buildBlockEntityDataPacket(location, replayData));
                    }
                }
            }
        }
    }

    private void processChunkSectionTransitions(User viewer, PlayerData playerData) {
        BlockView blockView = playerData.blockView();
        int worldEpoch = playerData.acquireWorldEpoch();
        Locatable ownLocation = playerData.ownLocation();
        UUID world = ownLocation == null ? null : ownLocation.world();
        UUID viewerUUID = viewer.getUUID();
        LongOpenHashSet columnsToResend = new LongOpenHashSet();
        List<ChunkSectionViewTransition> applied = new ArrayList<>();

        for (ChunkSectionViewTransition transition : blockView.drainSectionTransitions()) {
            TrackedChunkSection section = resolveCurrentSectionTransitionState(transition, worldEpoch);
            if (section == null) {
                continue;
            }
            if (transition.type() == ChunkSectionViewTransition.Type.HIDE
                    && !blockView.isCurrentEnabledChunkSectionMode(transition.modeToken())) {
                section.setVisible(true);
                section.setLastChecked(TrackedChunkSection.NEVER_CHECKED);
                continue;
            }
            columnsToResend.add(packColumnKey(section.chunkX(), section.chunkZ()));
            applied.add(transition);
        }

        Long2ObjectOpenHashMap<CachedWireColumn> wireColumns = wireColumnsByViewer.get(viewerUUID);
        for (long columnKey : columnsToResend) {
            CachedWireColumn cached = wireColumns == null ? null : wireColumns.get(columnKey);
            if (cached == null) {
                continue;
            }
            sendColumnVisibilityUpdate(viewer, blockView, world, cached);
        }

        for (ChunkSectionViewTransition transition : applied) {
            TrackedChunkSection section = transition.section();
            long columnKey = packColumnKey(section.chunkX(), section.chunkZ());
            CachedWireColumn cached = wireColumns == null ? null : wireColumns.get(columnKey);
            if (cached == null) {
                continue;
            }
            boolean show = transition.type() == ChunkSectionViewTransition.Type.SHOW;
            if (show) {
                requeueTilesInSection(blockView, world, section);
                resendTileEntitiesInSection(viewer, blockView, world, section);
            }
            PacketEventsEntityViewController.get().applyChunkSectionEntityGate(
                    viewer, playerData, section.chunkX(), section.sectionY(), section.chunkZ(), show);
        }
    }

    private boolean isChunkSectionVisibleForViewer(BlockView blockView, UUID world, int chunkX, int sectionY,
            int chunkZ, Locatable viewerEye) {
        if (hideBelowYConfig != null && hideBelowYConfig.enabled() && viewerEye != null) {
            if (hideBelowYConfig.shouldAutoHideSection(viewerEye.y(), sectionY)) {
                return false;
            }
        }
        if (chunkSectionConfig != null && chunkSectionConfig.enabled()) {
            return blockView.isChunkSectionVisible(world, chunkX, sectionY, chunkZ);
        }
        return true;
    }

    protected @Nullable User resolveUser(UUID viewerUUID) {
        return null;
    }

    private void checkHideBelowYMovement(User viewer, PlayerData playerData, UUID world, BlockView blockView) {
        if (hideBelowYConfig == null || !hideBelowYConfig.enabled() || world == null) {
            return;
        }
        if (!hideBelowYConfig.movementTrigger()) {
            return;
        }
        evaluateHideBelowYForPlayer(playerData, viewer);
    }

    private static java.lang.reflect.Method channelEventLoopMethod;
    private static java.lang.reflect.Method eventLoopInEventLoopMethod;
    private static java.lang.reflect.Method eventLoopExecuteMethod;
    private static volatile boolean nettyReflectionInitialized = false;

    private static void executeOnChannelEventLoop(User viewer, Runnable task) {
        Object rawChannel = viewer == null ? null : viewer.getChannel();
        if (rawChannel == null) {
            task.run();
            return;
        }
        try {
            if (!nettyReflectionInitialized) {
                synchronized (PacketEventsBlockViewController.class) {
                    if (!nettyReflectionInitialized) {
                        Class<?> channelClass = rawChannel.getClass();
                        channelEventLoopMethod = channelClass.getMethod("eventLoop");
                        Object eventLoop = channelEventLoopMethod.invoke(rawChannel);
                        if (eventLoop != null) {
                            Class<?> eventLoopClass = eventLoop.getClass();
                            eventLoopInEventLoopMethod = eventLoopClass.getMethod("inEventLoop");
                            eventLoopExecuteMethod = eventLoopClass.getMethod("execute", Runnable.class);
                        }
                        nettyReflectionInitialized = true;
                    }
                }
            }
            if (channelEventLoopMethod != null && eventLoopInEventLoopMethod != null
                    && eventLoopExecuteMethod != null) {
                Object eventLoop = channelEventLoopMethod.invoke(rawChannel);
                if (eventLoop != null) {
                    Boolean inEventLoop = (Boolean) eventLoopInEventLoopMethod.invoke(eventLoop);
                    if (Boolean.FALSE.equals(inEventLoop)) {
                        eventLoopExecuteMethod.invoke(eventLoop, task);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            Logger.error("Failed to execute task on Netty channel event loop", e, 3,
                    PacketEventsBlockViewController.class);
        }
        task.run();
    }

    public void evaluateHideBelowYForPlayer(PlayerData playerData) {
        evaluateHideBelowYForPlayer(playerData, null);
    }

    public void evaluateHideBelowYForPlayer(PlayerData playerData, @Nullable User viewer) {
        if (hideBelowYConfig == null || !hideBelowYConfig.enabled() || playerData == null) {
            return;
        }
        Locatable ownLocation = playerData.ownLocation();
        if (ownLocation == null || ownLocation.world() == null) {
            return;
        }
        UUID viewerUUID = playerData.getPlayerUUID();
        if (viewer == null) {
            viewer = resolveUser(viewerUUID);
        }
        if (viewer == null) {
            return;
        }
        User finalViewer = viewer;
        executeOnChannelEventLoop(finalViewer, () -> evaluateHideBelowYForPlayerOnNetty(playerData, finalViewer));
    }

    private void evaluateHideBelowYForPlayerOnNetty(PlayerData playerData, User viewer) {
        Locatable ownLocation = playerData.ownLocation();
        if (ownLocation == null || ownLocation.world() == null) {
            return;
        }
        UUID viewerUUID = playerData.getPlayerUUID();
        UUID world = ownLocation.world();
        BlockView blockView = playerData.blockView();
        Double lastY = lastEvaluatedPlayerY.get(viewerUUID);
        double currentY = ownLocation.y();
        if (lastY == null) {
            lastEvaluatedPlayerY.put(viewerUUID, currentY);
            return;
        }
        int oldCutoffSection = (int) Math.floor(lastY) >> 4;
        int newCutoffSection = (int) Math.floor(currentY) >> 4;
        boolean crossedCutoff = hideBelowYConfig != null
                && ((lastY >= hideBelowYConfig.yCutoff()) != (currentY >= hideBelowYConfig.yCutoff()));
        boolean crossedTrigger = hideBelowYConfig != null
                && ((lastY >= hideBelowYConfig.playerYTrigger()) != (currentY >= hideBelowYConfig.playerYTrigger()));
        boolean thresholdReached = oldCutoffSection != newCutoffSection || crossedCutoff || crossedTrigger
                || Math.abs(lastY - currentY) >= 8.0;

        if (thresholdReached) {
            double prevY = lastY;
            lastEvaluatedPlayerY.put(viewerUUID, currentY);
            Long2ObjectOpenHashMap<CachedWireColumn> wireColumns = wireColumnsByViewer.get(viewerUUID);
            if (wireColumns != null && !wireColumns.isEmpty()) {
                int pChunkX = (int) Math.floor(ownLocation.x()) >> 4;
                int pChunkZ = (int) Math.floor(ownLocation.z()) >> 4;
                Long2ObjectOpenHashMap<CachedWireColumn> pendingMap = pendingHideBelowYUpdates
                        .computeIfAbsent(viewerUUID, k -> new Long2ObjectOpenHashMap<>());

                for (CachedWireColumn cached : wireColumns.values()) {
                    int minSec = cached.minimumSectionY();
                    int secCount = cached.sectionCount();
                    boolean columnChanged = false;
                    for (int i = 0; i < secCount; i++) {
                        int sectionY = minSec + i;
                        boolean oldHide = hideBelowYConfig.shouldAutoHideSection(prevY, sectionY);
                        boolean newHide = hideBelowYConfig.shouldAutoHideSection(currentY, sectionY);
                        if (oldHide != newHide) {
                            columnChanged = true;
                            boolean sectionVisible = !newHide
                                    && (chunkSectionConfig == null || !chunkSectionConfig.enabled() || blockView
                                            .isChunkSectionVisible(world, cached.chunkX(), sectionY, cached.chunkZ()));
                            PacketEventsEntityViewController.get().applyChunkSectionEntityGate(
                                    viewer, playerData, cached.chunkX(), sectionY, cached.chunkZ(), sectionVisible);
                        }
                    }
                    if (columnChanged) {
                        int dx = cached.chunkX() - pChunkX;
                        int dz = cached.chunkZ() - pChunkZ;
                        int distSq = dx * dx + dz * dz;
                        long chunkKey = (((long) cached.chunkX()) << 32) | (cached.chunkZ() & 0xFFFFFFFFL);
                        if (distSq <= IMMEDIATE_INNER_RADIUS_SQ) {
                            sendColumnVisibilityUpdate(viewer, blockView, world, cached);
                            pendingMap.remove(chunkKey);
                        } else {
                            pendingMap.put(chunkKey, cached);
                        }
                    }
                }
            }
        }

        flushPendingHideBelowYChunkUpdates(viewer, playerData, world, blockView);
    }

    private void flushPendingHideBelowYChunkUpdates(User viewer, PlayerData playerData, UUID world,
            BlockView blockView) {
        UUID viewerUUID = playerData.getPlayerUUID();
        Long2ObjectOpenHashMap<CachedWireColumn> pendingMap = pendingHideBelowYUpdates.get(viewerUUID);
        if (pendingMap == null || pendingMap.isEmpty()) {
            return;
        }
        Locatable loc = playerData.ownLocation();
        if (loc == null) {
            return;
        }
        int pChunkX = (int) Math.floor(loc.x()) >> 4;
        int pChunkZ = (int) Math.floor(loc.z()) >> 4;

        List<CachedWireColumn> pendingList = new ArrayList<>(pendingMap.values());
        pendingList.sort((c1, c2) -> {
            int dx1 = c1.chunkX() - pChunkX;
            int dz1 = c1.chunkZ() - pChunkZ;
            int dx2 = c2.chunkX() - pChunkX;
            int dz2 = c2.chunkZ() - pChunkZ;
            return Integer.compare(dx1 * dx1 + dz1 * dz1, dx2 * dx2 + dz2 * dz2);
        });

        int toSend = Math.min(MAX_OUTER_CHUNKS_PER_FLUSH, pendingList.size());
        for (int i = 0; i < toSend; i++) {
            CachedWireColumn cached = pendingList.get(i);
            long chunkKey = (((long) cached.chunkX()) << 32) | (cached.chunkZ() & 0xFFFFFFFFL);
            pendingMap.remove(chunkKey);
            sendColumnVisibilityUpdate(viewer, blockView, world, cached);
        }
        if (pendingMap.isEmpty()) {
            pendingHideBelowYUpdates.remove(viewerUUID);
        }
    }

    public boolean forceShowChunkSection(PlayerData playerData, int chunkX, int sectionY, int chunkZ) {
        if (playerData == null) {
            return false;
        }
        Locatable ownLocation = playerData.ownLocation();
        UUID world = ownLocation == null ? null : ownLocation.world();
        if (world == null) {
            return false;
        }

        BlockView blockView = playerData.blockView();
        int currentTick = currentTickSupplier.getAsInt();
        TrackedChunkSection section = blockView.updateOrInsertChunkSection(world, chunkX, sectionY, chunkZ, true);
        if (section != null) {
            section.setVisible(true);
            section.setWantHiddenSinceTick(TrackedChunkSection.NOT_WANTING_HIDE);
            section.setLastChecked(currentTick);
        }

        User viewer = resolveUser(playerData.getPlayerUUID());
        if (viewer == null) {
            return true;
        }

        executeOnChannelEventLoop(viewer, () -> {
            Long2ObjectOpenHashMap<CachedWireColumn> wireColumns = wireColumnsByViewer.get(playerData.getPlayerUUID());
            long columnKey = packColumnKey(chunkX, chunkZ);
            CachedWireColumn cached = wireColumns == null ? null : wireColumns.get(columnKey);
            if (cached != null) {
                sendColumnVisibilityUpdateForced(viewer, blockView, world, cached, sectionY);
                if (section != null) {
                    requeueTilesInSection(blockView, world, section);
                    resendTileEntitiesInSection(viewer, blockView, world, section);
                }
                PacketEventsEntityViewController.get().applyChunkSectionEntityGate(
                        viewer, playerData, chunkX, sectionY, chunkZ, true);
            }
        });
        return true;
    }

    private void sendColumnVisibilityUpdate(User viewer, BlockView blockView, UUID world, CachedWireColumn cached) {
        sendColumnVisibilityUpdateForced(viewer, blockView, world, cached, Integer.MIN_VALUE);
    }

    private void sendColumnVisibilityUpdateForced(User viewer, BlockView blockView, UUID world, CachedWireColumn cached,
            int forcedSectionY) {
        PlayerData playerData = PlayerRegistry.getInstance().getPlayerData(viewer.getUUID());
        Locatable viewerEye = playerData == null ? null : playerData.ownLocation();
        BaseChunk[] sections = new BaseChunk[cached.sectionCount()];
        for (int i = 0; i < sections.length; i++) {
            int sectionY = cached.minimumSectionY() + i;
            boolean visible = sectionY == forcedSectionY || world == null || isChunkSectionVisibleForViewer(blockView, world, cached.chunkX(),
                    sectionY, cached.chunkZ(), viewerEye);
            DataPalette biomes = cached.biomePalette(i);
            BaseChunk unhidden = cached.unhiddenSection(i);
            if (visible) {
                sections[i] = unhidden != null ? unhidden
                        : buildWireSection(blockView.getBlockChunkData(cached.chunkX(), sectionY, cached.chunkZ()),
                                biomes);
            } else {
                sections[i] = hiddenWireSection(biomes, sectionY);
            }
        }
        Column column = buildColumn(cached, sections);
        LightData light = prepareLightForResend(cached.lightData());
        if (light != null) {
            viewer.writePacketSilently(new WrapperPlayServerChunkData(column, light));
        } else {
            viewer.writePacketSilently(new WrapperPlayServerChunkData(column));
        }
    }

    private static Column buildColumn(CachedWireColumn cached, BaseChunk[] sections) {
        TileEntity[] tiles = cached.tileEntities();
        if (cached.heightMapsNbt() != null) {
            return new Column(cached.chunkX(), cached.chunkZ(), true, sections, tiles, cached.heightMapsNbt());
        }
        if (cached.heightmapsMap() != null) {
            return new Column(cached.chunkX(), cached.chunkZ(), true, sections, tiles, cached.heightmapsMap());
        }
        return new Column(cached.chunkX(), cached.chunkZ(), true, sections, tiles);
    }

    private Chunk_v1_18 hiddenWireSection(DataPalette biomes, int sectionY) {
        if (hideSectionsAsAir()) {
            DataPalette airBlocks = new DataPalette(new SingletonPalette(0), null, PaletteType.CHUNK);
            if (biomes != null) {
                return new Chunk_v1_18(0, 0, airBlocks, biomes);
            }
            Chunk_v1_18 section = new Chunk_v1_18();
            section.set(0, 0, 0, 0);
            return section;
        }
        int hiddenBlockId = getHiddenBlockId(sectionY << 4);
        DataPalette solidBlocks = new DataPalette(new SingletonPalette(hiddenBlockId), null, PaletteType.CHUNK);
        if (biomes != null) {
            return new Chunk_v1_18(ChunkData.BLOCK_COUNT, 0, solidBlocks, biomes);
        }
        return new Chunk_v1_18(ChunkData.BLOCK_COUNT, 0, solidBlocks, PaletteType.BIOME.create());
    }

    private boolean hideSectionsAsAir() {
        return chunkSectionConfig == null || chunkSectionConfig.hideAsAir();
    }

    /**
     * Wire block id used while a section is hidden: air (0) or stone/deepslate
     * placeholder.
     */
    private int sectionHiddenWireBlockId(int blockY) {
        return hideSectionsAsAir() ? 0 : getHiddenBlockId(blockY);
    }

    private static Chunk_v1_18 buildWireSection(BlockChunkData data, DataPalette biomes) {
        if (data == null) {
            DataPalette airBlocks = new DataPalette(new SingletonPalette(0), null, PaletteType.CHUNK);
            return biomes != null
                    ? new Chunk_v1_18(0, 0, airBlocks, biomes)
                    : new Chunk_v1_18();
        }
        Chunk_v1_18 section = biomes != null
                ? new Chunk_v1_18(0, 0, new DataPalette(new SingletonPalette(0), null, PaletteType.CHUNK), biomes)
                : new Chunk_v1_18();
        if (biomes == null) {
            section.set(0, 0, 0, 0);
        }
        for (int y = 0; y < ChunkData.CHUNK_SIZE; y++) {
            for (int z = 0; z < ChunkData.CHUNK_SIZE; z++) {
                for (int x = 0; x < ChunkData.CHUNK_SIZE; x++) {
                    char blockID = data.getBlockID(x, y, z);
                    if (blockID != 0) {
                        section.set(x, y, z, blockID);
                    }
                }
            }
        }
        return section;
    }

    /**
     * Clone light for a column resend. PacketEvents' {@link LightData#clone()} is
     * shallow on the
     * nibble arrays; deep-copy them and force trustEdges so the client finalizes
     * lighting on replace.
     */
    private static LightData prepareLightForResend(LightData source) {
        if (source == null) {
            return null;
        }
        LightData light = source.clone();
        light.setTrustEdges(true);
        light.setSkyLightArray(deepCopyLightArrays(light.getSkyLightArray()));
        light.setBlockLightArray(deepCopyLightArrays(light.getBlockLightArray()));
        return light;
    }

    private static byte[][] deepCopyLightArrays(byte[][] arrays) {
        if (arrays == null) {
            return null;
        }
        byte[][] copy = new byte[arrays.length][];
        for (int i = 0; i < arrays.length; i++) {
            byte[] layer = arrays[i];
            copy[i] = layer == null ? null : layer.clone();
        }
        return copy;
    }

    private void sendSectionMultiBlockFallback(
            User viewer,
            BlockView blockView,
            TrackedChunkSection section,
            ChunkSectionViewTransition.Type type) {
        WrapperPlayServerMultiBlockChange.EncodedBlock[] blocks = type == ChunkSectionViewTransition.Type.HIDE
                ? buildSectionHideUpdates(blockView, section)
                : buildSectionRestoreUpdates(blockView, section);
        if (blocks.length != 0) {
            viewer.writePacketSilently(new WrapperPlayServerMultiBlockChange(
                    new Vector3i(section.chunkX(), section.sectionY(), section.chunkZ()),
                    false,
                    blocks));
        }
    }

    private void resendTileEntitiesInSection(User viewer, BlockView blockView, UUID world,
            TrackedChunkSection section) {
        if (world == null) {
            return;
        }
        int originX = section.chunkX() << 4;
        int originY = section.sectionY() << 4;
        int originZ = section.chunkZ() << 4;
        MutableBlockSpatialImpl key = new MutableBlockSpatialImpl(0, 0, 0);
        for (int y = 0; y < ChunkData.CHUNK_SIZE; y++) {
            for (int z = 0; z < ChunkData.CHUNK_SIZE; z++) {
                for (int x = 0; x < ChunkData.CHUNK_SIZE; x++) {
                    key.setBlockPosition(originX + x, originY + y, originZ + z);
                    TrackedTileEntity<PacketEventsTileEntityReplayData> tile = getTrackedTileEntity(blockView, world,
                            key);
                    if (tile == null || !tile.visible()) {
                        continue;
                    }
                    PacketEventsTileEntityReplayData replayData = tile.extraData();
                    if (replayData != null && replayData.blockEntityType() != null && replayData.nbt() != null) {
                        viewer.writePacketSilently(buildBlockEntityDataPacket(key, replayData));
                    }
                }
            }
        }
    }

    private void handleSingleBlockChange(PacketSendEvent event, User viewer, PlayerData playerData, UUID world,
            WrapperPlayServerBlockChange packet, boolean tileChecksEnabled, boolean sectionChecksEnabled) {
        char blockID = (char) packet.getBlockId();
        boolean tileEntity = blockInfoResolver.isTileEntity(blockID);
        Vector3i position = packet.getBlockPosition();
        ImmutableBlockSpatialImpl location = new ImmutableBlockSpatialImpl(position.getX(), position.getY(),
                position.getZ());

        playerData.blockView().upsertBlock(world, position.getX(), position.getY(), position.getZ(), blockID);

        if (viewer != null) {
            CachedWireColumn cached = getCachedWireColumn(viewer.getUUID(), location.chunkX(), location.chunkZ());
            if (cached != null) {
                int secIdx = location.chunkY() - cached.minimumSectionY();
                BaseChunk unhidden = cached.unhiddenSection(secIdx);
                if (unhidden instanceof Chunk_v1_18 chunkSection) {
                    chunkSection.set(location.blockX() & 15, location.blockY() & 15, location.blockZ() & 15, blockID);
                }
            }
        }

        boolean hideBelowYActive = hideBelowYConfig != null && hideBelowYConfig.enabled()
                && playerData.ownLocation() != null;
        boolean autoHideByY = hideBelowYActive
                && hideBelowYConfig.shouldAutoHideBlock(playerData.ownLocation().y(), location.blockY());
        boolean sectionHidden = sectionChecksEnabled && !playerData.blockView().isChunkSectionVisible(world,
                location.chunkX(), location.chunkY(), location.chunkZ());

        if (autoHideByY || sectionHidden) {
            if (sectionChecksEnabled) {
                ensureTrackedSection(playerData.blockView(), world, location, playerData.ownLocation(), true);
            }
            event.setCancelled(true);
            viewer.writePacketSilently(new WrapperPlayServerBlockChange(
                    new Vector3i(location.blockX(), location.blockY(), location.blockZ()),
                    sectionHiddenWireBlockId(location.blockY())));
            if (tileEntity) {
                playerData.blockView().updateOrInsertTileEntity(world, location, blockID, false);
            } else {
                playerData.blockView().removeTileEntity(world, location);
            }
            return;
        }

        if (tileEntity) {
            boolean visibleIfNew = !tileChecksEnabled || visibleIfNew(location, playerData.ownLocation(), world);
            TrackedTileEntity<?> state = playerData.blockView().updateOrInsertTileEntity(world, location, blockID,
                    visibleIfNew);
            if (!tileChecksEnabled) {
                playerData.blockView().recordOutboundTileEntityVisibility(state, true);
            } else if (state != null && !state.visible()) {
                event.setCancelled(true);
                sendHiddenBlock(viewer, location);
            }
        } else {
            playerData.blockView().removeTileEntity(world, location);
        }
    }

    private void ensureTrackedSection(BlockView blockView, UUID world, BlockSpatial location, Locatable playerLocation,
            boolean sectionChecksEnabled) {
        if (!sectionChecksEnabled) {
            return;
        }
        boolean visibleIfNew = false;
        if (playerLocation != null && playerLocation.world() != null && playerLocation.world().equals(world)) {
            visibleIfNew = ChunkSectionVisibilityUtil.isWithinAlwaysShow(
                    playerLocation.blockX() >> 4,
                    playerLocation.blockY() >> 4,
                    playerLocation.blockZ() >> 4,
                    location.chunkX(),
                    location.chunkY(),
                    location.chunkZ(),
                    chunkSectionConfig.alwaysShowRadiusChunks(),
                    chunkSectionConfig.alwaysShowVerticalDown(),
                    chunkSectionConfig.alwaysShowVerticalUp());
        }
        blockView.updateOrInsertChunkSection(world, location.chunkX(), location.chunkY(), location.chunkZ(),
                visibleIfNew);
    }

    private WrapperPlayServerMultiBlockChange.EncodedBlock[] buildSectionHideUpdates(BlockView blockView,
            TrackedChunkSection section) {
        BlockChunkData data = blockView.getBlockChunkData(section.chunkX(), section.sectionY(), section.chunkZ());
        if (data == null) {
            return new WrapperPlayServerMultiBlockChange.EncodedBlock[0];
        }
        List<WrapperPlayServerMultiBlockChange.EncodedBlock> blocks = new ArrayList<>();
        int originX = section.chunkX() << 4;
        int originY = section.sectionY() << 4;
        int originZ = section.chunkZ() << 4;
        for (int y = 0; y < ChunkData.CHUNK_SIZE; y++) {
            for (int z = 0; z < ChunkData.CHUNK_SIZE; z++) {
                for (int x = 0; x < ChunkData.CHUNK_SIZE; x++) {
                    int blockY = originY + y;
                    int hiddenId = sectionHiddenWireBlockId(blockY);
                    if (data.getBlockID(x, y, z) != hiddenId) {
                        blocks.add(new WrapperPlayServerMultiBlockChange.EncodedBlock(
                                hiddenId, originX + x, blockY, originZ + z));
                    }
                }
            }
        }
        return blocks.toArray(WrapperPlayServerMultiBlockChange.EncodedBlock[]::new);
    }

    private WrapperPlayServerMultiBlockChange.EncodedBlock[] buildSectionRestoreUpdates(BlockView blockView,
            TrackedChunkSection section) {
        BlockChunkData data = blockView.getBlockChunkData(section.chunkX(), section.sectionY(), section.chunkZ());
        if (data == null) {
            return new WrapperPlayServerMultiBlockChange.EncodedBlock[0];
        }
        List<WrapperPlayServerMultiBlockChange.EncodedBlock> blocks = new ArrayList<>();
        int originX = section.chunkX() << 4;
        int originY = section.sectionY() << 4;
        int originZ = section.chunkZ() << 4;
        for (int y = 0; y < ChunkData.CHUNK_SIZE; y++) {
            for (int z = 0; z < ChunkData.CHUNK_SIZE; z++) {
                for (int x = 0; x < ChunkData.CHUNK_SIZE; x++) {
                    char blockID = data.getBlockID(x, y, z);
                    if (blockID != 0) {
                        blocks.add(new WrapperPlayServerMultiBlockChange.EncodedBlock(blockID, originX + x, originY + y,
                                originZ + z));
                    }
                }
            }
        }
        return blocks.toArray(WrapperPlayServerMultiBlockChange.EncodedBlock[]::new);
    }

    private void requeueTilesInSection(BlockView blockView, UUID world, TrackedChunkSection section) {
        if (world == null) {
            return;
        }
        int originX = section.chunkX() << 4;
        int originY = section.sectionY() << 4;
        int originZ = section.chunkZ() << 4;
        MutableBlockSpatialImpl key = new MutableBlockSpatialImpl(0, 0, 0);
        for (int y = 0; y < ChunkData.CHUNK_SIZE; y++) {
            for (int z = 0; z < ChunkData.CHUNK_SIZE; z++) {
                for (int x = 0; x < ChunkData.CHUNK_SIZE; x++) {
                    key.setBlockPosition(originX + x, originY + y, originZ + z);
                    TrackedTileEntity<?> tile = blockView.getTrackedTileEntity(world, key);
                    if (tile != null) {
                        tile.setLastChecked(TrackedTileEntity.NEVER_CHECKED);
                    }
                }
            }
        }
    }

    private void sendHiddenBlock(User viewer, BlockSpatial location) {
        viewer.writePacketSilently(new WrapperPlayServerBlockChange(
                new Vector3i(location.blockX(), location.blockY(), location.blockZ()),
                getHiddenBlockId(location.blockY())));
    }

    private boolean visibleIfNew(BlockSpatial location, Locatable playerLocation, UUID packetWorld) {
        if (!tileEntityConfig.enabled()) {
            return false;
        }
        if (playerLocation == null || playerLocation.world() == null || !playerLocation.world().equals(packetWorld)) {
            return false;
        }
        return location.distanceSquared(playerLocation) <= hideOnSpawnDistanceSquared;
    }

    private WrapperPlayServerBlockEntityData buildBlockEntityDataPacket(BlockSpatial location,
            PacketEventsTileEntityReplayData replayData) {
        return new WrapperPlayServerBlockEntityData(
                new Vector3i(location.blockX(), location.blockY(), location.blockZ()),
                replayData.blockEntityType(),
                replayData.nbt());
    }

    @SuppressWarnings("unchecked")
    private static TrackedTileEntity<PacketEventsTileEntityReplayData> getTrackedTileEntity(BlockView blockView,
            UUID world, BlockSpatial position) {
        return (TrackedTileEntity<PacketEventsTileEntityReplayData>) blockView.getTrackedTileEntity(world, position);
    }

    @SuppressWarnings("unchecked")
    static @Nullable TrackedTileEntity<PacketEventsTileEntityReplayData> resolveCurrentTransitionState(
            BlockViewTransition transition, int currentWorldEpoch) {
        if (transition.worldEpoch() != currentWorldEpoch
                || !(transition.tileEntity() instanceof NettyTileEntity<?> tileEntity)
                || tileEntity.isRemoved()) {
            return null;
        }
        return (TrackedTileEntity<PacketEventsTileEntityReplayData>) transition.tileEntity();
    }

    static @Nullable TrackedChunkSection resolveCurrentSectionTransitionState(ChunkSectionViewTransition transition,
            int currentWorldEpoch) {
        if (transition.worldEpoch() != currentWorldEpoch
                || !(transition.section() instanceof NettyChunkSection section)
                || section.isRemoved()) {
            return null;
        }
        return section;
    }

    private PacketEventsTileEntityReplayData ensureTileReplayData(
            TrackedTileEntity<PacketEventsTileEntityReplayData> tileEntity) {
        PacketEventsTileEntityReplayData replayData = tileEntity.extraData();
        if (replayData == null) {
            replayData = new PacketEventsTileEntityReplayData();
            tileEntity.setExtraData(replayData);
        }
        return replayData;
    }
}
