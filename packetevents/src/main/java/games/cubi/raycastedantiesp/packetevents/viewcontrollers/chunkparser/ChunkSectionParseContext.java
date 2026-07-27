package games.cubi.raycastedantiesp.packetevents.viewcontrollers.chunkparser;

import games.cubi.locatables.api.Locatable;
import games.cubi.raycastedantiesp.core.config.raycast.HideBelowYConfig;
import games.cubi.raycastedantiesp.core.raycast.ChunkSectionVisibilityUtil;

import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;

import java.util.UUID;

/** Viewer context used while mutating CHUNK_DATA for occluded sections. */
public final class ChunkSectionParseContext {
    private final Locatable viewerEye;
    private final UUID viewerWorld;
    private final int alwaysShowRadiusChunks;
    private final int alwaysShowVerticalDown;
    private final int alwaysShowVerticalUp;
    private final boolean hideAsAir;
    private final HideBelowYConfig hideBelowYConfig;
    private final boolean sectionChecksEnabled;

    public ChunkSectionParseContext(Locatable viewerEye, int alwaysShowRadiusChunks) {
        this(viewerEye, alwaysShowRadiusChunks, 1, 12, true, null, true);
    }

    public ChunkSectionParseContext(Locatable viewerEye, int alwaysShowRadiusChunks, int alwaysShowVerticalSections) {
        this(viewerEye, alwaysShowRadiusChunks, alwaysShowVerticalSections, Math.max(alwaysShowVerticalSections, 12), true, null, true);
    }

    public ChunkSectionParseContext(
            Locatable viewerEye,
            int alwaysShowRadiusChunks,
            int alwaysShowVerticalDown,
            int alwaysShowVerticalUp
    ) {
        this(viewerEye, alwaysShowRadiusChunks, alwaysShowVerticalDown, alwaysShowVerticalUp, true, null, true);
    }

    public ChunkSectionParseContext(
            Locatable viewerEye,
            int alwaysShowRadiusChunks,
            int alwaysShowVerticalDown,
            int alwaysShowVerticalUp,
            boolean hideAsAir
    ) {
        this(viewerEye, alwaysShowRadiusChunks, alwaysShowVerticalDown, alwaysShowVerticalUp, hideAsAir, null, true);
    }

    public ChunkSectionParseContext(
            Locatable viewerEye,
            int alwaysShowRadiusChunks,
            int alwaysShowVerticalDown,
            int alwaysShowVerticalUp,
            boolean hideAsAir,
            HideBelowYConfig hideBelowYConfig
    ) {
        this(viewerEye, alwaysShowRadiusChunks, alwaysShowVerticalDown, alwaysShowVerticalUp, hideAsAir, hideBelowYConfig, true);
    }

    public ChunkSectionParseContext(
            Locatable viewerEye,
            int alwaysShowRadiusChunks,
            int alwaysShowVerticalDown,
            int alwaysShowVerticalUp,
            boolean hideAsAir,
            HideBelowYConfig hideBelowYConfig,
            boolean sectionChecksEnabled
    ) {
        this.viewerEye = viewerEye;
        this.viewerWorld = viewerEye == null ? null : viewerEye.world();
        this.alwaysShowRadiusChunks = Math.max(0, alwaysShowRadiusChunks);
        this.alwaysShowVerticalDown = Math.max(0, alwaysShowVerticalDown);
        this.alwaysShowVerticalUp = Math.max(0, alwaysShowVerticalUp);
        this.hideAsAir = hideAsAir;
        this.hideBelowYConfig = hideBelowYConfig;
        this.sectionChecksEnabled = sectionChecksEnabled;
    }

    private BaseChunk[] unhiddenSections;

    public void recordUnhiddenSection(int index, BaseChunk section) {
        if (index < 0) return;
        if (unhiddenSections == null) {
            unhiddenSections = new BaseChunk[Math.max(24, index + 1)];
        } else if (index >= unhiddenSections.length) {
            unhiddenSections = java.util.Arrays.copyOf(unhiddenSections, Math.max(unhiddenSections.length * 2, index + 1));
        }
        unhiddenSections[index] = section;
    }

    public BaseChunk[] unhiddenSections() {
        return unhiddenSections;
    }

    public boolean sectionChecksEnabled() {
        return sectionChecksEnabled;
    }

    public boolean hideAsAir() {
        return hideAsAir;
    }

    public HideBelowYConfig hideBelowYConfig() {
        return hideBelowYConfig;
    }

    public boolean shouldAutoHideSection(int sectionY) {
        if (hideBelowYConfig == null || !hideBelowYConfig.enabled() || viewerEye == null || viewerWorld == null) {
            return false;
        }
        return hideBelowYConfig.shouldAutoHideSection(viewerEye.y(), sectionY);
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
