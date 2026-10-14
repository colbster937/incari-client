package incari.client.render.entity;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Instanced copies of the game's entity pipelines (ADR 0024): the same shaders, state and
 * uniforms, plus the part matrices' uniform block, drawing {@link ModelMesh#VERTEX} vertices with
 * {@link #INSTANCE} data per instance. The copy carries the define {@link #FLAG}, which no shader
 * reads: the WebGL2 backend sees it, looks the shaders up without it, and rewrites the vertex stage
 * to place each part ({@code incari.render.webgl2.EntityInstanceRewrite}).
 *
 * <p>Render thread only.
 */
final class InstancedPipelines {

    /** Keep in step with the WebGL2 backend's {@code EntityInstanceRewrite.FLAG}. */
    static final String FLAG = "INCARI_INSTANCED";

    /** Keep in step with the WebGL2 backend's {@code EntityInstanceRewrite.BLOCK}. */
    static final String BLOCK = "IncariParts";

    /** Rows in the block, as the backend declares it: WebGL2's smallest allowed maximum block. */
    static final int ROWS = 1024;

    /** Per instance: the color, overlay and light the game puts on each vertex, and the first row. */
    static final VertexFormat INSTANCE = VertexFormat.builder(1)
            .addAttribute("Color", GpuFormat.RGBA8_UNORM)
            .addAttribute("UV1", GpuFormat.RG16_SINT)
            .addAttribute("UV2", GpuFormat.RG16_SINT)
            .addAttribute("IncariFirstRow", GpuFormat.R32_UINT)
            .build();

    private static final Identifier ENTITY_SHADER = Identifier.withDefaultNamespace("core/entity");

    /** The item shader declares the same inputs, so its vertex stage takes the same rewrite (ADR 0027). */
    private static final Identifier ITEM_SHADER = Identifier.withDefaultNamespace("core/item");

    private static final BindGroupLayout PARTS = BindGroupLayout.builder()
            .withUniform(BLOCK, UniformType.UNIFORM_BUFFER)
            .build();

    private static final IdentityHashMap<RenderPipeline, @Nullable RenderPipeline> COPIES = new IdentityHashMap<>();

    private InstancedPipelines() {}

    /**
     * The instanced copy of {@code pipeline}, or null if it isn't one of the game's entity or item
     * pipelines (the entity or item shader, the entity vertex format, quads).
     */
    static @Nullable RenderPipeline of(RenderPipeline pipeline) {
        if (COPIES.containsKey(pipeline)) {
            return COPIES.get(pipeline);
        }
        RenderPipeline copy = eligible(pipeline) ? new Instanced(pipeline) : null;
        COPIES.put(pipeline, copy);
        return copy;
    }

    private static boolean eligible(RenderPipeline pipeline) {
        List<@Nullable VertexFormat> formats = pipeline.getVertexFormatBindings();
        Identifier vertex = pipeline.getShaders().get(ShaderType.VERTEX);
        return (ENTITY_SHADER.equals(vertex) || ITEM_SHADER.equals(vertex))
                && !formats.isEmpty()
                && DefaultVertexFormat.ENTITY.equals(formats.getFirst())
                && formats.stream().skip(1).allMatch(format -> format == null)
                && pipeline.getPrimitiveTopology() == PrimitiveTopology.QUADS
                && !pipeline.getShaderDefines().flags().contains(FLAG);
    }

    /** A copy of a pipeline, instanced. */
    private static final class Instanced extends RenderPipeline {

        Instanced(RenderPipeline pipeline) {
            super(
                    Identifier.fromNamespaceAndPath(
                            "incari",
                            "instanced/" + pipeline.getLocation().getNamespace() + "/"
                                    + pipeline.getLocation().getPath()),
                    pipeline.getShaders(),
                    defines(pipeline.getShaderDefines()),
                    layouts(pipeline.getBindGroupLayouts()),
                    pipeline.getColorTargetStates().toArray(ColorTargetState[]::new),
                    pipeline.getDepthStencilState(),
                    pipeline.getPolygonMode(),
                    pipeline.isCull(),
                    new VertexFormat[] {ModelMesh.VERTEX, INSTANCE},
                    pipeline.getPrimitiveTopology(),
                    pipeline.pushConstantSize(),
                    pipeline.getSortKey());
        }

        private static ShaderDefines defines(ShaderDefines defines) {
            Set<String> flags = new HashSet<>(defines.flags());
            flags.add(FLAG);
            return new ShaderDefines(defines.values(), flags);
        }

        private static List<BindGroupLayout> layouts(List<BindGroupLayout> layouts) {
            List<BindGroupLayout> all = new ArrayList<>(layouts);
            all.add(PARTS);
            return all;
        }
    }
}
