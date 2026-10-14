package incari.tools.shaders;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.nio.ByteBuffer;
import java.util.Map;
import org.lwjgl.util.shaderc.Shaderc;

/**
 * GLSL to SPIR-V with shaderc, set up as the game sets it up for its own devices: Vulkan 1.2
 * semantics, uniforms bound automatically, debug info kept (SPIRV-Cross needs the names back), no
 * optimization. The browser's device reports depth as -1 to 1 and no draw parameters, so none of
 * the game's device-dependent macros are defined; only the pipeline's own defines are.
 *
 * <p>One compiler for the whole run; not thread-safe.
 */
final class SpirvCompiler implements AutoCloseable {

    private final long compiler = Shaderc.shaderc_compiler_initialize();

    /** Compiles an include-expanded source; the SPIR-V words are copied out of shaderc's result. */
    int[] compile(String name, String source, ShaderType type, Map<String, String> defines) throws ShaderException {
        long options = Shaderc.shaderc_compile_options_initialize();
        try {
            Shaderc.shaderc_compile_options_set_source_language(options, Shaderc.shaderc_source_language_glsl);
            Shaderc.shaderc_compile_options_set_target_env(
                    options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
            Shaderc.shaderc_compile_options_set_generate_debug_info(options);
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_zero);
            for (Map.Entry<String, String> define : defines.entrySet()) {
                Shaderc.shaderc_compile_options_add_macro_definition(options, define.getKey(), define.getValue());
            }
            int kind = type == ShaderType.VERTEX ? Shaderc.shaderc_vertex_shader : Shaderc.shaderc_fragment_shader;
            long result = Shaderc.shaderc_compile_into_spv(compiler, source, kind, name, "main", options);
            try {
                if (Shaderc.shaderc_result_get_compilation_status(result)
                        != Shaderc.shaderc_compilation_status_success) {
                    throw new ShaderException("shaderc: "
                            + String.valueOf(Shaderc.shaderc_result_get_error_message(result))
                                    .trim());
                }
                ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                if (bytes == null) {
                    throw new ShaderException("shaderc returned no SPIR-V");
                }
                int[] words = new int[bytes.remaining() / 4];
                bytes.order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(words);
                return words;
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
        }
    }

    @Override
    public void close() {
        Shaderc.shaderc_compiler_release(compiler);
    }
}
