package incari.tools.shaders;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import incari.render.webgl2.shaders.ShaderStageKey;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.resources.Identifier;

/**
 * Every pipeline the game can compile, as pairs of shader stages. The game's own pipelines come
 * from {@link RenderPipelines}, which registers them all in its static initializer; post effects
 * are built from their JSON files at run time, on {@link RenderPipelines#POST_PROCESSING_SNIPPET},
 * so each pass of each file in the jar is one more pipeline here.
 */
final class PipelineCatalog {

    /**
     * One pipeline's two stages, and the defines both are compiled with.
     *
     * @param wireframe whether it draws outlines (a debug view; WebGL2 needs WEBGL_polygon_mode)
     */
    record Pipeline(
            String name, ShaderStageKey vertex, ShaderStageKey fragment, ShaderDefines defines, boolean wireframe) {}

    private PipelineCatalog() {}

    static List<Pipeline> collect(GameShaderFiles files) throws ShaderException {
        BuildPlatform.install();
        // The registry keeps one pipeline per location, and some pipelines share one (the item
        // pipelines with and without glint), so the ones it drops are found among its fields.
        Set<RenderPipeline> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<RenderPipeline> all = new ArrayList<>();
        all.addAll(RenderPipelines.requiredPipelines());
        all.addAll(RenderPipelines.optionalPipelines());
        all.addAll(declaredPipelines());
        List<Pipeline> pipelines = new ArrayList<>();
        for (RenderPipeline pipeline : all) {
            if (seen.add(pipeline)) {
                pipelines.add(of(pipeline));
            }
        }
        for (Map.Entry<String, String> effect : files.postEffects().entrySet()) {
            pipelines.addAll(postEffect(effect.getKey(), effect.getValue()));
        }
        return pipelines;
    }

    /** Every pipeline {@link RenderPipelines} holds in a static field, directly or in an OIT set. */
    private static List<RenderPipeline> declaredPipelines() throws ShaderException {
        List<RenderPipeline> pipelines = new ArrayList<>();
        for (Field field : RenderPipelines.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Object value;
            try {
                field.setAccessible(true);
                value = field.get(null);
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new ShaderException("Can't read RenderPipelines." + field.getName() + ": " + e);
            }
            if (value instanceof RenderPipeline pipeline) {
                pipelines.add(pipeline);
            } else if (value instanceof OitPipelineSet set) {
                pipelines.add(set.depthBoundsPipeline());
                pipelines.add(set.transmittancePipeline());
                pipelines.add(set.accumulatePipeline());
            }
        }
        return pipelines;
    }

    private static Pipeline of(RenderPipeline pipeline) {
        Map<ShaderType, Identifier> shaders = pipeline.getShaders();
        // RenderPipeline.Builder refuses to build a pipeline without both stages.
        Identifier vertex = Objects.requireNonNull(shaders.get(ShaderType.VERTEX));
        Identifier fragment = Objects.requireNonNull(shaders.get(ShaderType.FRAGMENT));
        return new Pipeline(
                pipeline.getLocation().toString(),
                ShaderStageKey.of(ShaderType.VERTEX, vertex, pipeline.getShaderDefines()),
                ShaderStageKey.of(ShaderType.FRAGMENT, fragment, pipeline.getShaderDefines()),
                pipeline.getShaderDefines(),
                pipeline.getPolygonMode() == PolygonMode.WIREFRAME);
    }

    /** The passes of one post effect, built the way the game builds them (only the shaders matter). */
    private static List<Pipeline> postEffect(String id, String json) throws ShaderException {
        List<Pipeline> passes = new ArrayList<>();
        JsonElement passList = JsonParser.parseString(json).getAsJsonObject().get("passes");
        if (passList == null) {
            return passes;
        }
        int index = 0;
        for (JsonElement element : passList.getAsJsonArray()) {
            JsonObject pass = element.getAsJsonObject();
            Identifier vertex = parse(id, pass, "vertex_shader");
            Identifier fragment = parse(id, pass, "fragment_shader");
            RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                    .withVertexShader(vertex)
                    .withFragmentShader(fragment)
                    .withLocation(Identifier.parse(id).withSuffix("/" + index))
                    .build();
            passes.add(of(pipeline));
            index++;
        }
        return passes;
    }

    private static Identifier parse(String effect, JsonObject pass, String field) throws ShaderException {
        JsonElement value = pass.get(field);
        if (value == null) {
            throw new ShaderException("Post effect " + effect + " has a pass without " + field);
        }
        return Identifier.parse(value.getAsString());
    }
}
