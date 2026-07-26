package games.cubi.raycastedantiesp.paper;

import games.cubi.locatables.api.Locatable;
import games.cubi.locatables.api.Spatial;
import games.cubi.logs.Logger;
import games.cubi.raycastedantiesp.core.raycast.ParticleSpawner;
import games.cubi.raycastedantiesp.paper.locatables.LocatableAdapterUtils;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.UUID;

public class PaperParticleSpawner implements ParticleSpawner {
    public void spawnParticleAt(Locatable locatable, Colour colour) {
        Objects.requireNonNull(Bukkit.getWorld(locatable.world())).spawnParticle(Particle.DUST, locatable.x(), locatable.y(), locatable.z(), 0, toBukkitDust(colour));
    }

    //While not strictly-speaking thread-safe since bukkit player data is accessed, this effectively just dispatches a packet and should not cause any issues when called async.
    public void spawnParticleAt(UUID worldUUID, Spatial spatial, Colour colour) {
        World world = Logger.requireNonNull(LocatableAdapterUtils.getWorld(worldUUID), "UUID resolved to nonexistent world", 2, PaperParticleSpawner.class);
        world.spawnParticle(Particle.DUST, spatial.x(), spatial.y(), spatial.z(), 0, toBukkitDust(colour));
    }

    @Override
    public void spawnParticleAtForViewer(UUID viewer, UUID worldUUID, Spatial spatial, Colour colour) {
        Player player = Bukkit.getPlayer(viewer);
        if (player == null || !player.isOnline()) {
            return;
        }
        if (worldUUID != null && player.getWorld() != null && !player.getWorld().getUID().equals(worldUUID)) {
            return;
        }
        Particle.DustOptions dust = toBukkitDust(colour);
        float size = switch (colour) {
            case SECTION_SHOW_SAMPLE, SECTION_HIDE_SAMPLE, SECTION_SHOW_ENTRY -> 1.6f;
            default -> 1.0f;
        };
        if (size != 1.0f) {
            dust = new Particle.DustOptions(dust.getColor(), size);
        }
        player.spawnParticle(Particle.DUST, spatial.x(), spatial.y(), spatial.z(), 0, dust);
    }

    private static final Particle.DustOptions RED_DUST = new Particle.DustOptions(Color.RED, 1);
    private static final Particle.DustOptions GREEN_DUST = new Particle.DustOptions(Color.GREEN, 1);
    private static final Particle.DustOptions BLUE_DUST = new Particle.DustOptions(Color.BLUE, 1);
    private static final Particle.DustOptions SECTION_SHOW_STEP = new Particle.DustOptions(Color.fromRGB(0, 220, 255), 1);
    private static final Particle.DustOptions SECTION_SHOW_OCCLUDER = new Particle.DustOptions(Color.fromRGB(255, 220, 0), 1);
    private static final Particle.DustOptions SECTION_SHOW_ENTRY = new Particle.DustOptions(Color.fromRGB(50, 255, 50), 1);
    private static final Particle.DustOptions SECTION_SHOW_SAMPLE = new Particle.DustOptions(Color.fromRGB(255, 255, 255), 1);
    private static final Particle.DustOptions SECTION_HIDE_STEP = new Particle.DustOptions(Color.fromRGB(255, 140, 0), 1);
    private static final Particle.DustOptions SECTION_HIDE_OCCLUDER = new Particle.DustOptions(Color.fromRGB(255, 40, 40), 1);
    private static final Particle.DustOptions SECTION_HIDE_SAMPLE = new Particle.DustOptions(Color.fromRGB(220, 0, 255), 1);

    private static Particle.DustOptions toBukkitDust(Colour colour) {
        return switch (colour) {
            case RED -> RED_DUST;
            case GREEN -> GREEN_DUST;
            case BLUE -> BLUE_DUST;
            case SECTION_SHOW_STEP -> SECTION_SHOW_STEP;
            case SECTION_SHOW_OCCLUDER -> SECTION_SHOW_OCCLUDER;
            case SECTION_SHOW_ENTRY -> SECTION_SHOW_ENTRY;
            case SECTION_SHOW_SAMPLE -> SECTION_SHOW_SAMPLE;
            case SECTION_HIDE_STEP -> SECTION_HIDE_STEP;
            case SECTION_HIDE_OCCLUDER -> SECTION_HIDE_OCCLUDER;
            case SECTION_HIDE_SAMPLE -> SECTION_HIDE_SAMPLE;
        };
    }
}
