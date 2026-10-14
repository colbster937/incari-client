package incari.render.webgl2;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import incari.render.webgl2.shaders.ShaderManifest;
import incari.render.webgl2.shaders.ShaderStageKey;
import incari.render.webgl2.shaders.SourceHash;
import incari.render.webgl2.shaders.TranslatedStage;
import it.unimi.dsi.fastutil.objects.Object2IntArrayMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Turns the game's pipeline descriptions into WebGL2 pipelines, from the shaders the build
 * translated ({@link ShaderManifest}) rather than by compiling GLSL at run time as the game's
 * other backends do. The frontend's render passes only take its own pipeline type, so the result
 * is wrapped in one, with the uniform indices it binds by.
 *
 * <p>A pipeline WebGL2 can't run yet still compiles: to a pipeline that skips its draws. The game
 * refuses to load at all if a required pipeline fails, and the title screen doesn't need clouds.
 */
final class PipelineCompiler {

    /** The define of the game's terrain pipelines for indirect multi-draws. */
    private static final String MULTIDRAW_TERRAIN = "MULTIDRAW_TERRAIN";

    private final WebGl2Device device;
    private final ShaderManifest manifest;
    private final boolean eager;
    private final Set<String> warnedSources = new HashSet<>();

    PipelineCompiler(WebGl2Device device, ShaderManifest manifest, boolean eager) {
        this.device = device;
        this.manifest = manifest;
        this.eager = eager;
    }

    CompletableFuture<CompiledRenderPipeline.Pending> compile(RenderPipeline pipeline, ShaderSource source) {
        WebGl2Pipeline backend = backendPipeline(pipeline, source);
        if (eager || instanced(pipeline)) {
            backend.link();
        }
        if (instanced(pipeline) && backend.failed()) {
            // Incari's own copy: no pipeline at all, so the game's model renderer sees it
            // can't draw instanced and draws those models its own way, instead of skipping them.
            backend.close();
            return CompletableFuture.completedFuture(CompiledRenderPipeline.Pending.NULL);
        }
        List<BindGroupLayout.UniformDescription> uniforms =
                BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts());
        Object2IntMap<String> indices = new Object2IntArrayMap<>(uniforms.size());
        for (int i = 0; i < uniforms.size(); i++) {
            indices.put(uniforms.get(i).name(), i);
        }
        CompiledRenderPipeline compiled = new FrontendRenderPipeline(
                pipeline.getLocation().toString(),
                backend,
                pipeline.getVertexFormatBindings(),
                Object2IntMaps.unmodifiable(indices),
                uniforms,
                pipeline.getColorTargetStates(),
                pipeline.wantsDepthTexture(),
                pipeline.pushConstantSize());
        return CompletableFuture.completedFuture(() -> compiled);
    }

    private WebGl2Pipeline backendPipeline(RenderPipeline pipeline, ShaderSource source) {
        if (pipeline.getPolygonMode() == PolygonMode.WIREFRAME && !device.hasPolygonMode()) {
            return WebGl2Pipeline.unsupported(
                    device, pipeline, "wireframe (this browser has no WEBGL_polygon_mode, a debug view)");
        }
        TranslatedStage vertex = stage(pipeline, ShaderType.VERTEX, source);
        TranslatedStage fragment = stage(pipeline, ShaderType.FRAGMENT, source);
        if (vertex == null || fragment == null) {
            return WebGl2Pipeline.unsupported(
                    device,
                    pipeline,
                    "its shaders aren't in shaders.json (the build translates only the vanilla pipelines)");
        }
        String unsupported = vertex.unsupported() != null ? vertex.unsupported() : fragment.unsupported();
        if (unsupported != null) {
            return WebGl2Pipeline.unsupported(device, pipeline, unsupported);
        }
        if (instancedParticles(pipeline)) {
            // An instanced copy of a particle pipeline (ADR 0032): each particle's quad worked out per instance.
            try {
                vertex = ParticleInstanceRewrite.rewrite(vertex);
            } catch (IllegalArgumentException e) {
                WebGl.error("[Incari] " + pipeline.getLocation() + " can't draw instanced: " + e.getMessage());
                return WebGl2Pipeline.unsupported(device, pipeline, "instanced particles: " + e.getMessage());
            }
        } else if (instanced(pipeline)) {
            // An instanced copy of an entity pipeline: the model's parts placed per instance.
            try {
                vertex = EntityInstanceRewrite.rewrite(vertex);
            } catch (IllegalArgumentException e) {
                WebGl.error("[Incari] " + pipeline.getLocation() + " can't draw instanced: " + e.getMessage());
                return WebGl2Pipeline.unsupported(device, pipeline, "instanced models: " + e.getMessage());
            }
        }
        if (readsCompactTerrain(pipeline)) {
            // Incari's compact terrain vertex: the shader decodes it into the inputs it had.
            try {
                vertex = CompactTerrainRewrite.rewrite(vertex);
            } catch (IllegalArgumentException e) {
                return WebGl2Pipeline.unsupported(device, pipeline, "compact terrain vertices: " + e.getMessage());
            }
        }
        try {
            List<VertexLayout.Binding> layout =
                    VertexLayout.of(pipeline.getVertexFormatBindings(), vertex.attributes());
            // The game's multi-draw terrain pipelines, the only ones with per-instance data: their
            // draws are indirect, and each section's instance data is read by gl_DrawID.
            VertexLayout.DrawRowLayout rows =
                    device.multiDraw() && pipeline.getShaderDefines().flags().contains(MULTIDRAW_TERRAIN)
                            ? VertexLayout.drawRows(layout, vertex.attributes())
                            : null;
            if (rows != null) {
                try {
                    return WebGl2Pipeline.withDrawRows(
                            device,
                            pipeline,
                            vertex,
                            fragment,
                            rows,
                            DrawIdRewrite.rewrite(vertex.glsl(), rows.inputs()));
                } catch (IllegalArgumentException e) {
                    WebGl.warn("[Incari] " + pipeline.getLocation() + " draws without multi-draw: " + e.getMessage());
                }
            }
            return WebGl2Pipeline.of(device, pipeline, vertex, fragment, layout);
        } catch (VertexLayout.MismatchException e) {
            return WebGl2Pipeline.unsupported(
                    device, pipeline, e.getMessage() == null ? "vertex layout" : e.getMessage());
        }
    }

    /** Whether the pipeline is one of Incari's instanced copies (an entity's or a particle's). */
    private static boolean instanced(RenderPipeline pipeline) {
        Set<String> flags = pipeline.getShaderDefines().flags();
        return flags.contains(EntityInstanceRewrite.FLAG) || flags.contains(ParticleInstanceRewrite.FLAG);
    }

    private static boolean instancedParticles(RenderPipeline pipeline) {
        return pipeline.getShaderDefines().flags().contains(ParticleInstanceRewrite.FLAG);
    }

    /**
     * The defines the build translated the pipeline's shaders with: its own, less the flag of an
     * instanced copy, which is the copied pipeline's shaders rewritten here.
     */
    private static ShaderDefines translatedDefines(RenderPipeline pipeline) {
        ShaderDefines defines = pipeline.getShaderDefines();
        if (!instanced(pipeline)) {
            return defines;
        }
        Set<String> flags = new HashSet<>(defines.flags());
        flags.remove(EntityInstanceRewrite.FLAG);
        flags.remove(ParticleInstanceRewrite.FLAG);
        return new ShaderDefines(defines.values(), flags);
    }

    /** Whether the pipeline's vertices are Incari's compact terrain vertices (its first binding says). */
    private static boolean readsCompactTerrain(RenderPipeline pipeline) {
        List<@Nullable VertexFormat> formats = pipeline.getVertexFormatBindings();
        VertexFormat first = formats.isEmpty() ? null : formats.getFirst();
        return first != null && first.contains(CompactTerrainRewrite.POSITION_LIGHT);
    }

    private @Nullable TranslatedStage stage(RenderPipeline pipeline, ShaderType type, ShaderSource source) {
        Identifier id = pipeline.getShaders().get(type);
        if (id == null) {
            return null;
        }
        ShaderStageKey key = ShaderStageKey.of(type, id, translatedDefines(pipeline));
        TranslatedStage stage = null;
        if (device.depthZeroToOne()) {
            // The game compiles its shaders for a 0-to-1 depth range when the device has one; the
            // build translated a second variant of the stages that define changes.
            stage = manifest.stage(key.withFlag(ShaderStageKey.DEPTH_IS_ZERO_TO_ONE));
        }
        if (stage == null) {
            stage = manifest.stage(key);
        }
        if (stage != null) {
            String text = source.getShader(id, type);
            if (text != null && !SourceHash.of(text).equals(stage.sourceHash()) && warnedSources.add(id + " " + type)) {
                // Translating shaders in the page needs a GLSL compiler there; until then packs
                // can't change shaders, and the vanilla translation keeps the game drawing.
                WebGl.warn("[Incari] A resource pack changed " + type.getName() + " shader " + id
                        + "; the browser build still uses the vanilla one");
            }
        }
        return stage;
    }
}
