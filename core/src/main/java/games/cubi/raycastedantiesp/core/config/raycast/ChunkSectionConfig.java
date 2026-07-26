package games.cubi.raycastedantiesp.core.config.raycast;

import games.cubi.raycastedantiesp.core.config.Config;
import games.cubi.raycastedantiesp.core.config.ConfigReader;
import org.spongepowered.configurate.ConfigurationNode;

public record ChunkSectionConfig(
        boolean enabled,
        int maxOccludingCount,
        int alwaysShowRadiusChunks,
        int alwaysShowVerticalSections,
        int raycastRadiusChunks,
        int visibleRecheckIntervalTicks,
        int hideDelayTicks,
        boolean neighborPadding
) implements Config {
    public static ChunkSectionConfig load(ConfigurationNode node, String path) {
        ConfigurationNode verticalNode = node.node("always-show-vertical-sections");
        int vertical = verticalNode.virtual()
                ? 1
                : ConfigReader.integer(verticalNode, path + ".always-show-vertical-sections");
        return new ChunkSectionConfig(
                ConfigReader.bool(ConfigReader.node(node, "enabled"), path + ".enabled"),
                ConfigReader.integer(ConfigReader.node(node, "max-occluding-count"), path + ".max-occluding-count"),
                ConfigReader.integer(ConfigReader.node(node, "always-show-radius-chunks"), path + ".always-show-radius-chunks"),
                Math.max(0, vertical),
                ConfigReader.integer(ConfigReader.node(node, "raycast-radius-chunks"), path + ".raycast-radius-chunks"),
                ConfigReader.integer(ConfigReader.node(node, "visible-recheck-interval-ticks"), path + ".visible-recheck-interval-ticks"),
                ConfigReader.integer(ConfigReader.node(node, "hide-delay-ticks"), path + ".hide-delay-ticks"),
                ConfigReader.bool(ConfigReader.node(node, "neighbor-padding"), path + ".neighbor-padding")
        );
    }
}
