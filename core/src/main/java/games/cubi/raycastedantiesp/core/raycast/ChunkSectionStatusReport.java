package games.cubi.raycastedantiesp.core.raycast;

import games.cubi.locatables.api.Locatable;
import games.cubi.raycastedantiesp.core.config.raycast.ChunkSectionConfig;
import games.cubi.raycastedantiesp.core.tracked.TrackedChunkSection;
import games.cubi.raycastedantiesp.core.view.BlockView;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of tracked chunk-section visibility around a viewer (client state + fresh LOS probe).
 */
public final class ChunkSectionStatusReport {
    private final int total;
    private final int shown;
    private final int hidden;
    private final int raycastsCast;
    private final int raycastsSucceeded;
    private final int raycastsOccluded;
    private final int skippedRaycastHide;
    private final Map<String, Integer> hideReasons;

    private ChunkSectionStatusReport(
            int total,
            int shown,
            int hidden,
            int raycastsCast,
            int raycastsSucceeded,
            int raycastsOccluded,
            int skippedRaycastHide,
            Map<String, Integer> hideReasons
    ) {
        this.total = total;
        this.shown = shown;
        this.hidden = hidden;
        this.raycastsCast = raycastsCast;
        this.raycastsSucceeded = raycastsSucceeded;
        this.raycastsOccluded = raycastsOccluded;
        this.skippedRaycastHide = skippedRaycastHide;
        this.hideReasons = hideReasons;
    }

    public int total() {
        return total;
    }

    public int shown() {
        return shown;
    }

    public int hidden() {
        return hidden;
    }

    public int raycastsCast() {
        return raycastsCast;
    }

    public int raycastsSucceeded() {
        return raycastsSucceeded;
    }

    public int raycastsOccluded() {
        return raycastsOccluded;
    }

    public int skippedRaycastHide() {
        return skippedRaycastHide;
    }

    public Map<String, Integer> hideReasons() {
        return hideReasons;
    }

    /**
     * Evaluates every tracked section for {@code eye} and aggregates client visibility + LOS outcomes.
     */
    public static ChunkSectionStatusReport collect(Locatable eye, ChunkSectionConfig config, BlockView blockView) {
        int[] total = {0};
        int[] shown = {0};
        int[] hidden = {0};
        int[] raycastsCast = {0};
        int[] raycastsSucceeded = {0};
        int[] raycastsOccluded = {0};
        int[] skippedRaycastHide = {0};
        Map<String, Integer> hideReasons = new LinkedHashMap<>();

        LongOpenHashSet reachable = new LongOpenHashSet();
        LongOpenHashSet frontier = new LongOpenHashSet();
        ChunkSectionLosProbe.collectFrontierFromEye(eye, config.raycastRadiusChunks(), blockView, reachable, frontier);

        blockView.forEachTrackedChunkSection(section -> {
            total[0]++;
            if (section.visible()) {
                shown[0]++;
            } else {
                hidden[0]++;
            }

            ChunkSectionLosProbe.Decision decision = ChunkSectionLosProbe.evaluateSection(
                    eye, section.chunkX(), section.sectionY(), section.chunkZ(), config, blockView, reachable, frontier
            );
            boolean raycastPerformed = !decision.eyeInside()
                    && !decision.alwaysShow()
                    && decision.withinRaycastRadius()
                    && !decision.visGraphAutoHide();
            if (raycastPerformed) {
                raycastsCast[0]++;
                if (decision.raycastLos()) {
                    raycastsSucceeded[0]++;
                } else {
                    raycastsOccluded[0]++;
                }
            } else if (!decision.wouldShow()) {
                skippedRaycastHide[0]++;
            }

            if (!decision.wouldShow()) {
                bump(hideReasons, hideReasonCode(decision, raycastPerformed));
                for (String tag : connectivityTags(section)) {
                    bump(hideReasons, tag);
                }
                if (decision.visGraphUnreachableBelow()) {
                    bump(hideReasons, "VISGRAPH_UNREACHABLE_BELOW");
                }
            }
        });

        return new ChunkSectionStatusReport(
                total[0],
                shown[0],
                hidden[0],
                raycastsCast[0],
                raycastsSucceeded[0],
                raycastsOccluded[0],
                skippedRaycastHide[0],
                Collections.unmodifiableMap(sortedByCountDesc(hideReasons))
        );
    }

    private static String hideReasonCode(ChunkSectionLosProbe.Decision decision, boolean raycastPerformed) {
        if (!decision.withinRaycastRadius()) {
            return "OUTSIDE_RAYCAST_RADIUS";
        }
        if (decision.visGraphAutoHide()) {
            return "VISGRAPH_AUTO_HIDE";
        }
        if (raycastPerformed && !decision.raycastLos()) {
            return "RAYCAST_BLOCKED";
        }
        return "HIDDEN";
    }

    private static List<String> connectivityTags(TrackedChunkSection section) {
        long connectivity = section.visConnectivity();
        if (connectivity == SectionVisGraph.FULLY_OPEN) {
            return List.of("FULLY_OPEN");
        }
        if (connectivity == SectionVisGraph.SOLID) {
            return List.of("SOLID");
        }
        return List.of("PARTIAL");
    }

    private static void bump(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }

    private static Map<String, Integer> sortedByCountDesc(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> {
            int byCount = Integer.compare(b.getValue(), a.getValue());
            return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
        });
        Map<String, Integer> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : entries) {
            sorted.put(entry.getKey(), entry.getValue());
        }
        return sorted;
    }
}
