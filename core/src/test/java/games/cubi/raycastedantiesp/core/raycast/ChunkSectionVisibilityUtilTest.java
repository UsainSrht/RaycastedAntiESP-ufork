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
    void facingFacesSelectsHorizontalWhenEyeIsBelowAndOffset() {
        // Eye west/down/north of section (0,0,0) → W + N (DOWN skipped; it shoots through the column)
        List<SectionFace> faces = ChunkSectionVisibilityUtil.facingFaces(-1, -1, -1, 0, 0, 0);
        assertEquals(2, faces.size());
        assertTrue(faces.contains(SectionFace.WEST));
        assertTrue(faces.contains(SectionFace.NORTH));
        assertFalse(faces.contains(SectionFace.DOWN));
    }

    @Test
    void facingFacesUsesVerticalSilhouetteWhenEyeIsDirectlyBelow() {
        List<SectionFace> faces = ChunkSectionVisibilityUtil.facingFaces(8, -1, 8, 0, 0, 0);
        assertEquals(4, faces.size());
        assertTrue(faces.contains(SectionFace.WEST));
        assertTrue(faces.contains(SectionFace.EAST));
        assertTrue(faces.contains(SectionFace.NORTH));
        assertTrue(faces.contains(SectionFace.SOUTH));
    }

    @Test
    void facingFacesEmptyWhenEyeInsideSection() {
        assertTrue(ChunkSectionVisibilityUtil.facingFaces(8, 8, 8, 0, 0, 0).isEmpty());
        assertTrue(ChunkSectionVisibilityUtil.isEyeInsideSection(8, 8, 8, 0, 0, 0));
    }

    @Test
    void samplePointsDeduplicateSharedCornersAcrossFacingFaces() {
        List<ImmutableSpatialImpl> samples = ChunkSectionVisibilityUtil.samplePointsForFacingFaces(-1, 8, -1, 0, 0, 0);
        // W + N, each center + 4 corners, shared edge corners deduped
        assertTrue(samples.size() >= 6);
        assertTrue(samples.size() <= 10);
    }

    @Test
    void faceCenterSamplesPreferLowerRimWhenEyeIsBelow() {
        List<ImmutableSpatialImpl> samples = ChunkSectionVisibilityUtil.sampleFaceCentersForFacingFaces(8, -1, 8, 0, 0, 0);
        assertFalse(samples.isEmpty());
        assertTrue(samples.stream().anyMatch(s -> s.y() == 0.5));
    }

    @Test
    void closestFaceSampleClampsEyeProjectionOntoFacingFace() {
        // Eye west of section, aimed at lower-south corner of the west face.
        List<ImmutableSpatialImpl> samples = ChunkSectionVisibilityUtil.sampleClosestPointsOnFacingFaces(
                -4, 1.2, 2.3, 0, 0, 0
        );
        assertEquals(1, samples.size());
        ImmutableSpatialImpl sample = samples.getFirst();
        assertEquals(0.5, sample.x(), 1e-9);
        assertEquals(1.2, sample.y(), 1e-9);
        assertEquals(2.3, sample.z(), 1e-9);
    }

    @Test
    void alwaysShowIsHorizontalWithAsymmetricVerticalBand() {
        assertTrue(ChunkSectionVisibilityUtil.isWithinAlwaysShow(0, 4, 0, 2, 4, 0, 2, 1, 12));
        assertFalse(ChunkSectionVisibilityUtil.isWithinAlwaysShow(0, 4, 0, 3, 4, 0, 2, 1, 12));
        // Deep underground under the player must NOT be force-shown.
        assertFalse(ChunkSectionVisibilityUtil.isWithinAlwaysShow(0, 4, 0, 0, 0, 0, 3, 1, 12));
        // Same column, one section below eye — within down pad.
        assertTrue(ChunkSectionVisibilityUtil.isWithinAlwaysShow(0, 4, 0, 0, 3, 0, 3, 1, 12));
        // Hilltop well above eye — within up pad.
        assertTrue(ChunkSectionVisibilityUtil.isWithinAlwaysShow(0, 4, 0, 0, 10, 0, 3, 1, 12));
        assertFalse(ChunkSectionVisibilityUtil.isWithinAlwaysShow(0, 4, 0, 0, 17, 0, 3, 1, 12));
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
