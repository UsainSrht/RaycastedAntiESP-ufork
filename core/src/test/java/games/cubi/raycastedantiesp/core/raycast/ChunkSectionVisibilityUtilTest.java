package games.cubi.raycastedantiesp.core.raycast;

import games.cubi.locatables.implementations.ImmutableSpatialImpl;
import games.cubi.raycastedantiesp.core.tracked.TrackedChunkSection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSectionVisibilityUtilTest {
    @Test
    void facingFacesSelectsAtMostThreeViewerFacingSides() {
        // Eye west/down/north of section (0,0,0) → W, D, N
        List<SectionFace> faces = ChunkSectionVisibilityUtil.facingFaces(-1, -1, -1, 0, 0, 0);
        assertEquals(3, faces.size());
        assertTrue(faces.contains(SectionFace.WEST));
        assertTrue(faces.contains(SectionFace.DOWN));
        assertTrue(faces.contains(SectionFace.NORTH));
    }

    @Test
    void facingFacesEmptyWhenEyeInsideSection() {
        assertTrue(ChunkSectionVisibilityUtil.facingFaces(8, 8, 8, 0, 0, 0).isEmpty());
        assertTrue(ChunkSectionVisibilityUtil.isEyeInsideSection(8, 8, 8, 0, 0, 0));
    }

    @Test
    void samplePointsDeduplicateSharedCornersAcrossFacingFaces() {
        List<ImmutableSpatialImpl> samples = ChunkSectionVisibilityUtil.samplePointsForFacingFaces(-1, -1, -1, 0, 0, 0);
        // 3 faces × (center + 4 corners) = 15, minus shared corners → 10 unique
        assertEquals(10, samples.size());
    }

    @Test
    void faceCenterSamplesAreAtMostThree() {
        assertEquals(3, ChunkSectionVisibilityUtil.sampleFaceCentersForFacingFaces(-1, -1, -1, 0, 0, 0).size());
        assertEquals(1, ChunkSectionVisibilityUtil.sampleFaceCentersForFacingFaces(-1, 8, 8, 0, 0, 0).size());
    }

    @Test
    void alwaysShowIsHorizontalPlusVerticalBand() {
        // Horizontal radius 2
        assertTrue(ChunkSectionVisibilityUtil.isWithinAlwaysShow(0, 4, 0, 2, 4, 0, 2, 1));
        assertFalse(ChunkSectionVisibilityUtil.isWithinAlwaysShow(0, 4, 0, 3, 4, 0, 2, 1));
        // Deep underground under the player must NOT be force-shown (old 3D Chebyshev bug).
        assertFalse(ChunkSectionVisibilityUtil.isWithinAlwaysShow(0, 4, 0, 0, 0, 0, 3, 1));
        // Same column, one section below eye — within vertical pad.
        assertTrue(ChunkSectionVisibilityUtil.isWithinAlwaysShow(0, 4, 0, 0, 3, 0, 3, 1));
    }

    @Test
    void hideDelayRequiresContinuousWantHiddenTicks() {
        assertFalse(ChunkSectionVisibilityUtil.hideDelayElapsed(TrackedChunkSection.NOT_WANTING_HIDE, 10, 3));
        assertFalse(ChunkSectionVisibilityUtil.hideDelayElapsed(9, 10, 3));
        assertFalse(ChunkSectionVisibilityUtil.hideDelayElapsed(8, 10, 3));
        assertTrue(ChunkSectionVisibilityUtil.hideDelayElapsed(7, 10, 3));
        assertTrue(ChunkSectionVisibilityUtil.hideDelayElapsed(10, 10, 0));
    }
}
