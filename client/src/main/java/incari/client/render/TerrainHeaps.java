package incari.client.render;

import incari.client.tier.DeviceTier;

/**
 * The sizes of the terrain's GPU heaps (ADR 0025). The game keeps each layer's section meshes
 * in heaps of 128 MB of vertices and 32 MB of indices, adding a heap when one fills, and stages
 * uploads through a 98 MB buffer: sized for a desktop GPU, and reserved whole the moment a layer
 * draws its first section. In the browser three layers' heaps and the staging buffer reserved about
 * 540 MB at render distance 8, where the meshes themselves took a few tens of megabytes. Smaller
 * heaps reserve only what's used, give or take one heap; a layer that outgrows its first heap gets
 * another, at the cost of one more multi-draw a frame (ADR 0022). At {@link DeviceTier#HIGH} the
 * sizes are the game's.
 */
public final class TerrainHeaps {

    private TerrainHeaps() {}

    /** Hook: the bytes of each vertex heap, for the game's {@code gameSize}. */
    public static int vertexHeapSize(int gameSize) {
        return switch (DeviceTier.current()) {
            case POTATO -> 16 << 20;
            case STANDARD -> 32 << 20;
            case HIGH -> gameSize;
        };
    }

    /** Hook: the bytes of each index heap, for the game's {@code gameSize}. */
    public static int indexHeapSize(int gameSize) {
        return switch (DeviceTier.current()) {
            case POTATO -> 4 << 20;
            case STANDARD -> 8 << 20;
            case HIGH -> gameSize;
        };
    }

    /**
     * Hook: the bytes of the buffer section meshes are staged in before they're copied into the
     * heaps, for the game's {@code gameSize}. When it fills, the render thread copies what's staged
     * and starts again, so a smaller one only means that happens sooner on a frame of many uploads.
     * The largest section mesh (every face of a section of leaves, about 1.5 MB of compact vertices)
     * still fits.
     */
    public static int stagingSize(int gameSize) {
        return switch (DeviceTier.current()) {
            case POTATO -> 8 << 20;
            case STANDARD -> 16 << 20;
            case HIGH -> gameSize;
        };
    }
}
