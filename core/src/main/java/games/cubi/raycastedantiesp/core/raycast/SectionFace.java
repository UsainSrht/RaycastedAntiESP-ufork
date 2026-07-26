package games.cubi.raycastedantiesp.core.raycast;

/** Faces of a 16³ chunk section, indexed for VisGraph bit packing. */
public enum SectionFace {
    WEST(0, -1, 0, 0),
    EAST(1, 1, 0, 0),
    DOWN(2, 0, -1, 0),
    UP(3, 0, 1, 0),
    NORTH(4, 0, 0, -1),
    SOUTH(5, 0, 0, 1);

    public static final int COUNT = 6;

    private final int index;
    private final int nx;
    private final int ny;
    private final int nz;

    SectionFace(int index, int nx, int ny, int nz) {
        this.index = index;
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
    }

    public int index() {
        return index;
    }

    public int nx() {
        return nx;
    }

    public int ny() {
        return ny;
    }

    public int nz() {
        return nz;
    }

    public SectionFace opposite() {
        return switch (this) {
            case WEST -> EAST;
            case EAST -> WEST;
            case DOWN -> UP;
            case UP -> DOWN;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
        };
    }

    public static SectionFace byIndex(int index) {
        return VALUES[index];
    }

    private static final SectionFace[] VALUES = values();
}
