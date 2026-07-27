package games.cubi.raycastedantiesp.core.config.raycast;

import games.cubi.raycastedantiesp.core.config.Config;
import games.cubi.raycastedantiesp.core.config.ConfigReader;
import org.spongepowered.configurate.ConfigurationNode;

public record ChunkSectionConfig(
        boolean enabled,
        int maxOccludingCount,
        int alwaysShowRadiusChunks,
        int alwaysShowVerticalDown,
        int alwaysShowVerticalUp,
        int raycastRadiusChunks,
        int visibleRecheckIntervalTicks,
        int hiddenRecheckIntervalTicks,
        int hideDelayTicks,
        boolean neighborPadding,
        boolean hideAsAir,
        boolean directionalOcclusionCulling,
        int preemptiveNeighborReveal
) implements Config {
    public static ChunkSectionConfig load(ConfigurationNode node, String path) {
        ConfigurationNode downNode = node.node("always-show-vertical-down");
        ConfigurationNode upNode = node.node("always-show-vertical-up");
        ConfigurationNode legacyVertical = node.node("always-show-vertical-sections");
        ConfigurationNode hideAsAirNode = node.node("hide-as-air");
        ConfigurationNode hiddenRecheckNode = node.node("hidden-recheck-interval-ticks");
        ConfigurationNode directionalNode = node.node("directional-occlusion-culling");
        ConfigurationNode preemptiveNode = node.node("preemptive-neighbor-reveal");

        int down;
        if (!downNode.virtual()) {
            down = ConfigReader.integer(downNode, path + ".always-show-vertical-down");
        } else if (!legacyVertical.virtual()) {
            down = ConfigReader.integer(legacyVertical, path + ".always-show-vertical-sections");
        } else {
            down = 1;
        }

        int up;
        if (!upNode.virtual()) {
            up = ConfigReader.integer(upNode, path + ".always-show-vertical-up");
        } else if (!legacyVertical.virtual()) {
            // Legacy single band was too small for hilltops; prefer a generous upward default.
            up = Math.max(ConfigReader.integer(legacyVertical, path + ".always-show-vertical-sections"), 12);
        } else {
            up = 12;
        }

        // Default true: empty air. false: solid stone/deepslate placeholder (avoids stuck fade on some clients).
        boolean hideAsAir = hideAsAirNode.virtual()
                || ConfigReader.bool(hideAsAirNode, path + ".hide-as-air");

        // Sticky HIDE interval; default 10 when absent from older configs. 0/-1 = recheck every tick.
        int hiddenRecheck = hiddenRecheckNode.virtual()
                ? 10
                : ConfigReader.integer(hiddenRecheckNode, path + ".hidden-recheck-interval-ticks");

        boolean directionalOcclusionCulling = directionalNode.virtual()
                || ConfigReader.bool(directionalNode, path + ".directional-occlusion-culling");

        int preemptiveNeighborReveal = preemptiveNode.virtual()
                ? 1
                : ConfigReader.integer(preemptiveNode, path + ".preemptive-neighbor-reveal");

        return new ChunkSectionConfig(
                ConfigReader.bool(ConfigReader.node(node, "enabled"), path + ".enabled"),
                ConfigReader.integer(ConfigReader.node(node, "max-occluding-count"), path + ".max-occluding-count"),
                ConfigReader.integer(ConfigReader.node(node, "always-show-radius-chunks"), path + ".always-show-radius-chunks"),
                Math.max(0, down),
                Math.max(0, up),
                ConfigReader.integer(ConfigReader.node(node, "raycast-radius-chunks"), path + ".raycast-radius-chunks"),
                ConfigReader.integer(ConfigReader.node(node, "visible-recheck-interval-ticks"), path + ".visible-recheck-interval-ticks"),
                hiddenRecheck,
                ConfigReader.integer(ConfigReader.node(node, "hide-delay-ticks"), path + ".hide-delay-ticks"),
                ConfigReader.bool(ConfigReader.node(node, "neighbor-padding"), path + ".neighbor-padding"),
                hideAsAir,
                directionalOcclusionCulling,
                preemptiveNeighborReveal
        );
    }

    /** @deprecated use {@link #alwaysShowVerticalDown()} / {@link #alwaysShowVerticalUp()} */
    @Deprecated
    public int alwaysShowVerticalSections() {
        return Math.max(alwaysShowVerticalDown, alwaysShowVerticalUp);
    }
}
