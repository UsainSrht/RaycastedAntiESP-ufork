package games.cubi.raycastedantiesp.core.config.raycast;

import games.cubi.raycastedantiesp.core.config.Config;
import games.cubi.raycastedantiesp.core.config.ConfigReader;
import org.spongepowered.configurate.ConfigurationNode;

public record HideBelowYConfig(
        boolean enabled,
        int yCutoff,
        int playerYTrigger,
        int verticalDistanceBelowPlayer,
        int verticalDistanceAbovePlayer,
        int unhideBuffer,
        int recheckIntervalTicks,
        boolean movementTrigger
) implements Config {
    public static HideBelowYConfig load(ConfigurationNode node, String path) {
        ConfigurationNode enabledNode = node.node("enabled");
        ConfigurationNode cutoffNode = node.node("y-cutoff");
        ConfigurationNode triggerNode = node.node("player-y-trigger");
        ConfigurationNode distanceBelowNode = node.node("vertical-distance-below-player");
        ConfigurationNode distanceAboveNode = node.node("vertical-distance-above-player");
        ConfigurationNode bufferNode = node.node("unhide-buffer");
        ConfigurationNode recheckIntervalNode = node.node("recheck-interval-ticks");
        ConfigurationNode movementTriggerNode = node.node("movement-trigger");

        boolean enabled = !enabledNode.virtual() && ConfigReader.bool(enabledNode, path + ".enabled");
        int yCutoff = cutoffNode.virtual() ? 60 : ConfigReader.integer(cutoffNode, path + ".y-cutoff");
        int playerYTrigger = triggerNode.virtual() ? 64 : ConfigReader.integer(triggerNode, path + ".player-y-trigger");
        int verticalDistanceBelow = distanceBelowNode.virtual() ? 48 : ConfigReader.integer(distanceBelowNode, path + ".vertical-distance-below-player");
        int verticalDistanceAbove = distanceAboveNode.virtual() ? 48 : ConfigReader.integer(distanceAboveNode, path + ".vertical-distance-above-player");
        int unhideBuffer = bufferNode.virtual() ? 8 : ConfigReader.integer(bufferNode, path + ".unhide-buffer");
        int recheckIntervalTicks = recheckIntervalNode.virtual() ? 5 : Math.max(1, ConfigReader.integer(recheckIntervalNode, path + ".recheck-interval-ticks"));
        boolean movementTrigger = movementTriggerNode.virtual() || ConfigReader.bool(movementTriggerNode, path + ".movement-trigger");

        return new HideBelowYConfig(
                enabled,
                yCutoff,
                playerYTrigger,
                verticalDistanceBelow,
                verticalDistanceAbove,
                unhideBuffer,
                recheckIntervalTicks,
                movementTrigger
        );
    }

    /**
     * Determines whether a section at {@code sectionY} should be automatically hidden for a viewer at {@code playerY}.
     * Section block Y ranges from (sectionY * 16) to (sectionY * 16 + 15).
     */
    public boolean shouldAutoHideSection(double playerY, int sectionY) {
        if (!enabled) {
            return false;
        }
        int sectionMinY = sectionY << 4;
        int sectionMaxY = sectionMinY + 15;

        // Absolute Y cutoff condition: if player is at/above trigger Y (with buffer), hide sections whose top is below yCutoff
        if (playerY >= (playerYTrigger - unhideBuffer) && sectionMaxY < yCutoff) {
            return true;
        }
        // Relative distance below player: hide sections deeper than verticalDistanceBelowPlayer below playerY (only below yCutoff)
        if (verticalDistanceBelowPlayer > 0
                && sectionMaxY < yCutoff
                && sectionMaxY < (playerY - verticalDistanceBelowPlayer - unhideBuffer)) {
            return true;
        }
        // Relative distance above player: hide sections higher than verticalDistanceAbovePlayer above playerY (only when player is below yCutoff)
        if (verticalDistanceAbovePlayer > 0
                && playerY < yCutoff
                && sectionMinY > (playerY + verticalDistanceAbovePlayer + unhideBuffer)) {
            return true;
        }
        return false;
    }

    /**
     * Determines whether a block at {@code blockY} should be automatically hidden for a viewer at {@code playerY}.
     */
    public boolean shouldAutoHideBlock(double playerY, int blockY) {
        if (!enabled) {
            return false;
        }
        // Absolute Y cutoff condition: if player is at/above trigger Y (with buffer), hide blocks below yCutoff
        if (playerY >= (playerYTrigger - unhideBuffer) && blockY < yCutoff) {
            return true;
        }
        // Relative distance below player: hide blocks deeper than verticalDistanceBelowPlayer below playerY (only below yCutoff)
        if (verticalDistanceBelowPlayer > 0
                && blockY < yCutoff
                && blockY < (playerY - verticalDistanceBelowPlayer - unhideBuffer)) {
            return true;
        }
        // Relative distance above player: hide sections higher than verticalDistanceAbovePlayer above playerY (only when player is below yCutoff)
        if (verticalDistanceAbovePlayer > 0
                && playerY < yCutoff
                && blockY > (playerY + verticalDistanceAbovePlayer + unhideBuffer)) {
            return true;
        }
        return false;
    }
}
