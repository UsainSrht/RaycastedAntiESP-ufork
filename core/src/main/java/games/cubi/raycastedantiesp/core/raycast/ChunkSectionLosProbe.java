package games.cubi.raycastedantiesp.core.raycast;

import games.cubi.locatables.api.Locatable;
import games.cubi.locatables.implementations.ImmutableSpatialImpl;
import games.cubi.raycastedantiesp.core.config.raycast.ChunkSectionConfig;
import games.cubi.raycastedantiesp.core.tracked.TrackedChunkSection;
import games.cubi.raycastedantiesp.core.view.BlockView;
import games.cubi.raycastedantiesp.core.view.chunks.ChunkSectionStore;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** Shared section LOS evaluation / focused debug paint (engine + test command). */
public final class ChunkSectionLosProbe {
    private ChunkSectionLosProbe() {}

    public record Decision(
            int chunkX,
            int sectionY,
            int chunkZ,
            boolean wouldShow,
            String reason,
            boolean trackedPresent,
            boolean trackedVisible,
            boolean eyeInside,
            boolean alwaysShow,
            boolean withinRaycastRadius,
            boolean fullyOpen,
            /** True when outside VisGraph frontier — hard HIDE without raycast. */
            boolean visGraphAutoHide,
            /** Diagnostic: unreachable and below always-show-down band. */
            boolean visGraphUnreachableBelow,
            boolean raycastLos
    ) {}

    /**
     * Connectivity for VisGraph BFS: untracked / empty-air sections are pass-through ({@link SectionVisGraph#FULLY_OPEN}).
     */
    public static long connectivityOrAir(BlockView blockView, UUID world, long packedKey) {
        TrackedChunkSection tracked = blockView.getTrackedChunkSection(
                world,
                ChunkSectionStore.unpackChunkX(packedKey),
                ChunkSectionStore.unpackSectionY(packedKey),
                ChunkSectionStore.unpackChunkZ(packedKey)
        );
        return tracked == null ? SectionVisGraph.FULLY_OPEN : tracked.visConnectivity();
    }

    public static long connectivityOrAir(TrackedChunkSection tracked) {
        return tracked == null ? SectionVisGraph.FULLY_OPEN : tracked.visConnectivity();
    }

    /** Builds VisGraph reachable set from the eye (untracked air = {@link SectionVisGraph#FULLY_OPEN}). */
    public static void collectReachableFromEye(
            Locatable eye,
            int raycastRadiusChunks,
            BlockView blockView,
            LongOpenHashSet outReachable
    ) {
        UUID world = eye.world();
        SectionVisGraph.collectReachable(
                eye.blockX() >> 4,
                eye.blockY() >> 4,
                eye.blockZ() >> 4,
                raycastRadiusChunks,
                key -> connectivityOrAir(blockView, world, key),
                outReachable
        );
    }

    /** Reachable ∪ face-adjacent shell within raycast radius. */
    public static void collectFrontierFromEye(
            Locatable eye,
            int raycastRadiusChunks,
            BlockView blockView,
            LongOpenHashSet outReachable,
            LongOpenHashSet outFrontier
    ) {
        collectReachableFromEye(eye, raycastRadiusChunks, blockView, outReachable);
        SectionVisGraph.collectFrontier(
                eye.blockX() >> 4,
                eye.blockY() >> 4,
                eye.blockZ() >> 4,
                raycastRadiusChunks,
                outReachable,
                outFrontier
        );
    }

    public static Decision evaluate(Locatable eye, int blockX, int blockY, int blockZ, ChunkSectionConfig config, BlockView blockView) {
        return evaluateSection(eye, blockX >> 4, blockY >> 4, blockZ >> 4, config, blockView);
    }

    public static Decision evaluateSection(Locatable eye, int chunkX, int sectionY, int chunkZ, ChunkSectionConfig config, BlockView blockView) {
        LongOpenHashSet reachable = new LongOpenHashSet();
        LongOpenHashSet frontier = new LongOpenHashSet();
        collectFrontierFromEye(eye, config.raycastRadiusChunks(), blockView, reachable, frontier);
        return evaluateSection(eye, chunkX, sectionY, chunkZ, config, blockView, reachable, frontier);
    }

    /**
     * Same as {@link #evaluateSection(Locatable, int, int, int, ChunkSectionConfig, BlockView)} but reuses
     * precomputed VisGraph sets (engine / status report: one BFS per viewer tick).
     */
    public static Decision evaluateSection(
            Locatable eye,
            int chunkX,
            int sectionY,
            int chunkZ,
            ChunkSectionConfig config,
            BlockView blockView,
            LongOpenHashSet reachable,
            LongOpenHashSet frontier
    ) {
        UUID world = eye.world();
        int viewerChunkX = eye.blockX() >> 4;
        int viewerSectionY = eye.blockY() >> 4;
        int viewerChunkZ = eye.blockZ() >> 4;
        int raycastRadius = config.raycastRadiusChunks();
        int alwaysShowRadius = config.alwaysShowRadiusChunks();
        int alwaysShowVerticalDown = config.alwaysShowVerticalDown();
        int alwaysShowVerticalUp = config.alwaysShowVerticalUp();

        TrackedChunkSection tracked = blockView.getTrackedChunkSection(world, chunkX, sectionY, chunkZ);
        boolean trackedPresent = tracked != null;
        boolean trackedVisible = trackedPresent && tracked.visible();

        boolean eyeInside = ChunkSectionVisibilityUtil.isEyeInsideSection(eye.x(), eye.y(), eye.z(), chunkX, sectionY, chunkZ);
        boolean alwaysShow = ChunkSectionVisibilityUtil.isWithinAlwaysShow(
                viewerChunkX, viewerSectionY, viewerChunkZ,
                chunkX, sectionY, chunkZ,
                alwaysShowRadius, alwaysShowVerticalDown, alwaysShowVerticalUp
        );
        int chebyshev = ChunkSectionVisibilityUtil.chebyshevSectionDistance(
                viewerChunkX, viewerSectionY, viewerChunkZ, chunkX, sectionY, chunkZ
        );
        boolean withinRaycastRadius = chebyshev <= raycastRadius;

        if (eyeInside) {
            return new Decision(chunkX, sectionY, chunkZ, true, "eye inside section", trackedPresent, trackedVisible,
                    true, alwaysShow, withinRaycastRadius, false, false, false, true);
        }
        if (alwaysShow) {
            return new Decision(chunkX, sectionY, chunkZ, true, "always-show band", trackedPresent, trackedVisible,
                    false, true, withinRaycastRadius, false, false, false, true);
        }
        if (!withinRaycastRadius) {
            return new Decision(chunkX, sectionY, chunkZ, false, "outside raycast-radius-chunks", trackedPresent, trackedVisible,
                    false, false, false, false, false, false, false);
        }

        long connectivity = connectivityOrAir(tracked);
        boolean fullyOpen = connectivity == SectionVisGraph.FULLY_OPEN;
        long key = ChunkSectionStore.packChunkCoords(chunkX, sectionY, chunkZ);
        boolean inFrontier = frontier.contains(key);
        boolean visGraphUnreachableBelow = !reachable.contains(key)
                && SectionVisGraph.hasAnyOpenFace(connectivity)
                && sectionY < viewerSectionY - alwaysShowVerticalDown;

        if (!inFrontier) {
            String reason = "VisGraph auto-hide (outside frontier)";
            if (visGraphUnreachableBelow) {
                reason += " (also unreachable-below)";
            }
            return new Decision(
                    chunkX, sectionY, chunkZ,
                    false,
                    reason,
                    trackedPresent, trackedVisible,
                    false, false, true, fullyOpen, true, visGraphUnreachableBelow, false
            );
        }

        boolean raycastLos = canSeeByFacingFaces(
                eye, chunkX, sectionY, chunkZ,
                config.maxOccludingCount(),
                raycastRadius * 16,
                blockView
        );
        String reason = raycastLos ? "facing-face raycast LOS" : "facing-face raycast blocked";
        if (fullyOpen) {
            // Face graph is complete (air touches all sides) — common on open surface, not "empty".
            reason += " (VisGraph fully-open face graph)";
        }
        if (visGraphUnreachableBelow) {
            reason += " (VisGraph unreachable-below but in frontier shell)";
        }
        return new Decision(
                chunkX, sectionY, chunkZ,
                raycastLos,
                reason,
                trackedPresent, trackedVisible,
                false, false, true, fullyOpen, false, visGraphUnreachableBelow, raycastLos
        );
    }

    public static boolean canSeeByFacingFaces(
            Locatable eye,
            int chunkX,
            int sectionY,
            int chunkZ,
            int maxOccludingCount,
            int maxRaycastRadiusBlocks,
            BlockView blockView
    ) {
        if (ChunkSectionVisibilityUtil.isEyeInsideSection(eye.x(), eye.y(), eye.z(), chunkX, sectionY, chunkZ)) {
            return true;
        }
        List<ImmutableSpatialImpl> samples = ChunkSectionVisibilityUtil.sampleFaceCentersForFacingFaces(
                eye.x(), eye.y(), eye.z(), chunkX, sectionY, chunkZ
        );
        if (anySectionEntryRay(eye, chunkX, sectionY, chunkZ, samples, maxOccludingCount, maxRaycastRadiusBlocks, blockView)) {
            return true;
        }
        // Closest point on each facing face — critical for partially exposed rock faces.
        samples = ChunkSectionVisibilityUtil.sampleClosestPointsOnFacingFaces(
                eye.x(), eye.y(), eye.z(), chunkX, sectionY, chunkZ
        );
        if (anySectionEntryRay(eye, chunkX, sectionY, chunkZ, samples, maxOccludingCount, maxRaycastRadiusBlocks, blockView)) {
            return true;
        }
        samples = ChunkSectionVisibilityUtil.samplePointsForFacingFaces(
                eye.x(), eye.y(), eye.z(), chunkX, sectionY, chunkZ
        );
        return anySectionEntryRay(eye, chunkX, sectionY, chunkZ, samples, maxOccludingCount, maxRaycastRadiusBlocks, blockView);
    }

    /**
     * Checks if all line-of-sight sample rays targeting section (chunkX, sectionY, chunkZ)
     * traverse through at least one known occluded/solid section in occludedSections.
     */
    public static boolean isSectionOccludedByShadow(
            Locatable eye,
            int chunkX,
            int sectionY,
            int chunkZ,
            LongOpenHashSet occludedSections
    ) {
        if (occludedSections == null || occludedSections.isEmpty()) {
            return false;
        }
        int eyeChunkX = eye.blockX() >> 4;
        int eyeSectionY = eye.blockY() >> 4;
        int eyeChunkZ = eye.blockZ() >> 4;
        if (eyeChunkX == chunkX && eyeSectionY == sectionY && eyeChunkZ == chunkZ) {
            return false;
        }

        List<ImmutableSpatialImpl> samples = ChunkSectionVisibilityUtil.sampleFaceCentersForFacingFaces(
                eye.x(), eye.y(), eye.z(), chunkX, sectionY, chunkZ
        );
        if (samples.isEmpty()) {
            return false;
        }

        for (ImmutableSpatialImpl sample : samples) {
            if (!rayTraversesOccludedSection(eye.x(), eye.y(), eye.z(), eyeChunkX, eyeSectionY, eyeChunkZ,
                    sample.x(), sample.y(), sample.z(), chunkX, sectionY, chunkZ, occludedSections)) {
                return false;
            }
        }
        return true;
    }

    private static boolean rayTraversesOccludedSection(
            double eyeX, double eyeY, double eyeZ,
            int eyeChunkX, int eyeSectionY, int eyeChunkZ,
            double targetX, double targetY, double targetZ,
            int targetChunkX, int targetSectionY, int targetChunkZ,
            LongOpenHashSet occludedSections
    ) {
        double dx = targetX - eyeX;
        double dy = targetY - eyeY;
        double dz = targetZ - eyeZ;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq < 1e-4) {
            return false;
        }
        double dist = Math.sqrt(distSq);
        int steps = Math.max(1, (int) Math.ceil(dist / 8.0));
        double stepX = dx / steps;
        double stepY = dy / steps;
        double stepZ = dz / steps;

        double curX = eyeX + stepX;
        double curY = eyeY + stepY;
        double curZ = eyeZ + stepZ;

        for (int i = 1; i < steps; i++) {
            int sx = (int) Math.floor(curX) >> 4;
            int sy = (int) Math.floor(curY) >> 4;
            int sz = (int) Math.floor(curZ) >> 4;

            if (sx == targetChunkX && sy == targetSectionY && sz == targetChunkZ) {
                break;
            }
            if (sx == eyeChunkX && sy == eyeSectionY && sz == eyeChunkZ) {
                curX += stepX;
                curY += stepY;
                curZ += stepZ;
                continue;
            }

            long key = ChunkSectionStore.packChunkCoords(sx, sy, sz);
            if (occludedSections.contains(key)) {
                return true;
            }

            curX += stepX;
            curY += stepY;
            curZ += stepZ;
        }
        return false;
    }

    /**
     * Paints LOS sample rays for one section (centers + closest-on-face). Successful rays use SHOW colours.
     */
    public static void paintSectionDebugRays(
            Locatable eye,
            int chunkX,
            int sectionY,
            int chunkZ,
            int maxOccludingCount,
            int maxRaycastRadiusBlocks,
            BlockView blockView,
            ParticleSpawner particleSpawner,
            UUID viewer,
            int clearStride
    ) {
        LinkedHashSet<ImmutableSpatialImpl> samples = new LinkedHashSet<>();
        samples.addAll(ChunkSectionVisibilityUtil.sampleFaceCentersForFacingFaces(
                eye.x(), eye.y(), eye.z(), chunkX, sectionY, chunkZ
        ));
        samples.addAll(ChunkSectionVisibilityUtil.sampleClosestPointsOnFacingFaces(
                eye.x(), eye.y(), eye.z(), chunkX, sectionY, chunkZ
        ));
        if (samples.isEmpty()) {
            return;
        }
        for (ImmutableSpatialImpl sample : samples) {
            boolean ok = RaycastUtil.raycastUntilSectionEntry(
                    eye, sample, chunkX, sectionY, chunkZ,
                    maxOccludingCount, 0, maxRaycastRadiusBlocks,
                    false, blockView, 1, particleSpawner
            );
            ParticleSpawner.Colour sampleColour = ok
                    ? ParticleSpawner.Colour.SECTION_SHOW_SAMPLE
                    : ParticleSpawner.Colour.SECTION_HIDE_SAMPLE;
            ParticleSpawner.Colour stepColour = ok
                    ? ParticleSpawner.Colour.SECTION_SHOW_STEP
                    : ParticleSpawner.Colour.SECTION_HIDE_STEP;
            ParticleSpawner.Colour occluderColour = ok
                    ? ParticleSpawner.Colour.SECTION_SHOW_OCCLUDER
                    : ParticleSpawner.Colour.SECTION_HIDE_OCCLUDER;
            particleSpawner.spawnParticleAt(eye.world(), sample, sampleColour, viewer);
            RaycastUtil.raycastUntilSectionEntry(
                    eye, sample, chunkX, sectionY, chunkZ,
                    maxOccludingCount, 0, maxRaycastRadiusBlocks,
                    true, blockView, 1, particleSpawner, viewer,
                    stepColour, occluderColour, ParticleSpawner.Colour.SECTION_SHOW_ENTRY,
                    clearStride
            );
        }
    }

    private static boolean anySectionEntryRay(
            Locatable eye,
            int chunkX,
            int sectionY,
            int chunkZ,
            List<ImmutableSpatialImpl> samples,
            int maxOccludingCount,
            int maxRaycastRadiusBlocks,
            BlockView blockView
    ) {
        for (ImmutableSpatialImpl sample : samples) {
            if (RaycastUtil.raycastUntilSectionEntry(
                    eye, sample, chunkX, sectionY, chunkZ,
                    maxOccludingCount, 0, maxRaycastRadiusBlocks,
                    false, blockView, 1, null
            )) {
                return true;
            }
        }
        return false;
    }
}
