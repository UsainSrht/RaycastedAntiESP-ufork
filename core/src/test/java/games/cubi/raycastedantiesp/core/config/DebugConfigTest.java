package games.cubi.raycastedantiesp.core.config;

import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DebugConfigTest {
    @Test
    void chunkSectionDebugVerticalDownDefaultsToTwo() throws SerializationException {
        ConfigurationNode root = baseDebugNode();

        DebugConfig config = DebugConfig.load(root);

        assertEquals(DebugConfig.DEFAULT_CHUNK_SECTION_DEBUG_VERTICAL_DOWN, config.chunkSectionDebugVerticalDown());
        assertEquals(2, config.chunkSectionDebugVerticalDown());
    }

    @Test
    void chunkSectionDebugVerticalDownLoadsFromConfig() throws SerializationException {
        ConfigurationNode root = baseDebugNode();
        root.node("debug", "chunk-section-debug-vertical-down").set(5);

        DebugConfig config = DebugConfig.load(root);

        assertEquals(5, config.chunkSectionDebugVerticalDown());
    }

    @Test
    void chunkSectionDebugVerticalDownClampsNegativeToZero() throws SerializationException {
        ConfigurationNode root = baseDebugNode();
        root.node("debug", "chunk-section-debug-vertical-down").set(-3);

        DebugConfig config = DebugConfig.load(root);

        assertEquals(0, config.chunkSectionDebugVerticalDown());
    }

    private static ConfigurationNode baseDebugNode() throws SerializationException {
        ConfigurationNode root = BasicConfigurationNode.root();
        ConfigurationNode debug = root.node("debug");
        debug.node("info-level").set(5);
        debug.node("warn-level").set(5);
        debug.node("error-level").set(5);
        debug.node("particles").set(false);
        debug.node("timings").set(false);
        return root;
    }
}
