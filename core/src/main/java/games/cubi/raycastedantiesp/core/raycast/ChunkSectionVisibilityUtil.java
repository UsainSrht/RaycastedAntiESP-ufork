package games.cubi.raycastedantiesp.core.raycast;

import games.cubi.locatables.api.Spatial;
import games.cubi.locatables.implementations.ImmutableSpatialImpl;
import games.cubi.raycastedantiesp.core.chunks.ChunkData;
import games.cubi.raycastedantiesp.core.tracked.TrackedChunkSection;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Geometry helpers for per-viewer 16³ section visibility. */
public final class ChunkSectionVisibilityUtil {
    private ChunkSectionVisibilityUtil() {}

    public static int sectionMinBlock(int sectionCoord) {
        return sectionCoord << 4;
    }

    public static boolean isEyeInsideSection(double eyeX, double eyeY, double eyeZ, int chunkX, int sectionY, int chunkZ) {
        int minX = sectionMinBlock(chunkX);
        int minY = sectionMinBlock(sectionY);
        int minZ = sectionMinBlock(chunkZ);
        int maxX = minX + ChunkData.CHUNK_SIZE;
        int maxY = minY + ChunkData.CHUNK_SIZE;
        int maxZ = minZ + ChunkData.CHUNK_SIZE;
        return eyeX >= minX && eyeX < maxX
                && eyeY >= minY && eyeY < maxY
                && eyeZ >= minZ && eyeZ < maxZ;
    }

    public static boolean isEyeInsideSection(Spatial eye, TrackedChunkSection section) {
        return isEyeInsideSection(eye.x(), eye.y(), eye.z(), section.chunkX(), section.sectionY(), section.chunkZ());
    }

    public static boolean isBlockInSection(int blockX, int blockY, int blockZ, int chunkX, int sectionY, int chunkZ) {
        return (blockX >> 4) == chunkX && (blockY >> 4) == sectionY && (blockZ >> 4) == chunkZ;
    }

    public static int chebyshevSectionDistance(int ax, int ay, int az, int bx, int by, int bz) {
        return Math.max(Math.max(Math.abs(ax - bx), Math.abs(ay - by)), Math.abs(az - bz));
    }

    /**
     * Always-show is horizontal Chebyshev on XZ plus a small vertical band.
     * Full 3D Chebyshev force-shows entire cave stacks under the player (freecam leak).
     */
    public static boolean isWithinAlwaysShow(int viewerChunkX, int viewerSectionY, int viewerChunkZ,
                                            int chunkX, int sectionY, int chunkZ,
                                            int alwaysShowRadiusChunks, int alwaysShowVerticalSections) {
        int horizontal = Math.max(Math.abs(viewerChunkX - chunkX), Math.abs(viewerChunkZ - chunkZ));
        int vertical = Math.abs(viewerSectionY - sectionY);
        return horizontal <= alwaysShowRadiusChunks && vertical <= Math.max(0, alwaysShowVerticalSections);
    }

    /**
     * Faces of the section AABB that can face the eye (at most 3 when the eye is outside).
     * When the eye is inside, returns an empty list (caller should treat as visible).
     */
    public static List<SectionFace> facingFaces(double eyeX, double eyeY, double eyeZ, int chunkX, int sectionY, int chunkZ) {
        if (isEyeInsideSection(eyeX, eyeY, eyeZ, chunkX, sectionY, chunkZ)) {
            return List.of();
        }
        int minX = sectionMinBlock(chunkX);
        int minY = sectionMinBlock(sectionY);
        int minZ = sectionMinBlock(chunkZ);
        int maxX = minX + ChunkData.CHUNK_SIZE;
        int maxY = minY + ChunkData.CHUNK_SIZE;
        int maxZ = minZ + ChunkData.CHUNK_SIZE;
        List<SectionFace> faces = new ArrayList<>(3);
        if (eyeX < minX) {
            faces.add(SectionFace.WEST);
        } else if (eyeX >= maxX) {
            faces.add(SectionFace.EAST);
        }
        if (eyeY < minY) {
            faces.add(SectionFace.DOWN);
        } else if (eyeY >= maxY) {
            faces.add(SectionFace.UP);
        }
        if (eyeZ < minZ) {
            faces.add(SectionFace.NORTH);
        } else if (eyeZ >= maxZ) {
            faces.add(SectionFace.SOUTH);
        }
        return faces;
    }

    /**
     * Face-center samples only (at most 3). Hot path for section LOS — far cheaper than corners.
     */
    public static List<ImmutableSpatialImpl> sampleFaceCentersForFacingFaces(
            double eyeX, double eyeY, double eyeZ, int chunkX, int sectionY, int chunkZ) {
        List<SectionFace> faces = facingFaces(eyeX, eyeY, eyeZ, chunkX, sectionY, chunkZ);
        if (faces.isEmpty()) {
            return List.of();
        }
        double x0 = sectionMinBlock(chunkX) + 0.5;
        double y0 = sectionMinBlock(sectionY) + 0.5;
        double z0 = sectionMinBlock(chunkZ) + 0.5;
        double x1 = sectionMinBlock(chunkX) + ChunkData.CHUNK_SIZE - 0.5;
        double y1 = sectionMinBlock(sectionY) + ChunkData.CHUNK_SIZE - 0.5;
        double z1 = sectionMinBlock(chunkZ) + ChunkData.CHUNK_SIZE - 0.5;
        double cx = (x0 + x1) * 0.5;
        double cy = (y0 + y1) * 0.5;
        double cz = (z0 + z1) * 0.5;

        List<ImmutableSpatialImpl> samples = new ArrayList<>(faces.size());
        for (SectionFace face : faces) {
            samples.add(switch (face) {
                case WEST -> new ImmutableSpatialImpl(x0, cy, cz);
                case EAST -> new ImmutableSpatialImpl(x1, cy, cz);
                case DOWN -> new ImmutableSpatialImpl(cx, y0, cz);
                case UP -> new ImmutableSpatialImpl(cx, y1, cz);
                case NORTH -> new ImmutableSpatialImpl(cx, cy, z0);
                case SOUTH -> new ImmutableSpatialImpl(cx, cy, z1);
            });
        }
        return samples;
    }

    /**
     * Unique sample points (face center + 4 corners) for facing faces, in world space.
     */
    public static List<ImmutableSpatialImpl> samplePointsForFacingFaces(
            double eyeX, double eyeY, double eyeZ, int chunkX, int sectionY, int chunkZ) {
        List<SectionFace> faces = facingFaces(eyeX, eyeY, eyeZ, chunkX, sectionY, chunkZ);
        if (faces.isEmpty()) {
            return List.of();
        }
        double x0 = sectionMinBlock(chunkX) + 0.5;
        double y0 = sectionMinBlock(sectionY) + 0.5;
        double z0 = sectionMinBlock(chunkZ) + 0.5;
        double x1 = sectionMinBlock(chunkX) + ChunkData.CHUNK_SIZE - 0.5;
        double y1 = sectionMinBlock(sectionY) + ChunkData.CHUNK_SIZE - 0.5;
        double z1 = sectionMinBlock(chunkZ) + ChunkData.CHUNK_SIZE - 0.5;
        double cx = (x0 + x1) * 0.5;
        double cy = (y0 + y1) * 0.5;
        double cz = (z0 + z1) * 0.5;

        Set<ImmutableSpatialImpl> samples = new LinkedHashSet<>();
        for (SectionFace face : faces) {
            switch (face) {
                case WEST -> {
                    samples.add(new ImmutableSpatialImpl(x0, cy, cz));
                    samples.add(new ImmutableSpatialImpl(x0, y0, z0));
                    samples.add(new ImmutableSpatialImpl(x0, y0, z1));
                    samples.add(new ImmutableSpatialImpl(x0, y1, z0));
                    samples.add(new ImmutableSpatialImpl(x0, y1, z1));
                }
                case EAST -> {
                    samples.add(new ImmutableSpatialImpl(x1, cy, cz));
                    samples.add(new ImmutableSpatialImpl(x1, y0, z0));
                    samples.add(new ImmutableSpatialImpl(x1, y0, z1));
                    samples.add(new ImmutableSpatialImpl(x1, y1, z0));
                    samples.add(new ImmutableSpatialImpl(x1, y1, z1));
                }
                case DOWN -> {
                    samples.add(new ImmutableSpatialImpl(cx, y0, cz));
                    samples.add(new ImmutableSpatialImpl(x0, y0, z0));
                    samples.add(new ImmutableSpatialImpl(x0, y0, z1));
                    samples.add(new ImmutableSpatialImpl(x1, y0, z0));
                    samples.add(new ImmutableSpatialImpl(x1, y0, z1));
                }
                case UP -> {
                    samples.add(new ImmutableSpatialImpl(cx, y1, cz));
                    samples.add(new ImmutableSpatialImpl(x0, y1, z0));
                    samples.add(new ImmutableSpatialImpl(x0, y1, z1));
                    samples.add(new ImmutableSpatialImpl(x1, y1, z0));
                    samples.add(new ImmutableSpatialImpl(x1, y1, z1));
                }
                case NORTH -> {
                    samples.add(new ImmutableSpatialImpl(cx, cy, z0));
                    samples.add(new ImmutableSpatialImpl(x0, y0, z0));
                    samples.add(new ImmutableSpatialImpl(x0, y1, z0));
                    samples.add(new ImmutableSpatialImpl(x1, y0, z0));
                    samples.add(new ImmutableSpatialImpl(x1, y1, z0));
                }
                case SOUTH -> {
                    samples.add(new ImmutableSpatialImpl(cx, cy, z1));
                    samples.add(new ImmutableSpatialImpl(x0, y0, z1));
                    samples.add(new ImmutableSpatialImpl(x0, y1, z1));
                    samples.add(new ImmutableSpatialImpl(x1, y0, z1));
                    samples.add(new ImmutableSpatialImpl(x1, y1, z1));
                }
            }
        }
        return List.copyOf(samples);
    }

    /** True when continuous want-hidden has lasted at least {@code hideDelayTicks}. */
    public static boolean hideDelayElapsed(int wantHiddenSinceTick, int currentTick, int hideDelayTicks) {
        if (wantHiddenSinceTick == TrackedChunkSection.NOT_WANTING_HIDE
                || wantHiddenSinceTick == TrackedChunkSection.NEVER_CHECKED) {
            return false;
        }
        int delay = Math.max(0, hideDelayTicks);
        return currentTick - wantHiddenSinceTick >= delay;
    }
}
