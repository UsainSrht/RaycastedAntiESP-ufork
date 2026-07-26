package games.cubi.raycastedantiesp.packetevents.viewcontrollers.chunkparser;

import games.cubi.locatables.api.Locatable;
import games.cubi.raycastedantiesp.core.raycast.ChunkSectionVisibilityUtil;

import java.util.UUID;

/** Viewer context used while mutating CHUNK_DATA for occluded sections. */
public final class ChunkSectionParseContext {
    private final Locatable viewerEye;
    private final UUID viewerWorld;
    private final int alwaysShowRadiusChunks;
    private final int alwaysShowVerticalDown;
    private final int alwaysShowVerticalUp;
    private final boolean hideAsAir;

    public ChunkSectionParseContext(Locatable viewerEye, int alwaysShowRadiusChunks) {
        this(viewerEye, alwaysShowRadiusChunks, 1, 12, true);
    }

    public ChunkSectionParseContext(Locatable viewerEye, int alwaysShowRadiusChunks, int alwaysShowVerticalSections) {
        this(viewerEye, alwaysShowRadiusChunks, alwaysShowVerticalSections, Math.max(alwaysShowVerticalSections, 12), true);
    }

    public ChunkSectionParseContext(
            Locatable viewerEye,
            int alwaysShowRadiusChunks,
            int alwaysShowVerticalDown,
            int alwaysShowVerticalUp
    ) {
        this(viewerEye, alwaysShowRadiusChunks, alwaysShowVerticalDown, alwaysShowVerticalUp, true);
    }

    public ChunkSectionParseContext(
            Locatable viewerEye,
            int alwaysShowRadiusChunks,
            int alwaysShowVerticalDown,
            int alwaysShowVerticalUp,
            boolean hideAsAir
    ) {
        this.viewerEye = viewerEye;
        this.viewerWorld = viewerEye == null ? null : viewerEye.world();
        this.alwaysShowRadiusChunks = Math.max(0, alwaysShowRadiusChunks);
        this.alwaysShowVerticalDown = Math.max(0, alwaysShowVerticalDown);
        this.alwaysShowVerticalUp = Math.max(0, alwaysShowVerticalUp);
        this.hideAsAir = hideAsAir;
    }

    public boolean hideAsAir() {
        return hideAsAir;
    }

    public boolean isWithinAlwaysShow(UUID packetWorld, int chunkX, int sectionY, int chunkZ) {
        if (viewerEye == null || viewerWorld == null || packetWorld == null || !viewerWorld.equals(packetWorld)) {
            return false;
        }
        int viewerChunkX = viewerEye.blockX() >> 4;
        int viewerSectionY = viewerEye.blockY() >> 4;
        int viewerChunkZ = viewerEye.blockZ() >> 4;
        return ChunkSectionVisibilityUtil.isWithinAlwaysShow(
                viewerChunkX, viewerSectionY, viewerChunkZ,
                chunkX, sectionY, chunkZ,
                alwaysShowRadiusChunks, alwaysShowVerticalDown, alwaysShowVerticalUp
        );
    }
}
