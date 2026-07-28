package games.cubi.raycastedantiesp.paper.packets;

import com.github.retrooper.packetevents.protocol.player.User;
import games.cubi.raycastedantiesp.packetevents.viewcontrollers.PacketEventsCommonViewController;
import games.cubi.raycastedantiesp.paper.RaycastedAntiESP;
import games.cubi.raycastedantiesp.core.utils.VarHandler;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.lang.invoke.VarHandle;
import java.util.UUID;
import java.util.function.IntSupplier;

public final class PaperPacketEventsCommonViewController extends PacketEventsCommonViewController implements Listener {
    /** NEVER mutate a published map instance; replace it with a copied map. **/
    private volatile Object2ObjectArrayMap<String, UUID> worldIdByWorldName = new Object2ObjectArrayMap<>(); private static final VarHandle WORLD_ID_BY_WORLD_NAME = VarHandler.get(PaperPacketEventsCommonViewController.class, "worldIdByWorldName", Object2ObjectArrayMap.class);

    public PaperPacketEventsCommonViewController(IntSupplier currentTickSupplier) {
        super(currentTickSupplier);
        Bukkit.getWorlds().forEach(this::registerWorld);
        Bukkit.getPluginManager().registerEvents(this, RaycastedAntiESP.get());
    }

    @Override
    public UUID resolveWorldUUID(User user) {
        if (user.getDimensionType() == null || user.getDimensionType().getName() == null) {
            return null;
        }
        return resolveWorldUUID(user.getDimensionType().getName().toString());
    }

    @Override
    public UUID resolveWorldUUID(String worldName) {
        if (worldName == null) {
            return null;
        }
        Object2ObjectArrayMap<String, UUID> worldIds = worldIdsAcquire();
        return worldIds.get(worldName);
    }

    @Override
    public User resolveUser(UUID viewerUUID) {
        if (viewerUUID == null) {
            return null;
        }
        org.bukkit.entity.Player player = Bukkit.getPlayer(viewerUUID);
        if (player == null) {
            return null;
        }
        return com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager().getUser(player);
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        registerWorld(event.getWorld());
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        unregisterWorld(event.getWorld());
    }

    private synchronized void registerWorld(World world) {
        Object2ObjectArrayMap<String, UUID> updatedWorldIds = new Object2ObjectArrayMap<>(worldIdsAcquire());
        updatedWorldIds.put(world.getKey().toString(), world.getUID());
        WORLD_ID_BY_WORLD_NAME.setRelease(this, updatedWorldIds);
    }

    private synchronized void unregisterWorld(World world) {
        Object2ObjectArrayMap<String, UUID> updatedWorldIds = new Object2ObjectArrayMap<>(worldIdsAcquire());
        updatedWorldIds.remove(world.getKey().toString());
        WORLD_ID_BY_WORLD_NAME.setRelease(this, updatedWorldIds);
    }

    @SuppressWarnings("unchecked")
    private Object2ObjectArrayMap<String, UUID> worldIdsAcquire() {
        return (Object2ObjectArrayMap<String, UUID>) WORLD_ID_BY_WORLD_NAME.getAcquire(this);
    }
}
