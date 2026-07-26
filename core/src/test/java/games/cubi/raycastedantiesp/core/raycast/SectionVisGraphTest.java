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
}
