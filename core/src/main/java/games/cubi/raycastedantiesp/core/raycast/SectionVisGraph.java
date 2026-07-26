package games.cubi.raycastedantiesp.core.raycast;

import games.cubi.raycastedantiesp.core.chunks.ChunkData;
import games.cubi.raycastedantiesp.core.chunks.ChunkOcclusionView;
import games.cubi.raycastedantiesp.core.view.chunks.ChunkSectionStore;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongFunction;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Face-to-face air connectivity within a 16³ section.
 * Packed as 36 bits: bit (fromFace * 6 + toFace) means a non-occluding path connects those faces.
 */
public final class SectionVisGraph {
    public static final long FULLY_OPEN = computeFullyOpenMask();
    public static final long SOLID = 0L;
    private static final int ALL_FACES = (1 << SectionFace.COUNT) - 1;

    private SectionVisGraph() {}

    /**
     * Single multi-source flood fill over non-occluding cells (much cheaper than 6 independent BFS passes).
     */
    public static long computeConnectivity(ChunkOcclusionView section) {
        if (section == null) {
            return SOLID;
        }
        boolean[] visited = new boolean[ChunkData.BLOCK_COUNT];
        int[] queue = new int[ChunkData.BLOCK_COUNT];
        long mask = 0L;

        for (int packedStart = 0; packedStart < ChunkData.BLOCK_COUNT; packedStart++) {
            if (visited[packedStart]) {
                continue;
            }
            int sx = ChunkData.unpackX(packedStart);
            int sy = ChunkData.unpackY(packedStart);
            int sz = ChunkData.unpackZ(packedStart);
            if (section.isOccludingLocal(sx, sy, sz)) {
                visited[packedStart] = true;
                continue;
            }

            int head = 0;
            int tail = 0;
            visited[packedStart] = true;
            queue[tail++] = packedStart;
            int reachedFaces = 0;

            while (head < tail) {
                int packed = queue[head++];
                int x = ChunkData.unpackX(packed);
                int y = ChunkData.unpackY(packed);
                int z = ChunkData.unpackZ(packed);
                reachedFaces |= facesTouching(x, y, z);
                tail = enqueueIfOpen(section, visited, queue, tail, x - 1, y, z);
                tail = enqueueIfOpen(section, visited, queue, tail, x + 1, y, z);
                tail = enqueueIfOpen(section, visited, queue, tail, x, y - 1, z);
                tail = enqueueIfOpen(section, visited, queue, tail, x, y + 1, z);
                tail = enqueueIfOpen(section, visited, queue, tail, x, y, z - 1);
                tail = enqueueIfOpen(section, visited, queue, tail, x, y, z + 1);
            }

            if (reachedFaces == 0) {
                continue;
            }
            for (int from = 0; from < SectionFace.COUNT; from++) {
                if ((reachedFaces & (1 << from)) == 0) {
                    continue;
                }
                for (int to = 0; to < SectionFace.COUNT; to++) {
                    if ((reachedFaces & (1 << to)) != 0) {
                        mask |= 1L << (from * SectionFace.COUNT + to);
                    }
                }
            }
        }
        return mask;
    }

    public static boolean connects(long connectivity, SectionFace from, SectionFace to) {
        return (connectivity & (1L << (from.index() * SectionFace.COUNT + to.index()))) != 0L;
    }

    public static boolean isFaceOpen(long connectivity, SectionFace face) {
        return connects(connectivity, face, face);
    }

    /** True when any section face has a non-occluding cell (air can touch that side). */
    public static boolean hasAnyOpenFace(long connectivity) {
        for (int face = 0; face < SectionFace.COUNT; face++) {
            if (isFaceOpen(connectivity, SectionFace.byIndex(face))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether traversal can leave through {@code exitFace} when the path has access to any of
     * {@code reachableEntryFaces}.
     */
    public static boolean canExit(long connectivity, int reachableEntryFaces, SectionFace exitFace) {
        if (reachableEntryFaces == 0) {
            return false;
        }
        for (int from = 0; from < SectionFace.COUNT; from++) {
            if ((reachableEntryFaces & (1 << from)) == 0) {
                continue;
            }
            if (connects(connectivity, SectionFace.byIndex(from), exitFace)) {
                return true;
            }
        }
        return false;
    }

    /**
     * BFS through adjacent sections using cached face connectivity. Used as a HIDE-only filter for
     * unreachable <em>air</em> volumes. Solid shells stay candidates for face sampling.
     *
     * @param connectivityOf packed section key → connectivity mask ({@link #SOLID} if unknown)
     * @param outReachable   cleared then filled with packed section keys
     */
    public static void collectReachable(
            int eyeChunkX,
            int eyeSectionY,
            int eyeChunkZ,
            int maxChebyshevRadius,
            Long2LongFunction connectivityOf,
            LongOpenHashSet outReachable
    ) {
        outReachable.clear();
        long startKey = ChunkSectionStore.packChunkCoords(eyeChunkX, eyeSectionY, eyeChunkZ);
        outReachable.add(startKey);

        LongArrayList queueKeys = new LongArrayList();
        LongArrayList queueEntryFaces = new LongArrayList();
        queueKeys.add(startKey);
        queueEntryFaces.add(ALL_FACES);

        Long2IntOpenHashMap bestEntry = new Long2IntOpenHashMap();
        bestEntry.defaultReturnValue(0);
        bestEntry.put(startKey, ALL_FACES);

        for (int qi = 0; qi < queueKeys.size(); qi++) {
            long key = queueKeys.getLong(qi);
            int entryFaces = (int) queueEntryFaces.getLong(qi);
            int cx = ChunkSectionStore.unpackChunkX(key);
            int sy = ChunkSectionStore.unpackSectionY(key);
            int cz = ChunkSectionStore.unpackChunkZ(key);
            long connectivity = connectivityOf.applyAsLong(key);

            for (SectionFace exit : SectionFace.values()) {
                if (!canExit(connectivity, entryFaces, exit)) {
                    continue;
                }
                int nx = cx + exit.nx();
                int ny = sy + exit.ny();
                int nz = cz + exit.nz();
                if (ChunkSectionVisibilityUtil.chebyshevSectionDistance(eyeChunkX, eyeSectionY, eyeChunkZ, nx, ny, nz)
                        > maxChebyshevRadius) {
                    continue;
                }
                long nKey = ChunkSectionStore.packChunkCoords(nx, ny, nz);
                long nConn = connectivityOf.applyAsLong(nKey);
                SectionFace enter = exit.opposite();
                if (!isFaceOpen(nConn, enter)) {
                    continue;
                }
                int newEntry = 0;
                for (int to = 0; to < SectionFace.COUNT; to++) {
                    if (connects(nConn, enter, SectionFace.byIndex(to))) {
                        newEntry |= 1 << to;
                    }
                }
                if (newEntry == 0) {
                    continue;
                }
                int previous = bestEntry.get(nKey);
                int merged = previous | newEntry;
                if (previous == merged) {
                    continue;
                }
                bestEntry.put(nKey, merged);
                outReachable.add(nKey);
                queueKeys.add(nKey);
                queueEntryFaces.add(merged);
            }
        }
    }

    private static int enqueueIfOpen(ChunkOcclusionView section, boolean[] visited, int[] queue, int tail, int x, int y, int z) {
        if (x < 0 || x > 15 || y < 0 || y > 15 || z < 0 || z > 15) {
            return tail;
        }
        if (section.isOccludingLocal(x, y, z)) {
            return tail;
        }
        int packed = ChunkData.packUncheckedGuarded(x, y, z);
        if (visited[packed]) {
            return tail;
        }
        visited[packed] = true;
        queue[tail] = packed;
        return tail + 1;
    }

    private static int facesTouching(int x, int y, int z) {
        int faces = 0;
        if (x == 0) {
            faces |= 1 << SectionFace.WEST.index();
        }
        if (x == 15) {
            faces |= 1 << SectionFace.EAST.index();
        }
        if (y == 0) {
            faces |= 1 << SectionFace.DOWN.index();
        }
        if (y == 15) {
            faces |= 1 << SectionFace.UP.index();
        }
        if (z == 0) {
            faces |= 1 << SectionFace.NORTH.index();
        }
        if (z == 15) {
            faces |= 1 << SectionFace.SOUTH.index();
        }
        return faces;
    }

    private static long computeFullyOpenMask() {
        long mask = 0L;
        for (int from = 0; from < SectionFace.COUNT; from++) {
            for (int to = 0; to < SectionFace.COUNT; to++) {
                mask |= 1L << (from * SectionFace.COUNT + to);
            }
        }
        return mask;
    }
}
