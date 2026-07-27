package games.cubi.raycastedantiesp.core.config;

import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DebugConfigTest {
    @Test
    void debugConfigLoadsFromConfigNode() throws SerializationException {
        ConfigurationNode root = baseDebugNode();

        DebugConfig config = DebugConfig.load(root);

        assertEquals((byte) 5, config.getInfoLevel());
        assertEquals((byte) 5, config.getWarnLevel());
        assertEquals((byte) 5, config.getErrorLevel());
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
