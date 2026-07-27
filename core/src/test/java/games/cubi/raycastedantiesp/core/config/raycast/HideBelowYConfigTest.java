package games.cubi.raycastedantiesp.core.config.raycast;

import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import static org.junit.jupiter.api.Assertions.*;

class HideBelowYConfigTest {

    @Test
    void testVirtualNodeDefaults() {
        ConfigurationNode node = BasicConfigurationNode.root();
        HideBelowYConfig config = HideBelowYConfig.load(node, "checks.hide-below-y");
        assertFalse(config.enabled());
        assertEquals(60, config.yCutoff());
        assertEquals(64, config.playerYTrigger());
        assertEquals(48, config.verticalDistanceBelowPlayer());
        assertEquals(48, config.verticalDistanceAbovePlayer());
        assertEquals(8, config.unhideBuffer());
        assertEquals(5, config.recheckIntervalTicks());
        assertTrue(config.movementTrigger());
    }

    @Test
    void testCustomValues() throws SerializationException {
        ConfigurationNode node = BasicConfigurationNode.root();
        node.node("enabled").set(true);
        node.node("y-cutoff").set(50);
        node.node("player-y-trigger").set(70);
        node.node("vertical-distance-below-player").set(32);
        node.node("vertical-distance-above-player").set(24);
        node.node("unhide-buffer").set(4);
        node.node("recheck-interval-ticks").set(10);
        node.node("movement-trigger").set(false);

        HideBelowYConfig config = HideBelowYConfig.load(node, "checks.hide-below-y");
        assertTrue(config.enabled());
        assertEquals(50, config.yCutoff());
        assertEquals(70, config.playerYTrigger());
        assertEquals(32, config.verticalDistanceBelowPlayer());
        assertEquals(24, config.verticalDistanceAbovePlayer());
        assertEquals(4, config.unhideBuffer());
        assertEquals(10, config.recheckIntervalTicks());
        assertFalse(config.movementTrigger());
    }

    @Test
    void testShouldAutoHideBlockWhenDisabled() throws SerializationException {
        ConfigurationNode node = BasicConfigurationNode.root();
        node.node("enabled").set(false);
        node.node("y-cutoff").set(60);
        node.node("player-y-trigger").set(64);

        HideBelowYConfig config = HideBelowYConfig.load(node, "checks.hide-below-y");
        assertFalse(config.shouldAutoHideBlock(75, 40));
        assertFalse(config.shouldAutoHideSection(75, 2));
    }

    @Test
    void testShouldAutoHideBlockAbsoluteCutoff() throws SerializationException {
        ConfigurationNode node = BasicConfigurationNode.root();
        node.node("enabled").set(true);
        node.node("y-cutoff").set(60);
        node.node("player-y-trigger").set(64);
        node.node("vertical-distance-below-player").set(0);
        node.node("unhide-buffer").set(8);

        HideBelowYConfig config = HideBelowYConfig.load(node, "checks.hide-below-y");
        // Player at Y=70 (above trigger Y=64)
        // Block Y=50 is below yCutoff (60) -> should autohide
        assertTrue(config.shouldAutoHideBlock(70, 50));
        // Block Y=55 is below yCutoff (60) -> should autohide
        assertTrue(config.shouldAutoHideBlock(70, 55));
        // Block Y=62 is at/above yCutoff (60) -> not autohidden
        assertFalse(config.shouldAutoHideBlock(70, 62));

        // Player descends to Y=50 (below trigger Y=64 - buffer=8) -> absolute cutoff inactive
        assertFalse(config.shouldAutoHideBlock(50, 50));
    }

    @Test
    void testShouldAutoHideBlockRelativeDistanceBelowWithSurfaceGuard() throws SerializationException {
        ConfigurationNode node = BasicConfigurationNode.root();
        node.node("enabled").set(true);
        node.node("y-cutoff").set(60);
        node.node("player-y-trigger").set(64);
        node.node("vertical-distance-below-player").set(48);
        node.node("unhide-buffer").set(8);

        HideBelowYConfig config = HideBelowYConfig.load(node, "checks.hide-below-y");
        // Player flying at Y=150
        // Surface block Y=64 (>= yCutoff 60) should NOT be hidden by relative distance below player
        assertFalse(config.shouldAutoHideBlock(150, 64));

        // Subterranean block Y=40 (< yCutoff 60) AND < (150 - 48 - 8 = 94) -> SHOULD autohide
        assertTrue(config.shouldAutoHideBlock(150, 40));

        // Deep underground player at Y=-48: block Y=-110 (< yCutoff 60 AND < -48 - 48 - 8 = -104) -> SHOULD autohide
        assertTrue(config.shouldAutoHideBlock(-48, -110));
    }

    @Test
    void testShouldAutoHideBlockRelativeDistanceAbove() throws SerializationException {
        ConfigurationNode node = BasicConfigurationNode.root();
        node.node("enabled").set(true);
        node.node("y-cutoff").set(60);
        node.node("player-y-trigger").set(64);
        node.node("vertical-distance-above-player").set(48);
        node.node("unhide-buffer").set(8);

        HideBelowYConfig config = HideBelowYConfig.load(node, "checks.hide-below-y");
        // Player deep underground at Y=-20 (below yCutoff 60)
        // Block Y=40 is higher than (-20 + 48 + 8 = 36) -> should autohide
        assertTrue(config.shouldAutoHideBlock(-20, 40));

        // Block Y=30 is lower than 36 -> should NOT autohide
        assertFalse(config.shouldAutoHideBlock(-20, 30));

        // Player on surface at Y=65 (>= yCutoff 60) -> verticalDistanceAbovePlayer does NOT apply
        assertFalse(config.shouldAutoHideBlock(65, 120));
    }

    @Test
    void testDefaultCutoffGapFixed() throws SerializationException {
        ConfigurationNode node = BasicConfigurationNode.root();
        node.node("enabled").set(true);
        node.node("y-cutoff").set(32);
        node.node("player-y-trigger").set(36);
        node.node("vertical-distance-below-player").set(32);
        node.node("vertical-distance-above-player").set(32);
        node.node("unhide-buffer").set(8);

        HideBelowYConfig config = HideBelowYConfig.load(node, "checks.hide-below-y");

        // Player high at Y=80 (80 - 32 - 8 = 40 >= 32): Subterranean section 1 (16..31) and blocks < 32 are hidden
        assertTrue(config.shouldAutoHideBlock(80, 30));
        assertTrue(config.shouldAutoHideSection(80, 1));

        // Player descending to Y=60 (60 - 32 - 8 = 20 < 32): Section 1 (16..31) dynamically loads!
        assertFalse(config.shouldAutoHideSection(60, 1));
        assertFalse(config.shouldAutoHideBlock(60, 30));

        // Player standing at Y=33 (close to yCutoff=32): Section 1 and blocks near 32 are fully visible
        assertFalse(config.shouldAutoHideSection(33, 1));
        assertFalse(config.shouldAutoHideBlock(33, 30));
        assertFalse(config.shouldAutoHideBlock(33, 28));

        // Player descends to Y=30 (below yCutoff=32)
        assertFalse(config.shouldAutoHideSection(30, 1));
        assertFalse(config.shouldAutoHideBlock(30, 30));
        assertFalse(config.shouldAutoHideBlock(30, 28));
        assertFalse(config.shouldAutoHideBlock(30, 26));

        // Player at Y=20 -> Section 1 and blocks 20..30 not hidden
        assertFalse(config.shouldAutoHideSection(20, 1));
        assertFalse(config.shouldAutoHideBlock(20, 20));
    }
}
