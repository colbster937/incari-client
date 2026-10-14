package incari.render.webgl2;

import static org.teavm.jso.webgl.WebGL2RenderingContext.COMPILE_STATUS;
import static org.teavm.jso.webgl.WebGL2RenderingContext.FRAGMENT_SHADER;
import static org.teavm.jso.webgl.WebGL2RenderingContext.INVALID_INDEX;
import static org.teavm.jso.webgl.WebGL2RenderingContext.LINK_STATUS;
import static org.teavm.jso.webgl.WebGL2RenderingContext.VERTEX_SHADER;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import incari.render.webgl2.shaders.TranslatedStage;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.webgl.WebGL2RenderingContext;
import org.teavm.jso.webgl.WebGLProgram;
import org.teavm.jso.webgl.WebGLShader;
import org.teavm.jso.webgl.WebGLUniformLocation;

/**
 * A pipeline: its translated program plus the fixed-function state the game describes with it.
 *
 * <p>The program is linked on first use, not when the game compiles its pipelines at startup: the
 * game compiles every pipeline it has (over two hundred) before the title screen, and on a
 * Chromebook each WebGL link costs milliseconds of the page's only thread. A pipeline WebGL2 can't
 * run, or whose program fails to link, draws nothing and says why once.
 *
 * <p>Uniform {@code i} of the pipeline (the frontend's index) is uniform block binding point
 * {@code i} or texture unit {@code i}, fixed when the program links.
 *
 * <p>A pipeline with {@link #drawRows() draw rows} reads its per-instance inputs from a uniform
 * block by {@code gl_DrawID} instead of from attributes ({@link DrawIdRewrite}): the game's
 * multi-draw terrain pipelines, where the device has {@code WEBGL_multi_draw}.
 */
final class WebGl2Pipeline implements BackendRenderPipeline {

    private final WebGl2Device device;
    private final String name;
    private final @Nullable TranslatedStage vertex;
    private final @Nullable TranslatedStage fragment;
    private final List<BindGroupLayout.UniformDescription> uniforms;
    /**
     * Each uniform block's size as the linked program reports it, by uniform index (0 for
     * anything else). A driver may round a block up past the game's std140 size (Mesa's
     * radeonsi does, under ANGLE's OpenGL backend), and WebGL refuses to draw from a bound range
     * smaller than that.
     */
    private final int[] blockSizes;

    private final List<VertexLayout.Binding> attributes;
    private final VertexLayout.@Nullable DrawRowLayout drawRows;
    private final @Nullable String vertexGlsl;
    private final boolean terrain;
    private final int drawMode;
    private final boolean cull;
    private final boolean wireframe;
    private final @Nullable DepthStencilState depthState;
    private final @Nullable BlendFunction blend;
    private final int colorMask;
    private @Nullable String problem;
    private @Nullable WebGLProgram program;
    private boolean reported;
    private boolean closed;

    private WebGl2Pipeline(
            WebGl2Device device,
            RenderPipeline pipeline,
            @Nullable TranslatedStage vertex,
            @Nullable TranslatedStage fragment,
            List<VertexLayout.Binding> attributes,
            VertexLayout.@Nullable DrawRowLayout drawRows,
            @Nullable String vertexGlsl,
            @Nullable String problem) {
        this.device = device;
        this.name = pipeline.getLocation().toString();
        this.vertex = vertex;
        this.fragment = fragment;
        this.uniforms = BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts());
        this.blockSizes = new int[uniforms.size()];
        this.drawRows = drawRows;
        this.attributes = drawRows != null ? drawRows.attributes() : List.copyOf(attributes);
        this.vertexGlsl = vertexGlsl;
        // The profiler counts terrain's draws apart from the rest.
        this.terrain = pipeline.getLocation().getPath().contains("terrain");
        this.drawMode = GlFormats.drawMode(pipeline.getPrimitiveTopology());
        this.cull = pipeline.isCull();
        this.wireframe = pipeline.getPolygonMode() == PolygonMode.WIREFRAME;
        this.depthState = pipeline.getDepthStencilState();
        // WebGL2 has one blend state and one color mask for all attachments (per-attachment ones need
        // OES_draw_buffers_indexed); the game's blend is the same on every target by construction.
        BlendFunction firstBlend = null;
        int mask = ColorTargetState.WRITE_ALL;
        for (ColorTargetState target : pipeline.getColorTargetStates()) {
            if (target != null) {
                if (firstBlend == null && target.blendFunction().isPresent()) {
                    firstBlend = target.blendFunction().get();
                }
                mask = target.writeMask();
                break;
            }
        }
        this.blend = firstBlend;
        this.colorMask = mask;
        this.problem = problem;
    }

    static WebGl2Pipeline of(
            WebGl2Device device,
            RenderPipeline pipeline,
            TranslatedStage vertex,
            TranslatedStage fragment,
            List<VertexLayout.Binding> attributes) {
        return new WebGl2Pipeline(device, pipeline, vertex, fragment, attributes, null, null, null);
    }

    /**
     * A pipeline whose per-instance inputs are read as draw rows ({@link DrawIdRewrite}), with
     * {@code vertexGlsl} the rewritten vertex shader.
     */
    static WebGl2Pipeline withDrawRows(
            WebGl2Device device,
            RenderPipeline pipeline,
            TranslatedStage vertex,
            TranslatedStage fragment,
            VertexLayout.DrawRowLayout drawRows,
            String vertexGlsl) {
        return new WebGl2Pipeline(
                device, pipeline, vertex, fragment, drawRows.attributes(), drawRows, vertexGlsl, null);
    }

    /** A pipeline that draws nothing, because {@code reason}. */
    static WebGl2Pipeline unsupported(WebGl2Device device, RenderPipeline pipeline, String reason) {
        return new WebGl2Pipeline(device, pipeline, null, null, List.of(), null, null, reason);
    }

    String name() {
        return name;
    }

    List<VertexLayout.Binding> attributes() {
        return attributes;
    }

    /** Where the per-instance inputs come from if they're read as draw rows; null if they aren't. */
    VertexLayout.@Nullable DrawRowLayout drawRows() {
        return drawRows;
    }

    /** Whether it can't draw: unsupported, or its program didn't compile or link. */
    boolean failed() {
        return problem != null;
    }

    /** Whether this draws terrain. */
    boolean terrain() {
        return terrain;
    }

    /** The size the program needs bound for uniform block {@code index}, or 0. */
    int blockSize(int index) {
        return blockSizes[index];
    }

    List<BindGroupLayout.UniformDescription> uniforms() {
        return uniforms;
    }

    int drawMode() {
        return drawMode;
    }

    /**
     * Makes this the current program with its state, linking it first if it hasn't been; false if
     * it can't draw (the draw is then skipped).
     */
    boolean use() {
        if (closed) {
            return false;
        }
        WebGLProgram linked = link();
        if (linked == null) {
            if (!reported) {
                reported = true;
                WebGl.warn("[Incari] Skipping draws of " + name + ": " + problem);
            }
            return false;
        }
        GlState state = device.state();
        state.program(this, linked);
        state.cull(cull);
        state.polygonLines(wireframe);
        state.depth(depthState);
        state.blend(blend);
        state.colorMask(colorMask);
        return true;
    }

    /** Links the program if it hasn't been; null if the pipeline can't be drawn. */
    @Nullable
    WebGLProgram link() {
        if (program != null || problem != null) {
            return program;
        }
        TranslatedStage vertexStage = vertex;
        TranslatedStage fragmentStage = fragment;
        if (vertexStage == null || fragmentStage == null) {
            problem = "no shaders";
            return null;
        }
        WebGL2RenderingContext gl = device.gl();
        WebGLShader vertexShader =
                compile(VERTEX_SHADER, vertexStage, vertexGlsl != null ? vertexGlsl : vertexStage.glsl());
        WebGLShader fragmentShader = compile(FRAGMENT_SHADER, fragmentStage, fragmentStage.glsl());
        if (vertexShader == null || fragmentShader == null) {
            return null;
        }
        WebGLProgram linked = gl.createProgram();
        gl.attachShader(linked, vertexShader);
        gl.attachShader(linked, fragmentShader);
        gl.linkProgram(linked);
        // The program keeps what it needs; the shader objects only hold memory.
        gl.deleteShader(vertexShader);
        gl.deleteShader(fragmentShader);
        if (!gl.getProgramParameterb(linked, LINK_STATUS)) {
            problem = "program doesn't link: " + gl.getProgramInfoLog(linked);
            WebGl.error("[Incari] " + name + ": " + problem);
            gl.deleteProgram(linked);
            return null;
        }
        device.state().program(this, linked);
        if (drawRows != null) {
            int block = gl.getUniformBlockIndex(linked, DrawIdRewrite.BLOCK);
            if (block != INVALID_INDEX) {
                gl.uniformBlockBinding(linked, block, device.drawRows().binding());
            }
        }
        for (int i = 0; i < uniforms.size(); i++) {
            BindGroupLayout.UniformDescription uniform = uniforms.get(i);
            if (uniform.type() == UniformType.UNIFORM_BUFFER) {
                int block = gl.getUniformBlockIndex(linked, uniform.name());
                if (block != INVALID_INDEX) {
                    gl.uniformBlockBinding(linked, block, i);
                    blockSizes[i] = WebGl.uniformBlockDataSize(gl, linked, block);
                }
            } else if (uniform.type() == UniformType.COMBINED_IMAGE_SAMPLER
                    || uniform.type() == UniformType.TEXEL_BUFFER) {
                // A texel buffer is a texture here (TexelBufferTexture), on its unit like a sampler.
                WebGLUniformLocation location = gl.getUniformLocation(linked, uniform.name());
                if (location != null) {
                    gl.uniform1i(location, i);
                }
            }
        }
        program = linked;
        return linked;
    }

    private @Nullable WebGLShader compile(int type, TranslatedStage stage, String glsl) {
        WebGL2RenderingContext gl = device.gl();
        WebGLShader shader = gl.createShader(type);
        gl.shaderSource(shader, glsl);
        gl.compileShader(shader);
        if (!gl.getShaderParameterb(shader, COMPILE_STATUS)) {
            problem = stage.key() + " doesn't compile: " + gl.getShaderInfoLog(shader);
            WebGl.error("[Incari] " + name + ": " + problem);
            gl.deleteShader(shader);
            return null;
        }
        return shader;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            WebGLProgram linked = program;
            if (linked != null) {
                device.state().forgetProgram();
                device.gl().deleteProgram(linked);
                program = null;
            }
        }
    }
}
