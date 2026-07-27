package games.cubi.raycastedantiesp.paper.packets;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import games.cubi.raycastedantiesp.core.chunks.BlockInfoResolver;
import games.cubi.raycastedantiesp.packetevents.viewcontrollers.PacketEventsBlockViewController;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Material;

import java.util.function.IntSupplier;

public class PaperPacketEventsBlockViewController extends PacketEventsBlockViewController {
    private final int stoneBlockId = SpigotConversionUtil.fromBukkitBlockData(Material.STONE.createBlockData()).getGlobalId();
    private final int deepslateBlockId = SpigotConversionUtil.fromBukkitBlockData(Material.DEEPSLATE.createBlockData()).getGlobalId();

    public PaperPacketEventsBlockViewController(BlockInfoResolver blockInfoResolver, boolean trackAllBlocks, IntSupplier currentTickSupplier) {
        super(blockInfoResolver, trackAllBlocks, currentTickSupplier);
        PacketEvents.getAPI().getEventManager().registerListener(this, PacketListenerPriority.HIGHEST);
    }

    @Override
    protected int getHiddenBlockId(int blockY) {
        return blockY > 0 ? stoneBlockId : deepslateBlockId;
    }

    @Override
    protected com.github.retrooper.packetevents.protocol.player.User resolveUser(java.util.UUID viewerUUID) {
        org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(viewerUUID);
        if (player == null) {
            return null;
        }
        return PacketEvents.getAPI().getPlayerManager().getUser(player);
    }

    @Override
    protected void ensureOwnLocation(games.cubi.raycastedantiesp.core.players.PlayerData playerData, java.util.UUID viewerUUID) {
        if (playerData != null && (playerData.ownLocation() == null || playerData.ownLocation().world() == null)) {
            org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(viewerUUID);
            if (player != null && player.isOnline() && player.getWorld() != null) {
                org.bukkit.Location eyeLocation = player.getEyeLocation();
                playerData.updateOwnLocation(eyeLocation.getWorld().getUID(), eyeLocation.getX(), eyeLocation.getY(), eyeLocation.getZ());
            }
        }
    }
}
