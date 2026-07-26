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
    private final BlockInfoResolver blockInfoResolver;
    private final ChunkParser mutatingChunkParser;
    private final ChunkParser nonMutatingChunkParser;
    private final IntSupplier currentTickSupplier;
    private final PacketEventsCommonViewController common;
    private final Map<UUID, Long2ObjectOpenHashMap<CachedWireColumn>> wireColumnsByViewer = new ConcurrentHashMap<>();
    private TileEntityConfig tileEntityConfig = null;
    private ChunkSectionConfig chunkSectionConfig = null;
    private int hideOnSpawnDistanceSquared = 0;

    protected PacketEventsBlockViewController(BlockInfoResolver blockInfoResolver, boolean trackAllBlocks, IntSupplier currentTickSupplier) {
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

    public void removeViewer(UUID viewerUUID) {
        if (viewerUUID != null) {
            wireColumnsByViewer.remove(viewerUUID);
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
            hideOnSpawnDistanceSquared = tileEntityConfig.hideOnSpawnDistance() * tileEntityConfig.hideOnSpawnDistance();
        }
        if (config.getChunkSectionConfig() != chunkSectionConfig) {
            chunkSectionConfig = config.getChunkSectionConfig();
        }

        PlayerData playerData = PlayerRegistry.getInstance().getPlayerData(viewerUUID);
        if (playerData == null) {
            return;
        }

        UUID world = common.resolvePacketWorld(playerData, event.getUser());
        int currentTick = currentTickSupplier.getAsInt();
        boolean tileChecksEnabled = tileEntityConfig.enabled();
        boolean sectionChecksEnabled = chunkSectionConfig.enabled();
        playerData.blockView().applyTileEntityCheckMode(tileChecksEnabled, currentTick);
        playerData.blockView().applyChunkSectionCheckMode(sectionChecksEnabled, currentTick);

        handleBlockPackets(event, event.getUser(), viewerUUID, playerData, world, currentTick, tileChecksEnabled, sectionChecksEnabled);

        if (playerData.blockView().hasPendingTransitions()) {
            processTileEntityTransitions(event.getUser(), playerData);
        }
        if (playerData.blockView().hasPendingSectionTransitions()) {
            processChunkSectionTransitions(event.getUser(), playerData);
        }
    }

    private void handleBlockPackets(PacketSendEvent event, User viewer, UUID viewerUUID, PlayerData playerData, UUID world, int currentTick, boolean tileChecksEnabled, boolean sectionChecksEnabled) {
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
            handleMultiBlockChange(event, blockView, world, packet, playerData.ownLocation(), tileChecksEnabled, sectionChecksEnabled);
        } else if (event.getPacketType() == PacketType.Play.Server.BLOCK_ENTITY_DATA) {
            WrapperPlayServerBlockEntityData packet = new WrapperPlayServerBlockEntityData(event);
            ImmutableBlockSpatialImpl position = new ImmutableBlockSpatialImpl(packet.getPosition().getX(), packet.getPosition().getY(), packet.getPosition().getZ());
            if (sectionChecksEnabled && !blockView.isChunkSectionVisible(world, position.chunkX(), position.chunkY(), position.chunkZ())) {
                event.setCancelled(true);
                return;
            }
            TrackedTileEntity<PacketEventsTileEntityReplayData> tileEntity = getTrackedTileEntity(blockView, world, position);
            if (tileEntity == null) {
                // This can be triggered by things such as virtual signs
                Logger.warning("Received standalone block entity data for an uncached tile entity. Location: " + world + " " + position.blockX() + "," + position.blockY() + "," + position.blockZ() + ". Data:" + packet.getBlockEntityType().getName() + packet.getNBT(), 7, PacketEventsBlockViewController.class);
                return;
            }
            ensureTileReplayData(tileEntity).setBlockEntityData(packet.getBlockEntityType(), packet.getNBT());
            if (tileChecksEnabled && !blockView.isVisible(world, position, currentTick)) {
                event.setCancelled(true);
                sendHiddenBlock(viewer, position);
            }
        } else if (event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
            WrapperPlayServerChunkData packet = new WrapperPlayServerChunkData(event);
            // Tile mutation uses the mutating parser. Section-only mode keeps tiles non-mutating but still redacts
            // sections via sectionContext inside AbstractChunkParser.
            ChunkParser parser = tileChecksEnabled ? mutatingChunkParser : nonMutatingChunkParser;
            int minimumSectionY = playerData.nettyData().getCurrentWorldMinHeight() >> 4;
            ChunkSectionParseContext sectionContext = sectionChecksEnabled
                    ? new ChunkSectionParseContext(
                    playerData.ownLocation(),
                    chunkSectionConfig.alwaysShowRadiusChunks(),
                    chunkSectionConfig.alwaysShowVerticalDown(),
                    chunkSectionConfig.alwaysShowVerticalUp(),
                    chunkSectionConfig.hideAsAir()
            )
                    : null;
            Column column = packet.getColumn();
            @Nullable Column result = parser.parse(blockView, world, column, minimumSectionY, sectionContext);
            if (result != null) {
                packet.setColumn(result);
                event.markForReEncode(true);
                column = result;
            }
            cacheWireColumn(viewerUUID, column, packet.getLightData(), minimumSectionY);

        } else if (event.getPacketType() == PacketType.Play.Server.MAP_CHUNK_BULK) {
            WrapperPlayServerChunkDataBulk packet = new WrapperPlayServerChunkDataBulk(event);
            throw new RuntimeException("I didn't think this packet existed. Please report this to the developer with details on how to reproduce it so it can be implemented");
        }
    }

    private void removeChunk(UUID viewerUUID, WrapperPlayServerUnloadChunk packet, BlockView blockView, UUID world) {
        blockView.removeChunk(world, packet.getChunkX(), packet.getChunkZ());
        Long2ObjectOpenHashMap<CachedWireColumn> columns = wireColumnsByViewer.get(viewerUUID);
        if (columns != null) {
            columns.remove(packColumnKey(packet.getChunkX(), packet.getChunkZ()));
        }
    }

    private void cacheWireColumn(UUID viewerUUID, Column column, LightData lightData, int minimumSectionY) {
        if (viewerUUID == null || column == null) {
            return;
        }
        BaseChunk[] sections = column.getChunks();
        if (sections == null) {
            return;
        }
        DataPalette[] biomes = new DataPalette[sections.length];
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] instanceof Chunk_v1_18 chunk) {
                biomes[i] = chunk.getBiomeData();
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
                lightClone,
                heightMapsNbt,
                heightmapsMap,
                column.getTileEntities()
        );
        wireColumnsByViewer
                .computeIfAbsent(viewerUUID, ignored -> new Long2ObjectOpenHashMap<>())
                .put(packColumnKey(column.getX(), column.getZ()), cached);
    }

    private static long packColumnKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    private void handleMultiBlockChange(PacketSendEvent event, BlockView blockView, UUID world, WrapperPlayServerMultiBlockChange packet, Locatable playerLocation, boolean tileChecksEnabled, boolean sectionChecksEnabled) {
        MutableBlockSpatialImpl key = new MutableBlockSpatialImpl(0, 0, 0);
        for (WrapperPlayServerMultiBlockChange.EncodedBlock change : packet.getBlocks()) {
            char blockID = (char) change.getBlockId();
            boolean tileEntity = blockInfoResolver.isTileEntity(blockID);
            blockView.upsertBlock(world, change.getX(), change.getY(), change.getZ(), blockID);
            key.setBlockPosition(change.getX(), change.getY(), change.getZ());
            if (sectionChecksEnabled) {
                ensureTrackedSection(blockView, world, key, playerLocation, sectionChecksEnabled);
                if (!blockView.isChunkSectionVisible(world, key.chunkX(), key.chunkY(), key.chunkZ())) {
                    change.setBlockId(sectionHiddenWireBlockId(key.blockY()));
                    event.markForReEncode(true);
                    if (tileEntity) {
                        blockView.updateOrInsertTileEntity(world, key, blockID, false);
                    } else {
                        blockView.removeTileEntity(world, key);
                    }
                    continue;
                }
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
            TrackedTileEntity<PacketEventsTileEntityReplayData> state = resolveCurrentTransitionState(transition, worldEpoch);
            if (state == null || state.blockID() == 0) {
                continue;
            }
            Locatable ownLocation = playerData.ownLocation();
            UUID viewerWorld = ownLocation == null ? null : ownLocation.world();
            if (chunkSectionConfig != null && chunkSectionConfig.enabled()
                    && viewerWorld != null
                    && !blockView.isChunkSectionVisible(viewerWorld, location.chunkX(), location.chunkY(), location.chunkZ())) {
                // Terrain section is still hidden; tile SHOW would leak through air. Skip until the section is shown.
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
                            getHiddenBlockId(location.blockY())
                    ));
                }
                case SHOW -> {
                    viewer.writePacketSilently(new WrapperPlayServerBlockChange(
                            new Vector3i(location.blockX(), location.blockY(), location.blockZ()),
                            state.blockID()
                    ));
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
                // Fallback: per-section multiblock if we never saw CHUNK_DATA for this column.
                for (ChunkSectionViewTransition transition : applied) {
                    TrackedChunkSection section = transition.section();
                    if (packColumnKey(section.chunkX(), section.chunkZ()) != columnKey) {
                        continue;
                    }
                    sendSectionMultiBlockFallback(viewer, blockView, section, transition.type());
                }
                continue;
            }
            sendColumnVisibilityUpdate(viewer, blockView, world, cached);
        }

        for (ChunkSectionViewTransition transition : applied) {
            TrackedChunkSection section = transition.section();
            boolean show = transition.type() == ChunkSectionViewTransition.Type.SHOW;
            if (show) {
                requeueTilesInSection(blockView, world, section);
                resendTileEntitiesInSection(viewer, blockView, world, section);
            }
            PacketEventsEntityViewController.get().applyChunkSectionEntityGate(
                    viewer, playerData, section.chunkX(), section.sectionY(), section.chunkZ(), show);
        }
    }

    private void sendColumnVisibilityUpdate(User viewer, BlockView blockView, UUID world, CachedWireColumn cached) {
        BaseChunk[] sections = new BaseChunk[cached.sectionCount()];
        for (int i = 0; i < sections.length; i++) {
            int sectionY = cached.minimumSectionY() + i;
            boolean visible = world == null || blockView.isChunkSectionVisible(world, cached.chunkX(), sectionY, cached.chunkZ());
            DataPalette biomes = cached.biomePalette(i);
            if (visible) {
                sections[i] = buildWireSection(blockView.getBlockChunkData(cached.chunkX(), sectionY, cached.chunkZ()), biomes);
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

    /** Wire block id used while a section is hidden: air (0) or stone/deepslate placeholder. */
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
     * Clone light for a column resend. PacketEvents' {@link LightData#clone()} is shallow on the
     * nibble arrays; deep-copy them and force trustEdges so the client finalizes lighting on replace.
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
            ChunkSectionViewTransition.Type type
    ) {
        WrapperPlayServerMultiBlockChange.EncodedBlock[] blocks = type == ChunkSectionViewTransition.Type.HIDE
                ? buildSectionHideUpdates(blockView, section)
                : buildSectionRestoreUpdates(blockView, section);
        if (blocks.length != 0) {
            viewer.writePacketSilently(new WrapperPlayServerMultiBlockChange(
                    new Vector3i(section.chunkX(), section.sectionY(), section.chunkZ()),
                    false,
                    blocks
            ));
        }
    }

    private void resendTileEntitiesInSection(User viewer, BlockView blockView, UUID world, TrackedChunkSection section) {
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
                    TrackedTileEntity<PacketEventsTileEntityReplayData> tile = getTrackedTileEntity(blockView, world, key);
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

    private void handleSingleBlockChange(PacketSendEvent event, User viewer, PlayerData playerData, UUID world, WrapperPlayServerBlockChange packet, boolean tileChecksEnabled, boolean sectionChecksEnabled) {
        char blockID = (char) packet.getBlockId();
        boolean tileEntity = blockInfoResolver.isTileEntity(blockID);
        Vector3i position = packet.getBlockPosition();
        ImmutableBlockSpatialImpl location = new ImmutableBlockSpatialImpl(position.getX(), position.getY(), position.getZ());

        playerData.blockView().upsertBlock(world, position.getX(), position.getY(), position.getZ(), blockID);
        if (sectionChecksEnabled) {
            ensureTrackedSection(playerData.blockView(), world, location, playerData.ownLocation(), true);
            if (!playerData.blockView().isChunkSectionVisible(world, location.chunkX(), location.chunkY(), location.chunkZ())) {
                event.setCancelled(true);
                viewer.writePacketSilently(new WrapperPlayServerBlockChange(
                        new Vector3i(location.blockX(), location.blockY(), location.blockZ()),
                        sectionHiddenWireBlockId(location.blockY())
                ));
                if (tileEntity) {
                    playerData.blockView().updateOrInsertTileEntity(world, location, blockID, false);
                } else {
                    playerData.blockView().removeTileEntity(world, location);
                }
                return;
            }
        }
        if (tileEntity) {
            boolean visibleIfNew = !tileChecksEnabled || visibleIfNew(location, playerData.ownLocation(), world);
            TrackedTileEntity<?> state = playerData.blockView().updateOrInsertTileEntity(world, location, blockID, visibleIfNew);
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

    private void ensureTrackedSection(BlockView blockView, UUID world, BlockSpatial location, Locatable playerLocation, boolean sectionChecksEnabled) {
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
                    chunkSectionConfig.alwaysShowVerticalUp()
            );
        }
        blockView.updateOrInsertChunkSection(world, location.chunkX(), location.chunkY(), location.chunkZ(), visibleIfNew);
    }

    private WrapperPlayServerMultiBlockChange.EncodedBlock[] buildSectionHideUpdates(BlockView blockView, TrackedChunkSection section) {
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

    private WrapperPlayServerMultiBlockChange.EncodedBlock[] buildSectionRestoreUpdates(BlockView blockView, TrackedChunkSection section) {
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
                        blocks.add(new WrapperPlayServerMultiBlockChange.EncodedBlock(blockID, originX + x, originY + y, originZ + z));
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
                getHiddenBlockId(location.blockY())
        ));
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

    private WrapperPlayServerBlockEntityData buildBlockEntityDataPacket(BlockSpatial location, PacketEventsTileEntityReplayData replayData) {
        return new WrapperPlayServerBlockEntityData(
                new Vector3i(location.blockX(), location.blockY(), location.blockZ()),
                replayData.blockEntityType(),
                replayData.nbt()
        );
    }

    @SuppressWarnings("unchecked")
    private static TrackedTileEntity<PacketEventsTileEntityReplayData> getTrackedTileEntity(BlockView blockView, UUID world, BlockSpatial position) {
        return (TrackedTileEntity<PacketEventsTileEntityReplayData>) blockView.getTrackedTileEntity(world, position);
    }

    @SuppressWarnings("unchecked")
    static @Nullable TrackedTileEntity<PacketEventsTileEntityReplayData> resolveCurrentTransitionState(BlockViewTransition transition, int currentWorldEpoch) {
        if (transition.worldEpoch() != currentWorldEpoch
                || !(transition.tileEntity() instanceof NettyTileEntity<?> tileEntity)
                || tileEntity.isRemoved()) {
            return null;
        }
        return (TrackedTileEntity<PacketEventsTileEntityReplayData>) transition.tileEntity();
    }

    static @Nullable TrackedChunkSection resolveCurrentSectionTransitionState(ChunkSectionViewTransition transition, int currentWorldEpoch) {
        if (transition.worldEpoch() != currentWorldEpoch
                || !(transition.section() instanceof NettyChunkSection section)
                || section.isRemoved()) {
            return null;
        }
        return section;
    }

    private PacketEventsTileEntityReplayData ensureTileReplayData(TrackedTileEntity<PacketEventsTileEntityReplayData> tileEntity) {
        PacketEventsTileEntityReplayData replayData = tileEntity.extraData();
        if (replayData == null) {
            replayData = new PacketEventsTileEntityReplayData();
            tileEntity.setExtraData(replayData);
        }
        return replayData;
    }
}
