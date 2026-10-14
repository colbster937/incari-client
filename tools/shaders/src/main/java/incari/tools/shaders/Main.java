package incari.tools.shaders;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import incari.render.webgl2.shaders.ShaderManifest;
import incari.render.webgl2.shaders.ShaderStageKey;
import incari.render.webgl2.shaders.SourceHash;
import incari.render.webgl2.shaders.TranslatedStage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.renderer.ShaderDefines;
import org.jspecify.annotations.Nullable;

/**
 * Translates every shader stage the game can compile to GLSL ES 3.00 and writes the manifest the
 * WebGL2 device reads ({@code shaders.json}), with a report of what it did beside it
 * ({@code shaders.txt}).
 *
 * <pre>
 * incari-shaders --client-jar &lt;jar&gt; --minecraft &lt;version&gt; --out &lt;shaders.json&gt;
 *                [--require-glslang]
 * </pre>
 *
 * Fails if a vanilla shader doesn't compile or translate, or if glslang rejects a translated
 * program. A stage WebGL2 can't run yet (a texel buffer, say) isn't a failure: it's written as
 * unsupported, listed in the report, and the device skips that pipeline's draws.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (UsageException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    static int run(String[] args) throws IOException, InterruptedException, UsageException {
        @Nullable Path clientJar = null;
        @Nullable Path out = null;
        String minecraft = "";
        boolean requireGlslang = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--client-jar" -> clientJar = Path.of(value(args, ++i));
                case "--out" -> out = Path.of(value(args, ++i));
                case "--minecraft" -> minecraft = value(args, ++i);
                case "--require-glslang" -> requireGlslang = true;
                default -> throw new UsageException("Unknown option '" + args[i] + "'");
            }
        }
        if (clientJar == null || out == null || minecraft.isEmpty()) {
            throw new UsageException("Usage: incari-shaders --client-jar <jar> --minecraft <version> --out <file>"
                    + " [--require-glslang]");
        }

        GameShaderFiles files = GameShaderFiles.read(clientJar);
        List<PipelineCatalog.Pipeline> pipelines;
        try {
            pipelines = PipelineCatalog.collect(files);
        } catch (ShaderException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }

        Map<String, TranslatedStage> stages = new TreeMap<>();
        List<String> errors = new ArrayList<>();
        try (SpirvCompiler compiler = new SpirvCompiler()) {
            for (PipelineCatalog.Pipeline pipeline : pipelines) {
                for (ShaderStageKey key : List.of(pipeline.vertex(), pipeline.fragment())) {
                    if (stages.containsKey(key.asString())) {
                        continue;
                    }
                    try {
                        TranslatedStage plain = translate(files, compiler, key, pipeline.defines(), false);
                        stages.put(key.asString(), plain);
                        // A device with clip control (0-to-1 depth, as the game's desktop backends
                        // set up) compiles the game's shaders with one more define; a stage it
                        // changes gets a second translation (ADR 0012). Only the order-independent
                        // transparency passes read it, but every stage that includes their file
                        // mentions it, so the translations are compared rather than the sources.
                        if (readsZeroToOne(files, key)) {
                            TranslatedStage zeroToOne = translate(files, compiler, key, pipeline.defines(), true);
                            if (!zeroToOne.glsl().equals(plain.glsl())) {
                                stages.put(zeroToOne.key(), zeroToOne);
                            }
                        }
                    } catch (ShaderException e) {
                        errors.add(key + " (first used by " + pipeline.name() + "): " + e.getMessage());
                    }
                }
            }
        }

        Path glslang = GlslangCheck.find();
        int linked = 0;
        Map<String, String> unsupportedPipelines = new LinkedHashMap<>();
        if (glslang == null && requireGlslang) {
            errors.add("glslangValidator isn't installed (package: glslang), and --require-glslang was given");
        }
        try (GlslangCheck check = glslang == null ? null : new GlslangCheck(glslang)) {
            for (PipelineCatalog.Pipeline pipeline : pipelines) {
                String vertexVariant = pipeline.vertex()
                        .withFlag(ShaderStageKey.DEPTH_IS_ZERO_TO_ONE)
                        .asString();
                String fragmentVariant = pipeline.fragment()
                        .withFlag(ShaderStageKey.DEPTH_IS_ZERO_TO_ONE)
                        .asString();
                boolean hasVariant = stages.containsKey(vertexVariant) || stages.containsKey(fragmentVariant);
                for (boolean zeroToOne : hasVariant ? new boolean[] {false, true} : new boolean[] {false}) {
                    TranslatedStage vertex = stages.get(pipeline.vertex().asString());
                    TranslatedStage fragment = stages.get(pipeline.fragment().asString());
                    if (zeroToOne) {
                        vertex = stages.getOrDefault(vertexVariant, vertex);
                        fragment = stages.getOrDefault(fragmentVariant, fragment);
                    }
                    if (vertex == null || fragment == null) {
                        continue; // Already an error.
                    }
                    if (check != null && vertex.isSupported() && fragment.isSupported()) {
                        linked += check(check, pipeline, vertex, fragment, stages, errors);
                    }
                }
            }
            for (PipelineCatalog.Pipeline pipeline : pipelines) {
                TranslatedStage vertex = stages.get(pipeline.vertex().asString());
                TranslatedStage fragment = stages.get(pipeline.fragment().asString());
                if (vertex == null || fragment == null) {
                    continue; // Already an error.
                }
                String unsupported = vertex.unsupported() != null ? vertex.unsupported() : fragment.unsupported();
                if (unsupported != null) {
                    unsupportedPipelines.put(pipeline.name(), unsupported);
                }
            }
        }

        StringBuilder report = new StringBuilder();
        report.append("Shaders for WebGL2 (GLSL ES 3.00), translated from Minecraft ")
                .append(minecraft)
                .append('\n');
        report.append(pipelines.size())
                .append(" pipelines, ")
                .append(stages.size())
                .append(" distinct stages, ")
                .append(files.shaderCount())
                .append(" shader files in the jar\n");
        report.append(
                glslang == null
                        ? "Not checked with glslang (glslangValidator isn't installed)\n"
                        : linked + " programs compiled and linked by glslang (with the 0-to-1 depth variants)\n");
        report.append('\n')
                .append(unsupportedPipelines.size())
                .append(" pipelines WebGL2 can't run yet (their draws are skipped):\n");
        unsupportedPipelines.forEach((name, reason) ->
                report.append("  ").append(name).append(": ").append(reason).append('\n'));
        StringBuilder every = new StringBuilder();
        every.append("\nEvery pipeline, and what WebGL2 needs for it (ADR 0012):\n");
        for (PipelineCatalog.Pipeline pipeline : pipelines) {
            every.append("  ")
                    .append(pipeline.name())
                    .append(": ")
                    .append(needs(pipeline, stages))
                    .append('\n');
        }
        if (!errors.isEmpty()) {
            report.append('\n').append(errors.size()).append(" errors:\n");
            errors.forEach(error ->
                    report.append("  ").append(error.replace("\n", "\n    ")).append('\n'));
        }
        System.out.print(report);

        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String reportName = out.getFileName().toString().replaceFirst("\\.json$", "") + ".txt";
        // The full list goes to the file only: over two hundred lines.
        Files.writeString(out.resolveSibling(reportName), report.toString() + every, StandardCharsets.UTF_8);
        if (!errors.isEmpty()) {
            Files.deleteIfExists(out);
            return 1;
        }
        Files.writeString(out, new ShaderManifest(minecraft, stages.values()).toJson(), StandardCharsets.UTF_8);
        System.out.println("Wrote " + out + " (" + Files.size(out) / 1024 + " KiB)");
        return 0;
    }

    /** What a pipeline needs of WebGL2 beyond the basics, for the report's full list. */
    private static String needs(PipelineCatalog.Pipeline pipeline, Map<String, TranslatedStage> stages) {
        List<String> notes = new ArrayList<>();
        TranslatedStage vertex = stages.get(pipeline.vertex().asString());
        TranslatedStage fragment = stages.get(pipeline.fragment().asString());
        if (vertex == null || fragment == null) {
            return "not translated (see the errors)";
        }
        String unsupported = vertex.unsupported() != null ? vertex.unsupported() : fragment.unsupported();
        if (unsupported != null) {
            return "can't run yet: " + unsupported;
        }
        String glsl = vertex.glsl() + fragment.glsl();
        if (glsl.contains("incari_texel_")) {
            notes.add("texel buffer as a 2D texture");
        }
        if (glsl.contains("incari_out_")) {
            notes.add("fragment output array as separate outputs");
        }
        if (stages.containsKey(pipeline.vertex()
                        .withFlag(ShaderStageKey.DEPTH_IS_ZERO_TO_ONE)
                        .asString())
                || stages.containsKey(pipeline.fragment()
                        .withFlag(ShaderStageKey.DEPTH_IS_ZERO_TO_ONE)
                        .asString())) {
            notes.add("a 0-to-1 depth variant");
        }
        if (pipeline.wireframe()) {
            notes.add("WEBGL_polygon_mode (skipped without it)");
        }
        return notes.isEmpty() ? "runs as translated" : String.join(", ", notes);
    }

    /**
     * Compiles and translates one stage.
     *
     * @param zeroToOne compile with the game's define for a 0-to-1 depth range, and file the
     *     result under the key with that flag
     */
    private static TranslatedStage translate(
            GameShaderFiles files, SpirvCompiler compiler, ShaderStageKey key, ShaderDefines defines, boolean zeroToOne)
            throws ShaderException {
        String source = source(files, key);
        String expanded = Includes.expand(source, files.includes());
        Map<String, String> macros = new LinkedHashMap<>(defines.values());
        for (String flag : defines.flags()) {
            macros.put(flag, "");
        }
        if (zeroToOne) {
            macros.put(ShaderStageKey.DEPTH_IS_ZERO_TO_ONE, "");
        }
        String extension = key.type() == ShaderType.VERTEX ? ".vsh" : ".fsh";
        int[] spirv = compiler.compile(key.id() + extension, expanded, key.type(), macros);
        String filed =
                zeroToOne ? key.withFlag(ShaderStageKey.DEPTH_IS_ZERO_TO_ONE).asString() : key.asString();
        return EsslTranslator.translate(filed, SourceHash.of(source), spirv, key.type());
    }

    /** Whether the stage's source, with its includes, reads the 0-to-1 depth define. */
    private static boolean readsZeroToOne(GameShaderFiles files, ShaderStageKey key) throws ShaderException {
        return Includes.expand(source(files, key), files.includes()).contains(ShaderStageKey.DEPTH_IS_ZERO_TO_ONE);
    }

    private static String source(GameShaderFiles files, ShaderStageKey key) throws ShaderException {
        String source = files.shader(key.type(), key.id().toString());
        if (source == null) {
            throw new ShaderException("No " + key.type().getName() + " shader " + key.id() + " in the jar");
        }
        return source;
    }

    /**
     * Links one program with glslang: 1 if it links, 0 if it can't (then either the stage is marked
     * unsupported, for a construct GLSL ES lacks, or the problem is added to {@code errors}).
     */
    private static int check(
            GlslangCheck check,
            PipelineCatalog.Pipeline pipeline,
            TranslatedStage vertex,
            TranslatedStage fragment,
            Map<String, TranslatedStage> stages,
            List<String> errors)
            throws IOException, InterruptedException {
        String problem = check.link(vertex.glsl(), fragment.glsl());
        GlslangCheck.EsLimit limit = problem == null ? null : GlslangCheck.esLimit(problem);
        if (limit != null) {
            // A construct GLSL ES can't express (desktop GLSL can): the stage waits for a WebGL2
            // rewrite rather than failing the build.
            TranslatedStage stage = limit.vertex() ? vertex : fragment;
            stages.put(
                    stage.key(),
                    TranslatedStage.unsupported(
                            stage.key(), stage.sourceHash(), "GLSL ES 3.00 has no " + limit.what()));
            return 0;
        }
        if (problem != null) {
            errors.add(pipeline.name() + " (" + vertex.key() + " + " + fragment.key()
                    + ") doesn't link as GLSL ES 3.00:\n" + problem);
            return 0;
        }
        return 1;
    }

    private static String value(String[] args, int index) throws UsageException {
        if (index >= args.length) {
            throw new UsageException(args[index - 1] + " needs a value");
        }
        return args[index];
    }

    /** Bad command-line arguments. */
    static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }
}
