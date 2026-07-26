package games.cubi.raycastedantiesp.core.tracked;

import games.cubi.raycastedantiesp.core.raycast.SectionVisGraph;
import games.cubi.raycastedantiesp.core.utils.VarHandler;

import java.lang.invoke.VarHandle;

/** Section visibility and lifecycle are shared with the engine. */
public final class NettyChunkSection implements TrackedChunkSection {
    private static final int REMOVED = Integer.MIN_VALUE + 11;

    private volatile boolean visible;
    private volatile int lastChecked; private static final VarHandle LAST_CHECKED = VarHandler.get(NettyChunkSection.class, "lastChecked", int.class);
    private volatile long visConnectivity = SectionVisGraph.SOLID;
    private volatile boolean visConnectivityDirty = true;
    private volatile int wantHiddenSinceTick = NOT_WANTING_HIDE;

    private final int chunkX;
    private final int sectionY;
    private final int chunkZ;

    public NettyChunkSection(int chunkX, int sectionY, int chunkZ, boolean visible, int lastChecked) {
        this.chunkX = chunkX;
        this.sectionY = sectionY;
        this.chunkZ = chunkZ;
        this.visible = visible;
        LAST_CHECKED.set(this, lastChecked);
    }

    public NettyChunkSection(int chunkX, int sectionY, int chunkZ, boolean visible) {
        this(chunkX, sectionY, chunkZ, visible, NEVER_CHECKED);
    }

    @Override
    public int chunkX() {
        return chunkX;
    }

    @Override
    public int sectionY() {
        return sectionY;
    }

    @Override
    public int chunkZ() {
        return chunkZ;
    }

    @Override
    public boolean visible() {
        return visible;
    }

    @Override
    public TrackedChunkSection setVisible(boolean visible) {
        this.visible = visible;
        return this;
    }

    @Override
    public int lastChecked() {
        return (int) LAST_CHECKED.getAcquire(this);
    }

    @Override
    public TrackedChunkSection setLastChecked(int checkedTick) {
        int current = (int) LAST_CHECKED.getAcquire(this);
        while (current != REMOVED) {
            if (LAST_CHECKED.weakCompareAndSetRelease(this, current, checkedTick)) {
                break;
            }
            current = (int) LAST_CHECKED.getAcquire(this);
        }
        return this;
    }

    @Override
    public long visConnectivity() {
        return visConnectivity;
    }

    @Override
    public TrackedChunkSection setVisConnectivity(long connectivity) {
        this.visConnectivity = connectivity;
        this.visConnectivityDirty = false;
        return this;
    }

    @Override
    public boolean visConnectivityDirty() {
        return visConnectivityDirty;
    }

    @Override
    public TrackedChunkSection markVisConnectivityDirty() {
        this.visConnectivityDirty = true;
        return this;
    }

    @Override
    public int wantHiddenSinceTick() {
        return wantHiddenSinceTick;
    }

    @Override
    public TrackedChunkSection setWantHiddenSinceTick(int tick) {
        this.wantHiddenSinceTick = tick;
        return this;
    }

    /** Marks this allocation as detached. Removed nodes are never reused. */
    public void markRemoved() {
        LAST_CHECKED.setRelease(this, REMOVED);
    }

    public boolean isRemoved() {
        return (int) LAST_CHECKED.getAcquire(this) == REMOVED;
    }
}
