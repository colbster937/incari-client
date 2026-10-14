package incari.client.render.mesher;

import incari.mesher.MeshTables;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/** The game's section layers as the mesher numbers them. */
public final class Layers {

    private Layers() {}

    public static int index(ChunkSectionLayer layer) {
        return switch (layer) {
            case SOLID -> MeshTables.LAYER_SOLID;
            case CUTOUT -> MeshTables.LAYER_CUTOUT;
            case TRANSLUCENT -> MeshTables.LAYER_TRANSLUCENT;
        };
    }
}
