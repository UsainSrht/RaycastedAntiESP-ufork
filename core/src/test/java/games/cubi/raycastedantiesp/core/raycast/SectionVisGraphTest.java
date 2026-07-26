package games.cubi.raycastedantiesp.core.raycast;

import games.cubi.raycastedantiesp.core.chunks.ChunkData;
import games.cubi.raycastedantiesp.core.chunks.OccludingChunkData;
import games.cubi.raycastedantiesp.core.view.chunks.ChunkSectionStore;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionVisGraphTest {
    @Test
    void solidSectionHasNoConnectivity() {
        assertEquals(SectionVisGraph.SOLID, SectionVisGraph.computeConnectivity(OccludingChunkData.solid()));
        assertFalse(SectionVisGraph.isFaceOpen(SectionVisGraph.SOLID, SectionFace.EAST));
    }

    @Test
    void emptySectionIsFullyOpen() {
        long mask = SectionVisGraph.computeConnectivity(OccludingChunkData.empty());
        assertEquals(SectionVisGraph.FULLY_OPEN, mask);
        assertTrue(SectionVisGraph.connects(mask, SectionFace.WEST, SectionFace.EAST));
    }

    @Test
    void sparseSurfaceOccludersStillFullyOpenWhenAirTouchesAllFaces() {
        // Patch of dirt that does not seal any face — same FULLY_OPEN mask as empty air.
        OccludingChunkData data = OccludingChunkData.empty();
        for (int x = 4; x <= 11; x++) {
            for (int z = 4; z <= 11; z++) {
                data = data.setOccluding(x, 0, z, true);
            }
        }
        long mask = SectionVisGraph.computeConnectivity(data);
        assertEquals(SectionVisGraph.FULLY_OPEN, mask);
    }

    @Test
    void eastWestTunnelConnectsOnlyThoseFaces() {
        OccludingChunkData data = OccludingChunkData.filled();
        for (int x = 0; x < ChunkData.CHUNK_SIZE; x++) {
            data = data.setOccluding(x, 8, 8, false);
        }
        long mask = SectionVisGraph.computeConnectivity(data);
        assertTrue(SectionVisGraph.connects(mask, SectionFace.WEST, SectionFace.EAST));
        assertTrue(SectionVisGraph.connects(mask, SectionFace.EAST, SectionFace.WEST));
        assertFalse(SectionVisGraph.connects(mask, SectionFace.NORTH, SectionFace.SOUTH));
        assertFalse(SectionVisGraph.isFaceOpen(mask, SectionFace.UP));
    }

    @Test
    void bfsDoesNotReachPastSolidBarrier() {
        Long2LongOpenHashMap connectivity = new Long2LongOpenHashMap();
        connectivity.defaultReturnValue(SectionVisGraph.SOLID);
        long eye = ChunkSectionStore.packChunkCoords(0, 0, 0);
        long openEast = ChunkSectionStore.packChunkCoords(1, 0, 0);
        long beyondSolid = ChunkSectionStore.packChunkCoords(2, 0, 0);
        connectivity.put(eye, SectionVisGraph.FULLY_OPEN);
        connectivity.put(openEast, SectionVisGraph.FULLY_OPEN);
        connectivity.put(beyondSolid, SectionVisGraph.FULLY_OPEN);
        // Section at (1) replaced mentally: insert solid wall at x=1 by SOLID connectivity
        connectivity.put(openEast, SectionVisGraph.SOLID);

        LongOpenHashSet reachable = new LongOpenHashSet();
        SectionVisGraph.collectReachable(0, 0, 0, 8, connectivity, reachable);

        assertTrue(reachable.contains(eye));
        assertFalse(reachable.contains(openEast));
        assertFalse(reachable.contains(beyondSolid));
    }

    @Test
    void bfsFollowsOpenTunnelThroughAdjacentSections() {
        Long2LongOpenHashMap connectivity = new Long2LongOpenHashMap();
        connectivity.defaultReturnValue(SectionVisGraph.SOLID);
        for (int x = 0; x <= 3; x++) {
            connectivity.put(ChunkSectionStore.packChunkCoords(x, 0, 0), SectionVisGraph.FULLY_OPEN);
        }

        LongOpenHashSet reachable = new LongOpenHashSet();
        SectionVisGraph.collectReachable(0, 0, 0, 8, connectivity, reachable);

        assertTrue(reachable.contains(ChunkSectionStore.packChunkCoords(0, 0, 0)));
        assertTrue(reachable.contains(ChunkSectionStore.packChunkCoords(3, 0, 0)));
    }

    @Test
    void solidShellHasNoOpenFacesWhileAirVolumeDoes() {
        assertFalse(SectionVisGraph.hasAnyOpenFace(SectionVisGraph.SOLID));
        assertTrue(SectionVisGraph.hasAnyOpenFace(SectionVisGraph.FULLY_OPEN));
    }

    @Test
    void bfsTreatsDefaultFullyOpenAsAirPassThrough() {
        // Untracked air: default FULLY_OPEN so hops through missing sections still reach tracked open faces.
        Long2LongOpenHashMap connectivity = new Long2LongOpenHashMap();
        connectivity.defaultReturnValue(SectionVisGraph.FULLY_OPEN);
        long target = ChunkSectionStore.packChunkCoords(2, 0, 0);
        connectivity.put(target, SectionVisGraph.FULLY_OPEN);

        LongOpenHashSet reachable = new LongOpenHashSet();
        SectionVisGraph.collectReachable(0, 0, 0, 8, connectivity, reachable);

        assertTrue(reachable.contains(ChunkSectionStore.packChunkCoords(0, 0, 0)));
        assertTrue(reachable.contains(ChunkSectionStore.packChunkCoords(1, 0, 0)));
        assertTrue(reachable.contains(target));
    }

    @Test
    void bfsStillBlockedByExplicitSolidWallWithFullyOpenDefault() {
        // With air pass-through default, a single SOLID cell is bypassed via adjacent air.
        // A full SOLID wall at x=1 within radius must still seal off x=2.
        Long2LongOpenHashMap connectivity = new Long2LongOpenHashMap();
        connectivity.defaultReturnValue(SectionVisGraph.FULLY_OPEN);
        for (int y = -2; y <= 2; y++) {
            for (int z = -2; z <= 2; z++) {
                connectivity.put(ChunkSectionStore.packChunkCoords(1, y, z), SectionVisGraph.SOLID);
            }
        }
        long beyond = ChunkSectionStore.packChunkCoords(2, 0, 0);
        connectivity.put(beyond, SectionVisGraph.FULLY_OPEN);

        LongOpenHashSet reachable = new LongOpenHashSet();
        SectionVisGraph.collectReachable(0, 0, 0, 2, connectivity, reachable);

        assertTrue(reachable.contains(ChunkSectionStore.packChunkCoords(0, 0, 0)));
        assertFalse(reachable.contains(ChunkSectionStore.packChunkCoords(1, 0, 0)));
        assertFalse(reachable.contains(beyond));
    }

    @Test
    void frontierIncludesFaceNeighborsOfReachable() {
        LongOpenHashSet reachable = new LongOpenHashSet();
        long eye = ChunkSectionStore.packChunkCoords(0, 0, 0);
        reachable.add(eye);

        LongOpenHashSet frontier = new LongOpenHashSet();
        SectionVisGraph.collectFrontier(0, 0, 0, 8, reachable, frontier);

        assertTrue(frontier.contains(eye));
        assertTrue(frontier.contains(ChunkSectionStore.packChunkCoords(1, 0, 0)));
        assertTrue(frontier.contains(ChunkSectionStore.packChunkCoords(-1, 0, 0)));
        assertTrue(frontier.contains(ChunkSectionStore.packChunkCoords(0, 1, 0)));
        assertTrue(frontier.contains(ChunkSectionStore.packChunkCoords(0, -1, 0)));
        assertTrue(frontier.contains(ChunkSectionStore.packChunkCoords(0, 0, 1)));
        assertTrue(frontier.contains(ChunkSectionStore.packChunkCoords(0, 0, -1)));
        // Diagonal is not a face neighbor.
        assertFalse(frontier.contains(ChunkSectionStore.packChunkCoords(1, 1, 0)));
    }

    @Test
    void frontierRespectsChebyshevRadius() {
        LongOpenHashSet reachable = new LongOpenHashSet();
        reachable.add(ChunkSectionStore.packChunkCoords(2, 0, 0));

        LongOpenHashSet frontier = new LongOpenHashSet();
        SectionVisGraph.collectFrontier(0, 0, 0, 2, reachable, frontier);

        assertTrue(frontier.contains(ChunkSectionStore.packChunkCoords(2, 0, 0)));
        assertTrue(frontier.contains(ChunkSectionStore.packChunkCoords(1, 0, 0)));
        // Neighbor at x=3 is Chebyshev 3 from eye — outside radius 2.
        assertFalse(frontier.contains(ChunkSectionStore.packChunkCoords(3, 0, 0)));
    }
}
