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
     * Always-show is horizontal Chebyshev on XZ with an asymmetric vertical band:
     * small downward (protect caves) and large upward (hilltops / tree canopy).
     */
    public static boolean isWithinAlwaysShow(int viewerChunkX, int viewerSectionY, int viewerChunkZ,
                                            int chunkX, int sectionY, int chunkZ,
                                            int alwaysShowRadiusChunks,
                                            int alwaysShowVerticalDown,
                                            int alwaysShowVerticalUp) {
        int horizontal = Math.max(Math.abs(viewerChunkX - chunkX), Math.abs(viewerChunkZ - chunkZ));
        if (horizontal > alwaysShowRadiusChunks) {
            return false;
        }
        int dy = sectionY - viewerSectionY;
        return dy >= -Math.max(0, alwaysShowVerticalDown) && dy <= Math.max(0, alwaysShowVerticalUp);
    }

    /**
     * @deprecated prefer the asymmetric overload
     */
    @Deprecated
    public static boolean isWithinAlwaysShow(int viewerChunkX, int viewerSectionY, int viewerChunkZ,
                                            int chunkX, int sectionY, int chunkZ,
                                            int alwaysShowRadiusChunks, int alwaysShowVerticalSections) {
        return isWithinAlwaysShow(
                viewerChunkX, viewerSectionY, viewerChunkZ,
                chunkX, sectionY, chunkZ,
                alwaysShowRadiusChunks, alwaysShowVerticalSections, alwaysShowVerticalSections
        );
    }

    /**
     * Faces of the section AABB that can face the eye (at most 3 when the eye is outside).
     * When the eye is inside, returns an empty list (caller should treat as visible).
     * <p>
     * When the eye is directly below the section, returns all four vertical faces instead of only
     * {@link SectionFace#DOWN} — a center ray to DOWN travels through the solid column beneath and
     * false-hides surface tops.
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

        boolean west = eyeX < minX;
        boolean east = eyeX >= maxX;
        boolean north = eyeZ < minZ;
        boolean south = eyeZ >= maxZ;
        boolean below = eyeY < minY;
        boolean above = eyeY >= maxY;
        boolean hasHorizontal = west || east || north || south;

        if (below && !hasHorizontal) {
            return List.of(SectionFace.WEST, SectionFace.EAST, SectionFace.NORTH, SectionFace.SOUTH);
        }

        List<SectionFace> faces = new ArrayList<>(3);
        if (west) {
            faces.add(SectionFace.WEST);
        } else if (east) {
            faces.add(SectionFace.EAST);
        }
        if (below) {
            // Prefer vertical silhouette faces; keep DOWN only as a secondary when already offset in XZ.
            if (hasHorizontal) {
                // Horizontal faces already capture the silhouette; skip DOWN-through-column.
            } else {
                faces.add(SectionFace.DOWN);
            }
        } else if (above) {
            faces.add(SectionFace.UP);
        }
        if (north) {
            faces.add(SectionFace.NORTH);
        } else if (south) {
            faces.add(SectionFace.SOUTH);
        }
        return faces;
    }

    /**
     * Face-center samples. When the eye is below the section, samples sit on the lower rim of vertical
     * faces so rays do not travel through the solid column under the target.
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
        boolean below = eyeY < sectionMinBlock(sectionY);

        Set<ImmutableSpatialImpl> samples = new LinkedHashSet<>();
        for (SectionFace face : faces) {
            switch (face) {
                case WEST -> {
                    samples.add(new ImmutableSpatialImpl(x0, below ? y0 : cy, cz));
                    if (below) {
                        samples.add(new ImmutableSpatialImpl(x0, cy, cz));
                    }
                }
                case EAST -> {
                    samples.add(new ImmutableSpatialImpl(x1, below ? y0 : cy, cz));
                    if (below) {
                        samples.add(new ImmutableSpatialImpl(x1, cy, cz));
                    }
                }
                case DOWN -> samples.add(new ImmutableSpatialImpl(cx, y0, cz));
                case UP -> samples.add(new ImmutableSpatialImpl(cx, y1, cz));
                case NORTH -> {
                    samples.add(new ImmutableSpatialImpl(cx, below ? y0 : cy, z0));
                    if (below) {
                        samples.add(new ImmutableSpatialImpl(cx, cy, z0));
                    }
                }
                case SOUTH -> {
                    samples.add(new ImmutableSpatialImpl(cx, below ? y0 : cy, z1));
                    if (below) {
                        samples.add(new ImmutableSpatialImpl(cx, cy, z1));
                    }
                }
            }
        }
        return List.copyOf(samples);
    }

    /**
     * For each facing face, the point on that face closest to the eye (clamped to the face).
     * Catches side-surface LOS that face-centers/corners miss (e.g. a visible patch offset on a rock face).
     */
    public static List<ImmutableSpatialImpl> sampleClosestPointsOnFacingFaces(
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

        Set<ImmutableSpatialImpl> samples = new LinkedHashSet<>();
        for (SectionFace face : faces) {
            switch (face) {
                case WEST -> samples.add(new ImmutableSpatialImpl(x0, clamp(eyeY, y0, y1), clamp(eyeZ, z0, z1)));
                case EAST -> samples.add(new ImmutableSpatialImpl(x1, clamp(eyeY, y0, y1), clamp(eyeZ, z0, z1)));
                case DOWN -> samples.add(new ImmutableSpatialImpl(clamp(eyeX, x0, x1), y0, clamp(eyeZ, z0, z1)));
                case UP -> samples.add(new ImmutableSpatialImpl(clamp(eyeX, x0, x1), y1, clamp(eyeZ, z0, z1)));
                case NORTH -> samples.add(new ImmutableSpatialImpl(clamp(eyeX, x0, x1), clamp(eyeY, y0, y1), z0));
                case SOUTH -> samples.add(new ImmutableSpatialImpl(clamp(eyeX, x0, x1), clamp(eyeY, y0, y1), z1));
            }
        }
        return List.copyOf(samples);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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
