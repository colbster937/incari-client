package incari.client.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Particles drawn instanced (ADR 0032). The game keeps each frame's particles as twelve floats and
 * two ints a particle (position, rotation, scale, the sprite's corners; color, light), then writes
 * four vertices a particle from them, each through the vertex builder's checks. Here the floats
 * and ints go to the GPU as they are, one instance a particle, and an instanced copy of the
 * particle pipeline works the quad's corners out in its vertex stage, as the game works them out
 * ({@code incari.render.webgl2.ParticleInstanceRewrite}). A layer's draw is the game's own draw, at
 * the same place among the group's: the order of translucent particles is the game's.
 *
 * <p>A layer is drawn this way only if every pipeline it may draw with (its own, and its
 * order-independent transparency stages') has an instanced copy the backend compiled; otherwise
 * the game builds its vertices, as before. The positions come from the GPU's arithmetic instead of
 * the CPU's, so they can differ in the last bits.
 *
 * <p>Only the page turns it on ({@code ?particlequads=game} to compare): the desktop's backends don't
 * know the rewrite. Render thread only.
 */
public final class ParticleInstancing {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParticleInstancing.class);

    /** Keep in step with the WebGL2 backend's {@code ParticleInstanceRewrite.FLAG}. */
    static final String FLAG = "INCARI_PARTICLES_INSTANCED";

    /** The game's twelve floats a particle, as the rewritten shader reads them. */
    static final VertexFormat FLOATS = VertexFormat.builder(1)
            .addAttribute("IncariCenter", GpuFormat.RGB32_FLOAT)
            .addAttribute("IncariRotation", GpuFormat.RGBA32_FLOAT)
            .addAttribute("IncariScale", GpuFormat.R32_FLOAT)
            .addAttribute("IncariUvs", GpuFormat.RGBA32_FLOAT)
            .build();

    /** And its two ints: the color as the game's ARGB int, the light as its two shorts. */
    static final VertexFormat INTS = VertexFormat.builder(1)
            .addAttribute("IncariColor", GpuFormat.RGBA8_UNORM)
            .addAttribute("UV2", GpuFormat.RG16_SINT)
            .build();

    static final int FLOATS_PER_PARTICLE = 12;
    static final int INTS_PER_PARTICLE = 2;

    private static final Identifier PARTICLE_SHADER = Identifier.withDefaultNamespace("core/particle");

    private static boolean enabled;
    private static boolean announced;

    private static final IdentityHashMap<RenderPipeline, @Nullable RenderPipeline> COPIES = new IdentityHashMap<>();

    /** A draw's particles: the game's floats and ints, copied out as the layers are prepared. */
    private static final class Batch {
        float[] floats = new float[1024 * FLOATS_PER_PARTICLE];
        int[] ints = new int[1024 * INTS_PER_PARTICLE];
        int count;
        long floatsOffset;
        long intsOffset;

        void add(float[] fromFloats, int[] fromInts, int particles) {
            int need = count + particles;
            if (need * FLOATS_PER_PARTICLE > floats.length) {
                int capacity = Integer.highestOneBit(need - 1) << 1;
                floats = Arrays.copyOf(floats, capacity * FLOATS_PER_PARTICLE);
                ints = Arrays.copyOf(ints, capacity * INTS_PER_PARTICLE);
            }
            System.arraycopy(fromFloats, 0, floats, count * FLOATS_PER_PARTICLE, particles * FLOATS_PER_PARTICLE);
            System.arraycopy(fromInts, 0, ints, count * INTS_PER_PARTICLE, particles * INTS_PER_PARTICLE);
            count = need;
        }
    }

    /** What a layer's particles are handed to: the game's arrays and how many particles they hold. */
    @FunctionalInterface
    public interface Sink {
        void accept(float[] floats, int[] ints, int count);
    }

    private static final IdentityHashMap<StagedVertexBuffer.Draw, Batch> BATCHES = new IdentityHashMap<>();
    private static final List<Batch> POOL = new ArrayList<>();
    private static int used;

    private static @Nullable ByteBuffer floatData;
    private static @Nullable ByteBuffer intData;
    private static @Nullable GpuBuffer floatBuffer;
    private static @Nullable GpuBuffer intBuffer;

    private ParticleInstancing() {}

    /** Draws particles instanced from now on. The page calls it before the game starts. */
    public static void enable() {
        enabled = true;
    }

    /**
     * Hook ({@code QuadParticleFeatureRenderer.prepareGroup}): takes a layer's particles for the
     * game's {@code draw}, instead of the game building their vertices into it.
     *
     * @return false if the game builds them
     */
    public static boolean take(
            QuadParticleRenderState particles, SingleQuadParticle.Layer layer, StagedVertexBuffer.Draw draw) {
        if (!enabled || !drawable(layer)) {
            return false;
        }
        Batch batch = BATCHES.get(draw);
        if (batch == null) {
            if (used == POOL.size()) {
                POOL.add(new Batch());
            }
            batch = POOL.get(used++);
            batch.count = 0;
            BATCHES.put(draw, batch);
        }
        particles.incariParticles(layer, batch::add);
        return true;
    }

    /** Hook ({@code finishPrepare}): uploads every draw's particles. */
    public static void upload() {
        if (used == 0) {
            return;
        }
        long floatBytes = 0;
        long intBytes = 0;
        for (int i = 0; i < used; i++) {
            Batch batch = POOL.get(i);
            batch.floatsOffset = floatBytes;
            floatBytes += (long) batch.count * FLOATS.getVertexSize();
            batch.intsOffset = intBytes;
            intBytes += (long) batch.count * INTS.getVertexSize();
        }
        if (floatBytes == 0) {
            return;
        }
        ByteBuffer floats = room(floatData, floatBytes);
        ByteBuffer ints = room(intData, intBytes);
        floatData = floats;
        intData = ints;
        for (int i = 0; i < used; i++) {
            Batch batch = POOL.get(i);
            floats.position((int) batch.floatsOffset);
            floats.asFloatBuffer().put(batch.floats, 0, batch.count * FLOATS_PER_PARTICLE);
            ints.position((int) batch.intsOffset);
            ints.asIntBuffer().put(batch.ints, 0, batch.count * INTS_PER_PARTICLE);
        }
        GpuBuffer floatTarget = fit(floatBuffer, floatBytes, "particle floats");
        GpuBuffer intTarget = fit(intBuffer, intBytes, "particle ints");
        floatBuffer = floatTarget;
        intBuffer = intTarget;
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.writeToBuffer(floatTarget.slice(0, floatBytes), floats.slice(0, (int) floatBytes));
        encoder.writeToBuffer(intTarget.slice(0, intBytes), ints.slice(0, (int) intBytes));
        RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(6);
    }

    /**
     * Hook ({@code QuadParticleFeatureRenderer.drawLayers}, for each of a group's layers): draws the
     * game's {@code draw} instanced if its particles were taken, with the instanced copy of the
     * {@code pipeline} the game would draw it with.
     *
     * @return false if the game draws it
     */
    public static boolean draw(
            StagedVertexBuffer.Draw draw, RenderPass renderPass, RenderPipeline pipeline, AbstractTexture texture) {
        Batch batch = BATCHES.get(draw);
        if (batch == null) {
            return false;
        }
        GpuBuffer floats = floatBuffer;
        GpuBuffer ints = intBuffer;
        RenderPipeline copy = copyOf(pipeline);
        if (batch.count == 0 || floats == null || ints == null || copy == null) {
            // Taken but nothing to draw (or, never expected, no copy: take checked them all).
            return true;
        }
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        renderPass.setPipeline(RenderSystem.getCompiledPipeline(copy));
        renderPass.setVertexBuffer(0, floats.slice(batch.floatsOffset, (long) batch.count * FLOATS.getVertexSize()));
        renderPass.setVertexBuffer(1, ints.slice(batch.intsOffset, (long) batch.count * INTS.getVertexSize()));
        renderPass.setIndexBuffer(indices.getBuffer(), indices.type());
        renderPass.setUniform("Sampler0", texture.getTextureView(), texture.getSampler());
        renderPass.drawIndexed(6, batch.count, 0, 0, 0);
        if (!announced) {
            announced = true;
            LOGGER.info("Particles are drawn instanced (first: {} with {})", batch.count, pipeline.getLocation());
        }
        return true;
    }

    /** Hook ({@code finishExecute}): the frame is drawn. */
    public static void endFrame() {
        BATCHES.clear();
        used = 0;
    }

    /** Whether every pipeline the layer may draw with has a compiled instanced copy. */
    private static boolean drawable(SingleQuadParticle.Layer layer) {
        if (!compiled(layer.pipeline())) {
            return false;
        }
        OitPipelineSet oit = layer.oitPipelineSet();
        if (oit != null) {
            for (OitStage stage : OitStage.values()) {
                if (!compiled(oit.getPipeline(stage))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean compiled(RenderPipeline pipeline) {
        RenderPipeline copy = copyOf(pipeline);
        return copy != null && RenderSystem.getCompiledPipelineNullable(copy) != null;
    }

    /** The instanced copy of a particle pipeline, or null if it isn't one (the particle shader, its vertex format, quads). */
    private static @Nullable RenderPipeline copyOf(RenderPipeline pipeline) {
        if (COPIES.containsKey(pipeline)) {
            return COPIES.get(pipeline);
        }
        RenderPipeline copy = eligible(pipeline) ? new Instanced(pipeline) : null;
        COPIES.put(pipeline, copy);
        if (copy != null && RenderSystem.getCompiledPipelineNullable(copy) == null) {
            LOGGER.warn(
                    "Particles drawn with {} go the game's way: the backend has no instanced pipeline for it",
                    pipeline.getLocation());
        }
        return copy;
    }

    private static boolean eligible(RenderPipeline pipeline) {
        List<@Nullable VertexFormat> formats = pipeline.getVertexFormatBindings();
        return PARTICLE_SHADER.equals(pipeline.getShaders().get(ShaderType.VERTEX))
                && !formats.isEmpty()
                && DefaultVertexFormat.PARTICLE.equals(formats.getFirst())
                && formats.stream().skip(1).allMatch(format -> format == null)
                && pipeline.getPrimitiveTopology() == PrimitiveTopology.QUADS
                && !pipeline.getShaderDefines().flags().contains(FLAG);
    }

    private static ByteBuffer room(@Nullable ByteBuffer buffer, long bytes) {
        if (buffer != null && buffer.capacity() >= bytes) {
            buffer.clear();
            return buffer;
        }
        int capacity = Integer.highestOneBit((int) Math.max(bytes, 1024) - 1) << 1;
        return ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
    }

    private static GpuBuffer fit(@Nullable GpuBuffer buffer, long bytes, String name) {
        if (buffer != null && buffer.size() >= bytes) {
            return buffer;
        }
        if (buffer != null) {
            buffer.close();
        }
        long size = Long.highestOneBit(Math.max(bytes, 4096) - 1) << 1;
        return RenderSystem.getDevice()
                .createBuffer(
                        () -> "Incari instanced " + name, GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, size);
    }

    /** A copy of a particle pipeline, instanced. */
    private static final class Instanced extends RenderPipeline {

        Instanced(RenderPipeline pipeline) {
            super(
                    Identifier.fromNamespaceAndPath(
                            "incari",
                            "instanced/" + pipeline.getLocation().getNamespace() + "/"
                                    + pipeline.getLocation().getPath()),
                    pipeline.getShaders(),
                    defines(pipeline.getShaderDefines()),
                    pipeline.getBindGroupLayouts(),
                    pipeline.getColorTargetStates().toArray(ColorTargetState[]::new),
                    pipeline.getDepthStencilState(),
                    pipeline.getPolygonMode(),
                    pipeline.isCull(),
                    new VertexFormat[] {FLOATS, INTS},
                    pipeline.getPrimitiveTopology(),
                    pipeline.pushConstantSize(),
                    pipeline.getSortKey());
        }

        private static ShaderDefines defines(ShaderDefines defines) {
            Set<String> flags = new HashSet<>(defines.flags());
            flags.add(FLAG);
            return new ShaderDefines(defines.values(), flags);
        }
    }
}
