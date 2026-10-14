package incari.render.webgl2;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class GlFormatsTest {

    // WebGL2 enum values, from the WebGL 2.0 specification.
    private static final int RGBA8 = 0x8058;
    private static final int RGBA = 0x1908;
    private static final int UNSIGNED_BYTE = 0x1401;
    private static final int DEPTH_COMPONENT32F = 0x8CAC;
    private static final int DEPTH_ATTACHMENT = 0x8D00;
    private static final int DEPTH_STENCIL_ATTACHMENT = 0x821A;
    private static final int COLOR_ATTACHMENT0 = 0x8CE0;
    private static final int TRIANGLES = 0x0004;
    private static final int LINES = 0x0001;
    private static final int GEQUAL = 0x0206;
    private static final int FLOAT = 0x1406;

    @Test
    void theGamesCommonTargetsExist() {
        assertThat(GlFormats.texture(GpuFormat.RGBA8_UNORM))
                .isEqualTo(new GlFormats.TextureFormat(RGBA8, RGBA, UNSIGNED_BYTE));
        assertThat(GlFormats.texture(GpuFormat.D32_FLOAT))
                .extracting(GlFormats.TextureFormat::internalFormat)
                .isEqualTo(DEPTH_COMPONENT32F);
    }

    @Test
    void withoutFloatBlendingTargetsAreHalfFloats() {
        int rgba32f = 0x8814;
        int rgba16f = 0x881A;
        GlFormats.TextureFormat full = GlFormats.texture(GpuFormat.RGBA32_FLOAT);
        assertThat(full).isNotNull();
        assertThat(GlFormats.withoutFloatBlending(full))
                .isEqualTo(new GlFormats.TextureFormat(rgba16f, RGBA, FLOAT))
                .isNotEqualTo(full);
        assertThat(full.internalFormat()).isEqualTo(rgba32f);
        GlFormats.TextureFormat bytes = GlFormats.texture(GpuFormat.RGBA8_UNORM);
        assertThat(bytes).isNotNull();
        assertThat(GlFormats.withoutFloatBlending(bytes)).isSameAs(bytes);
    }

    @Test
    void onlyExtensionFormatsAreMissing() {
        EnumSet<GpuFormat> missing = EnumSet.noneOf(GpuFormat.class);
        for (GpuFormat format : GpuFormat.values()) {
            if (GlFormats.texture(format) == null) {
                missing.add(format);
            }
        }
        // 16-bit normalized formats need EXT_texture_norm16; WebGL has no stencil-only textures.
        assertThat(missing)
                .containsExactlyInAnyOrder(
                        GpuFormat.R16_UNORM,
                        GpuFormat.R16_SNORM,
                        GpuFormat.RG16_UNORM,
                        GpuFormat.RG16_SNORM,
                        GpuFormat.RGB16_UNORM,
                        GpuFormat.RGB16_SNORM,
                        GpuFormat.RGBA16_UNORM,
                        GpuFormat.RGBA16_SNORM,
                        GpuFormat.S8_UINT);
    }

    @Test
    void attachmentFollowsTheAspects() {
        assertThat(GlFormats.attachment(GpuFormat.RGBA8_UNORM)).isEqualTo(COLOR_ATTACHMENT0);
        assertThat(GlFormats.attachment(GpuFormat.D32_FLOAT)).isEqualTo(DEPTH_ATTACHMENT);
        assertThat(GlFormats.attachment(GpuFormat.D24_UNORM_S8_UINT)).isEqualTo(DEPTH_STENCIL_ATTACHMENT);
    }

    @Test
    void attributesKeepTheirNormalization() {
        assertThat(GlFormats.attribute(GpuFormat.RGBA8_UNORM))
                .isEqualTo(new GlFormats.AttributeFormat(4, UNSIGNED_BYTE, true));
        assertThat(GlFormats.attribute(GpuFormat.RGB32_FLOAT))
                .isEqualTo(new GlFormats.AttributeFormat(3, FLOAT, false));
        assertThat(GlFormats.attribute(GpuFormat.RG16_SINT).normalized()).isFalse();
    }

    @Test
    void linesAndQuadsDrawAsTriangles() {
        // The game widens lines into quads in its shaders, and splits quads with an index buffer.
        assertThat(GlFormats.drawMode(PrimitiveTopology.LINES)).isEqualTo(TRIANGLES);
        assertThat(GlFormats.drawMode(PrimitiveTopology.QUADS)).isEqualTo(TRIANGLES);
        assertThat(GlFormats.drawMode(PrimitiveTopology.DEBUG_LINES)).isEqualTo(LINES);
    }

    @Test
    void depthComparesAsTheGameWritesThem() {
        assertThat(GlFormats.compareOp(CompareOp.GREATER_THAN_OR_EQUAL)).isEqualTo(GEQUAL);
    }
}
