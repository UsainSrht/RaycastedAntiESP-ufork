package games.cubi.raycastedantiesp.core.config.raycast;

import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSectionConfigTest {
    @Test
    void loadsHideDelayNeighborPaddingAndVerticalAlwaysShow() throws SerializationException {
        ConfigurationNode node = BasicConfigurationNode.root();
        node.node("enabled").set(true);
        node.node("max-occluding-count").set(2);
        node.node("always-show-radius-chunks").set(4);
        node.node("always-show-vertical-sections").set(1);
        node.node("raycast-radius-chunks").set(6);
        node.node("visible-recheck-interval-ticks").set(5);
        node.node("hide-delay-ticks").set(3);
        node.node("neighbor-padding").set(true);

        ChunkSectionConfig config = ChunkSectionConfig.load(node, "checks.chunk-section");

        assertEquals(3, config.hideDelayTicks());
        assertTrue(config.neighborPadding());
        assertEquals(6, config.raycastRadiusChunks());
        assertEquals(1, config.alwaysShowVerticalSections());
        assertEquals(2, config.maxOccludingCount());
    }

    @Test
    void defaultsVerticalAlwaysShowWhenMissing() throws SerializationException {
        ConfigurationNode node = BasicConfigurationNode.root();
        node.node("enabled").set(false);
        node.node("max-occluding-count").set(2);
        node.node("always-show-radius-chunks").set(4);
        node.node("raycast-radius-chunks").set(6);
        node.node("visible-recheck-interval-ticks").set(5);
        node.node("hide-delay-ticks").set(3);
        node.node("neighbor-padding").set(true);

        ChunkSectionConfig config = ChunkSectionConfig.load(node, "checks.chunk-section");
        assertEquals(1, config.alwaysShowVerticalSections());
    }
}
