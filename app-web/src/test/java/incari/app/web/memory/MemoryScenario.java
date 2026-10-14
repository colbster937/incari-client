package incari.app.web.memory;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * The game's own off-heap memory users, run through whatever {@code MemoryUtil} the class loader
 * binds: LWJGL's on the desktop, web-classlib's stand-in in the browser build or under
 * {@link SubstitutingClassLoader}. Vertices through {@code BufferBuilder}'s fast and generic paths,
 * sorted index buffers, {@code ByteBufferBuilder}'s growth and compaction, {@code NativeImage}
 * pixels and PNG decoding, a uniform block on the {@code MemoryStack}, and {@code MemoryUtil}
 * directly. Each result is the bytes the game would hand the GPU, by name.
 *
 * <p>Deterministic: its own generator, not {@code Random}. Bytes the game leaves unwritten (the
 * fourth byte of a packed normal) come from {@code malloc} and are garbage, so they're zeroed in
 * the copies returned. Also compiled into a browser probe, so it uses nothing TeaVM lacks.
 */
public final class MemoryScenario implements Supplier<Map<String, byte[]>> {

    private int seed = 0x1234567;

    @Override
    public Map<String, byte[]> get() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try {
            entityQuads(out);
            blockQuads(out);
            genericFormats(out);
            compaction(out);
            images(out);
            pngs(out);
            uniforms(out);
            memoryUtil(out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    /** The entity fast path, then the quads sorted back to front, as translucent geometry is. */
    private void entityQuads(Map<String, byte[]> out) {
        // Small, so the builder grows (reallocates) several times.
        try (ByteBufferBuilder vertices = new ByteBufferBuilder(64);
                ByteBufferBuilder indices = new ByteBufferBuilder(16)) {
            BufferBuilder builder = new BufferBuilder(vertices, PrimitiveTopology.QUADS, DefaultVertexFormat.ENTITY);
            for (int i = 0; i < 4 * 700; i++) {
                builder.addVertex(
                        coordinate(),
                        coordinate(),
                        coordinate(),
                        next(),
                        unit(),
                        unit(),
                        next() & 0xFFFF_FFFF,
                        next(),
                        signed(),
                        signed(),
                        signed());
            }
            try (MeshData mesh = builder.buildOrThrow()) {
                out.put("entity.vertices", vertexBytes(mesh.vertexBuffer(), DefaultVertexFormat.ENTITY));
                mesh.sortQuads(indices, VertexSorting.byDistance(3.0F, -2.0F, 5.0F));
                ByteBuffer sorted = mesh.indexBuffer();
                out.put("entity.indices", bytes(sorted == null ? ByteBuffer.allocate(0) : sorted));
            }
        }
    }

    private void blockQuads(Map<String, byte[]> out) {
        try (ByteBufferBuilder vertices = new ByteBufferBuilder(256)) {
            BufferBuilder builder = new BufferBuilder(vertices, PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK);
            for (int i = 0; i < 4 * 300; i++) {
                builder.addVertex(
                        coordinate(), coordinate(), coordinate(), next(), unit(), unit(), 0, next(), 0.0F, 1.0F, 0.0F);
            }
            try (MeshData mesh = builder.buildOrThrow()) {
                out.put("block.vertices", vertexBytes(mesh.vertexBuffer(), DefaultVertexFormat.BLOCK));
            }
        }
    }

    /** Element by element, the path most of the GUI and debug renderers take. */
    private void genericFormats(Map<String, byte[]> out) {
        try (ByteBufferBuilder vertices = new ByteBufferBuilder(32)) {
            BufferBuilder lines = new BufferBuilder(
                    vertices, PrimitiveTopology.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH);
            for (int i = 0; i < 4 * 50; i++) {
                lines.addVertex(coordinate(), coordinate(), coordinate())
                        .setColor(next())
                        .setNormal(signed(), signed(), signed())
                        .setLineWidth(unit() * 4.0F);
            }
            try (MeshData mesh = lines.buildOrThrow()) {
                out.put(
                        "lines.vertices",
                        vertexBytes(mesh.vertexBuffer(), DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH));
            }
            BufferBuilder gui = new BufferBuilder(
                    vertices, PrimitiveTopology.TRIANGLES, DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR);
            for (int i = 0; i < 3 * 90; i++) {
                gui.addVertex(coordinate(), coordinate(), 0.0F)
                        .setUv(unit(), unit())
                        .setLight(next())
                        .setColor(next() & 0xFF, next() & 0xFF, next() & 0xFF, next() & 0xFF);
            }
            try (MeshData mesh = gui.buildOrThrow()) {
                out.put(
                        "gui.vertices",
                        vertexBytes(mesh.vertexBuffer(), DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR));
            }
        }
    }

    /**
     * Two meshes built from one buffer while a third is being written: closing both moves the
     * unfinished one to the buffer's start with an overlapping {@code memCopy}.
     */
    private void compaction(Map<String, byte[]> out) {
        try (ByteBufferBuilder shared = new ByteBufferBuilder(128)) {
            MeshData first = quads(shared, 3);
            MeshData second = quads(shared, 5);
            out.put("compaction.second", vertexBytes(second.vertexBuffer(), DefaultVertexFormat.POSITION_COLOR));
            BufferBuilder third =
                    new BufferBuilder(shared, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (int i = 0; i < 4 * 7; i++) {
                third.addVertex(coordinate(), coordinate(), coordinate()).setColor(next());
            }
            first.close();
            second.close();
            try (MeshData mesh = third.buildOrThrow()) {
                out.put("compaction.third", vertexBytes(mesh.vertexBuffer(), DefaultVertexFormat.POSITION_COLOR));
            }
        }
    }

    private MeshData quads(ByteBufferBuilder buffer, int count) {
        BufferBuilder builder = new BufferBuilder(buffer, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < 4 * count; i++) {
            builder.addVertex(coordinate(), coordinate(), coordinate()).setColor(next());
        }
        return builder.buildOrThrow();
    }

    private void images(Map<String, byte[]> out) {
        try (NativeImage image = new NativeImage(37, 23, false);
                NativeImage zeroed = new NativeImage(NativeImage.Format.RGBA, 16, 16, true);
                NativeImage wide = new NativeImage(41, 23, true)) {
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    image.setPixel(x, y, next());
                }
            }
            image.fillRect(3, 4, 9, 5, 0x80FF4020);
            image.copyRect(0, 0, 20, 10, 12, 8, true, false);
            image.copyRect(zeroed, 5, 5, 2, 2, 10, 10, false, true);
            wide.copyFrom(image);
            out.put("image.pixels", bytes(image.getPixelBytes()));
            out.put("image.zeroed", bytes(zeroed.getPixelBytes()));
            out.put("image.wide", bytes(wide.getPixelBytes()));
            try (NativeImage mapped = image.mappedCopy(argb -> argb ^ 0x00FF00FF)) {
                out.put("image.mapped", bytes(mapped.getPixelBytes()));
                out.put("image.abgr", ints(mapped.getPixelsABGR()));
            }
            byte[] luminance = new byte[16];
            try (NativeImage alpha = new NativeImage(NativeImage.Format.LUMINANCE_ALPHA, 4, 4, true)) {
                for (int i = 0; i < luminance.length; i++) {
                    luminance[i] = alpha.getLuminanceOrAlpha(i % 4, i / 4);
                }
            }
            out.put("image.luminance", luminance);
        }
    }

    /** PNGs of every colour type and row filter, decoded by STB on the desktop and PngDecoder in the browser. */
    private void pngs(Map<String, byte[]> out) throws IOException {
        int[][] kinds = {{6, 4}, {2, 3}, {0, 1}, {4, 2}, {3, 1}};
        for (int[] kind : kinds) {
            byte[] png = png(29, 17, kind[0], kind[1]);
            // byte[] reads through the MemoryStack; an InputStream through memAlloc and memRealloc.
            try (NativeImage fromBytes = NativeImage.read(png);
                    NativeImage fromStream = NativeImage.read(new ByteArrayInputStream(png))) {
                out.put("png.type" + kind[0], bytes(fromBytes.getPixelBytes()));
                out.put("png.type" + kind[0] + ".stream", bytes(fromStream.getPixelBytes()));
            }
        }
        // Larger than the stack, so NativeImage.read(byte[]) takes memAlloc instead.
        byte[] big = png(300, 120, 6, 4);
        try (NativeImage image = NativeImage.read(big)) {
            out.put("png.big", bytes(image.getPixelBytes()));
        }
    }

    private void uniforms(Map<String, byte[]> out) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer block = Std140Builder.onStack(stack, 96)
                    .putVec4(unit(), unit(), unit(), unit())
                    .putIVec4(next(), next(), next(), next())
                    .putVec3(unit(), unit(), unit())
                    .putFloat(unit())
                    .putVec2(unit(), unit())
                    .putIVec2(next(), next())
                    .putInt(next())
                    .putFloat(unit())
                    .putVec2(unit(), unit())
                    .get();
            out.put("std140", bytes(block));
            java.nio.IntBuffer ints = stack.callocInt(5);
            ints.put(2, next());
            out.put("stack.ints", bytes(MemoryUtil.memByteBuffer(MemoryUtil.memAddress(ints), 20)));
        }
    }

    private void memoryUtil(Map<String, byte[]> out) {
        long address = MemoryUtil.nmemCalloc(8, 8);
        MemoryUtil.memPutInt(address, 0x11223344);
        MemoryUtil.memPutFloat(address + 4, 1.25F);
        MemoryUtil.memPutShort(address + 8, (short) -2);
        MemoryUtil.memPutByte(address + 10, (byte) 0x7F);
        // Overlapping, to a lower address, as ByteBufferBuilder compacts. (LWJGL's memCopy is
        // memcpy: an overlap the other way gives different bytes on different machines.)
        MemoryUtil.memCopy(address + 3, address + 1, 11);
        ByteBuffer text = MemoryUtil.memUTF8("héllo, wörld");
        long copy = MemoryUtil.nmemAlloc(text.remaining() + 1);
        MemoryUtil.memCopy(MemoryUtil.memAddress(text), copy, text.remaining() + 1);
        String back = MemoryUtil.memUTF8(copy);
        ByteBuffer grown = MemoryUtil.memRealloc(MemoryUtil.memAlloc(4).putInt(0, 42), 4096);
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        append(result, bytes(MemoryUtil.memByteBuffer(address, 64)));
        append(result, back.getBytes(StandardCharsets.UTF_8));
        result.write(MemoryUtil.memGetInt(address + 3));
        result.write(MemoryUtil.memGetByte(address + 13));
        result.write(grown.getInt(0));
        result.write(grown.capacity() >> 8);
        MemoryUtil.memFree(grown);
        MemoryUtil.nmemFree(copy);
        MemoryUtil.memFree(text);
        MemoryUtil.nmemFree(address);
        out.put("memoryutil", result.toByteArray());
    }

    // --- PNG files, written here so the check needs no game assets ------------------------------

    /**
     * A {@code width}x{@code height} 8-bit PNG of {@code colorType} (0 grey, 2 RGB, 3 palette, 4
     * grey and alpha, 6 RGBA), its rows filtered by each of the five filters in turn.
     */
    private byte[] png(int width, int height, int colorType, int channels) {
        byte[][] rows = new byte[height][width * channels];
        for (byte[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                // Some runs of equal bytes, so the filters have something to predict.
                row[i] = (byte) ((next() & 3) == 0 ? next() : i * 7);
            }
        }
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        byte[] previous = new byte[width * channels];
        for (int y = 0; y < height; y++) {
            int filter = y % 5;
            raw.write(filter);
            append(raw, filtered(rows[y], previous, channels, filter));
            previous = rows[y];
        }
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        append(file, new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        writeInt(header, width);
        writeInt(header, height);
        append(header, new byte[] {8, (byte) colorType, 0, 0, 0});
        chunk(file, "IHDR", header.toByteArray());
        if (colorType == 3) {
            byte[] palette = new byte[256 * 3];
            byte[] alpha = new byte[256];
            for (int i = 0; i < 256; i++) {
                palette[i * 3] = (byte) i;
                palette[i * 3 + 1] = (byte) (255 - i);
                palette[i * 3 + 2] = (byte) (i * 3);
                alpha[i] = (byte) (i * 5);
            }
            chunk(file, "PLTE", palette);
            chunk(file, "tRNS", alpha);
        }
        Deflater deflater = new Deflater();
        deflater.setInput(raw.toByteArray());
        deflater.finish();
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        while (!deflater.finished()) {
            packed.write(chunk, 0, deflater.deflate(chunk));
        }
        deflater.end();
        chunk(file, "IDAT", packed.toByteArray());
        chunk(file, "IEND", new byte[0]);
        return file.toByteArray();
    }

    private static byte[] filtered(byte[] row, byte[] above, int stride, int filter) {
        byte[] result = new byte[row.length];
        for (int i = 0; i < row.length; i++) {
            int a = i >= stride ? row[i - stride] & 0xFF : 0;
            int b = above[i] & 0xFF;
            int c = i >= stride ? above[i - stride] & 0xFF : 0;
            int prediction =
                    switch (filter) {
                        case 1 -> a;
                        case 2 -> b;
                        case 3 -> (a + b) / 2;
                        case 4 -> paeth(a, b, c);
                        default -> 0;
                    };
            result[i] = (byte) (row[i] - prediction);
        }
        return result;
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
    }

    private static void chunk(ByteArrayOutputStream file, String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        writeInt(file, data.length);
        append(file, name);
        append(file, data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        writeInt(file, (int) crc.getValue());
    }

    /** {@code out.writeBytes(bytes)}, which TeaVM's class library lacks. */
    private static void append(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    // --- Values and bytes ---------------------------------------------------------------------

    /** xorshift32: the same sequence on every platform. */
    private int next() {
        seed ^= seed << 13;
        seed ^= seed >>> 17;
        seed ^= seed << 5;
        return seed;
    }

    private float unit() {
        return (next() >>> 8) / (float) (1 << 24);
    }

    private float signed() {
        return unit() * 2.0F - 1.0F;
    }

    private float coordinate() {
        return (unit() - 0.5F) * 64.0F;
    }

    /**
     * A result's fingerprint, to compare a browser run with a desktop one by eye: 64-bit FNV-1a
     * (TeaVM has no {@code MessageDigest}).
     */
    public static String digest(byte[] bytes) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            hash = (hash ^ (b & 0xFF)) * 0x100000001b3L;
        }
        return Long.toHexString(hash) + "/" + bytes.length;
    }

    /** A buffer's remaining bytes, from its own position; the buffer doesn't move. */
    private static byte[] bytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
    }

    private static byte[] ints(int[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4);
        buffer.asIntBuffer().put(values);
        return buffer.array();
    }

    /** Vertex bytes with the padding byte after each packed normal cleared (malloc leaves it unset). */
    private static byte[] vertexBytes(ByteBuffer vertices, VertexFormat format) {
        byte[] bytes = bytes(vertices);
        VertexFormatElement normal = format.getElement("Normal");
        if (normal != null) {
            int stride = format.getVertexSize();
            for (int at = normal.offset() + 3; at < bytes.length; at += stride) {
                bytes[at] = 0;
            }
        }
        return bytes;
    }
}
