package games.cubi.raycastedantiesp.core.view.controller;

import games.cubi.raycastedantiesp.core.tracked.NettyEntity;
import games.cubi.raycastedantiesp.core.players.PlayerData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketEntityViewControllerSectionGateTest {
    @Test
    void floorDiv16HandlesPositiveAndNegativeCoordinates() {
        assertEquals(0, PacketEntityViewController.floorDiv16(0.0));
        assertEquals(0, PacketEntityViewController.floorDiv16(15.9));
        assertEquals(1, PacketEntityViewController.floorDiv16(16.0));
        assertEquals(-1, PacketEntityViewController.floorDiv16(-0.1));
        assertEquals(-1, PacketEntityViewController.floorDiv16(-16.0));
        assertEquals(-2, PacketEntityViewController.floorDiv16(-16.1));
    }

    @Test
    void entityInChunkSectionMatchesEntityFeetPosition() {
        StubEntity entity = new StubEntity(16.5, -0.5, 32.1);
        assertTrue(PacketEntityViewController.entityInChunkSection(entity, 1, -1, 2));
        assertFalse(PacketEntityViewController.entityInChunkSection(entity, 0, -1, 2));
        assertFalse(PacketEntityViewController.entityInChunkSection(entity, 1, 0, 2));
    }

    private static final class StubEntity extends NettyEntity<Object, games.cubi.raycastedantiesp.core.utils.Clearable> {
        private StubEntity(double x, double y, double z) {
            super(null, x, y, z, 1, UUID.randomUUID(), false, new Object(), true);
        }
    }
}
