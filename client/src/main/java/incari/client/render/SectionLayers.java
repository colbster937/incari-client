package incari.client.render;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/**
 * The game's section layers, one array for everyone (an allocation fix, ADR 0025). The level
 * renderer walked {@code ChunkSectionLayer.values()} for every visible section every frame, and each
 * call copies the enum's array: hundreds of arrays a frame at render distance 8.
 */
public final class SectionLayers {

    private static final ChunkSectionLayer[] ALL = ChunkSectionLayer.values();

    private SectionLayers() {}

    /** Hook: the layers in the game's order. Shared: never written. */
    @SuppressWarnings("MutablePublicArray") // Shared on purpose: the point is not to copy it.
    public static ChunkSectionLayer[] all() {
        return ALL;
    }
}
