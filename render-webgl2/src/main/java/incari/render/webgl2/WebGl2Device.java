package incari.render.webgl2;

import static org.teavm.jso.webgl.WebGL2RenderingContext.MAX_COLOR_ATTACHMENTS;
import static org.teavm.jso.webgl.WebGL2RenderingContext.MAX_COMBINED_TEXTURE_IMAGE_UNITS;
import static org.teavm.jso.webgl.WebGL2RenderingContext.MAX_DRAW_BUFFERS;
import static org.teavm.jso.webgl.WebGL2RenderingContext.MAX_TEXTURE_SIZE;
import static org.teavm.jso.webgl.WebGL2RenderingContext.MAX_UNIFORM_BUFFER_BINDINGS;
import static org.teavm.jso.webgl.WebGL2RenderingContext.MAX_VERTEX_ATTRIBS;
import static org.teavm.jso.webgl.WebGL2RenderingContext.RENDERER;
import static org.teavm.jso.webgl.WebGL2RenderingContext.SHADING_LANGUAGE_VERSION;
import static org.teavm.jso.webgl.WebGL2RenderingContext.TEXTURE0;
import static org.teavm.jso.webgl.WebGL2RenderingContext.UNIFORM_BUFFER_OFFSET_ALIGNMENT;
import static org.teavm.jso.webgl.WebGL2RenderingContext.UNPACK_ALIGNMENT;
import static org.teavm.jso.webgl.WebGL2RenderingContext.VENDOR;
import static org.teavm.jso.webgl.WebGL2RenderingContext.VERSION;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import incari.client.render.GpuFrameStats;
import incari.client.render.PipelineCompilingBackend;
import incari.render.webgl2.shaders.ShaderManifest;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.JSObject;
import org.teavm.jso.webgl.WebGL2RenderingContext;
import org.teavm.jso.webgl.WebGLTexture;

/**
 * Mojang's {@code GpuDevice} backend API over WebGL2, under the game's own frontend
 * ({@code FrontendGpuDevice}), which validates every call before it gets here.
 *
 * <p>What it tells the game: depth from 0 to 1 where the browser has EXT_clip_control (else -1 to
 * 1, as OpenGL without clip control), and no persistent mapping. Where the browser has
 * WEBGL_multi_draw it also claims indirect multi-draws and a base instance, which is what makes the
 * game draw terrain through its multi-draw path: the render pass carries each indirect draw out as
 * a few {@code multiDrawElementsWEBGL} calls, one per vertex heap (ADR 0022). Without it the
 * game takes its plain path, a draw per section. Pipelines come from
 * the build's translated shaders ({@link PipelineCompiler}), not from shaderc.
 *
 * <p>It counts each frame's draw calls, and times the GPU's work where the browser can
 * ({@link GpuTimer}), for the profiler.
 *
 * <p>Runs on the page's only thread; no call blocks except the read-backs noted in
 * {@link WebGl2CommandEncoder}.
 */
final class WebGl2Device implements GpuDeviceBackend, PipelineCompilingBackend, GpuFrameStats {

    /** The memory the game may plan to allocate; it sizes large textures from this. Chromebooks share 4 GB. */
    private static final long MAX_ALLOCATION = 512L * 1024 * 1024;

    /** Sub-draws the game may put in one indirect draw; the render pass splits them as it needs. */
    private static final int MAX_INDIRECT_DRAWS = 1 << 16;

    private final WebGL2RenderingContext gl;
    private final Staging staging = new Staging();
    private final PendingUploads pendingUploads = new PendingUploads();
    private final Framebuffers framebuffers = new Framebuffers(this);
    private final GlState state;
    private final Bindings bindings;
    private final PipelineCompiler pipelines;
    private final DeviceInfo info;
    private final boolean anisotropy;
    private final boolean floatBlend;
    private final boolean zeroToOne;
    private final boolean polygonMode;
    private final @Nullable JSObject multiDraw;
    private final GpuTimer gpuTimer;
    private final GpuCensus gpuCensus;
    private final @Nullable QuadIndices quadIndices;
    private final @Nullable DrawRows drawRows;
    private final @Nullable RebasedIndices rebasedIndices;
    /** Counters for {@link WebGl#publishStats}. */
    int frames;

    int draws;
    int skippedDraws;

    /** This frame's draw calls, terrain's among them, and the terrain sections they drew. */
    int frameDraws;

    int frameTerrainDraws;
    int frameTerrainSections;
    private int lastDraws;
    private int lastTerrainDraws;
    private int lastTerrainSections;

    /** The texture unit uploads bind on: the last one, which no pipeline's uniform index reaches. */
    private final int updateUnit;

    /**
     * @param allowMultiDraw whether to draw terrain with WEBGL_multi_draw where the browser has it
     *     (the page's {@code ?multidraw=off} turns it off, to compare)
     */
    WebGl2Device(WebGL2RenderingContext gl, ShaderManifest manifest, boolean eagerShaders, boolean allowMultiDraw) {
        this.gl = gl;
        JSObject polygonMode = WebGl.polygonModeExtension(gl);
        this.polygonMode = polygonMode != null;
        this.state = new GlState(gl, polygonMode);
        int units = gl.getParameteri(MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        int uniformBindings = gl.getParameteri(MAX_UNIFORM_BUFFER_BINDINGS);
        this.updateUnit = units - 1;
        // The last uniform buffer binding point is the multi-draw rows' (DrawRows); the pipelines'
        // uniforms use the ones below it.
        this.bindings =
                new Bindings(gl, Math.min(units - 1, uniformBindings - 1), gl.getParameteri(MAX_VERTEX_ATTRIBS));
        this.gpuTimer = new GpuTimer(gl);
        this.gpuCensus = new GpuCensus(gl);
        this.multiDraw = allowMultiDraw ? WebGl.extension(gl, "WEBGL_multi_draw") : null;
        if (multiDraw != null) {
            this.quadIndices = new QuadIndices(this);
            this.drawRows = new DrawRows(this, uniformBindings - 1, gl.getParameteri(UNIFORM_BUFFER_OFFSET_ALIGNMENT));
            this.rebasedIndices = new RebasedIndices(this);
        } else {
            this.quadIndices = null;
            this.drawRows = null;
            this.rebasedIndices = null;
        }
        this.pipelines = new PipelineCompiler(this, manifest, eagerShaders);
        // Rendering to float targets (the game's HDR and translucency buffers) is an extension in
        // WebGL2; so is anisotropic filtering.
        WebGl.enableExtension(gl, "EXT_color_buffer_float");
        WebGl.enableExtension(gl, "EXT_color_buffer_half_float");
        this.floatBlend = WebGl.enableExtension(gl, "EXT_float_blend");
        WebGl.enableExtension(gl, "OES_texture_float_linear");
        this.anisotropy = WebGl.enableExtension(gl, "EXT_texture_filter_anisotropic");
        // Reversed-Z as on the desktop: the game puts the near plane at depth 1 and the far one at
        // 0, which only pays off in precision where depth runs 0 to 1. Without the extension the
        // game builds its projection for -1 to 1 instead, as for any OpenGL without clip control.
        this.zeroToOne = WebGl.clipControlZeroToOne(gl);
        WebGl.enableExtension(gl, "WEBGL_debug_renderer_info");
        // The game packs its uploads tightly.
        gl.pixelStorei(UNPACK_ALIGNMENT, 1);
        // One vertex array for everything: attributes are re-pointed per pipeline, not per object.
        gl.bindVertexArray(gl.createVertexArray());
        this.info = describe();
        GpuFrameStats.install(this);
    }

    private DeviceInfo describe() {
        String renderer = WebGl.stringParameter(gl, WebGl.UNMASKED_RENDERER);
        String vendor = WebGl.stringParameter(gl, WebGl.UNMASKED_VENDOR);
        Set<String> extensions = new HashSet<>();
        JSObject supported = WebGl.supportedExtensions(gl);
        for (int i = 0; i < WebGl.length(supported); i++) {
            extensions.add(WebGl.stringAt(supported, i));
        }
        int maxAnisotropy =
                anisotropy ? Math.max(1, (int) WebGl.numberParameter(gl, WebGl.MAX_TEXTURE_MAX_ANISOTROPY)) : 1;
        return new DeviceInfo(
                renderer.isEmpty() ? WebGl.stringParameter(gl, RENDERER) : renderer,
                vendor.isEmpty() ? WebGl.stringParameter(gl, VENDOR) : vendor,
                WebGl.stringParameter(gl, VERSION) + ", " + WebGl.stringParameter(gl, SHADING_LANGUAGE_VERSION),
                zeroToOne,
                "WebGL2",
                1.0F,
                new DeviceLimits(
                        maxAnisotropy,
                        gl.getParameteri(UNIFORM_BUFFER_OFFSET_ALIGNMENT),
                        gl.getParameteri(MAX_TEXTURE_SIZE),
                        MAX_ALLOCATION,
                        0,
                        Math.min(gl.getParameteri(MAX_COLOR_ATTACHMENTS), gl.getParameteri(MAX_DRAW_BUFFERS)),
                        multiDraw != null ? MAX_INDIRECT_DRAWS : 0),
                // Indirect draws and a base instance are emulated (WebGl2RenderPass), so they're
                // only claimed where the terrain they're for can be multi-drawn.
                multiDraw != null
                        ? new DeviceFeatures(false, false, false, false, true, true, true, false)
                        : new DeviceFeatures(false, false, false, false, false, false, false, false),
                Set.copyOf(extensions),
                new HintsAndWorkarounds(false, false, false, false),
                DeviceType.OTHER);
    }

    WebGL2RenderingContext gl() {
        return gl;
    }

    Staging staging() {
        return staging;
    }

    /** Uniform buffers written since the last draw (ADR 0027). */
    PendingUploads pendingUploads() {
        return pendingUploads;
    }

    Framebuffers framebuffers() {
        return framebuffers;
    }

    GlState state() {
        return state;
    }

    Bindings bindings() {
        return bindings;
    }

    boolean hasAnisotropy() {
        return anisotropy;
    }

    /** Whether depth runs from 0 to 1 (EXT_clip_control), as the game's desktop backends set it. */
    boolean depthZeroToOne() {
        return zeroToOne;
    }

    /** Whether wireframe pipelines can draw (WEBGL_polygon_mode). */
    boolean hasPolygonMode() {
        return polygonMode;
    }

    /** Whether terrain is multi-drawn (WEBGL_multi_draw). */
    boolean multiDraw() {
        return multiDraw != null;
    }

    /** WEBGL_multi_draw's object; only where {@link #multiDraw()}. */
    JSObject multiDrawExtension() {
        JSObject extension = multiDraw;
        if (extension == null) {
            throw new IllegalStateException("No WEBGL_multi_draw");
        }
        return extension;
    }

    /** The multi-draw rows; only where {@link #multiDraw()}. */
    DrawRows drawRows() {
        DrawRows rows = drawRows;
        if (rows == null) {
            throw new IllegalStateException("No WEBGL_multi_draw");
        }
        return rows;
    }

    /** Rebase quad-indexed draws onto {@link QuadIndices}; the page's {@code ?rebase=off} doesn't, to compare. */
    static volatile boolean rebaseQuadDraws = true;

    /** The page's {@code ?drawcensus}: draws per pipeline logged every {@link #CENSUS_FRAMES} frames. */
    static volatile boolean census;

    /** The page's {@code ?gpucensus}: each draw's GPU time, by pipeline ({@link GpuCensus}). */
    static volatile boolean timeDraws;

    private static final int CENSUS_FRAMES = 600;
    private final java.util.Map<String, Integer> censusDraws = new java.util.TreeMap<>();
    private int censusFrames;

    /** Logs the census: each pipeline's draws a frame, most first (development only). */
    private void logCensus() {
        StringBuilder line = new StringBuilder("[Incari] Draws a frame by pipeline over ")
                .append(censusFrames)
                .append(" frames:");
        censusDraws.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> line.append(String.format(
                        java.util.Locale.ROOT, " %s=%.2f", entry.getKey(), entry.getValue() / (double) censusFrames)));
        WebGl.info(line.toString());
        censusDraws.clear();
        censusFrames = 0;
    }

    /**
     * Whether a draw from the game's sequential quad indices is drawn from the backend's own
     * instead, leaving the vertex attributes in place (ADR 0027): where the device has them.
     */
    boolean rebasesQuadDraws() {
        return rebaseQuadDraws && quadIndices != null;
    }

    /** The sequential quad indices multi-draws read; only where {@link #multiDraw()}. */
    QuadIndices quadIndices() {
        QuadIndices indices = quadIndices;
        if (indices == null) {
            throw new IllegalStateException("No WEBGL_multi_draw");
        }
        return indices;
    }

    /** The streamed rebased indices multi-draws of sections with their own indices read; only where {@link #multiDraw()}. */
    RebasedIndices rebasedIndices() {
        RebasedIndices indices = rebasedIndices;
        if (indices == null) {
            throw new IllegalStateException("No WEBGL_multi_draw");
        }
        return indices;
    }

    GpuTimer gpuTimer() {
        return gpuTimer;
    }

    /** Before a draw's call: times it, under {@code ?gpucensus}. */
    void drawStarting() {
        if (timeDraws) {
            gpuCensus.begin();
        }
    }

    /** After a draw's call by {@code pipeline}. */
    void drawIssued(WebGl2Pipeline pipeline) {
        if (timeDraws) {
            gpuCensus.end(pipeline.name());
        }
    }

    /** Counts a draw call; {@code sections} is how many terrain sections it drew, if it drew terrain. */
    void countDraw(WebGl2Pipeline pipeline, int sections) {
        draws++;
        frameDraws++;
        if (census) {
            censusDraws.merge(pipeline.name(), 1, Integer::sum);
        }
        if (pipeline.terrain()) {
            frameTerrainDraws++;
            frameTerrainSections += sections;
        }
    }

    /** Called once a frame, after the blit to the canvas: the frame's counts become the last frame's. */
    void frameEnded() {
        gpuTimer.frameEnded();
        if (timeDraws) {
            gpuCensus.frameEnded();
        }
        if (census && ++censusFrames == CENSUS_FRAMES) {
            logCensus();
        }
        lastDraws = frameDraws;
        lastTerrainDraws = frameTerrainDraws;
        lastTerrainSections = frameTerrainSections;
        frameDraws = 0;
        frameTerrainDraws = 0;
        frameTerrainSections = 0;
    }

    @Override
    public double gpuMillis() {
        return gpuTimer.lastMillis();
    }

    @Override
    public int drawCalls() {
        return lastDraws;
    }

    @Override
    public int terrainDrawCalls() {
        return lastTerrainDraws;
    }

    @Override
    public int terrainSections() {
        return lastTerrainSections;
    }

    /** Binds a texture to change it, on a unit of its own so the draw bindings stay as they are. */
    void bindTextureForUpdate(int target, WebGLTexture texture) {
        gl.activeTexture(TEXTURE0 + updateUnit);
        gl.bindTexture(target, texture);
    }

    /** Called after something outside the state caches changed WebGL's bindings. */
    void invalidateBindings() {
        // The surface blit only touches framebuffers and the scissor, which every pass sets anew.
    }

    @Override
    public GpuSurfaceBackend createSurface(long windowHandle, BooleanSupplier isIconified) {
        return new WebGl2Surface(this);
    }

    @Override
    public CommandEncoderBackend createCommandEncoder() {
        return new WebGl2CommandEncoder(this);
    }

    @Override
    public GpuSampler createSampler(
            AddressMode addressModeU,
            AddressMode addressModeV,
            FilterMode minFilter,
            FilterMode magFilter,
            int maxAnisotropy,
            OptionalDouble maxLod) {
        return new WebGl2Sampler(this, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    @Override
    public GpuTexture createTexture(
            @Nullable String label,
            @GpuTexture.Usage int usage,
            GpuFormat format,
            int width,
            int height,
            int depthOrLayers,
            int mipLevels) {
        GlFormats.TextureFormat glFormat = GlFormats.texture(format);
        if (glFormat == null) {
            throw new UnsupportedOperationException("WebGL2 has no " + format + " textures (" + label + ")");
        }
        if (!floatBlend && (usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
            // Blending into 32-bit float targets needs EXT_float_blend, which some mobile GPUs
            // lack; the game blends into one (the transparency passes' depth bounds, MAX), so it
            // gets 16-bit floats there instead, like the passes' other targets.
            glFormat = GlFormats.withoutFloatBlending(glFormat);
        }
        return new WebGl2Texture(
                this, usage, label == null ? "" : label, format, glFormat, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        return new WebGl2TextureView((WebGl2Texture) texture, baseMipLevel, mipLevels);
    }

    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, long size) {
        return new WebGl2Buffer(this, usage, size);
    }

    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, ByteBuffer data) {
        WebGl2Buffer buffer = new WebGl2Buffer(this, usage, data.remaining());
        buffer.write(0, data);
        return buffer;
    }

    @Override
    public List<String> getLastDebugMessages() {
        return List.of();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return false;
    }

    /** Never called: the frontend hands pipelines to {@link #compilePipeline(RenderPipeline, ShaderSource, Executor)}. */
    @Override
    public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo pipelineCreateInfo) {
        throw new UnsupportedOperationException("WebGL2 pipelines are compiled from the build's translated shaders");
    }

    @Override
    public CompletableFuture<CompiledRenderPipeline.Pending> compilePipeline(
            RenderPipeline pipeline, ShaderSource shaderSource, Executor executor) {
        return pipelines.compile(pipeline, shaderSource);
    }

    @Override
    public void close() {}

    @Override
    public GpuQueryPool createTimestampQueryPool(int size) {
        return new GpuQueryPool() {
            @Override
            public int size() {
                return size;
            }

            @Override
            public OptionalLong getValue(int index) {
                return OptionalLong.empty();
            }

            @Override
            public OptionalLong[] getValues(int index, int count) {
                OptionalLong[] values = new OptionalLong[count];
                java.util.Arrays.fill(values, OptionalLong.empty());
                return values;
            }

            @Override
            public void close() {}
        };
    }

    @Override
    public long getTimestampCalibrationOffset() {
        return 0;
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return info;
    }
}
