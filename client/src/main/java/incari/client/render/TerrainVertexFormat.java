package incari.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;

/**
 * The vertex format of the game's terrain pipelines (ADR 0023): the game's own block format
 * (28 bytes a vertex), or Incari's compact one (16), which the browser build uses with Incari's
 * mesher. The terrain pipelines take it from here when they're built (a hook in
 * {@code RenderPipelines}), so the entry point chooses before the game starts.
 *
 * <p>The compact vertex: position and light in four unsigned shorts (x, y and z in 1/2048ths of a
 * block from -8, then block light in the low byte and sky light in the high one), the color as the
 * game writes it, and the texture coordinates in two unsigned shorts (1/32768ths of the atlas). The
 * mesher writes it ({@code incari.mesher.VertexFormats}); the WebGL2 backend's terrain shaders read
 * it ({@code incari.render.webgl2.CompactTerrainRewrite}).
 */
public final class TerrainVertexFormat {

    /** Incari's compact terrain vertex. The element names are the backend's to recognise. */
    public static final VertexFormat COMPACT = VertexFormat.builder(0)
            .addAttribute("IncariPositionLight", GpuFormat.RGBA16_UINT)
            .addAttribute("Color", GpuFormat.RGBA8_UNORM)
            .addAttribute("IncariUv", GpuFormat.RG16_UINT)
            .build();

    private static volatile boolean compact;

    private TerrainVertexFormat() {}

    /** Draws terrain with {@link #COMPACT} vertices. Call before the game starts. */
    public static void useCompact() {
        compact = true;
    }

    public static boolean compact() {
        return compact;
    }

    /** Hook: the terrain pipelines' vertex format. */
    public static VertexFormat terrain() {
        return compact ? COMPACT : DefaultVertexFormat.BLOCK;
    }
}
