package incari.render.webgl2;

import static org.teavm.jso.webgl.WebGL2RenderingContext.*;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.AddressMode;
import org.jspecify.annotations.Nullable;

/**
 * The game's formats and states in WebGL2's terms. Pure mapping, no GL calls, so it runs (and is
 * tested) on a normal JVM too: the WebGL constants are compile-time constants.
 */
final class GlFormats {

    /** How a texture format is stored ({@code texStorage2D}) and uploaded ({@code texSubImage2D}). */
    record TextureFormat(int internalFormat, int format, int type) {}

    /** WebGL2's {@code RGBA8}, which TeaVM's binding leaves out. */
    private static final int RGBA8 = 0x8058;

    /** How a vertex attribute is read ({@code vertexAttrib[I]Pointer}). */
    record AttributeFormat(int size, int type, boolean normalized) {}

    private GlFormats() {}

    /**
     * The texture format, or null where WebGL2 has none: 16-bit normalized formats need an
     * extension (EXT_texture_norm16) Chromebooks rarely have, and a stencil-only texture doesn't
     * exist in WebGL.
     */
    static @Nullable TextureFormat texture(GpuFormat format) {
        return switch (format) {
            case R8_UNORM -> new TextureFormat(R8, RED, UNSIGNED_BYTE);
            case R8_SNORM -> new TextureFormat(R8_SNORM, RED, BYTE);
            case RG8_UNORM -> new TextureFormat(RG8, RG, UNSIGNED_BYTE);
            case RG8_SNORM -> new TextureFormat(RG8_SNORM, RG, BYTE);
            case RGB8_UNORM -> new TextureFormat(RGB8, RGB, UNSIGNED_BYTE);
            case RGB8_SNORM -> new TextureFormat(RGB8_SNORM, RGB, BYTE);
            case RGBA8_UNORM -> new TextureFormat(RGBA8, RGBA, UNSIGNED_BYTE);
            case RGBA8_SNORM -> new TextureFormat(RGBA8_SNORM, RGBA, BYTE);
            case R8_UINT -> new TextureFormat(R8UI, RED_INTEGER, UNSIGNED_BYTE);
            case R8_SINT -> new TextureFormat(R8I, RED_INTEGER, BYTE);
            case RG8_UINT -> new TextureFormat(RG8UI, RG_INTEGER, UNSIGNED_BYTE);
            case RG8_SINT -> new TextureFormat(RG8I, RG_INTEGER, BYTE);
            case RGB8_UINT -> new TextureFormat(RGB8UI, RGB_INTEGER, UNSIGNED_BYTE);
            case RGB8_SINT -> new TextureFormat(RGB8I, RGB_INTEGER, BYTE);
            case RGBA8_UINT -> new TextureFormat(RGBA8UI, RGBA_INTEGER, UNSIGNED_BYTE);
            case RGBA8_SINT -> new TextureFormat(RGBA8I, RGBA_INTEGER, BYTE);
            case R16_UINT -> new TextureFormat(R16UI, RED_INTEGER, UNSIGNED_SHORT);
            case R16_SINT -> new TextureFormat(R16I, RED_INTEGER, SHORT);
            case RG16_UINT -> new TextureFormat(RG16UI, RG_INTEGER, UNSIGNED_SHORT);
            case RG16_SINT -> new TextureFormat(RG16I, RG_INTEGER, SHORT);
            case RGB16_UINT -> new TextureFormat(RGB16UI, RGB_INTEGER, UNSIGNED_SHORT);
            case RGB16_SINT -> new TextureFormat(RGB16I, RGB_INTEGER, SHORT);
            case RGBA16_UINT -> new TextureFormat(RGBA16UI, RGBA_INTEGER, UNSIGNED_SHORT);
            case RGBA16_SINT -> new TextureFormat(RGBA16I, RGBA_INTEGER, SHORT);
            case R32_UINT -> new TextureFormat(R32UI, RED_INTEGER, UNSIGNED_INT);
            case R32_SINT -> new TextureFormat(R32I, RED_INTEGER, INT);
            case RG32_UINT -> new TextureFormat(RG32UI, RG_INTEGER, UNSIGNED_INT);
            case RG32_SINT -> new TextureFormat(RG32I, RG_INTEGER, INT);
            case RGB32_UINT -> new TextureFormat(RGB32UI, RGB_INTEGER, UNSIGNED_INT);
            case RGB32_SINT -> new TextureFormat(RGB32I, RGB_INTEGER, INT);
            case RGBA32_UINT -> new TextureFormat(RGBA32UI, RGBA_INTEGER, UNSIGNED_INT);
            case RGBA32_SINT -> new TextureFormat(RGBA32I, RGBA_INTEGER, INT);
            case R16_FLOAT -> new TextureFormat(R16F, RED, HALF_FLOAT);
            case RG16_FLOAT -> new TextureFormat(RG16F, RG, HALF_FLOAT);
            case RGB16_FLOAT -> new TextureFormat(RGB16F, RGB, HALF_FLOAT);
            case RGBA16_FLOAT -> new TextureFormat(RGBA16F, RGBA, HALF_FLOAT);
            case R32_FLOAT -> new TextureFormat(R32F, RED, FLOAT);
            case RG32_FLOAT -> new TextureFormat(RG32F, RG, FLOAT);
            case RGB32_FLOAT -> new TextureFormat(RGB32F, RGB, FLOAT);
            case RGBA32_FLOAT -> new TextureFormat(RGBA32F, RGBA, FLOAT);
            case RGB10A2_UNORM -> new TextureFormat(RGB10_A2, RGBA, UNSIGNED_INT_2_10_10_10_REV);
            case RGB10A2_UINT -> new TextureFormat(RGB10_A2UI, RGBA_INTEGER, UNSIGNED_INT_2_10_10_10_REV);
            case RG11B10_FLOAT -> new TextureFormat(R11F_G11F_B10F, RGB, UNSIGNED_INT_10F_11F_11F_REV);
            case D16_UNORM -> new TextureFormat(DEPTH_COMPONENT16, DEPTH_COMPONENT, UNSIGNED_SHORT);
            case D32_FLOAT -> new TextureFormat(DEPTH_COMPONENT32F, DEPTH_COMPONENT, FLOAT);
            case D24_UNORM_S8_UINT -> new TextureFormat(DEPTH24_STENCIL8, DEPTH_STENCIL, UNSIGNED_INT_24_8);
            case D32_FLOAT_S8_UINT ->
                new TextureFormat(DEPTH32F_STENCIL8, DEPTH_STENCIL, FLOAT_32_UNSIGNED_INT_24_8_REV);
            case R16_UNORM,
                    R16_SNORM,
                    RG16_UNORM,
                    RG16_SNORM,
                    RGB16_UNORM,
                    RGB16_SNORM,
                    RGBA16_UNORM,
                    RGBA16_SNORM,
                    S8_UINT -> null;
        };
    }

    /**
     * The storage for a render target of {@code format} on a device that can't blend into 32-bit
     * floats: the 16-bit float format with as many channels (uploads and read-backs keep their
     * 32-bit data; WebGL converts). Any other format is returned as it is.
     */
    static TextureFormat withoutFloatBlending(TextureFormat format) {
        int half =
                switch (format.internalFormat()) {
                    case R32F -> R16F;
                    case RG32F -> RG16F;
                    case RGB32F -> RGB16F;
                    case RGBA32F -> RGBA16F;
                    default -> 0;
                };
        return half == 0 ? format : new TextureFormat(half, format.format(), format.type());
    }

    /** The framebuffer attachment point a texture of this format goes to. */
    static int attachment(GpuFormat format) {
        if (format.hasDepthAspect()) {
            return format.hasStencilAspect() ? DEPTH_STENCIL_ATTACHMENT : DEPTH_ATTACHMENT;
        }
        return COLOR_ATTACHMENT0;
    }

    /** The vertex attribute layout of a vertex format element. */
    static AttributeFormat attribute(GpuFormat format) {
        int type =
                switch (format.componentType()) {
                    case UNORM_8, UINT_8 -> UNSIGNED_BYTE;
                    case SNORM_8, SINT_8 -> BYTE;
                    case UNORM_16, UINT_16 -> UNSIGNED_SHORT;
                    case SNORM_16, SINT_16 -> SHORT;
                    case UINT_32 -> UNSIGNED_INT;
                    case SINT_32 -> INT;
                    case FLOAT_16 -> HALF_FLOAT;
                    case FLOAT_32 -> FLOAT;
                    case OPAQUE_8, OPAQUE_16, OPAQUE_32, OPAQUE_64 ->
                        throw new IllegalArgumentException(format + " can't be a vertex attribute");
                };
        boolean normalized =
                switch (format.componentType()) {
                    case UNORM_8, SNORM_8, UNORM_16, SNORM_16 -> true;
                    default -> false;
                };
        return new AttributeFormat(format.componentCount(), type, normalized);
    }

    /**
     * The draw mode. The game draws {@code LINES} and {@code QUADS} as triangles: its line shaders
     * widen each segment into a quad, and quads come with an index buffer that splits them.
     */
    static int drawMode(PrimitiveTopology topology) {
        return switch (topology) {
            case LINES, TRIANGLES, QUADS -> TRIANGLES;
            case DEBUG_LINES -> LINES;
            case DEBUG_LINE_STRIP -> LINE_STRIP;
            case POINTS -> POINTS;
            case TRIANGLE_STRIP -> TRIANGLE_STRIP;
            case TRIANGLE_FAN -> TRIANGLE_FAN;
        };
    }

    static int indexType(IndexType type) {
        return switch (type) {
            case SHORT -> UNSIGNED_SHORT;
            case INT -> UNSIGNED_INT;
        };
    }

    static int compareOp(CompareOp op) {
        return switch (op) {
            case ALWAYS_PASS -> ALWAYS;
            case LESS_THAN -> LESS;
            case LESS_THAN_OR_EQUAL -> LEQUAL;
            case EQUAL -> EQUAL;
            case NOT_EQUAL -> NOTEQUAL;
            case GREATER_THAN_OR_EQUAL -> GEQUAL;
            case GREATER_THAN -> GREATER;
            case NEVER_PASS -> NEVER;
        };
    }

    static int blendFactor(BlendFactor factor) {
        return switch (factor) {
            case CONSTANT_ALPHA -> CONSTANT_ALPHA;
            case CONSTANT_COLOR -> CONSTANT_COLOR;
            case DST_ALPHA -> DST_ALPHA;
            case DST_COLOR -> DST_COLOR;
            case ONE -> ONE;
            case ONE_MINUS_CONSTANT_ALPHA -> ONE_MINUS_CONSTANT_ALPHA;
            case ONE_MINUS_CONSTANT_COLOR -> ONE_MINUS_CONSTANT_COLOR;
            case ONE_MINUS_DST_ALPHA -> ONE_MINUS_DST_ALPHA;
            case ONE_MINUS_DST_COLOR -> ONE_MINUS_DST_COLOR;
            case ONE_MINUS_SRC_ALPHA -> ONE_MINUS_SRC_ALPHA;
            case ONE_MINUS_SRC_COLOR -> ONE_MINUS_SRC_COLOR;
            case SRC_ALPHA -> SRC_ALPHA;
            case SRC_ALPHA_SATURATE -> SRC_ALPHA_SATURATE;
            case SRC_COLOR -> SRC_COLOR;
            case ZERO -> ZERO;
        };
    }

    static int blendOp(BlendOp op) {
        return switch (op) {
            case ADD -> FUNC_ADD;
            case SUBTRACT -> FUNC_SUBTRACT;
            case REVERSE_SUBTRACT -> FUNC_REVERSE_SUBTRACT;
            case MIN -> MIN;
            case MAX -> MAX;
        };
    }

    static int wrap(AddressMode mode) {
        return switch (mode) {
            case REPEAT -> REPEAT;
            case CLAMP_TO_EDGE -> CLAMP_TO_EDGE;
        };
    }
}
