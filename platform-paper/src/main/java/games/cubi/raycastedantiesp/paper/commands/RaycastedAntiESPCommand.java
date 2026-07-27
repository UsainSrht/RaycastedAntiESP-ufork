/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright © 2026 Cubicake.
 * This file is part of RaycastedAntiESP.
 * RaycastedAntiESP is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License v3.0 only, which can be accessed at https://www.gnu.org/licenses/agpl-3.0.html.
 * See README.md for warranty disclaimer and further information.
 */

package games.cubi.raycastedantiesp.paper.commands;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import games.cubi.locatables.api.Locatable;
import games.cubi.locatables.api.MutableFloatingSpatial;
import games.cubi.locatables.api.Spatial;
import games.cubi.locatables.implementations.MutableLocatableImpl;
import games.cubi.locatables.implementations.MutableSpatialImpl;
import games.cubi.logs.Logger;
import games.cubi.raycastedantiesp.core.chunks.BlockChunkData;
import games.cubi.raycastedantiesp.core.chunks.ChunkData;
import games.cubi.raycastedantiesp.core.config.ConfigManager;
import games.cubi.raycastedantiesp.core.config.raycast.ChunkSectionConfig;
import games.cubi.raycastedantiesp.core.tracked.TrackedChunkSection;
import games.cubi.raycastedantiesp.core.tracked.TrackedEntity;
import games.cubi.raycastedantiesp.core.players.PlayerData;
import games.cubi.raycastedantiesp.core.players.PlayerRegistry;
import games.cubi.raycastedantiesp.core.raycast.ChunkSectionLosProbe;
import games.cubi.raycastedantiesp.core.raycast.ChunkSectionStatusReport;
import games.cubi.raycastedantiesp.core.raycast.RaycastUtil;
import games.cubi.raycastedantiesp.core.view.AbstractBlockView;
import games.cubi.raycastedantiesp.core.view.EntityView;
import games.cubi.raycastedantiesp.paper.RaycastedAntiESP;
import games.cubi.raycastedantiesp.paper.UpdateChecker;
import games.cubi.raycastedantiesp.paper.packets.PacketEventsPaperBlockInfoResolver;
import games.cubi.raycastedantiesp.packetevents.viewcontrollers.PacketEventsBlockViewController;

import games.cubi.raycastedantiesp.paper.utils.PaperScheduler;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.kyori.adventure.text.minimessage.MiniMessage;

import net.strokkur.commands.*;
import net.strokkur.commands.arguments.StringArg;
import net.strokkur.commands.arguments.StringArgType;
import net.strokkur.commands.paper.Description;
import net.strokkur.commands.permission.Permission;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

// Credit to Strokkur for making StrokkCommands, a non-hideous way to use the power of brigadier.

@Command("raycastedantiesp")
@Aliases({"raesp", "antiesp", "reo"})
@Description("Command for management of the RaycastedAntiESP plugin")
@Permission("raycastedantiesp.command")
public class RaycastedAntiESPCommand {
    @DefaultExecutes
    public void helpCommand(CommandSender sender) {
        sender.sendRichMessage("<white>RaycastedAntiESP <yellow>v" + RaycastedAntiESP.get().getDescription().getVersion());
        sender.sendRichMessage("<white>Commands:");
        sender.sendRichMessage("<green>/raycastedantiesp reload <gray>- Reloads the config");
        sender.sendRichMessage("<green>/raycastedantiesp config-values <gray>- Shows all config values");
        sender.sendRichMessage("<green>/raycastedantiesp set <key> <value> <gray>- Sets a config value");
        sender.sendRichMessage("<green>/raycastedantiesp add <key> <value> <gray>- Adds a value to a list config");
        sender.sendRichMessage("<green>/raycastedantiesp remove <key> <value> <gray>- Removes a value from a list config");
        sender.sendRichMessage("<green>/raycastedantiesp test chunk-section <x> <y> <z> [player] <gray>- Test LOS to a section (block xyz)");
        sender.sendRichMessage("<green>/raycastedantiesp test show-chunk-section <x> <y> <z> [player] <gray>- Force show chunk section to client");
        sender.sendRichMessage("<green>/raycastedantiesp test status-chunk-section [player] <gray>- Snapshot chunk-section show/hide + raycast stats");
        sender.sendRichMessage(Attribution.attributionCommandDescription); //Using constant from Attribution class to ensure that it cannot be deleted without the developer noticing that they are obligated to replace it with an equivalent notice.
    }

    @Executes("reload")
    void reloadCommand(CommandSender sender) {
        try {
            ConfigManager.get().load();
            sender.sendMessage("[RaycastedAntiESP] Config reloaded.");
        } catch (RuntimeException e) {
            sender.sendRichMessage("<red>[RaycastedAntiESP] Config reload rejected: <white>" + e.getMessage());
        }
    }

    @Executes("config-values")
    void configValuesCommand(CommandSender sender) {
        ConfigManager config = ConfigManager.get();
        //dynamic config values
        sender.sendMessage("[RaycastedAntiESP] Config values: ");

        for (var entry : config.getConfigValues().entrySet()) {
            String path = entry.getKey();
            Object val = entry.getValue();
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<green>" + path + "<gray> = <white>" + val));
        }
    }

    @Executes("set")
    void setCommand(@StringArg(StringArgType.STRING) String key, @StringArg(StringArgType.GREEDY) String value, CommandSender sender) {
        ConfigManager config = ConfigManager.get();
        ConfigManager.SetConfigResult result = config.setConfigValue(key, value);
        sendConfigMutationResult(sender, result, "Set", key, value);
    }

    @Executes("add")
    void addCommand(@StringArg(StringArgType.STRING) String key, @StringArg(StringArgType.GREEDY) String value, CommandSender sender) {
        ConfigManager config = ConfigManager.get();
        ConfigManager.SetConfigResult result = config.addConfigListValue(key, value);
        sendConfigMutationResult(sender, result, "Added", key, value);
    }

    @Executes("remove")
    void removeCommand(@StringArg(StringArgType.STRING) String key, @StringArg(StringArgType.GREEDY) String value, CommandSender sender) {
        ConfigManager config = ConfigManager.get();
        ConfigManager.SetConfigResult result = config.removeConfigListValue(key, value);
        sendConfigMutationResult(sender, result, "Removed", key, value);
    }

    @Executes("check-for-updates")
    void checkForUpdatesCommand(CommandSender sender) {
        UpdateChecker.checkForUpdates(RaycastedAntiESP.get(), sender);
    }

    @Executes("print-block-ids")
    void printBlockIDsCommand() {
        PacketEventsPaperBlockInfoResolver.get.iterateBlockIDs(true);
    }

    private void sendConfigMutationResult(CommandSender sender, ConfigManager.SetConfigResult result, String action, String key, String value) {
        if (!result.success()) {
            sender.sendRichMessage("<red>Invalid config change: <white>" + result.message());
            return;
        }
        sender.sendRichMessage("<white>" + action + " <green>" + value + "<white> for <green>" + key);
        if (result.restartRequired()) {
            sender.sendRichMessage("<yellow>This change was saved but requires a restart: <white>" + result.message());
        }
    }

    @Subcommand("test")
    static class TestCommands {
        @Executes("log")
        void logString(@StringArg(StringArgType.GREEDY) String message) {
            Logger.info(message, 1, RaycastedAntiESPCommand.class);
        }

        @Executes("location-drift")
        void testCommand(CommandSender sender) {
            assert Attribution.class == Attribution.class;
            assert Attribution.READ_COMMENTS_BEFORE_EDITING_OR_DELETING_CLASS_OR_FACE_LEGAL_ACTION == 0; //Using constant from Attribution class to ensure that it cannot be deleted without the developer noticing that they are obligated to replace it with an equivalent notice.
            Player player = (Player) sender;
            PlayerData playerData = PlayerRegistry.getInstance().getPlayerData(player.getUniqueId());
            Entity closestEntity = player.getNearbyEntities(10,10,10).getFirst();
            if (closestEntity == null) return;
            player.sendRichMessage("Closest entity is "+closestEntity.getName());
            Spatial entityPosition = playerData.entityView().getPosition(closestEntity.getUniqueId());
            Location bukkitLoc = closestEntity.getLocation().clone();
            player.sendRichMessage("Entity location according to PacketEvents is "+entityPosition);
            player.sendRichMessage("Entity location according to Bukkit is "+bukkitLoc);
            double driftX = Math.abs(entityPosition.x() - bukkitLoc.getX());
            double driftZ = Math.abs(entityPosition.z() - bukkitLoc.getZ());
            if (driftX < 0.0005) driftX = 0;
            if (driftZ < 0.0005) driftZ = 0;
            Logger.debug("Drift is X: "+driftX+" Z: "+driftZ);
            sender.sendRichMessage("Drift is X: "+driftX+" Z: "+driftZ);
        }

        @Executes("benchmark")
        void debugCommand(Player player) throws CommandSyntaxException {
            //benchmark raycast speed by generating 1000 locatables normally distributed approx 50 blocks around the player and raycasting to them, then printing the average time taken

            Locatable[] locatables = new Locatable[10000];
            PlayerData playerData = PlayerRegistry.getInstance().getPlayerData(player.getUniqueId());
            Locatable playerLocatable = playerData.ownLocation();
            MutableFloatingSpatial unitDirection = new MutableSpatialImpl(0, 0, 0);
            for (int i = 0; i < locatables.length; i++) {
                unitDirection.setX(Math.random() - 0.5);
                unitDirection.setY(Math.random() - 0.5);
                unitDirection.setZ(Math.random() - 0.5);
                unitDirection.normalise();
                unitDirection.scalarMultiply(50);
                locatables[i] = new MutableLocatableImpl(playerLocatable.world(), playerLocatable.x(), playerLocatable.y(), playerLocatable.z()).add(unitDirection);
            }
            Bukkit.getAsyncScheduler().runNow(RaycastedAntiESP.get(), (ignored) -> {
                int successfulRays = 0;
                long startTime = System.nanoTime();
                for (Locatable locatable : locatables) {
                    if (RaycastUtil.raycast(playerLocatable, locatable, 3, 0, 100, false, playerData.blockView(), 1, null)) successfulRays++;
                }
                long endTime = System.nanoTime();
                long duration = endTime - startTime;
                double averageTime = duration / (double) locatables.length;
                final int successfulRaysFinal = successfulRays;
                PaperScheduler.runForAudience(RaycastedAntiESP.get(), player, () -> {
                    player.sendRichMessage("Average raycast time: " + averageTime + " nanoseconds");
                    player.sendRichMessage("Total raycast time: " + duration + " nanoseconds");
                    player.sendRichMessage("Successful rays: " + successfulRaysFinal + "/" + locatables.length);
                });
            });
        }

        @Executes("loaded-chunks")
        void loadedChunksCommand(Player player) {
            PlayerData playerData = PlayerRegistry.getInstance().getPlayerData(player.getUniqueId());
            AbstractBlockView<?, ?> pbsm = (AbstractBlockView<?, ?>) playerData.blockView();
            player.sendMessage(pbsm.loadedChunkCount() +"chunks loaded");
        }

        @Executes("chunk-section")
        void testChunkSectionSelf(int x, int y, int z, CommandSender sender) {
            if (!(sender instanceof Player player)) {
                sender.sendRichMessage("<red>[RaycastedAntiESP] Specify a player: /reo test chunk-section <x> <y> <z> <player>");
                return;
            }
            testChunkSection(sender, player, x, y, z);
        }

        @Executes("chunk-section")
        void testChunkSectionOther(int x, int y, int z, Player target, CommandSender sender) {
            testChunkSection(sender, target, x, y, z);
        }

        @Executes("show-chunk-section")
        void showChunkSectionSelf(int x, int y, int z, CommandSender sender) {
            if (!(sender instanceof Player player)) {
                sender.sendRichMessage("<red>[RaycastedAntiESP] Specify a player: /reo test show-chunk-section <x> <y> <z> <player>");
                return;
            }
            showChunkSection(sender, player, x, y, z);
        }

        @Executes("show-chunk-section")
        void showChunkSectionOther(int x, int y, int z, Player target, CommandSender sender) {
            showChunkSection(sender, target, x, y, z);
        }

        @Executes("status-chunk-section")
        void statusChunkSectionSelf(CommandSender sender) {
            if (!(sender instanceof Player player)) {
                sender.sendRichMessage("<red>[RaycastedAntiESP] Specify a player: /reo test status-chunk-section <player>");
                return;
            }
            statusChunkSection(sender, player);
        }

        @Executes("status-chunk-section")
        void statusChunkSectionOther(CommandSender sender, Player target) {
            statusChunkSection(sender, target);
        }

        private void statusChunkSection(CommandSender sender, Player viewer) {
            PlayerData playerData = PlayerRegistry.getInstance().getPlayerData(viewer.getUniqueId());
            if (playerData == null) {
                reportChunkSectionLine(sender, "<red>Player data not loaded for " + describeViewer(viewer.getUniqueId()) + ".");
                return;
            }
            Locatable eye = playerData.ownLocation();
            if (eye == null || eye.world() == null) {
                reportChunkSectionLine(sender, "<red>Viewer has no location yet.");
                return;
            }
            ChunkSectionConfig sectionConfig = ConfigManager.get().getChunkSectionConfig();
            if (!sectionConfig.enabled()) {
                reportChunkSectionLine(sender, "<yellow>checks.chunk-section.enabled is false — enable it for live culling.</yellow>");
            }

            reportChunkSectionLine(sender, "<gold>----- <yellow><bold>chunk-section status (scanning…)</bold></yellow> -----</gold>");
            reportChunkSectionLine(sender, "<gray>viewer=</gray><white>" + describeViewer(viewer.getUniqueId()) + "</white>");
            final CommandSender reportTo = sender;
            final PlayerData data = playerData;
            final Locatable eyeSnapshot = eye;
            final ChunkSectionConfig configSnapshot = sectionConfig;
            Bukkit.getAsyncScheduler().runNow(RaycastedAntiESP.get(), (ignored) -> {
                ChunkSectionStatusReport report = ChunkSectionStatusReport.collect(
                        eyeSnapshot, configSnapshot, data.blockView()
                );
                PaperScheduler.runForAudience(RaycastedAntiESP.get(), reportTo, () -> sendStatusReport(reportTo, report));
            });
        }

        private static void sendStatusReport(CommandSender sender, ChunkSectionStatusReport report) {
            reportChunkSectionLine(sender, "<gray>sections total=</gray><white>" + report.total()
                    + "</white> <gray>shown=</gray><green>" + report.shown()
                    + "</green> <gray>hidden=</gray><red>" + report.hidden() + "</red>");
            reportChunkSectionLine(sender, "<gray>raycasts cast=</gray><white>" + report.raycastsCast()
                    + "</white> <gray>succeeded=</gray><green>" + report.raycastsSucceeded()
                    + "</green> <gray>occluded=</gray><red>" + report.raycastsOccluded()
                    + "</red> <gray>skippedHide=</gray><yellow>" + report.skippedRaycastHide() + "</yellow>");
            if (report.hideReasons().isEmpty()) {
                reportChunkSectionLine(sender, "<gray>hideReasons=</gray><dark_gray>(none)</dark_gray>");
            } else {
                StringBuilder reasons = new StringBuilder("<gray>hideReasons=</gray>");
                boolean first = true;
                for (Map.Entry<String, Integer> entry : report.hideReasons().entrySet()) {
                    if (!first) {
                        reasons.append(" ");
                    }
                    first = false;
                    reasons.append("<yellow>").append(entry.getKey()).append(" ").append(entry.getValue()).append("x</yellow>");
                }
                reportChunkSectionLine(sender, reasons.toString());
            }
            reportChunkSectionLine(sender, "<gold>----- <yellow>end chunk-section status</yellow> -----</gold>");
        }

        private void testChunkSection(CommandSender sender, Player viewer, int blockX, int blockY, int blockZ) {
            PlayerData playerData = PlayerRegistry.getInstance().getPlayerData(viewer.getUniqueId());
            if (playerData == null) {
                reportChunkSectionLine(sender, "<red>Player data not loaded for " + describeViewer(viewer.getUniqueId()) + ".");
                return;
            }
            Locatable eye = playerData.ownLocation();
            if (eye == null || eye.world() == null) {
                reportChunkSectionLine(sender, "<red>Viewer has no location yet.");
                return;
            }
            ChunkSectionConfig sectionConfig = ConfigManager.get().getChunkSectionConfig();
            if (!sectionConfig.enabled()) {
                reportChunkSectionLine(sender, "<yellow>checks.chunk-section.enabled is false — enable it for live culling.</yellow>");
            }

            int chunkX = blockX >> 4;
            int sectionY = blockY >> 4;
            int chunkZ = blockZ >> 4;
            ChunkSectionLosProbe.Decision decision = ChunkSectionLosProbe.evaluate(
                    eye, blockX, blockY, blockZ, sectionConfig, playerData.blockView()
            );

            TrackedChunkSection tracked = playerData.blockView().getTrackedChunkSection(eye.world(), chunkX, sectionY, chunkZ);

            reportChunkSectionLine(sender, "<gold>----- <yellow><bold>chunk-section LOS test</bold></yellow> -----</gold>");
            reportChunkSectionLine(sender, "<gray>viewer=</gray><white>" + describeViewer(viewer.getUniqueId()) + "</white>");
            reportChunkSectionLine(sender, "<gray>block=</gray><yellow>" + blockX + " " + blockY + " " + blockZ
                    + "</yellow> <gray>→</gray> <gray>section=</gray><aqua>" + chunkX + " " + sectionY + " " + chunkZ + "</aqua>");
            reportChunkSectionLine(sender, "<gray>tracked=</gray>" + (decision.trackedPresent() ? "<green>true</green>" : "<red>false</red>")
                    + " <gray>clientVisible=</gray>" + (decision.trackedVisible() ? "<green>true</green>" : "<red>false</red>"));
            reportChunkSectionLine(sender, "<gray>decision=</gray>" + (decision.wouldShow() ? "<green><bold>SHOW</bold></green>" : "<red><bold>HIDE</bold></red>")
                    + " <gray>reason=</gray><yellow>" + decision.reason() + "</yellow>");

            List<String> clientReasons = buildClientVisibleReasons(
                    eye, chunkX, sectionY, chunkZ, sectionConfig, playerData.blockView(), tracked, decision
            );
            String formattedReasons = clientReasons.stream()
                    .map(reason -> colorizeReason(reason))
                    .collect(Collectors.joining("<gray>; </gray>"));
            reportChunkSectionLine(sender, "<gray>clientVisibleReason=</gray>" + formattedReasons);

            if (decision.trackedVisible() && !decision.wouldShow()) {
                reportChunkSectionLine(sender, "<gray>note=</gray><yellow>client still visible while LOS says HIDE (see clientVisibleReason above)</yellow>");
            }

            reportChunkSectionLine(sender, "<gray>eyeInside=</gray>" + (decision.eyeInside() ? "<green>true</green>" : "<white>false</white>")
                    + " <gray>alwaysShow=</gray>" + (decision.alwaysShow() ? "<green>true</green>" : "<white>false</white>")
                    + " <gray>inRadius=</gray>" + (decision.withinRaycastRadius() ? "<green>true</green>" : "<red>false</red>")
                    + " <gray>fullyOpen=</gray>" + (decision.fullyOpen() ? "<green>true</green>" : "<white>false</white>")
                    + " <gray>visGraphAutoHide=</gray>" + (decision.visGraphAutoHide() ? "<red>true</red>" : "<white>false</white>")
                    + " <gray>visGraphUnreachableBelow=</gray>" + (decision.visGraphUnreachableBelow() ? "<yellow>true</yellow>" : "<white>false</white>")
                    + " <gray>raycast=</gray>" + (decision.raycastLos() ? "<green>true</green>" : "<red>false</red>"));
            sendSectionContentSummary(sender, playerData, chunkX, sectionY, chunkZ, decision.fullyOpen());

            boolean raysOn = playerData.toggleChunkSectionRayDebugTarget(chunkX, sectionY, chunkZ);
            if (raysOn) {
                reportChunkSectionLine(sender, "<gray>focused ray debug=</gray><green><bold>ON</bold></green> <gray>(only this section, every 5 ticks)</gray>");
            } else {
                reportChunkSectionLine(sender, "<gray>focused ray debug=</gray><red><bold>OFF</bold></red>");
            }
            reportChunkSectionLine(sender, "<gold>----- <yellow>end chunk-section LOS test</yellow> -----</gold>");
        }

        private void showChunkSection(CommandSender sender, Player viewer, int blockX, int blockY, int blockZ) {
            PlayerData playerData = PlayerRegistry.getInstance().getPlayerData(viewer.getUniqueId());
            if (playerData == null) {
                reportChunkSectionLine(sender, "<red>Player data not loaded for " + describeViewer(viewer.getUniqueId()) + ".");
                return;
            }
            Locatable eye = playerData.ownLocation();
            if (eye == null || eye.world() == null) {
                reportChunkSectionLine(sender, "<red>Viewer has no location yet.");
                return;
            }

            int chunkX = blockX >> 4;
            int sectionY = blockY >> 4;
            int chunkZ = blockZ >> 4;

            boolean success = PacketEventsBlockViewController.get().forceShowChunkSection(playerData, chunkX, sectionY, chunkZ);

            reportChunkSectionLine(sender, "<gold>----- <yellow><bold>show-chunk-section</bold></yellow> -----</gold>");
            reportChunkSectionLine(sender, "<gray>viewer=</gray><white>" + describeViewer(viewer.getUniqueId()) + "</white>");
            reportChunkSectionLine(sender, "<gray>block=</gray><yellow>" + blockX + " " + blockY + " " + blockZ
                    + "</yellow> <gray>→</gray> <gray>section=</gray><aqua>" + chunkX + " " + sectionY + " " + chunkZ + "</aqua>");
            if (success) {
                reportChunkSectionLine(sender, "<green>Force showed chunk section to client.</green>");
            } else {
                reportChunkSectionLine(sender, "<red>Failed to force show chunk section (wire column not cached or user offline).</red>");
            }
            reportChunkSectionLine(sender, "<gold>----- <yellow>end show-chunk-section</yellow> -----</gold>");
        }

        private static List<String> buildClientVisibleReasons(
                Locatable eye,
                int chunkX,
                int sectionY,
                int chunkZ,
                ChunkSectionConfig sectionConfig,
                games.cubi.raycastedantiesp.core.view.BlockView blockView,
                TrackedChunkSection tracked,
                ChunkSectionLosProbe.Decision decision
        ) {
            List<String> reasons = new ArrayList<>();

            if (tracked == null) {
                reasons.add("UNTRACKED (section is not tracked in block view — default client rendering)");
                return reasons;
            }

            if (!tracked.visible()) {
                reasons.add("HIDDEN (section is culled and hidden as air on client)");
                return reasons;
            }

            if (decision.wouldShow()) {
                reasons.add("DIRECT_SHOW: " + decision.reason());
            }

            boolean neighborPadding = sectionConfig.neighborPadding();
            int preemptiveReveal = sectionConfig.preemptiveNeighborReveal();
            int r = Math.max(preemptiveReveal > 0 ? preemptiveReveal : 0, neighborPadding ? 1 : 0);

            if (r > 0) {
                LongOpenHashSet reachable = new LongOpenHashSet();
                LongOpenHashSet frontier = new LongOpenHashSet();
                ChunkSectionLosProbe.collectFrontierFromEye(eye, sectionConfig.raycastRadiusChunks(), blockView, reachable, frontier);

                List<String> neighborPaddingDetails = new ArrayList<>();
                for (int dx = -r; dx <= r; dx++) {
                    for (int dy = -r; dy <= r; dy++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            int nx = chunkX + dx;
                            int ny = sectionY + dy;
                            int nz = chunkZ + dz;
                            TrackedChunkSection neighborTracked = blockView.getTrackedChunkSection(eye.world(), nx, ny, nz);
                            if (neighborTracked != null) {
                                ChunkSectionLosProbe.Decision neighborDecision = ChunkSectionLosProbe.evaluateSection(
                                        eye, nx, ny, nz, sectionConfig, blockView, reachable, frontier
                                );
                                if (neighborDecision.wouldShow() && (preemptiveReveal > 0 || neighborTracked.visible())) {
                                    neighborPaddingDetails.add("section " + nx + " " + ny + " " + nz + " (" + neighborDecision.reason() + ")");
                                }
                            }
                        }
                    }
                }
                if (!neighborPaddingDetails.isEmpty()) {
                    if (neighborPaddingDetails.size() == 1) {
                        reasons.add("NEIGHBOR_PADDING from " + neighborPaddingDetails.get(0));
                    } else {
                        reasons.add("NEIGHBOR_PADDING from " + neighborPaddingDetails.size() + " neighbors (e.g. " + neighborPaddingDetails.get(0) + ")");
                    }
                }
            }

            int wantHiddenSince = tracked.wantHiddenSinceTick();
            if (wantHiddenSince != TrackedChunkSection.NOT_WANTING_HIDE) {
                int currentTick = Bukkit.getCurrentTick();
                int elapsed = currentTick - wantHiddenSince;
                int delayTicks = sectionConfig.hideDelayTicks();
                if (elapsed < delayTicks) {
                    reasons.add("HIDE_DELAY_PENDING (elapsed " + elapsed + "/" + delayTicks + " ticks)");
                } else {
                    reasons.add("HIDE_DELAY_ELAPSED (wanted hide since tick " + wantHiddenSince + ")");
                }
            }

            int lastChecked = tracked.lastChecked();
            if (lastChecked != TrackedChunkSection.NEVER_CHECKED) {
                int currentTick = Bukkit.getCurrentTick();
                int age = currentTick - lastChecked;
                int recheckTicks = sectionConfig.visibleRecheckIntervalTicks();
                if (recheckTicks > 0 && age < recheckTicks) {
                    reasons.add("STICKY_RECHECK (last checked " + age + "/" + recheckTicks + " ticks ago)");
                }
            } else {
                reasons.add("UNCHECKED (tracked but never evaluated by engine tick yet)");
            }

            if (reasons.isEmpty()) {
                reasons.add("CLIENT_VISIBLE (pending engine tick update)");
            }

            return reasons;
        }

        private static String colorizeReason(String reason) {
            if (reason.startsWith("NEIGHBOR_PADDING")) {
                int fromIdx = reason.indexOf(" from ");
                if (fromIdx != -1) {
                    return "<gold><bold>NEIGHBOR_PADDING</bold></gold> <gray>from " + reason.substring(fromIdx + 6) + "</gray>";
                }
                return "<gold><bold>" + reason + "</bold></gold>";
            }
            if (reason.startsWith("HIDE_DELAY_PENDING")) {
                return "<yellow><bold>HIDE_DELAY_PENDING</bold></yellow> <gray>" + reason.substring("HIDE_DELAY_PENDING".length()).trim() + "</gray>";
            }
            if (reason.startsWith("HIDE_DELAY_ELAPSED")) {
                return "<yellow><bold>HIDE_DELAY_ELAPSED</bold></yellow> <gray>" + reason.substring("HIDE_DELAY_ELAPSED".length()).trim() + "</gray>";
            }
            if (reason.startsWith("STICKY_RECHECK")) {
                return "<yellow><bold>STICKY_RECHECK</bold></yellow> <gray>" + reason.substring("STICKY_RECHECK".length()).trim() + "</gray>";
            }
            if (reason.startsWith("DIRECT_SHOW")) {
                return "<green><bold>DIRECT_SHOW</bold></green><gray>: </gray><green>" + reason.substring("DIRECT_SHOW:".length()).trim() + "</green>";
            }
            if (reason.startsWith("HIDDEN")) {
                return "<dark_gray>" + reason + "</dark_gray>";
            }
            if (reason.startsWith("UNTRACKED")) {
                return "<red>" + reason + "</red>";
            }
            if (reason.startsWith("UNCHECKED")) {
                return "<yellow>" + reason + "</yellow>";
            }
            return "<yellow>" + reason + "</yellow>";
        }

        /** Chat + plain console line (easy to copy from server log). */
        private static void reportChunkSectionLine(CommandSender sender, String richText) {
            sender.sendRichMessage("<gray>[<gold>RaycastedAntiESP</gold>]</gray> " + richText);
            String plain = richText.replaceAll("<[^>]+>", "");
            Logger.info("[chunk-section-test] " + plain, 1, RaycastedAntiESPCommand.class);
        }

        @Executes("entity-id")
        void getFromEntityID(int entityID, Player player) {
            PlayerData playerData = PlayerRegistry.getInstance().getPlayerData(player.getUniqueId());
            if (playerData == null) {
                player.sendRichMessage("<red>No player data is registered for " + describeViewer(player.getUniqueId()) + ".");
                return;
            }

            Entity bukkitEntity = SpigotConversionUtil.getEntityById(player.getWorld(), entityID);
            player.sendRichMessage("<white>Entity with ID " + entityID + " for viewer " + describeViewer(playerData.getPlayerUUID()) + ":");
            if (bukkitEntity == null) {
                player.sendRichMessage("<gray>According to Bukkit: <red>not found");
            } else {
                sendBukkitEntityData(player, bukkitEntity);
            }

            int matches = reportEntityIDMatches(player, playerData, entityID);
            if (matches == 0) {
                player.sendRichMessage("<gray>According to PacketEvents: <red>not found in either tracked view");
            }
        }

        @Executes("entity-id")
        void getFromEntityID(int entityID, CommandSender sender) {
            sender.sendRichMessage("<white>Searching all connected player views for entity ID " + entityID + ":");
            int matches = 0;
            for (PlayerData playerData : PlayerRegistry.getInstance().getAllPlayerData()) {
                if (!playerData.isConnected()) {
                    continue;
                }
                matches += reportEntityIDMatches(sender, playerData, entityID);
            }
            if (matches == 0) {
                sender.sendRichMessage("<red>No tracked entity with ID " + entityID + " was found.");
            }
        }

        @Executes("entity-uuid-raw")
        void getFromRawUUID(String entityUUIDraw, CommandSender sender) {
            sender.sendRichMessage("<white>Searching all connected player views for entity UUID " + entityUUIDraw + ":");
            UUID entityUUID = UUID.fromString(entityUUIDraw);
            getFromUUID(entityUUID, sender);
        }
        void getFromUUID(UUID entityUUID, CommandSender sender) {
            int matches = 0;
            for (PlayerData playerData : PlayerRegistry.getInstance().getAllPlayerData()) {
                if (!playerData.isConnected()) {
                    continue;
                }
                matches += reportEntityUUIDMatches(sender, playerData, entityUUID);
            }
            if (matches == 0) {
                sender.sendRichMessage("<red>No tracked entity with UUID " + entityUUID + " was found.");
            }
        }

        @Executes("entity-uuid")
        void getFromEntityUUID(Entity entity, CommandSender sender) {
            UUID entityUUID = entity.getUniqueId();
            sender.sendRichMessage("<white>Bukkit entity data:");
            sendBukkitEntityData(sender, entity);
            getFromUUID(entityUUID, sender);
        }

        private int reportEntityIDMatches(CommandSender sender, PlayerData playerData, int entityID) {
            return reportEntityIDMatches(sender, playerData, playerData.entityView(), "entity view", entityID)
                    + reportEntityIDMatches(sender, playerData, playerData.playerView(), "player view", entityID);
        }

        private int reportEntityIDMatches(CommandSender sender, PlayerData playerData, EntityView<?> view, String viewName, int entityID) {
            int matches = 0;
            for (UUID entityUUID : view.getKnownEntities()) {
                TrackedEntity<?, ?> entity = view.getEntity(entityUUID);
                if (entity == null || entity.entityID() != entityID) {
                    continue;
                }
                sendTrackedEntityMatch(sender, playerData, viewName, entity);
                matches++;
            }
            return matches;
        }

        private int reportEntityUUIDMatches(CommandSender sender, PlayerData playerData, UUID entityUUID) {
            int matches = reportEntityUUIDMatch(sender, playerData, playerData.entityView(), "entity view", entityUUID);
            return matches + reportEntityUUIDMatch(sender, playerData, playerData.playerView(), "player view", entityUUID);
        }

        private int reportEntityUUIDMatch(CommandSender sender, PlayerData playerData, EntityView<?> view, String viewName, UUID entityUUID) {
            TrackedEntity<?, ?> entity = view.getEntity(entityUUID);
            if (entity == null) {
                return 0;
            }
            sendTrackedEntityMatch(sender, playerData, viewName, entity);
            return 1;
        }

        private void sendTrackedEntityMatch(CommandSender sender, PlayerData playerData, String viewName, TrackedEntity<?, ?> entity) {
            sender.sendRichMessage("<green>Match for viewer <white>" + describeViewer(playerData.getPlayerUUID()) + "<green> in <white>" + viewName + "<green>:");
            sender.sendRichMessage("<gray>According to PacketEvents: <white>" + entity);
        }

        private void sendBukkitEntityData(CommandSender sender, Entity entity) {
            sender.sendRichMessage("<gray>According to Bukkit: <white>" + entity);
            sender.sendRichMessage("<gray>Entity ID: <white>" + entity.getEntityId());
            sender.sendRichMessage("<gray>Entity UUID: <white>" + entity.getUniqueId());
            sender.sendRichMessage("<gray>Entity type: <white>" + entity.getType());
            sender.sendRichMessage("<gray>Entity name: <white>" + entity.getName());
            sender.sendRichMessage("<gray>Entity string: <white>" + entity.getAsString());
        }

        private String describeViewer(UUID playerUUID) {
            Player player = Bukkit.getPlayer(playerUUID);
            return player == null ? playerUUID.toString() : player.getName() + " (" + playerUUID + ")";
        }

        /**
         * Reports what the plugin stored for the section. {@code fullyOpen} means zero occluding cells
         * in that store — non-occluding blocks (leaves, snow, glass, …) still count as “has blocks”.
         */
        private void sendSectionContentSummary(CommandSender sender, PlayerData playerData, int chunkX, int sectionY, int chunkZ, boolean fullyOpen) {
            BlockChunkData data = playerData.blockView().getBlockChunkData(chunkX, sectionY, chunkZ);
            if (data == null) {
                reportChunkSectionLine(sender, "<gray>stored=</gray><dark_gray>none (air / not in block store)</dark_gray>");
                return;
            }
            int nonAir = 0;
            int occluding = 0;
            Map<Integer, Integer> topIds = new LinkedHashMap<>();
            for (int packed = 0; packed < ChunkData.BLOCK_COUNT; packed++) {
                int lx = ChunkData.unpackX(packed);
                int ly = ChunkData.unpackY(packed);
                int lz = ChunkData.unpackZ(packed);
                int blockId = data.getBlockID(lx, ly, lz);
                if (blockId != 0) {
                    nonAir++;
                    topIds.merge(blockId, 1, Integer::sum);
                }
                if (data.isOccludingLocal(lx, ly, lz)) {
                    occluding++;
                }
            }
            reportChunkSectionLine(sender, "<gray>storedCells</gray> <gray>nonAir=</gray><white>" + nonAir
                    + "</white> <gray>occluding=</gray><white>" + occluding + "</white> <gray>total=</gray><white>" + ChunkData.BLOCK_COUNT + "</white>");
            if (fullyOpen) {
                reportChunkSectionLine(sender, "<gray>fullyOpen=</gray><yellow>air connects all 6 faces (not 'empty'); occluding="
                        + occluding + " nonAir=" + nonAir + "</yellow>");
            }
            reportChunkSectionLine(sender, "<gray>topBlocks=</gray><yellow>" + formatTopBlockStates(topIds, 5) + "</yellow>");
        }

        private static String formatTopBlockStates(Map<Integer, Integer> counts, int limit) {
            return counts.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .limit(limit)
                    .map(e -> describeBlockState(e.getKey()) + "×" + e.getValue())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("(none)");
        }

        private static String describeBlockState(int blockStateId) {
            try {
                BlockData blockData = SpigotConversionUtil.toBukkitBlockData(WrappedBlockState.getByGlobalId(blockStateId));
                if (blockData != null) {
                    return blockData.getMaterial().name() + "(" + blockStateId + ")";
                }
            } catch (RuntimeException ignored) {
                // fall through
            }
            return "id:" + blockStateId;
        }

        @DefaultExecutes
        public void helpCommand(@NotNull CommandSender sender) {
            sender.sendRichMessage("<white>Test subcommands:");
            sender.sendRichMessage("<green>/raycastedantiesp test location-drift <gray>- Tests the drift between Bukkit and PacketEvents entity locations");
            sender.sendRichMessage("<green>/raycastedantiesp test benchmark <gray>- Benchmarks raycast speed by raycasting to 1000 random locatables around the player and printing the average time taken");
            sender.sendRichMessage("<green>/raycastedantiesp test loaded-chunks <gray>- Shows the number of chunks currently loaded in the player's block view");
            sender.sendRichMessage("<green>/raycastedantiesp test chunk-section <x> <y> <z> [player] <gray>- Test LOS to the section containing block xyz; toggles focused rays");
            sender.sendRichMessage("<green>/raycastedantiesp test show-chunk-section <x> <y> <z> [player] <gray>- Force show section containing block xyz to client");
            sender.sendRichMessage("<green>/raycastedantiesp test status-chunk-section [player] <gray>- Snapshot shown/hidden sections, raycast outcomes, and hide-reason counts");
            sender.sendRichMessage("<green>/raycastedantiesp test entity-id <entity ID> [player] <gray>- Finds an entity by ID in one player's views, or in all player views when no player is supplied");
            sender.sendRichMessage("<green>/raycastedantiesp test entity-uuid <entity> <gray>- Shows Bukkit data and all tracked view data for a native entity selection or UUID");
        }
    }
}
