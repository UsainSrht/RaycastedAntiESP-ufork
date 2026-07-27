package games.cubi.raycastedantiesp.core.config.raycast;

import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSectionConfigTest {
    @Test
    void loadsAsymmetricVerticalAlwaysShow() throws SerializationException {
        ConfigurationNode node = baseNode();
        node.node("always-show-vertical-down").set(1);
        node.node("always-show-vertical-up").set(12);

        ChunkSectionConfig config = ChunkSectionConfig.load(node, "checks.chunk-section");

        assertEquals(1, config.alwaysShowVerticalDown());
        assertEquals(12, config.alwaysShowVerticalUp());
        assertEquals(10, config.hiddenRecheckIntervalTicks());
        assertEquals(3, config.hideDelayTicks());
        assertTrue(config.neighborPadding());
        assertTrue(config.hideAsAir());
        assertTrue(config.directionalOcclusionCulling());
        assertEquals(1, config.preemptiveNeighborReveal());
    }

    @Test
    void hiddenRecheckDefaultsToTenWhenAbsent() throws SerializationException {
        assertEquals(10, ChunkSectionConfig.load(baseNode(), "checks.chunk-section").hiddenRecheckIntervalTicks());
    }

    @Test
    void hiddenRecheckCanBeConfigured() throws SerializationException {
        ConfigurationNode node = baseNode();
        node.node("hidden-recheck-interval-ticks").set(20);
        assertEquals(20, ChunkSectionConfig.load(node, "checks.chunk-section").hiddenRecheckIntervalTicks());
    }

    @Test
    void legacyVerticalKeyMapsToDownAndGenerousUp() throws SerializationException {
        ConfigurationNode node = baseNode();
        node.node("always-show-vertical-sections").set(1);

        ChunkSectionConfig config = ChunkSectionConfig.load(node, "checks.chunk-section");
        assertEquals(1, config.alwaysShowVerticalDown());
        assertEquals(12, config.alwaysShowVerticalUp());
    }

    @Test
    void hideAsAirDefaultsTrueAndCanBeDisabled() throws SerializationException {
        assertTrue(ChunkSectionConfig.load(baseNode(), "checks.chunk-section").hideAsAir());

        ConfigurationNode node = baseNode();
        node.node("hide-as-air").set(false);
        assertFalse(ChunkSectionConfig.load(node, "checks.chunk-section").hideAsAir());
    }

    private static ConfigurationNode baseNode() throws SerializationException {
        ConfigurationNode node = BasicConfigurationNode.root();
        node.node("enabled").set(true);
        node.node("max-occluding-count").set(2);
        node.node("always-show-radius-chunks").set(4);
        node.node("raycast-radius-chunks").set(6);
        node.node("visible-recheck-interval-ticks").set(5);
        node.node("hide-delay-ticks").set(3);
        node.node("neighbor-padding").set(true);
        return node;
    }
}
