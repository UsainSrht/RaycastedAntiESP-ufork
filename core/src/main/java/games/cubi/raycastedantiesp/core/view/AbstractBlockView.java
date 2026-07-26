package games.cubi.raycastedantiesp.core.view;

import ca.spottedleaf.concurrentutil.collection.MultiThreadedQueue;
import ca.spottedleaf.concurrentutil.map.SWMRInt2ObjectHashTable;
import ca.spottedleaf.concurrentutil.map.SWMRLong2ObjectHashTable;
import games.cubi.locatables.api.BlockLocatable;
import games.cubi.locatables.api.BlockSpatial;
import games.cubi.logs.Logger;
import games.cubi.raycastedantiesp.core.chunks.BlockInfoResolver;
import games.cubi.raycastedantiesp.core.chunks.BlockChunkData;
import games.cubi.raycastedantiesp.core.chunks.ChunkOcclusionView;
import games.cubi.raycastedantiesp.core.chunks.OccludingChunkData;
import games.cubi.raycastedantiesp.core.raycast.SectionVisGraph;
import games.cubi.raycastedantiesp.core.tracked.NettyChunkSection;
import games.cubi.raycastedantiesp.core.tracked.TrackedChunkSection;
import games.cubi.raycastedantiesp.core.tracked.TrackedTileEntity;
import games.cubi.raycastedantiesp.core.tracked.NettyTileEntity;
import games.cubi.raycastedantiesp.core.players.PlayerData;
import games.cubi.raycastedantiesp.core.utils.Clearable;
import games.cubi.raycastedantiesp.core.utils.InvasivelyLinkedSWMRList;
import games.cubi.raycastedantiesp.core.utils.VarHandler;
import games.cubi.raycastedantiesp.core.view.chunks.BlockChunkSectionStore;
import games.cubi.raycastedantiesp.core.view.chunks.ChunkSectionStore;
import games.cubi.raycastedantiesp.core.view.chunks.OccludingChunkSectionStore;

import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import static games.cubi.raycastedantiesp.core.chunks.ChunkData.packUncheckedGuarded;
import static games.cubi.raycastedantiesp.core.view.chunks.ChunkSectionStore.packChunkCoords;

public abstract class AbstractBlockView<R extends Clearable, T extends NettyTileEntity<R>> implements BlockView {
    public static final int CHUNK_SIZE = 16;
    public static final int LOCAL_MASK = CHUNK_SIZE - 1;

    private final ChunkSectionStore chunks;
    private final boolean trackAllBlocks;
    /**
     * The int key represents a packed chunk column bucket, with (signed) 16 bits allocated for x and z respectively. While chunk columns over 60k chunks apart may overlap, this is unlikely to ever actually occur.
     * <p> {@code NettyTileEntity<R>} is an {@link InvasivelyLinkedSWMRList}, so all tile entities in the column chain from the head entry.
     * <p> Since direct access to this object outside {@link AbstractBlockView} is impossible, a marker head object is skipped, and head removal is handled separately.
     * **/
    private final SWMRInt2ObjectHashTable<NettyTileEntity<R>> knownTileEntitiesByColumnBucket = new SWMRInt2ObjectHashTable<>();
    private final SWMRLong2ObjectHashTable<NettyChunkSection> knownChunkSections = new SWMRLong2ObjectHashTable<>();
    private final MultiThreadedQueue<BlockViewTransition> transitions = new MultiThreadedQueue<>();
    private final MultiThreadedQueue<ChunkSectionViewTransition> sectionTransitions = new MultiThreadedQueue<>();
    private final IntSupplier worldEpochSupplier;
    private volatile UUID trackedWorld;
    // Bit 0 is enabled; higher bits are a generation. The generation prevents enabled -> disabled -> enabled ABA from
    // accepting an old raycast and also tags transitions that may be drained after their originating mode was replaced.
    private volatile long tileEntityCheckModeToken; private static final VarHandle TILE_ENTITY_CHECK_MODE_TOKEN = VarHandler.get(AbstractBlockView.class, "tileEntityCheckModeToken", long.class);
    private volatile long chunkSectionCheckModeToken; private static final VarHandle CHUNK_SECTION_CHECK_MODE_TOKEN = VarHandler.get(AbstractBlockView.class, "chunkSectionCheckModeToken", long.class);

    protected AbstractBlockView(BlockInfoResolver blockInfoResolver, boolean trackAllBlocks, IntSupplier worldEpochSupplier) {
        Logger.requireNonNull(blockInfoResolver, "blockInfoResolver was null", 1, AbstractBlockView.class);
        Logger.requireNonNull(worldEpochSupplier, "worldEpochSupplier was null", 1, AbstractBlockView.class);
        this.trackAllBlocks = trackAllBlocks;
        this.worldEpochSupplier = worldEpochSupplier;
        this.chunks = trackAllBlocks
                ? new BlockChunkSectionStore(blockInfoResolver)
                : new OccludingChunkSectionStore(blockInfoResolver);
    }

    protected abstract T createTrackedTileEntity(BlockSpatial position, char blockID, boolean visible);


    @Override
    public boolean isBlockOccluding(int x, int y, int z) {
        return chunks.isOccluding(x, y, z);
    }

    @Override
    public boolean isBlockOccluding(BlockLocatable location) {
        if (location == null || !isTrackedWorld(location.world())) {
            return false;
        }

        return isBlockOccluding(location.blockX(), location.blockY(), location.blockZ());
    }

    public int loadedChunkCount() {
        return chunks.loadedSectionCount();
    }

    @Override
    public T updateOrInsertTileEntity(UUID world, BlockSpatial position, char blockID, boolean visibleIfNew) {
        if (position == null || !ensureTrackedWorld(world)) {
            return null;
        }
        SWMRInt2ObjectHashTable<NettyTileEntity<R>> map = knownTileEntitiesByColumnBucket;
        int bucketKey = packColumnBucket(position.chunkX(), position.chunkZ());
        NettyTileEntity<R> head = map.get(bucketKey);
        T tileEntity = findTileEntityInBucket(head, position);
        if (tileEntity == null) {
            tileEntity = createTrackedTileEntity(position, blockID, visibleIfNew);
            if (tileEntity == null) {
                Logger.error("createTrackedTileEntity returned null", 3, AbstractBlockView.class);
                return null;
            }
            if (head == null) {
                map.put(bucketKey, tileEntity);
            } else {
                head.linkAfter(tileEntity);
            }
        }
        tileEntity.clearExtraData();
        tileEntity.setBlockID(blockID);
        return tileEntity;
    }

    @Override
    public void removeTileEntity(UUID world, BlockSpatial position) {
        if (position == null || !isTrackedWorld(world)) {
            return;
        }
        SWMRInt2ObjectHashTable<NettyTileEntity<R>> map = knownTileEntitiesByColumnBucket;
        int bucketKey = packColumnBucket(position.chunkX(), position.chunkZ());
        NettyTileEntity<R> head = map.get(bucketKey);
        T tileEntity = findTileEntityInBucket(head, position);
        if (tileEntity != null) {
            removeNode(map, bucketKey, head, tileEntity);
        }
    }

    @Override
    public T getTrackedTileEntity(UUID world, BlockSpatial position) {
        if (position == null || !isTrackedWorld(world)) {
            return null;
        }
        return findTileEntityInBucket(knownTileEntitiesByColumnBucket.get(packColumnBucket(position.chunkX(), position.chunkZ())), position);
    }

    @Override
    public boolean isVisible(UUID world, BlockSpatial position, int currentTick) {
        if (position == null || !isTrackedWorld(world)) {
            return true;
        }
        T state = getTrackedTileEntity(world, position);
        return state == null || state.visible();
    }

    @Override
    public void applyTileEntityVisibilityDecision(TrackedTileEntity<?> tileEntity, boolean visible, int currentTick, long modeToken, int expectedWorldEpoch) {
        if (!(tileEntity instanceof NettyTileEntity<?> nettyTileEntity)) {
            return;
        }
        @SuppressWarnings("unchecked") T typed = (T) nettyTileEntity;
        commitTileEntityVisibilityDecision(typed, typed.visible(), visible, currentTick, modeToken, expectedWorldEpoch);
    }

    private void commitTileEntityVisibilityDecision(T tileEntity, boolean currentVisibility, boolean shouldBeVisible, int currentTick, long modeToken, int expectedWorldEpoch) {
        if (!isCurrentWorldEpoch(expectedWorldEpoch) || tileEntityCheckModeTokenAcquire() != modeToken) {
            return;
        }
        if (!modeEnabled(modeToken)) {
            shouldBeVisible = true;
        }
        boolean visibilityChanged = currentVisibility != shouldBeVisible;
        tileEntity.setVisible(shouldBeVisible);
        tileEntity.setLastChecked(currentTick);
        if (visibilityChanged) {
            transitions.add(new BlockViewTransition(
                    shouldBeVisible ? BlockViewTransition.Type.SHOW : BlockViewTransition.Type.HIDE,
                    tileEntity,
                    modeToken,
                    expectedWorldEpoch
            ));
        }
    }

    @Override
    // Only used on non-mutation paths
    public void recordOutboundTileEntityVisibility(TrackedTileEntity<?> tileEntity, boolean visible) {
        if (tileEntity != null) {
            tileEntity.setVisible(visible);
            tileEntity.setLastChecked(TrackedTileEntity.NEVER_CHECKED);
        }
    }

    @Override
    public void applyTileEntityCheckMode(boolean enabled, int currentTick) {
        long current = tileEntityCheckModeTokenAcquire();
        if (modeEnabled(current) == enabled) {
            return;
        }
        // Drop the enabled bit, increment the generation, then restore bit 0 below only for enabled mode.
        long next = ((current >>> 1) + 1L) << 1;
        if (enabled) {
            forEachTileEntity(tileEntity -> tileEntity.setLastChecked(TrackedTileEntity.NEVER_CHECKED));
            TILE_ENTITY_CHECK_MODE_TOKEN.setRelease(this, next | 1L);
            return;
        }

        TILE_ENTITY_CHECK_MODE_TOKEN.setRelease(this, next);
        int worldEpoch = worldEpochSupplier.getAsInt();
        forEachTileEntity(tileEntity -> {
            boolean visible = tileEntity.visible();
            if (!visible) {
                @SuppressWarnings("unchecked") T typed = (T) tileEntity;
                commitTileEntityVisibilityDecision(typed, false, true, currentTick, next, worldEpoch);
            }
        });
    }

    @Override
    public long tileEntityCheckModeToken() {
        return tileEntityCheckModeTokenAcquire();
    }

    @Override
    public boolean isCurrentEnabledTileEntityMode(long modeToken) {
        return modeEnabled(modeToken) && tileEntityCheckModeTokenAcquire() == modeToken;
    }

    @Override
    public Collection<TrackedTileEntity<?>> getKnownTileEntities() {
        ArrayList<TrackedTileEntity<?>> snapshot = new ArrayList<>();
        forEachTileEntity(snapshot::add);
        return List.copyOf(snapshot);
    }

    @Override
    public int forEachNeedingRecheck(int recheckTicks, int currentTick, Consumer<TrackedTileEntity<?>> action) {
        return knownTileEntitiesByColumnBucket.forEachValueSummed(head -> {
            int processed = 0;
            for (NettyTileEntity<R> tileEntity = head; tileEntity != null; tileEntity = tileEntity.nextAcquire()) {
                int lastChecked = tileEntity.lastChecked();
                if (tileEntity.visible() && lastChecked != TrackedTileEntity.NEVER_CHECKED && (recheckTicks < 0 || currentTick - lastChecked < recheckTicks)) {
                    continue;
                }
                action.accept(tileEntity);
                processed++;
            }
            return processed;
        });
    }

    @Override
    public int updateVisibilityForEachNeedingRecheck(int recheckTicks, int currentTick, long modeToken, int expectedWorldEpoch, VisibilityResolver action) {
        if (!isCurrentEnabledTileEntityMode(modeToken) || !isCurrentWorldEpoch(expectedWorldEpoch)) {
            return 0;
        }
        return knownTileEntitiesByColumnBucket.forEachValueSummed(head -> {
            int processed = 0;
            for (NettyTileEntity<R> tileEntity = head; tileEntity != null; tileEntity = tileEntity.nextAcquire()) {
                boolean currentVisibility = tileEntity.visible();
                int lastChecked = tileEntity.lastChecked();
                if (currentVisibility && lastChecked != TrackedTileEntity.NEVER_CHECKED && (recheckTicks < 0 || currentTick - lastChecked < recheckTicks)) {
                    continue;
                }
                byte shouldBeVisible = action.setVisible(tileEntity);
                if (shouldBeVisible != VisibilityResolver.SKIPPED) {
                    @SuppressWarnings("unchecked") T typed = (T) tileEntity;
                    commitTileEntityVisibilityDecision(typed, currentVisibility, shouldBeVisible == VisibilityResolver.SHOW, currentTick, modeToken, expectedWorldEpoch);
                }
                processed++;
            }
            return processed;
        });
    }

    @Override
    public boolean hasPendingTransitions() {
        return !transitions.isEmpty();
    }

    @Override
    public List<BlockViewTransition> drainTransitions() {
        List<BlockViewTransition> drained = new ArrayList<>();
        BlockViewTransition transition;
        while ((transition = transitions.poll()) != null) {
            drained.add(transition);
        }
        return drained;
    }

    @Override
    public NettyChunkSection updateOrInsertChunkSection(UUID world, int chunkX, int sectionY, int chunkZ, boolean visibleIfNew) {
        if (!ensureTrackedWorld(world)) {
            return null;
        }
        long key = packChunkCoords(chunkX, sectionY, chunkZ);
        NettyChunkSection existing = knownChunkSections.get(key);
        if (existing != null && !existing.isRemoved()) {
            return existing;
        }
        NettyChunkSection created = new NettyChunkSection(chunkX, sectionY, chunkZ, visibleIfNew);
        knownChunkSections.put(key, created);
        refreshVisConnectivity(created);
        return created;
    }

    @Override
    public NettyChunkSection getTrackedChunkSection(UUID world, int chunkX, int sectionY, int chunkZ) {
        if (!isTrackedWorld(world)) {
            return null;
        }
        NettyChunkSection section = knownChunkSections.get(packChunkCoords(chunkX, sectionY, chunkZ));
        return section == null || section.isRemoved() ? null : section;
    }

    @Override
    public void forEachTrackedChunkSection(Consumer<TrackedChunkSection> action) {
        knownChunkSections.forEachValue(section -> {
            if (!section.isRemoved()) {
                action.accept(section);
            }
        });
    }

    @Override
    public void refreshDirtySectionVisConnectivity() {
        knownChunkSections.forEachValue(section -> {
            if (!section.isRemoved() && section.visConnectivityDirty()) {
                refreshVisConnectivity(section);
            }
        });
    }

    @Override
    public boolean isChunkSectionVisible(UUID world, int chunkX, int sectionY, int chunkZ) {
        if (!modeEnabled(chunkSectionCheckModeTokenAcquire())) {
            return true;
        }
        if (!isTrackedWorld(world)) {
            return true;
        }
        NettyChunkSection section = getTrackedChunkSection(world, chunkX, sectionY, chunkZ);
        return section == null || section.visible();
    }

    @Override
    public void applyChunkSectionVisibilityDecision(TrackedChunkSection section, boolean visible, int currentTick, long modeToken, int expectedWorldEpoch) {
        if (!(section instanceof NettyChunkSection nettyChunkSection)) {
            return;
        }
        commitChunkSectionVisibilityDecision(nettyChunkSection, nettyChunkSection.visible(), visible, currentTick, modeToken, expectedWorldEpoch);
    }

    private void commitChunkSectionVisibilityDecision(NettyChunkSection section, boolean currentVisibility, boolean shouldBeVisible, int currentTick, long modeToken, int expectedWorldEpoch) {
        if (!isCurrentWorldEpoch(expectedWorldEpoch) || chunkSectionCheckModeTokenAcquire() != modeToken) {
            return;
        }
        if (!modeEnabled(modeToken)) {
            shouldBeVisible = true;
        }
        boolean visibilityChanged = currentVisibility != shouldBeVisible;
        section.setVisible(shouldBeVisible);
        section.setLastChecked(currentTick);
        if (visibilityChanged) {
            sectionTransitions.add(new ChunkSectionViewTransition(
                    shouldBeVisible ? ChunkSectionViewTransition.Type.SHOW : ChunkSectionViewTransition.Type.HIDE,
                    section,
                    modeToken,
                    expectedWorldEpoch
            ));
        }
    }

    @Override
    public void recordOutboundChunkSectionVisibility(TrackedChunkSection section, boolean visible) {
        if (section != null) {
            section.setVisible(visible);
            section.setLastChecked(TrackedChunkSection.NEVER_CHECKED);
        }
    }

    @Override
    public void applyChunkSectionCheckMode(boolean enabled, int currentTick) {
        long current = chunkSectionCheckModeTokenAcquire();
        if (modeEnabled(current) == enabled) {
            return;
        }
        long next = ((current >>> 1) + 1L) << 1;
        if (enabled) {
            knownChunkSections.forEachValue(section -> section.setLastChecked(TrackedChunkSection.NEVER_CHECKED));
            CHUNK_SECTION_CHECK_MODE_TOKEN.setRelease(this, next | 1L);
            return;
        }

        CHUNK_SECTION_CHECK_MODE_TOKEN.setRelease(this, next);
        int worldEpoch = worldEpochSupplier.getAsInt();
        knownChunkSections.forEachValue(section -> {
            if (!section.visible()) {
                commitChunkSectionVisibilityDecision(section, false, true, currentTick, next, worldEpoch);
            }
        });
    }

    @Override
    public long chunkSectionCheckModeToken() {
        return chunkSectionCheckModeTokenAcquire();
    }

    @Override
    public boolean isCurrentEnabledChunkSectionMode(long modeToken) {
        return modeEnabled(modeToken) && chunkSectionCheckModeTokenAcquire() == modeToken;
    }

    @Override
    public int updateChunkSectionVisibilityForEachNeedingRecheck(int recheckTicks, int currentTick, long modeToken, int expectedWorldEpoch, ChunkSectionVisibilityResolver action) {
        if (!isCurrentEnabledChunkSectionMode(modeToken) || !isCurrentWorldEpoch(expectedWorldEpoch)) {
            return 0;
        }
        int[] processed = {0};
        knownChunkSections.forEachValue(section -> {
            if (section.isRemoved()) {
                return;
            }
            boolean currentVisibility = section.visible();
            int lastChecked = section.lastChecked();
            if (currentVisibility && lastChecked != TrackedChunkSection.NEVER_CHECKED && (recheckTicks < 0 || currentTick - lastChecked < recheckTicks)) {
                return;
            }
            byte shouldBeVisible = action.setVisible(section);
            if (shouldBeVisible != ChunkSectionVisibilityResolver.SKIPPED) {
                commitChunkSectionVisibilityDecision(section, currentVisibility, shouldBeVisible == ChunkSectionVisibilityResolver.SHOW, currentTick, modeToken, expectedWorldEpoch);
            }
            processed[0]++;
        });
        return processed[0];
    }

    @Override
    public boolean hasPendingSectionTransitions() {
        return !sectionTransitions.isEmpty();
    }

    @Override
    public List<ChunkSectionViewTransition> drainSectionTransitions() {
        List<ChunkSectionViewTransition> drained = new ArrayList<>();
        ChunkSectionViewTransition transition;
        while ((transition = sectionTransitions.poll()) != null) {
            drained.add(transition);
        }
        return drained;
    }

    @Override
    public BlockChunkData getBlockChunkData(int chunkX, int sectionY, int chunkZ) {
        if (!(chunks instanceof BlockChunkSectionStore blockStore)) {
            return null;
        }
        return blockStore.getSection(chunkX, sectionY, chunkZ);
    }

    @Override
    public void upsertBlock(UUID world, int x, int y, int z, int blockID) {
        if (!ensureTrackedWorld(world)) {
            return;
        }
        chunks.setBlockID(x, y, z, blockID);
        NettyChunkSection tracked = getTrackedChunkSection(world, x >> 4, y >> 4, z >> 4);
        if (tracked != null) {
            tracked.markVisConnectivityDirty();
        }
    }

    @Override
    public void removeChunk(UUID world, int chunkX, int chunkZ) {
        if (!isTrackedWorld(world)) {
            return;
        }
        chunks.removeColumn(chunkX, chunkZ);
        removeTileEntitiesInChunk(chunkX, chunkZ);
        removeTrackedSectionsInChunk(chunkX, chunkZ);
    }

    @Override
    public void removeChunkSection(UUID world, int chunkX, int chunkY, int chunkZ) {
        if (!isTrackedWorld(world)) {
            return;
        }
        chunks.removeSection(chunkX, chunkY, chunkZ);
        removeTrackedSection(chunkX, chunkY, chunkZ);
    }

    @Override
    public void pruneTileEntitiesAbsentFromChunkSections(UUID world, int chunkX, int chunkZ, int minimumSectionY, int sectionCount, long[][] presentBySection) {
        if (!isTrackedWorld(world) || sectionCount < 0 || (presentBySection != null && presentBySection.length != sectionCount)) {
            return;
        }
        SWMRInt2ObjectHashTable<NettyTileEntity<R>> map = knownTileEntitiesByColumnBucket;
        int bucketKey = packColumnBucket(chunkX, chunkZ);
        // The wrapped key selects a collision bucket; full chunk coordinates below establish ownership.
        NettyTileEntity<R> head = map.get(bucketKey);
        NettyTileEntity<R> currentHead = head;
        for (NettyTileEntity<R> current = head; current != null; ) {
            NettyTileEntity<R> next = current.nextAcquire();
            if (current.chunkX() == chunkX && current.chunkZ() == chunkZ) {
                int sectionIndex = current.chunkY() - minimumSectionY;
                long[] present = sectionIndex >= 0 && sectionIndex < sectionCount && presentBySection != null
                        ? presentBySection[sectionIndex]
                        : null;
                if (sectionIndex < 0 || sectionIndex >= sectionCount || present == null) {
                    currentHead = removeNode(map, bucketKey, currentHead, current);
                } else {
                    int packed = packUncheckedGuarded(current.blockX(), current.blockY(), current.blockZ());
                    // Guarded packing masks the full coordinates to local x/y/z. The high bits choose the 64-bit word;
                    // Java masks the long shift distance to the low six bits, selecting the bit within that word.
                    if ((present[packed >>> 6] & 1L << packed) == 0L) {
                        currentHead = removeNode(map, bucketKey, currentHead, current);
                    }
                }
            }
            current = next;
        }
    }

    @Override
    public void replaceChunkSection(UUID world, int chunkX, int chunkY, int chunkZ, BlockChunkData data) {
        if (!ensureTrackedWorld(world)) {
            return;
        }
        chunks.replaceSection(chunkX, chunkY, chunkZ, data);
        NettyChunkSection tracked = getTrackedChunkSection(world, chunkX, chunkY, chunkZ);
        if (tracked != null) {
            tracked.setVisConnectivity(SectionVisGraph.computeConnectivity(data));
        }
    }

    @Override
    public void replaceChunkSectionOcclusion(UUID world, int chunkX, int chunkY, int chunkZ, OccludingChunkData data) {
        if (trackAllBlocks) {
            throw new UnsupportedOperationException("Block chunk storage requires full block IDs for section replacement");
        }
        if (!ensureTrackedWorld(world)) {
            return;
        }
        chunks.replaceSectionOcclusion(chunkX, chunkY, chunkZ, data);
        NettyChunkSection tracked = getTrackedChunkSection(world, chunkX, chunkY, chunkZ);
        if (tracked != null) {
            tracked.setVisConnectivity(SectionVisGraph.computeConnectivity(data));
        }
    }

    private void refreshVisConnectivity(NettyChunkSection section) {
        ChunkOcclusionView data = sectionOcclusionView(section.chunkX(), section.sectionY(), section.chunkZ());
        section.setVisConnectivity(SectionVisGraph.computeConnectivity(data));
    }

    private ChunkOcclusionView sectionOcclusionView(int chunkX, int sectionY, int chunkZ) {
        if (chunks instanceof BlockChunkSectionStore blockStore) {
            return blockStore.getSection(chunkX, sectionY, chunkZ);
        }
        if (chunks instanceof OccludingChunkSectionStore occlusionStore) {
            return occlusionStore.get(packChunkCoords(chunkX, sectionY, chunkZ));
        }
        return null;
    }

    @Override
    public void clear() {
        trackedWorld = null;
        clearTrackedState();
    }

    private boolean isTrackedWorld(UUID world) {
        UUID current = trackedWorld;
        return world != null && current != null && current.equals(world);
    }

    private boolean ensureTrackedWorld(UUID world) {
        if (world == null) {
            return false;
        }
        if (world.equals(trackedWorld)) {
            return true;
        }
        trackedWorld = null;
        clearTrackedState();
        trackedWorld = world;
        return true;
    }

    private void clearTrackedState() {
        chunks.clear();
        forEachTileEntity(NettyTileEntity::markRemoved);
        knownTileEntitiesByColumnBucket.clear();
        knownChunkSections.forEachValue(NettyChunkSection::markRemoved);
        knownChunkSections.clear();
        transitions.clear();
        sectionTransitions.clear();
    }

    private void removeTrackedSection(int chunkX, int sectionY, int chunkZ) {
        long key = packChunkCoords(chunkX, sectionY, chunkZ);
        NettyChunkSection section = knownChunkSections.remove(key);
        if (section != null) {
            section.markRemoved();
        }
    }

    private void removeTrackedSectionsInChunk(int chunkX, int chunkZ) {
        // Visit every packed section Y for this column, matching ChunkSectionStore.removeColumn.
        long key = packChunkCoords(chunkX, 0, chunkZ);
        for (int i = 0; i < ChunkSectionStore.SECTION_Y_COUNT; i++) {
            NettyChunkSection section = knownChunkSections.remove(key);
            if (section != null) {
                section.markRemoved();
            }
            key += ChunkSectionStore.SECTION_Y_INCREMENT;
        }
    }

    private void removeTileEntitiesInChunk(int chunkX, int chunkZ) {
        SWMRInt2ObjectHashTable<NettyTileEntity<R>> map = knownTileEntitiesByColumnBucket;
        int bucketKey = packColumnBucket(chunkX, chunkZ);
        NettyTileEntity<R> head = map.get(bucketKey);
        if (head == null) {
            return;
        }

        boolean collision = false;
        for (NettyTileEntity<R> current = head; current != null; current = current.nextAcquire()) {
            if (current.chunkX() != chunkX || current.chunkZ() != chunkZ) {
                collision = true;
                break;
            }
        }
        if (!collision) {
            // Removing the map entry publishes removal of the whole bucket. Keep the old chain intact so readers that
            // already acquired its head can finish their weakly-consistent traversal; detached nodes are never reused.
            map.remove(bucketKey, head);
            for (NettyTileEntity<R> current = head; current != null; current = current.nextAcquire()) {
                current.markRemoved();
            }
            return;
        }

        Logger.info("Chunk collision occured, failing back to linear scan for tile entity removal in chunk " + chunkX + ", " + chunkZ, 5, AbstractBlockView.class); // I want to see if this occurs frequently

        // Wrapped-key collisions are rare. Remove matching nodes individually while preserving other columns.
        NettyTileEntity<R> currentHead = head;
        for (NettyTileEntity<R> current = head; current != null; ) {
            NettyTileEntity<R> next = current.nextAcquire();
            if (current.chunkX() == chunkX && current.chunkZ() == chunkZ) {
                currentHead = removeNode(map, bucketKey, currentHead, current);
            }
            current = next;
        }
    }

    private NettyTileEntity<R> removeNode(SWMRInt2ObjectHashTable<NettyTileEntity<R>> map, int bucketKey, NettyTileEntity<R> head, NettyTileEntity<R> node) {
        node.markRemoved();
        if (node != head) {
            node.unlink();
            return head;
        }
        NettyTileEntity<R> successor = node.nextAcquire();
        if (successor == null) {
            map.remove(bucketKey, node);
        } else {
            map.put(bucketKey, successor);
        }
        node.detachHeadWriterOnly();
        return successor;
    }

    @SuppressWarnings("unchecked")
    private T findTileEntityInBucket(NettyTileEntity<R> bucketHead, BlockSpatial position) {
        for (NettyTileEntity<R> current = bucketHead; current != null; current = current.nextAcquire()) {
            if (current.blockX() == position.blockX() && current.blockY() == position.blockY() && current.blockZ() == position.blockZ()) {
                return (T) current;
            }
        }
        return null;
    }

    private boolean isCurrentWorldEpoch(int expectedWorldEpoch) {
        return PlayerData.isStableWorldEpoch(expectedWorldEpoch) && worldEpochSupplier.getAsInt() == expectedWorldEpoch;
    }

    private long tileEntityCheckModeTokenAcquire() {
        return (long) TILE_ENTITY_CHECK_MODE_TOKEN.getAcquire(this);
    }

    private long chunkSectionCheckModeTokenAcquire() {
        return (long) CHUNK_SECTION_CHECK_MODE_TOKEN.getAcquire(this);
    }

    private void forEachTileEntity(Consumer<NettyTileEntity<R>> action) {
        knownTileEntitiesByColumnBucket.forEachValue(head -> {
            for (NettyTileEntity<R> current = head; current != null; current = current.nextAcquire()) {
                action.accept(current);
            }
        });
    }

    private static int packColumnBucket(int chunkX, int chunkZ) {
        // Keep the low 16 bits of X, then put the low 16 bits of Z in the upper half of the int.
        // Full coordinates on every tile disambiguate columns whose wrapped keys collide.
        return (chunkX & 0xFFFF) | (chunkZ & 0xFFFF) << 16;
    }

    private static boolean modeEnabled(long modeToken) {
        return (modeToken & 1L) != 0L;
    }
}
