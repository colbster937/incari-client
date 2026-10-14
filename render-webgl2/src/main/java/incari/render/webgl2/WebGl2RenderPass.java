package incari.render.webgl2;

import static org.teavm.jso.webgl.WebGL2RenderingContext.ARRAY_BUFFER;
import static org.teavm.jso.webgl.WebGL2RenderingContext.ELEMENT_ARRAY_BUFFER;
import static org.teavm.jso.webgl.WebGL2RenderingContext.TEXTURE0;
import static org.teavm.jso.webgl.WebGL2RenderingContext.TEXTURE_2D;
import static org.teavm.jso.webgl.WebGL2RenderingContext.UNIFORM_BUFFER;
import static org.teavm.jso.webgl.WebGL2RenderingContext.UNSIGNED_INT;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.teavm.jso.webgl.WebGL2RenderingContext;

/**
 * Draws in one render pass. State is applied when a draw needs it, and only what changed: the
 * frontend tells this pass about every uniform and buffer as the game sets them, often more than
 * once between draws.
 *
 * <p>WebGL2 can't offset the vertex index of an indexed draw ({@code baseVertex}) or the instance
 * of an instanced one ({@code firstInstance}), so the vertex attributes are pointed that many
 * vertices, or instances, further into their buffers instead. Indirect draws, which only terrain
 * makes, are read from the game's copy of their commands and multi-drawn
 * ({@link #drawIndexedIndirect}).
 */
final class WebGl2RenderPass implements RenderPassBackend {

    private static final int MAX_UNIFORMS = 32;

    /** An indirect command: index count, instance count, first index, base vertex, first instance. */
    private static final int COMMAND_BYTES = 20;

    /** A row of zeros, for a draw-row pipeline drawn without its instance data bound. */
    private static final ByteBuffer ZERO_ROW = ByteBuffer.allocate(DrawIdRewrite.ROW_BYTES);

    private final WebGl2Device device;
    private final RenderPass.RenderArea area;
    private @Nullable WebGl2Pipeline pipeline;
    private final @Nullable GpuBufferSlice[] vertexBuffers = new GpuBufferSlice[RenderPass.MAX_VERTEX_BUFFERS];
    private @Nullable WebGl2Buffer indexBuffer;
    private int indexType;
    private int indexSize;
    private final @Nullable Object[] uniforms = new Object[MAX_UNIFORMS];
    private boolean uniformsDirty = true;
    private boolean attributesDirty = true;
    private int attributesBaseVertex;
    private int attributesBaseInstance;
    private boolean warned;

    WebGl2RenderPass(WebGl2Device device, RenderPass.RenderArea area) {
        this.device = device;
        this.area = area;
    }

    @Override
    public void pushDebugGroup(Supplier<String> label) {}

    @Override
    public void popDebugGroup() {}

    @Override
    public void setPipeline(BackendRenderPipeline next) {
        WebGl2Pipeline glPipeline = (WebGl2Pipeline) next;
        if (glPipeline != pipeline) {
            pipeline = glPipeline;
            uniformsDirty = true;
            attributesDirty = true;
        }
    }

    @Override
    public void setUniform(int index, @Nullable Object value) {
        if (index >= MAX_UNIFORMS) {
            throw new IllegalArgumentException("Pipeline uniform " + index + " is beyond WebGL2's " + MAX_UNIFORMS);
        }
        uniforms[index] = value;
        uniformsDirty = true;
    }

    @Override
    public void pushConstants(ByteBuffer value) {
        // No pipeline WebGL2 can run has push constants: the shader tool marks those unsupported.
    }

    @Override
    public void enableScissor(int x, int y, int width, int height) {
        device.gl().scissor(x, y, width, height);
    }

    @Override
    public void disableScissor() {
        device.gl().scissor(area.x(), area.y(), area.width(), area.height());
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // the same buffer object, not an equal one
    public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {
        GpuBufferSlice previous = vertexBuffers[slot];
        vertexBuffers[slot] = vertexBuffer;
        // The game sets the frame's staged vertex buffer again for every draw; the attributes only
        // need pointing again when the buffer or the slice's start changes (ADR 0027).
        if (previous == null
                || vertexBuffer == null
                || previous.buffer() != vertexBuffer.buffer()
                || previous.offset() != vertexBuffer.offset()) {
            attributesDirty = true;
        }
    }

    @Override
    public void setIndexBuffer(GpuBuffer buffer, IndexType type) {
        indexBuffer = (WebGl2Buffer) buffer;
        indexType = GlFormats.indexType(type);
        indexSize = type.bytes;
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        WebGl2Buffer indices = indexBuffer;
        WebGl2Pipeline current = currentPipeline();
        // A draw-row pipeline reads no per-instance attributes: its first instance picks a row.
        boolean rows = current.drawRows() != null;
        if (indices == null || instanceCount == 0) {
            return;
        }
        if (!rows && device.rebasesQuadDraws() && indices.quadSequenceOf(indexSize)) {
            drawQuads(current, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
            return;
        }
        if (!prepare(vertexOffset, rows ? 0 : firstInstance)) {
            return;
        }
        if (rows && !singleRow(current, firstInstance, instanceCount)) {
            return;
        }
        WebGL2RenderingContext gl = device.gl();
        gl.bindBuffer(ELEMENT_ARRAY_BUFFER, indices.handle());
        int mode = current.drawMode();
        device.drawStarting();
        if (instanceCount == 1) {
            gl.drawElements(mode, indexCount, indexType, firstIndex * indexSize);
        } else {
            gl.drawElementsInstanced(mode, indexCount, indexType, firstIndex * indexSize, instanceCount);
        }
        device.drawIssued(current);
        device.countDraw(current, 1);
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        WebGl2Pipeline current = currentPipeline();
        boolean rows = current.drawRows() != null;
        if (instanceCount == 0 || !prepare(0, rows ? 0 : firstInstance)) {
            return;
        }
        if (rows && !singleRow(current, firstInstance, instanceCount)) {
            return;
        }
        int mode = current.drawMode();
        device.drawStarting();
        if (instanceCount == 1) {
            device.gl().drawArrays(mode, firstVertex, vertexCount);
        } else {
            device.gl().drawArraysInstanced(mode, firstVertex, vertexCount, instanceCount);
        }
        device.drawIssued(current);
        device.countDraw(current, 1);
    }

    /**
     * Draws from the game's sequential quad indices with the backend's own ({@link QuadIndices}),
     * read from quad {@code vertexOffset / 4} on: the indices there already count from that
     * vertex, so the vertex attributes stay put (ADR 0027). Every staged draw (the GUI, entities,
     * text, particles) comes from one shared vertex buffer at its own base vertex, and re-pointing
     * the attributes costs a buffer bind and a call per attribute. Only a base vertex that isn't a
     * multiple of four moves them, by the remainder.
     */
    private void drawQuads(
            WebGl2Pipeline current,
            int indexCount,
            int instanceCount,
            int firstIndex,
            int vertexOffset,
            int firstInstance) {
        int remainder = vertexOffset & 3;
        long first = firstIndex + (long) (vertexOffset - remainder) / 4 * 6;
        if (!prepare(remainder, firstInstance)) {
            return;
        }
        device.quadIndices().bind(first + indexCount);
        WebGL2RenderingContext gl = device.gl();
        int offset = Math.toIntExact(first * 4);
        device.drawStarting();
        if (instanceCount == 1) {
            gl.drawElements(current.drawMode(), indexCount, UNSIGNED_INT, offset);
        } else {
            gl.drawElementsInstanced(current.drawMode(), indexCount, UNSIGNED_INT, offset, instanceCount);
        }
        device.drawIssued(current);
        device.countDraw(current, 1);
    }

    /**
     * An indirect draw, as the game's terrain makes them (ADR 0022): {@code drawCount}
     * commands of five ints (index count, instance count, first index, base vertex, first
     * instance), read from the game's copy since WebGL2 can't read them from a buffer. Where the
     * pipeline reads its instance data as draw rows, they're multi-drawn: sequential quads with the
     * backend's own ({@link #multiDrawQuads}), own indices rebased ({@link #multiDrawRebased}).
     * Otherwise each is drawn alone.
     */
    @Override
    public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
        WebGl2Buffer indices = indexBuffer;
        if (indices == null) {
            return;
        }
        ByteBuffer memory = ((WebGl2Buffer) commands.buffer()).memory();
        int base = Math.toIntExact(commands.offset());
        WebGl2Pipeline current = currentPipeline();
        if (current.drawRows() != null && indices.quadSequenceOf(indexSize)) {
            multiDrawQuads(current, memory, base, drawCount);
            return;
        }
        if (current.drawRows() != null) {
            multiDrawRebased(current, indices, memory, base, drawCount);
            return;
        }
        for (int i = 0; i < drawCount; i++) {
            int command = base + i * COMMAND_BYTES;
            drawIndexed(
                    memory.getInt(command),
                    memory.getInt(command + 4),
                    memory.getInt(command + 8),
                    memory.getInt(command + 12),
                    memory.getInt(command + 16));
        }
    }

    /**
     * Draws indirect commands over the game's sequential quad indices with the backend's own
     * ({@link QuadIndices}), whose indices already count from each command's base vertex, so the
     * commands differ only in where their indices start and can share a multi-draw. A base vertex
     * that isn't a multiple of four can't be reached that way; those commands are drawn by a
     * multi-draw of their own, with the vertex attributes moved by the remainder. So a heap's
     * commands take at most four calls (more past {@link DrawIdRewrite#ROWS} sub-draws).
     */
    private void multiDrawQuads(WebGl2Pipeline current, ByteBuffer memory, int base, int drawCount) {
        DrawRows rows = device.drawRows();
        for (int remainder = 0; remainder < 4; remainder++) {
            rows.clear();
            long needed = 0;
            for (int i = 0; i < drawCount; i++) {
                int command = base + i * COMMAND_BYTES;
                int baseVertex = memory.getInt(command + 12);
                int instances = memory.getInt(command + 4);
                if ((baseVertex & 3) != remainder || instances == 0) {
                    continue;
                }
                int indexCount = memory.getInt(command);
                long first = memory.getInt(command + 8) + (long) (baseVertex - remainder) / 4 * 6;
                if (instances != 1 || first * 4 > Integer.MAX_VALUE) {
                    warnOnce("an indirect draw of " + instances + " instances from index " + first);
                    continue;
                }
                addRow(current, rows, memory.getInt(command + 16), indexCount, (int) first * 4);
                needed = Math.max(needed, first + indexCount);
                if (rows.full()) {
                    flushQuads(current, rows, remainder, needed);
                    rows.clear();
                    needed = 0;
                }
            }
            if (rows.count() > 0) {
                flushQuads(current, rows, remainder, needed);
            }
        }
    }

    /**
     * Draws indirect commands whose indices are their own (translucent sections, sorted back to
     * front) with the indices copied, each plus its command's base vertex, and kept until the game
     * sorts that section again ({@link RebasedIndices}), so they share multi-draws, in the
     * commands' order.
     */
    private void multiDrawRebased(
            WebGl2Pipeline current, WebGl2Buffer indices, ByteBuffer memory, int base, int drawCount) {
        DrawRows rows = device.drawRows();
        RebasedIndices rebased = device.rebasedIndices();
        long total = 0;
        for (int i = 0; i < drawCount; i++) {
            total += memory.getInt(base + i * COMMAND_BYTES);
        }
        // Room for every command's copy: a batch's offsets must all stay in the same arena.
        rebased.begin(total);
        rows.clear();
        for (int i = 0; i < drawCount; i++) {
            int command = base + i * COMMAND_BYTES;
            int instances = memory.getInt(command + 4);
            if (instances == 0) {
                continue;
            }
            if (instances != 1) {
                warnOnce("an indirect draw of " + instances + " instances");
                continue;
            }
            int indexCount = memory.getInt(command);
            int offset = rebased.offsetOf(
                    indices, indexSize, memory.getInt(command + 8), indexCount, memory.getInt(command + 12));
            addRow(current, rows, memory.getInt(command + 16), indexCount, offset);
            if (rows.full()) {
                flushRebased(current, rows, rebased);
                rows.clear();
            }
        }
        if (rows.count() > 0) {
            flushRebased(current, rows, rebased);
        }
    }

    private void flushRebased(WebGl2Pipeline current, DrawRows rows, RebasedIndices rebased) {
        if (!prepare(0, 0)) {
            return;
        }
        rebased.upload();
        rows.upload();
        device.drawStarting();
        rows.multiDraw(device.multiDrawExtension(), current.drawMode());
        device.drawIssued(current);
        device.countDraw(current, rows.count());
    }

    private void flushQuads(WebGl2Pipeline current, DrawRows rows, int baseVertex, long indices) {
        if (!prepare(baseVertex, 0)) {
            return;
        }
        device.quadIndices().bind(indices);
        rows.upload();
        device.drawStarting();
        rows.multiDraw(device.multiDrawExtension(), current.drawMode());
        device.drawIssued(current);
        device.countDraw(current, rows.count());
    }

    /**
     * Binds one draw row, for a single draw with a draw-row pipeline; false (and the draw skipped)
     * for several instances, which one row can't describe.
     */
    private boolean singleRow(WebGl2Pipeline current, int firstInstance, int instanceCount) {
        if (instanceCount != 1) {
            warnOnce("a draw of " + instanceCount + " instances");
            return false;
        }
        DrawRows rows = device.drawRows();
        rows.clear();
        addRow(current, rows, firstInstance, 0, 0);
        rows.upload();
        return true;
    }

    /** Adds the row of instance {@code instance} of the draw-row slot's vertex buffer. */
    private void addRow(WebGl2Pipeline current, DrawRows rows, int instance, int indexCount, int indexByteOffset) {
        VertexLayout.DrawRowLayout layout = current.drawRows();
        GpuBufferSlice slice = layout == null ? null : vertexBuffers[layout.slot()];
        if (layout == null || slice == null) {
            rows.add(ZERO_ROW, 0, 0, indexCount, indexByteOffset);
            return;
        }
        int offset = Math.toIntExact(slice.offset() + (long) instance * layout.stride());
        rows.add(((WebGl2Buffer) slice.buffer()).memory(), offset, layout.stride(), indexCount, indexByteOffset);
    }

    private void warnOnce(String what) {
        if (!warned) {
            warned = true;
            WebGl.warn("[Incari] Skipping draws of " + currentPipeline().name() + ": " + what
                    + ", but its instance data is read per draw");
        }
    }

    // The device reports no direct multi-draws, so the frontend never calls these.

    @Override
    public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        throw new UnsupportedOperationException("WebGL2 has no multi-draw");
    }

    @Override
    public void multiDrawIndexed(
            PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
        throw new UnsupportedOperationException("WebGL2 has no multi-draw");
    }

    @Override
    public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        throw new UnsupportedOperationException("WebGL2 has no multi-draw");
    }

    @Override
    public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
        throw new UnsupportedOperationException("WebGL2 has no multi-draw");
    }

    @Override
    public void drawIndirect(GpuBufferSlice commands, int drawCount) {
        // Only terrain draws indirectly, and always with indices.
        throw new UnsupportedOperationException("WebGL2 has no indirect draws without indices");
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {}

    private WebGl2Pipeline currentPipeline() {
        WebGl2Pipeline current = pipeline;
        if (current == null) {
            throw new IllegalStateException("No pipeline bound");
        }
        return current;
    }

    /**
     * Applies what the next draw needs, with per-vertex attributes from vertex {@code baseVertex}
     * and per-instance ones from instance {@code baseInstance}; false if it can't be drawn (the
     * pipeline can't run here).
     */
    private boolean prepare(int baseVertex, int baseInstance) {
        // Uniforms written since the last draw reach WebGL now, in one upload per buffer (ADR 0027).
        device.pendingUploads().flush();
        WebGl2Pipeline current = currentPipeline();
        if (!current.use()) {
            device.skippedDraws++;
            return false;
        }
        if (uniformsDirty) {
            bindUniforms(current);
            uniformsDirty = false;
        }
        if (attributesDirty || baseVertex != attributesBaseVertex || baseInstance != attributesBaseInstance) {
            bindAttributes(current, baseVertex, baseInstance);
            attributesDirty = false;
            attributesBaseVertex = baseVertex;
            attributesBaseInstance = baseInstance;
        }
        return true;
    }

    private void bindUniforms(WebGl2Pipeline current) {
        WebGL2RenderingContext gl = device.gl();
        List<BindGroupLayout.UniformDescription> descriptions = current.uniforms();
        for (int i = 0; i < descriptions.size() && i < MAX_UNIFORMS; i++) {
            Object value = uniforms[i];
            if (value == null) {
                continue;
            }
            UniformType type = descriptions.get(i).type();
            if (type == UniformType.UNIFORM_BUFFER && value instanceof GpuBufferSlice slice) {
                WebGl2Buffer buffer = (WebGl2Buffer) slice.buffer();
                // Bind at least what the program says the block takes, where the buffer has it: the
                // bytes past the game's slice are never read, but WebGL checks the range's size.
                long length =
                        Math.max(slice.length(), Math.min(current.blockSize(i), buffer.glSize() - slice.offset()));
                if (device.bindings().uniformBuffer(i, buffer, slice.offset(), length)) {
                    gl.bindBufferRange(
                            UNIFORM_BUFFER,
                            i,
                            buffer.handle(),
                            Math.toIntExact(slice.offset()),
                            Math.toIntExact(length));
                }
            } else if (type == UniformType.TEXEL_BUFFER && value instanceof GpuBufferSlice slice) {
                // The frontend only takes whole buffers as texel buffers.
                GpuFormat format = descriptions.get(i).gpuFormat();
                if (format == null) {
                    continue;
                }
                TexelBufferTexture texture = ((WebGl2Buffer) slice.buffer()).texelTexture(format);
                if (device.bindings().texelBuffer(i, texture)) {
                    gl.activeTexture(TEXTURE0 + i);
                    gl.bindTexture(TEXTURE_2D, texture.handle());
                    gl.bindSampler(i, null);
                }
            } else if (type == UniformType.COMBINED_IMAGE_SAMPLER && value instanceof TextureViewAndSampler pair) {
                WebGl2TextureView view = (WebGl2TextureView) pair.view();
                WebGl2Texture texture = view.glTexture();
                WebGl2Sampler sampler = (WebGl2Sampler) pair.sampler();
                boolean rebind = device.bindings().texture(i, texture, sampler);
                boolean levels = texture.needsLevels(view.baseMipLevel(), view.mipLevels());
                if (rebind || levels) {
                    gl.activeTexture(TEXTURE0 + i);
                }
                if (rebind) {
                    gl.bindTexture(texture.target(), texture.handle());
                    gl.bindSampler(i, sampler.handle());
                }
                if (levels) {
                    texture.restrictLevels(view.baseMipLevel(), view.mipLevels());
                }
            }
        }
    }

    private void bindAttributes(WebGl2Pipeline current, int baseVertex, int baseInstance) {
        WebGL2RenderingContext gl = device.gl();
        int enabled = 0;
        for (VertexLayout.Binding binding : current.attributes()) {
            GpuBufferSlice slice = vertexBuffers[binding.slot()];
            if (slice == null) {
                continue;
            }
            WebGl2Buffer buffer = (WebGl2Buffer) slice.buffer();
            gl.bindBuffer(ARRAY_BUFFER, buffer.handle());
            // Per-instance data moves with the first instance instead of the base vertex.
            long first = binding.divisor() == 0
                    ? (long) baseVertex * binding.stride()
                    : (long) baseInstance / binding.divisor() * binding.stride();
            int offset = Math.toIntExact(slice.offset() + binding.offset() + first);
            GlFormats.AttributeFormat format = binding.format();
            if (binding.integer()) {
                gl.vertexAttribIPointer(binding.location(), format.size(), format.type(), binding.stride(), offset);
            } else {
                gl.vertexAttribPointer(
                        binding.location(),
                        format.size(),
                        format.type(),
                        format.normalized(),
                        binding.stride(),
                        offset);
            }
            device.bindings().divisor(binding.location(), binding.divisor());
            enabled |= 1 << binding.location();
        }
        device.bindings().enableAttributes(enabled);
    }
}
