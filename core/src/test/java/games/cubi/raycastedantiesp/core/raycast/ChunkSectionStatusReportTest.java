package games.cubi.raycastedantiesp.core.raycast;

import games.cubi.locatables.api.Locatable;
import games.cubi.locatables.implementations.ImmutableLocatableImpl;
import games.cubi.raycastedantiesp.core.config.raycast.ChunkSectionConfig;
import games.cubi.raycastedantiesp.core.tracked.TrackedChunkSection;
import games.cubi.raycastedantiesp.core.view.BlockView;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSectionStatusReportTest {
    @Test
    void aggregatesShownHiddenAndOutsideRadiusHide() throws SerializationException {
        UUID world = UUID.randomUUID();
        Locatable eye = new ImmutableLocatableImpl(world, 8.5, 8.5, 8.5);
        ChunkSectionConfig config = loadConfig();

        List<TrackedChunkSection> sections = List.of(
                stubSection(0, 0, 0, true, SectionVisGraph.FULLY_OPEN),
                stubSection(20, 0, 0, false, SectionVisGraph.FULLY_OPEN),
                stubSection(21, 0, 0, false, SectionVisGraph.SOLID)
        );
        BlockView view = blockView(sections);

        ChunkSectionStatusReport report = ChunkSectionStatusReport.collect(eye, config, view);

        assertEquals(3, report.total());
        assertEquals(1, report.shown());
        assertEquals(2, report.hidden());
        assertEquals(0, report.raycastsCast());
        assertEquals(2, report.skippedRaycastHide());
        assertEquals(2, report.hideReasons().get("OUTSIDE_RAYCAST_RADIUS"));
        assertEquals(1, report.hideReasons().get("FULLY_OPEN"));
        assertEquals(1, report.hideReasons().get("SOLID"));
    }

    @Test
    void eyeSectionCountsAsShownWithoutRaycast() throws SerializationException {
        UUID world = UUID.randomUUID();
        Locatable eye = new ImmutableLocatableImpl(world, 8.5, 8.5, 8.5);
        ChunkSectionConfig config = loadConfig();
        BlockView view = blockView(List.of(stubSection(0, 0, 0, true, 1L)));

        ChunkSectionStatusReport report = ChunkSectionStatusReport.collect(eye, config, view);

        assertEquals(1, report.total());
        assertEquals(1, report.shown());
        assertEquals(0, report.hidden());
        assertEquals(0, report.raycastsCast());
        assertTrue(report.hideReasons().isEmpty());
    }

    @Test
    void visGraphAutoHideCountsAsSkippedHide() throws SerializationException {
        UUID world = UUID.randomUUID();
        Locatable eye = new ImmutableLocatableImpl(world, 8.5, 8.5, 8.5);
        ChunkSectionConfig config = loadConfig();
        List<TrackedChunkSection> sections = List.of(
                stubSection(0, 0, 0, true, SectionVisGraph.FULLY_OPEN),
                stubSection(1, 0, 0, false, SectionVisGraph.SOLID),
                stubSection(-1, 0, 0, false, SectionVisGraph.SOLID),
                stubSection(0, 1, 0, false, SectionVisGraph.SOLID),
                stubSection(0, -1, 0, false, SectionVisGraph.SOLID),
                stubSection(0, 0, 1, false, SectionVisGraph.SOLID),
                stubSection(0, 0, -1, false, SectionVisGraph.SOLID),
                stubSection(2, 0, 0, false, SectionVisGraph.FULLY_OPEN)
        );
        BlockView view = blockView(sections);

        ChunkSectionStatusReport report = ChunkSectionStatusReport.collect(eye, config, view);

        assertEquals(8, report.total());
        assertEquals(1, report.hideReasons().get("VISGRAPH_AUTO_HIDE"));
        assertTrue(report.skippedRaycastHide() >= 1);
    }

    private static ChunkSectionConfig loadConfig() throws SerializationException {
        var node = BasicConfigurationNode.root();
        node.node("enabled").set(true);
        node.node("max-occluding-count").set(2);
        node.node("always-show-radius-chunks").set(0);
        node.node("always-show-vertical-down").set(0);
        node.node("always-show-vertical-up").set(0);
        node.node("raycast-radius-chunks").set(2);
        node.node("visible-recheck-interval-ticks").set(5);
        node.node("hidden-recheck-interval-ticks").set(10);
        node.node("hide-delay-ticks").set(3);
        node.node("neighbor-padding").set(false);
        return ChunkSectionConfig.load(node, "checks.chunk-section");
    }

    private static TrackedChunkSection stubSection(int chunkX, int sectionY, int chunkZ, boolean visible, long connectivity) {
        return new TrackedChunkSection() {
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
                return this;
            }

            @Override
            public int lastChecked() {
                return NEVER_CHECKED;
            }

            @Override
            public TrackedChunkSection setLastChecked(int lastChecked) {
                return this;
            }

            @Override
            public long visConnectivity() {
                return connectivity;
            }

            @Override
            public TrackedChunkSection setVisConnectivity(long connectivity) {
                return this;
            }

            @Override
            public boolean visConnectivityDirty() {
                return false;
            }

            @Override
            public TrackedChunkSection markVisConnectivityDirty() {
                return this;
            }

            @Override
            public int wantHiddenSinceTick() {
                return NOT_WANTING_HIDE;
            }

            @Override
            public TrackedChunkSection setWantHiddenSinceTick(int tick) {
                return this;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static BlockView blockView(List<TrackedChunkSection> sections) {
        return (BlockView) Proxy.newProxyInstance(
                BlockView.class.getClassLoader(),
                new Class<?>[]{BlockView.class},
                (proxy, method, args) -> {
                    if ("forEachTrackedChunkSection".equals(method.getName())) {
                        Consumer<TrackedChunkSection> action = (Consumer<TrackedChunkSection>) args[0];
                        for (TrackedChunkSection section : new ArrayList<>(sections)) {
                            action.accept(section);
                        }
                        return null;
                    }
                    if ("getTrackedChunkSection".equals(method.getName())) {
                        int chunkX = (Integer) args[1];
                        int sectionY = (Integer) args[2];
                        int chunkZ = (Integer) args[3];
                        for (TrackedChunkSection section : sections) {
                            if (section.chunkX() == chunkX
                                    && section.sectionY() == sectionY
                                    && section.chunkZ() == chunkZ) {
                                return section;
                            }
                        }
                        return null;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == long.class) {
                        return 0L;
                    }
                    return null;
                }
        );
    }
}
