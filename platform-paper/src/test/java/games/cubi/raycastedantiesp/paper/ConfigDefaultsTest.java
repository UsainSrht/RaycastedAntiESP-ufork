package games.cubi.raycastedantiesp.paper;

import games.cubi.raycastedantiesp.core.config.ConfigLoadException;
import games.cubi.raycastedantiesp.core.config.ConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigDefaultsTest {
    @BeforeEach
    @AfterEach
    void resetConfigManagerSingleton() throws Exception {
        Field instance = ConfigManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Test
    void legacyVersionTwoConfigReceivesEnabledRetentionDefaults(@TempDir Path dataFolder) throws IOException {
        byte[] defaults = readDefaultConfig();
        String legacyConfig = new String(defaults, StandardCharsets.UTF_8)
                .replace("        keep-client-entity-when-hidden: true\r\n", "")
                .replace("        keep-client-entity-when-hidden: true\n", "");
        Path configPath = dataFolder.resolve("config.yml");
        Files.writeString(configPath, legacyConfig, StandardCharsets.UTF_8);

        ConfigManager manager = ConfigManager.initialiseConfigManager(
                () -> new ByteArrayInputStream(defaults), dataFolder, List.of());

        assertTrue(manager.getPlayerConfig().keepClientEntityWhenHidden());
        assertTrue(manager.getEntityConfig().keepClientEntityWhenHidden());
        assertEquals("2.0", manager.getConfigFile().node("config-version").getString());

        String mergedConfig = Files.readString(configPath, StandardCharsets.UTF_8);
        assertEquals(2, mergedConfig.split("keep-client-entity-when-hidden: true", -1).length - 1);
    }

    @Test
    void defaultChunkSectionConfigIncludesRaycastRadiusChunks(@TempDir Path dataFolder) throws IOException {
        byte[] defaults = readDefaultConfig();
        Files.write(dataFolder.resolve("config.yml"), defaults);

        ConfigManager manager = ConfigManager.initialiseConfigManager(
                () -> new ByteArrayInputStream(defaults), dataFolder, List.of());

        assertFalse(manager.getChunkSectionConfig().enabled());
        assertEquals(10, manager.getChunkSectionConfig().raycastRadiusChunks());
        assertEquals(1, manager.getChunkSectionConfig().alwaysShowRadiusChunks());
        assertEquals(1, manager.getChunkSectionConfig().alwaysShowVerticalDown());
        assertEquals(1, manager.getChunkSectionConfig().alwaysShowVerticalUp());
        assertEquals(3, manager.getChunkSectionConfig().maxOccludingCount());
        assertEquals(5, manager.getChunkSectionConfig().visibleRecheckIntervalTicks());
        assertEquals(10, manager.getChunkSectionConfig().hiddenRecheckIntervalTicks());
        assertEquals(3, manager.getChunkSectionConfig().hideDelayTicks());
        assertTrue(manager.getChunkSectionConfig().neighborPadding());
        assertTrue(manager.getChunkSectionConfig().hideAsAir());
        assertTrue(manager.getChunkSectionConfig().directionalOcclusionCulling());
        assertEquals(1, manager.getChunkSectionConfig().preemptiveNeighborReveal());
        assertEquals(2, manager.getDebugConfig().chunkSectionDebugVerticalDown());
        assertTrue(manager.getHideBelowYConfig().enabled());
        assertEquals(32, manager.getHideBelowYConfig().yCutoff());
        assertEquals(36, manager.getHideBelowYConfig().playerYTrigger());
        assertEquals(32, manager.getHideBelowYConfig().verticalDistanceBelowPlayer());
        assertEquals(32, manager.getHideBelowYConfig().verticalDistanceAbovePlayer());
        assertEquals(8, manager.getHideBelowYConfig().unhideBuffer());
    }

    @Test
    void chunkSectionEnabledRequiresTrackAllBlocks(@TempDir Path dataFolder) throws IOException {
        byte[] defaults = readDefaultConfig();
        String invalid = new String(defaults, StandardCharsets.UTF_8)
                .replace("track-all-blocks: false", "track-all-blocks: false")
                .replace("enabled: false\n        max-occluding-count: 3", "enabled: true\n        max-occluding-count: 3")
                .replace("enabled: false\r\n        max-occluding-count: 3", "enabled: true\r\n        max-occluding-count: 3");
        assertTrue(invalid.contains("chunk-section:"), "precondition: chunk-section present");
        assertTrue(invalid.contains("enabled: true\n        max-occluding-count: 3")
                || invalid.contains("enabled: true\r\n        max-occluding-count: 3"), "precondition: section checks enabled");
        Files.writeString(dataFolder.resolve("config.yml"), invalid, StandardCharsets.UTF_8);

        ConfigLoadException thrown = assertThrows(ConfigLoadException.class, () ->
                ConfigManager.initialiseConfigManager(() -> new ByteArrayInputStream(defaults), dataFolder, List.of())
        );
        assertTrue(thrown.getMessage().contains("track-all-blocks"));
    }

    private static byte[] readDefaultConfig() throws IOException {
        try (InputStream input = ConfigDefaultsTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(input);
            return input.readAllBytes();
        }
    }
}
