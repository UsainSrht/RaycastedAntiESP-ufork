package games.cubi.raycastedantiesp.packetevents.viewcontrollers.chunkparser;

import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.HeightmapType;
import com.github.retrooper.packetevents.protocol.world.chunk.LightData;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;

import java.util.Map;

/** Per-viewer cache of the last CHUNK_DATA wire metadata needed to resend a column. */
public final class CachedWireColumn {
    private final int chunkX;
    private final int chunkZ;
    private final int minimumSectionY;
    private final DataPalette[] biomePalettes;
    private final BaseChunk[] unhiddenSections;
    private final LightData lightData;
    private final NBTCompound heightMapsNbt;
    private final Map<HeightmapType, long[]> heightmapsMap;
    private final TileEntity[] tileEntities;

    public CachedWireColumn(
            int chunkX,
            int chunkZ,
            int minimumSectionY,
            DataPalette[] biomePalettes,
            LightData lightData,
            NBTCompound heightMapsNbt,
            Map<HeightmapType, long[]> heightmapsMap,
            TileEntity[] tileEntities
    ) {
        this(chunkX, chunkZ, minimumSectionY, biomePalettes, null, lightData, heightMapsNbt, heightmapsMap, tileEntities);
    }

    public CachedWireColumn(
            int chunkX,
            int chunkZ,
            int minimumSectionY,
            DataPalette[] biomePalettes,
            BaseChunk[] unhiddenSections,
            LightData lightData,
            NBTCompound heightMapsNbt,
            Map<HeightmapType, long[]> heightmapsMap,
            TileEntity[] tileEntities
    ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minimumSectionY = minimumSectionY;
        this.biomePalettes = biomePalettes;
        this.unhiddenSections = unhiddenSections;
        this.lightData = lightData;
        this.heightMapsNbt = heightMapsNbt;
        this.heightmapsMap = heightmapsMap;
        this.tileEntities = tileEntities == null ? new TileEntity[0] : tileEntities;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public int minimumSectionY() {
        return minimumSectionY;
    }

    public int sectionCount() {
        return biomePalettes.length;
    }

    public DataPalette biomePalette(int sectionIndex) {
        return sectionIndex >= 0 && sectionIndex < biomePalettes.length ? biomePalettes[sectionIndex] : null;
    }

    public BaseChunk unhiddenSection(int sectionIndex) {
        return unhiddenSections != null && sectionIndex >= 0 && sectionIndex < unhiddenSections.length ? unhiddenSections[sectionIndex] : null;
    }

    public LightData lightData() {
        return lightData;
    }

    public NBTCompound heightMapsNbt() {
        return heightMapsNbt;
    }

    public Map<HeightmapType, long[]> heightmapsMap() {
        return heightmapsMap;
    }

    public TileEntity[] tileEntities() {
        return tileEntities;
    }
}
