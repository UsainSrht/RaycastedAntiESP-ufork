package games.cubi.raycastedantiesp.core.view;

import games.cubi.raycastedantiesp.core.tracked.TrackedChunkSection;

// Retains the originating object identity so a delayed transition cannot target a replacement at the same coordinates.
public record ChunkSectionViewTransition(Type type, TrackedChunkSection section, long modeToken, int worldEpoch) {
    public enum Type {
        SHOW,
        HIDE,
    }
}
