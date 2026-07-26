package games.cubi.raycastedantiesp.packetevents.viewcontrollers.chunkparser;

import games.cubi.locatables.api.Locatable;
import games.cubi.raycastedantiesp.core.raycast.ChunkSectionVisibilityUtil;

import java.util.UUID;

/** Viewer context used while mutating CHUNK_DATA for occluded sections. */
public final class ChunkSectionParseContext {
    private final Locatable viewerEye;
    private final UUID viewerWorld;
    private final int alwaysShowRadiusChunks;
    private final int alwaysShowVerticalSections;

    public ChunkSectionParseContext(Locatable viewerEye, int alwaysShowRadiusChunks) {
        this(viewerEye, alwaysShowRadiusChunks, 1);
    }

    public ChunkSectionParseContext(Locatable viewerEye, int alwaysShowRadiusChunks, int alwaysShowVerticalSections) {
        this.viewerEye = viewerEye;
        this.viewerWorld = viewerEye == null ? null : viewerEye.world();
        this.alwaysShowRadiusChunks = Math.max(0, alwaysShowRadiusChunks);
        this.alwaysShowVerticalSections = Math.max(0, alwaysShowVerticalSections);
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
                alwaysShowRadiusChunks, alwaysShowVerticalSections
        );
    }
}
