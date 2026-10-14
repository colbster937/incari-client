package incari.tools.shaders;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import incari.render.webgl2.shaders.TranslatedStage;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcReflectedResource;

/**
 * SPIR-V to GLSL ES 3.00 with SPIRV-Cross, plus the reflection the WebGL2 device needs.
 *
 * <p>WebGL2 changes three things about how a program is wired, and the translation follows them:
 * <ul>
 *   <li>ES 3.00 has no {@code layout(binding)} and no locations on the varyings between stages,
 *       which match by name there. The game's stages match by location, so both sides of each
 *       varying are renamed after its location ({@code incari_io_3}).
 *   <li>Uniform blocks and samplers keep the game's names, which are the pipeline's uniform
 *       names: the device binds them by name when it links the program.
 *   <li>Vertex inputs keep their names and locations; the device matches them to the vertex
 *       format's elements by name, as the game's own pipeline builder does.
 * </ul>
 * WebGL2 has no storage buffers or push constants; a stage using one is reported unsupported
 * rather than translated wrongly. Texel buffers and fragment output arrays, which GLSL ES 3.00
 * lacks too, are rewritten ({@link EsslRewrites}).
 */
final class EsslTranslator {

    // SPIR-V enum values (the SPIR-V specification's, as LWJGL passes them through).
    private static final int DECORATION_LOCATION = 30;
    private static final int DIM_2D = 1;
    private static final int DIM_CUBE = 3;
    private static final int DIM_BUFFER = 5;

    /** The prefix of the varyings' shared names; the location follows. */
    static final String VARYING_PREFIX = "incari_io_";

    /** The prefix of uniform block instance names; the block's name follows. */
    static final String BLOCK_PREFIX = "incari_ubo_";

    private EsslTranslator() {}

    static TranslatedStage translate(String key, String sourceHash, int[] spirv, ShaderType type)
            throws ShaderException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer out = stack.mallocPointer(1);
            check(0, Spvc.spvc_context_create(out), "create a context");
            long context = out.get(0);
            try {
                IntBuffer words =
                        MemoryUtil.memAllocInt(spirv.length).put(spirv).flip();
                try {
                    check(context, Spvc.spvc_context_parse_spirv(context, words, spirv.length, out), "parse SPIR-V");
                } finally {
                    MemoryUtil.memFree(words);
                }
                long ir = out.get(0);
                check(
                        context,
                        Spvc.spvc_context_create_compiler(
                                context, Spvc.SPVC_BACKEND_GLSL, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, out),
                        "create a GLSL compiler");
                long compiler = out.get(0);
                check(context, Spvc.spvc_compiler_create_shader_resources(compiler, out), "list resources");
                long resources = out.get(0);

                String unsupported = unsupportedResources(compiler, resources, stack);
                if (unsupported != null) {
                    return TranslatedStage.unsupported(key, sourceHash, unsupported);
                }
                List<TranslatedStage.Attribute> attributes = new ArrayList<>();
                if (type == ShaderType.VERTEX) {
                    for (SpvcReflectedResource input : list(resources, Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT, stack)) {
                        long handle = Spvc.spvc_compiler_get_type_handle(compiler, input.type_id());
                        int baseType = Spvc.spvc_type_get_basetype(handle);
                        attributes.add(new TranslatedStage.Attribute(
                                input.nameString(),
                                Spvc.spvc_compiler_get_decoration(compiler, input.id(), DECORATION_LOCATION),
                                baseType == Spvc.SPVC_BASETYPE_INT32 || baseType == Spvc.SPVC_BASETYPE_UINT32));
                    }
                    renameVaryings(compiler, list(resources, Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT, stack));
                } else {
                    renameVaryings(compiler, list(resources, Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT, stack));
                }
                List<String> blocks = new ArrayList<>();
                for (SpvcReflectedResource block : list(resources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, stack)) {
                    blocks.add(block.nameString());
                    // The same block gets the same instance name in both stages; SPIRV-Cross would
                    // number them per stage, which the linker only tolerates.
                    Spvc.spvc_compiler_set_name(compiler, block.id(), BLOCK_PREFIX + block.nameString());
                }
                List<String> samplers = new ArrayList<>();
                List<String> texelBuffers = new ArrayList<>();
                for (SpvcReflectedResource sampler : list(resources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, stack)) {
                    samplers.add(sampler.nameString());
                    long handle = Spvc.spvc_compiler_get_type_handle(compiler, sampler.type_id());
                    if (Spvc.spvc_type_get_image_dimension(handle) == DIM_BUFFER) {
                        texelBuffers.add(sampler.nameString());
                    }
                }

                check(context, Spvc.spvc_compiler_create_compiler_options(compiler, out), "create options");
                long options = out.get(0);
                Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_GLSL_VERSION, 300);
                Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_GLSL_ES, true);
                // The game's shaders were written for desktop GLSL, where everything is highp.
                Spvc.spvc_compiler_options_set_bool(
                        options, Spvc.SPVC_COMPILER_OPTION_GLSL_ES_DEFAULT_FLOAT_PRECISION_HIGHP, true);
                Spvc.spvc_compiler_options_set_bool(
                        options, Spvc.SPVC_COMPILER_OPTION_GLSL_ES_DEFAULT_INT_PRECISION_HIGHP, true);
                // WebGL2 can't draw from a base instance, and the device says so (nonZeroFirstInstance).
                Spvc.spvc_compiler_options_set_bool(
                        options, Spvc.SPVC_COMPILER_OPTION_GLSL_SUPPORT_NONZERO_BASE_INSTANCE, false);
                Spvc.spvc_compiler_options_set_bool(
                        options, Spvc.SPVC_COMPILER_OPTION_GLSL_SEPARATE_SHADER_OBJECTS, false);
                Spvc.spvc_compiler_options_set_bool(
                        options, Spvc.SPVC_COMPILER_OPTION_GLSL_ENABLE_420PACK_EXTENSION, false);
                check(context, Spvc.spvc_compiler_install_compiler_options(compiler, options), "set options");
                check(context, Spvc.spvc_compiler_compile(compiler, out), "translate");
                String glsl = EsslRewrites.texelBuffers(MemoryUtil.memUTF8(out.get(0)), texelBuffers);
                if (type == ShaderType.FRAGMENT) {
                    glsl = EsslRewrites.fragmentOutputArrays(glsl);
                }
                return new TranslatedStage(key, sourceHash, glsl, attributes, blocks, samplers, null);
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    /** Why WebGL2 can't run the stage yet, or null if it can. */
    private static @Nullable String unsupportedResources(long compiler, long resources, MemoryStack stack)
            throws ShaderException {
        if (!list(resources, Spvc.SPVC_RESOURCE_TYPE_PUSH_CONSTANT, stack).isEmpty()) {
            return "push constants (WebGL2 has none; emulating them is future work)";
        }
        if (!list(resources, Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER, stack).isEmpty()) {
            return "storage buffers (not in WebGL2)";
        }
        if (!list(resources, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE, stack).isEmpty()
                || !list(resources, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS, stack)
                        .isEmpty()) {
            return "separate images and samplers (not in GLSL ES)";
        }
        for (SpvcReflectedResource sampler : list(resources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, stack)) {
            int dimension =
                    Spvc.spvc_type_get_image_dimension(Spvc.spvc_compiler_get_type_handle(compiler, sampler.type_id()));
            if (dimension != DIM_2D && dimension != DIM_CUBE && dimension != DIM_BUFFER) {
                return "sampler " + sampler.nameString() + " of SPIR-V dimension " + dimension;
            }
        }
        return null;
    }

    private static void renameVaryings(long compiler, List<SpvcReflectedResource> varyings) {
        for (SpvcReflectedResource varying : varyings) {
            int location = Spvc.spvc_compiler_get_decoration(compiler, varying.id(), DECORATION_LOCATION);
            Spvc.spvc_compiler_set_name(compiler, varying.id(), VARYING_PREFIX + location);
        }
    }

    private static List<SpvcReflectedResource> list(long resources, int type, MemoryStack stack)
            throws ShaderException {
        PointerBuffer list = stack.mallocPointer(1);
        PointerBuffer count = stack.mallocPointer(1);
        check(0, Spvc.spvc_resources_get_resource_list_for_type(resources, type, list, count), "list resources");
        List<SpvcReflectedResource> result = new ArrayList<>();
        SpvcReflectedResource.Buffer buffer = SpvcReflectedResource.create(list.get(0), (int) count.get(0));
        for (int i = 0; i < count.get(0); i++) {
            result.add(buffer.get(i));
        }
        return result;
    }

    private static void check(long context, int result, String what) throws ShaderException {
        if (result != Spvc.SPVC_SUCCESS) {
            String detail = context == 0 ? "" : ": " + Spvc.spvc_context_get_last_error_string(context);
            throw new ShaderException("SPIRV-Cross couldn't " + what + " (error " + result + ")" + detail);
        }
    }
}
