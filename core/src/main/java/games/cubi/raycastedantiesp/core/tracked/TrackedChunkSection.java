package games.cubi.raycastedantiesp.core.tracked;

public interface TrackedChunkSection {
    int NEVER_CHECKED = Integer.MIN_VALUE;
    int NOT_WANTING_HIDE = Integer.MIN_VALUE + 1;

    int chunkX();

    int sectionY();

    int chunkZ();

    boolean visible();

    TrackedChunkSection setVisible(boolean visible);

    int lastChecked();

    TrackedChunkSection setLastChecked(int lastChecked);

    /** Packed face-to-face VisGraph connectivity for this section. */
    long visConnectivity();

    TrackedChunkSection setVisConnectivity(long connectivity);

    boolean visConnectivityDirty();

    TrackedChunkSection markVisConnectivityDirty();

    /**
     * Tick when continuous hide desire began, or {@link #NOT_WANTING_HIDE}.
     */
    int wantHiddenSinceTick();

    TrackedChunkSection setWantHiddenSinceTick(int tick);
}
