package games.cubi.raycastedantiesp.packetevents.viewcontrollers.chunkparser;

import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import games.cubi.raycastedantiesp.core.view.BlockView;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public interface ChunkParser {
    /**
     * Populates the view from the column and returns a replacement only when the outgoing packet was mutated.
     */
    @Nullable Column parse(BlockView blockView, UUID world, Column column, int minimumSectionY);

    /**
     * Populates the view from the column and optionally redacts occluded sections.
     *
     * @param sectionContext non-null when chunk-section checks are enabled for this viewer
     */
    @Nullable Column parse(BlockView blockView, UUID world, Column column, int minimumSectionY, @Nullable ChunkSectionParseContext sectionContext);
}
