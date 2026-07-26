package games.cubi.raycastedantiesp.core.raycast;

import games.cubi.locatables.api.Locatable;
import games.cubi.locatables.api.Spatial;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public interface ParticleSpawner {

    enum Colour {
        RED,
        GREEN,
        BLUE,
        /** Clear steps on a section ray that ends in SHOW. */
        SECTION_SHOW_STEP,
        /** Occluder on a section ray that still ends in SHOW (budget not exhausted). */
        SECTION_SHOW_OCCLUDER,
        /** Exact point where a SHOW ray entered the target section. */
        SECTION_SHOW_ENTRY,
        /** Face/corner sample target for a section that will SHOW. */
        SECTION_SHOW_SAMPLE,
        /** Clear steps on section rays that all fail (HIDE). */
        SECTION_HIDE_STEP,
        /** Occluder that contributes to HIDE. */
        SECTION_HIDE_OCCLUDER,
        /** Face/corner sample target for a section that will HIDE. */
        SECTION_HIDE_SAMPLE,
    }

    void spawnParticleAt(Locatable locatable, Colour colour);

    void spawnParticleAt(UUID world, Spatial spatial, Colour colour);

    /**
     * Spawns a particle visible only to {@code viewer}, when non-null.
     * When {@code viewer} is null, falls back to {@link #spawnParticleAt(UUID, Spatial, Colour)}.
     */
    default void spawnParticleAt(UUID world, Spatial spatial, Colour colour, @Nullable UUID viewer) {
        if (viewer == null) {
            spawnParticleAt(world, spatial, colour);
            return;
        }
        spawnParticleAtForViewer(viewer, world, spatial, colour);
    }

    void spawnParticleAtForViewer(UUID viewer, UUID world, Spatial spatial, Colour colour);
}
