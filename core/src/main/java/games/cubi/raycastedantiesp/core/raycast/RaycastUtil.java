package games.cubi.raycastedantiesp.core.raycast;

import games.cubi.locatables.api.Locatable;
import games.cubi.locatables.api.MutableFloatingSpatial;
import games.cubi.locatables.api.Spatial;
import games.cubi.locatables.implementations.MutableSpatialImpl;
import games.cubi.logs.Logger;
import games.cubi.raycastedantiesp.core.view.BlockView;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class RaycastUtil {

    //True: Has line-of-sight
    //This is deliberately a ray-stepping algorithm rather than DDA as it is much faster (2x in benchmarking)
    //Missing blocks is acceptable, as it will be assumed the player can see past those corners.
    //While this uses objects, JHM and in-game profiling have both shown that all objects used here are consistently scalarised by the JVM.
    public static boolean raycast(Locatable start, Spatial end, int maxOccluding, int alwaysShowRadius, int maxRaycastRadius, boolean debug, BlockView snap, int stepSize, ParticleSpawner particleSpawner) {
        MutableFloatingSpatial clonedEnd = end.cloneAndIfBlockThenCentre();
        double total = start.distance(clonedEnd) - stepSize; //benchmarking shows that calling distance() is faster than distanceSquared() then checking distanceSquared < stepSize*stepSize every time despite the latter replacing a square root with multiplication
        if (total <= alwaysShowRadius) return true;
        if (total > maxRaycastRadius) return false;
        if (debug && particleSpawner == null) {
            Logger.errorAndReturn(new RuntimeException("raycast called with debug enabled but no ParticleSpawner supplied"), 2, RaycastUtil.class);
        }

        Spatial dir = clonedEnd.subtract(start).normalise().scalarMultiply(stepSize);

        MutableFloatingSpatial current = new MutableSpatialImpl(start.x(),start.y(),start.z());

        for (double traveled = 0; traveled < total; traveled += stepSize) { //benchmarking shows that for loop is marginally faster than while loop initially (after running for a while they are equal
            current.add(dir);

            if (snap.isBlockOccluding(current.blockX(), current.blockY(), current.blockZ())) {
                maxOccluding--;
                if (debug) particleSpawner.spawnParticleAt(start.world(), current, ParticleSpawner.Colour.RED);
                if (maxOccluding < 1) return false;
                continue;
            }

            if (debug) particleSpawner.spawnParticleAt(start.world(), current, ParticleSpawner.Colour.GREEN);
        }
        return true;
    }

    /**
     * Raycasts toward {@code end}, succeeding as soon as a step enters the target section AABB.
     * Occluding blocks inside the target section are ignored (shell visibility, not buried-center).
     */
    public static boolean raycastUntilSectionEntry(
            Locatable start,
            Spatial end,
            int targetChunkX,
            int targetSectionY,
            int targetChunkZ,
            int maxOccluding,
            int alwaysShowRadius,
            int maxRaycastRadius,
            boolean debug,
            BlockView snap,
            int stepSize,
            ParticleSpawner particleSpawner
    ) {
        return raycastUntilSectionEntry(
                start, end, targetChunkX, targetSectionY, targetChunkZ,
                maxOccluding, alwaysShowRadius, maxRaycastRadius,
                debug, snap, stepSize, particleSpawner,
                null,
                ParticleSpawner.Colour.GREEN,
                ParticleSpawner.Colour.RED,
                ParticleSpawner.Colour.GREEN
        );
    }

    /**
     * Section LOS ray with optional viewer-scoped debug particles and explicit colours.
     */
    public static boolean raycastUntilSectionEntry(
            Locatable start,
            Spatial end,
            int targetChunkX,
            int targetSectionY,
            int targetChunkZ,
            int maxOccluding,
            int alwaysShowRadius,
            int maxRaycastRadius,
            boolean debug,
            BlockView snap,
            int stepSize,
            ParticleSpawner particleSpawner,
            @Nullable UUID debugViewer,
            ParticleSpawner.Colour clearColour,
            ParticleSpawner.Colour occluderColour,
            ParticleSpawner.Colour entryColour
    ) {
        return raycastUntilSectionEntry(
                start, end, targetChunkX, targetSectionY, targetChunkZ,
                maxOccluding, alwaysShowRadius, maxRaycastRadius,
                debug, snap, stepSize, particleSpawner, debugViewer,
                clearColour, occluderColour, entryColour,
                1
        );
    }

    /**
     * @param debugClearStride emit a clear-path particle every N steps (occluders/entry always). {@code 1} = every step.
     */
    public static boolean raycastUntilSectionEntry(
            Locatable start,
            Spatial end,
            int targetChunkX,
            int targetSectionY,
            int targetChunkZ,
            int maxOccluding,
            int alwaysShowRadius,
            int maxRaycastRadius,
            boolean debug,
            BlockView snap,
            int stepSize,
            ParticleSpawner particleSpawner,
            @Nullable UUID debugViewer,
            ParticleSpawner.Colour clearColour,
            ParticleSpawner.Colour occluderColour,
            ParticleSpawner.Colour entryColour,
            int debugClearStride
    ) {
        if (ChunkSectionVisibilityUtil.isEyeInsideSection(start.x(), start.y(), start.z(), targetChunkX, targetSectionY, targetChunkZ)) {
            return true;
        }
        MutableFloatingSpatial clonedEnd = end.cloneAndIfBlockThenCentre();
        double total = start.distance(clonedEnd) - stepSize;
        if (total <= alwaysShowRadius) {
            return true;
        }
        if (total > maxRaycastRadius) {
            return false;
        }
        if (debug && particleSpawner == null) {
            Logger.errorAndReturn(new RuntimeException("raycastUntilSectionEntry called with debug enabled but no ParticleSpawner supplied"), 2, RaycastUtil.class);
        }

        Spatial dir = clonedEnd.subtract(start).normalise().scalarMultiply(stepSize);
        MutableFloatingSpatial current = new MutableSpatialImpl(start.x(), start.y(), start.z());
        UUID world = start.world();
        int clearStride = Math.max(1, debugClearStride);
        int clearStepIndex = 0;

        for (double traveled = 0; traveled < total; traveled += stepSize) {
            current.add(dir);
            if (ChunkSectionVisibilityUtil.isBlockInSection(current.blockX(), current.blockY(), current.blockZ(), targetChunkX, targetSectionY, targetChunkZ)) {
                if (debug) {
                    particleSpawner.spawnParticleAt(world, current, entryColour, debugViewer);
                }
                return true;
            }
            if (snap.isBlockOccluding(current.blockX(), current.blockY(), current.blockZ())) {
                maxOccluding--;
                if (debug) {
                    particleSpawner.spawnParticleAt(world, current, occluderColour, debugViewer);
                }
                if (maxOccluding < 1) {
                    return false;
                }
                continue;
            }
            if (debug) {
                if (clearStepIndex % clearStride == 0) {
                    particleSpawner.spawnParticleAt(world, current, clearColour, debugViewer);
                }
                clearStepIndex++;
            }
        }
        // Endpoint may sit on the shell without an intermediate step registering inside; treat as success if end is in section.
        boolean atEnd = ChunkSectionVisibilityUtil.isBlockInSection(clonedEnd.blockX(), clonedEnd.blockY(), clonedEnd.blockZ(), targetChunkX, targetSectionY, targetChunkZ);
        if (atEnd && debug) {
            particleSpawner.spawnParticleAt(world, clonedEnd, entryColour, debugViewer);
        }
        return atEnd;
    }
}
