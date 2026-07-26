package games.cubi.raycastedantiesp.core.raycast;

import games.cubi.locatables.api.Locatable;
import games.cubi.locatables.api.Spatial;
import games.cubi.locatables.implementations.ImmutableLocatableImpl;
import games.cubi.locatables.implementations.ImmutableSpatialImpl;
import games.cubi.raycastedantiesp.core.view.BlockView;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaycastUntilSectionEntryTest {
    @Test
    void succeedsWhenRayEntersTargetSectionEvenIfInteriorIsSolid() {
        UUID world = UUID.randomUUID();
        Locatable eye = new ImmutableLocatableImpl(world, -8.5, 8.5, 8.5);
        Spatial faceSample = new ImmutableSpatialImpl(0.5, 8.5, 8.5);
        // Solid rock filling section (0,0,0); entry-stop must succeed at the shell.
        BlockView view = occludingBlockView(Set.of(), solidSectionBlocks(0, 0, 0));

        assertTrue(RaycastUtil.raycastUntilSectionEntry(
                eye, faceSample, 0, 0, 0, 1, 0, 64, false, view, 1, null
        ));
    }

    @Test
    void failsWhenOccludersBlockPathBeforeSectionEntry() {
        UUID world = UUID.randomUUID();
        Locatable eye = new ImmutableLocatableImpl(world, -8.5, 8.5, 8.5);
        Spatial faceSample = new ImmutableSpatialImpl(0.5, 8.5, 8.5);
        Set<Long> walls = new HashSet<>();
        walls.add(packBlock(-1, 8, 8));
        BlockView view = occludingBlockView(walls, Set.of());

        assertFalse(RaycastUtil.raycastUntilSectionEntry(
                eye, faceSample, 0, 0, 0, 1, 0, 64, false, view, 1, null
        ));
    }

    @Test
    void succeedsImmediatelyWhenEyeAlreadyInsideTargetSection() {
        UUID world = UUID.randomUUID();
        Locatable eye = new ImmutableLocatableImpl(world, 8.5, 8.5, 8.5);
        Spatial anywhere = new ImmutableSpatialImpl(100, 100, 100);
        BlockView view = occludingBlockView(Set.of(), Set.of());

        assertTrue(RaycastUtil.raycastUntilSectionEntry(
                eye, anywhere, 0, 0, 0, 1, 0, 64, false, view, 1, null
        ));
    }

    private static Set<Long> solidSectionBlocks(int chunkX, int sectionY, int chunkZ) {
        Set<Long> blocks = new HashSet<>();
        int minX = chunkX << 4;
        int minY = sectionY << 4;
        int minZ = chunkZ << 4;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    blocks.add(packBlock(minX + x, minY + y, minZ + z));
                }
            }
        }
        return blocks;
    }

    private static long packBlock(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | ((long) z & 0x3FFFFFFL);
    }

    private static BlockView occludingBlockView(Set<Long> occluders, Set<Long> moreOccluders) {
        Set<Long> all = new HashSet<>(occluders);
        all.addAll(moreOccluders);
        return (BlockView) Proxy.newProxyInstance(
                BlockView.class.getClassLoader(),
                new Class<?>[]{BlockView.class},
                (proxy, method, args) -> {
                    if ("isBlockOccluding".equals(method.getName()) && args != null && args.length == 3) {
                        return all.contains(packBlock((int) args[0], (int) args[1], (int) args[2]));
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    return null;
                }
        );
    }
}
