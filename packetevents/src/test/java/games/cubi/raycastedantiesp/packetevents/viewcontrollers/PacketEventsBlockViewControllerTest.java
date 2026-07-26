package games.cubi.raycastedantiesp.packetevents.viewcontrollers;

import games.cubi.locatables.implementations.ImmutableBlockSpatialImpl;
import games.cubi.raycastedantiesp.core.chunks.BlockInfoResolver;
import games.cubi.raycastedantiesp.core.tracked.NettyChunkSection;
import games.cubi.raycastedantiesp.core.tracked.NettyTileEntity;
import games.cubi.raycastedantiesp.core.tracked.TrackedChunkSection;
import games.cubi.raycastedantiesp.core.tracked.TrackedTileEntity;
import games.cubi.raycastedantiesp.core.view.BlockViewTransition;
import games.cubi.raycastedantiesp.core.view.ChunkSectionViewTransition;
import games.cubi.raycastedantiesp.packetevents.view.PacketEventsBlockView;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketEventsBlockViewControllerTest {
    private static final IntSupplier STABLE_WORLD_EPOCH = () -> 2;
    private static final BlockInfoResolver RESOLVER = new BlockInfoResolver() {
        @Override public boolean isOccluding(int blockStateID) { return false; }
        @Override public boolean isTileEntity(int blockStateID) { return blockStateID != 0; }
        @Override public boolean hasBlockEntityData(int blockStateID) { return blockStateID != 0; }
    };

    @Test
    void transitionCannotTargetReplacementAtSameCoordinates() {
        UUID world = UUID.randomUUID();
        ImmutableBlockSpatialImpl location = new ImmutableBlockSpatialImpl(3, 64, 5);
        PacketEventsBlockView view = new PacketEventsBlockView(RESOLVER, true, STABLE_WORLD_EPOCH);
        view.applyTileEntityCheckMode(true, 0);
        view.updateOrInsertTileEntity(world, location, (char) 1, true);
        TrackedTileEntity<?> original = view.getTrackedTileEntity(world, location);
        view.applyTileEntityVisibilityDecision(original, false, 1, view.tileEntityCheckModeToken(), 2);
        BlockViewTransition transition = view.drainTransitions().getFirst();

        view.removeTileEntity(world, location);
        view.updateOrInsertTileEntity(world, location, (char) 2, true);
        TrackedTileEntity<?> replacement = view.getTrackedTileEntity(world, location);
        int replacementLastChecked = replacement.lastChecked();

        assertSame(original, transition.tileEntity());
        assertTrue(((NettyTileEntity<?>) original).isRemoved());
        original.setLastChecked(42);
        assertTrue(((NettyTileEntity<?>) original).isRemoved());
        assertNull(PacketEventsBlockViewController.resolveCurrentTransitionState(transition, 2));
        assertTrue(replacement.visible());
        assertEquals(replacementLastChecked, replacement.lastChecked());
    }

    @Test
    void currentTransitionStillResolvesByIdentity() {
        UUID world = UUID.randomUUID();
        ImmutableBlockSpatialImpl location = new ImmutableBlockSpatialImpl(3, 64, 5);
        PacketEventsBlockView view = new PacketEventsBlockView(RESOLVER, true, STABLE_WORLD_EPOCH);
        view.applyTileEntityCheckMode(true, 0);
        TrackedTileEntity<?> tileEntity = view.updateOrInsertTileEntity(world, location, (char) 1, true);
        view.applyTileEntityVisibilityDecision(tileEntity, false, 1, view.tileEntityCheckModeToken(), 2);
        BlockViewTransition transition = view.drainTransitions().getFirst();

        assertSame(transition.tileEntity(), PacketEventsBlockViewController.resolveCurrentTransitionState(transition, 2));
    }

    @Test
    void showTransitionCannotTargetReplacementAtSameCoordinates() {
        UUID world = UUID.randomUUID();
        ImmutableBlockSpatialImpl location = new ImmutableBlockSpatialImpl(3, 64, 5);
        PacketEventsBlockView view = new PacketEventsBlockView(RESOLVER, true, STABLE_WORLD_EPOCH);
        view.applyTileEntityCheckMode(true, 0);
        TrackedTileEntity<?> original = view.updateOrInsertTileEntity(world, location, (char) 1, false);
        view.applyTileEntityVisibilityDecision(original, true, 1, view.tileEntityCheckModeToken(), 2);
        BlockViewTransition transition = view.drainTransitions().getFirst();

        view.removeTileEntity(world, location);
        view.updateOrInsertTileEntity(world, location, (char) 2, false);
        TrackedTileEntity<?> replacement = view.getTrackedTileEntity(world, location);
        int replacementLastChecked = replacement.lastChecked();

        assertNull(PacketEventsBlockViewController.resolveCurrentTransitionState(transition, 2));
        assertFalse(replacement.visible());
        assertEquals(replacementLastChecked, replacement.lastChecked());
    }

    @Test
    void transitionCannotCrossWorldEpoch() {
        UUID firstWorld = UUID.randomUUID();
        UUID secondWorld = UUID.randomUUID();
        ImmutableBlockSpatialImpl position = new ImmutableBlockSpatialImpl(3, 64, 5);
        AtomicInteger worldEpoch = new AtomicInteger(2);
        PacketEventsBlockView view = new PacketEventsBlockView(RESOLVER, true, worldEpoch::getAcquire);
        view.applyTileEntityCheckMode(true, 0);
        TrackedTileEntity<?> original = view.updateOrInsertTileEntity(firstWorld, position, (char) 1, true);
        view.applyTileEntityVisibilityDecision(original, false, 1, view.tileEntityCheckModeToken(), worldEpoch.getAcquire());
        BlockViewTransition transition = view.drainTransitions().getFirst();

        worldEpoch.setRelease(4);
        TrackedTileEntity<?> replacement = view.updateOrInsertTileEntity(secondWorld, position, (char) 2, true);

        assertNull(PacketEventsBlockViewController.resolveCurrentTransitionState(transition, worldEpoch.getAcquire()));
        assertTrue(replacement.visible());
    }

    @Test
    void sectionTransitionCannotTargetReplacementAtSameCoordinates() {
        UUID world = UUID.randomUUID();
        PacketEventsBlockView view = new PacketEventsBlockView(RESOLVER, true, STABLE_WORLD_EPOCH);
        view.applyChunkSectionCheckMode(true, 0);
        TrackedChunkSection original = view.updateOrInsertChunkSection(world, 3, 4, 5, true);
        view.applyChunkSectionVisibilityDecision(original, false, 1, view.chunkSectionCheckModeToken(), 2);
        ChunkSectionViewTransition transition = view.drainSectionTransitions().getFirst();

        view.removeChunkSection(world, 3, 4, 5);
        TrackedChunkSection replacement = view.updateOrInsertChunkSection(world, 3, 4, 5, true);

        assertSame(original, transition.section());
        assertTrue(((NettyChunkSection) original).isRemoved());
        assertNull(PacketEventsBlockViewController.resolveCurrentSectionTransitionState(transition, 2));
        assertTrue(replacement.visible());
    }

    @Test
    void currentSectionTransitionStillResolvesByIdentity() {
        UUID world = UUID.randomUUID();
        PacketEventsBlockView view = new PacketEventsBlockView(RESOLVER, true, STABLE_WORLD_EPOCH);
        view.applyChunkSectionCheckMode(true, 0);
        TrackedChunkSection section = view.updateOrInsertChunkSection(world, 3, 4, 5, true);
        view.applyChunkSectionVisibilityDecision(section, false, 1, view.chunkSectionCheckModeToken(), 2);
        ChunkSectionViewTransition transition = view.drainSectionTransitions().getFirst();

        assertSame(transition.section(), PacketEventsBlockViewController.resolveCurrentSectionTransitionState(transition, 2));
    }
}
