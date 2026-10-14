package incari.classlib.image;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Encodes 8-bit images as PNG, for the browser build's stand-in of LWJGL's STB image writer (the
 * game's screenshots). Written from the PNG specification: one IDAT chunk, and for each row the
 * filter whose output has the smallest sum of absolute values, the heuristic the specification
 * suggests.
 */
public final class PngEncoder {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private PngEncoder() {}

    /**
     * The PNG file of an image.
     *
     * @param channels 1 (gray), 2 (gray and alpha), 3 (RGB) or 4 (RGBA)
     * @param pixels {@code width * height * channels} bytes, rows top to bottom, no padding
     */
    public static byte[] encode(int width, int height, int channels, byte[] pixels) {
        if (width <= 0 || height <= 0 || channels < 1 || channels > 4) {
            throw new IllegalArgumentException(width + "x" + height + " with " + channels + " channels");
        }
        int stride = width * channels;
        if (pixels.length < (long) stride * height) {
            throw new IllegalArgumentException("Too few pixels for " + width + "x" + height);
        }
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(SIGNATURE, 0, SIGNATURE.length);

        byte[] header = new byte[13];
        putInt(header, 0, width);
        putInt(header, 4, height);
        header[8] = 8;
        header[9] = (byte) colorType(channels);
        chunk(file, "IHDR", header, header.length);

        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] chunkBuffer = new byte[64 * 1024];
        byte[][] candidates = new byte[5][stride + 1];
        try {
            for (int y = 0; y < height; y++) {
                int row = y * stride;
                byte[] best = filterRow(pixels, row, y == 0 ? -1 : row - stride, stride, channels, candidates);
                deflater.setInput(best, 0, stride + 1);
                drain(deflater, compressed, chunkBuffer);
            }
            deflater.finish();
            while (!deflater.finished()) {
                int count = deflater.deflate(chunkBuffer);
                compressed.write(chunkBuffer, 0, count);
            }
        } finally {
            deflater.end();
        }
        byte[] data = compressed.toByteArray();
        chunk(file, "IDAT", data, data.length);
        chunk(file, "IEND", new byte[0], 0);
        return file.toByteArray();
    }

    private static void drain(Deflater deflater, ByteArrayOutputStream out, byte[] buffer) {
        while (!deflater.needsInput()) {
            int count = deflater.deflate(buffer);
            if (count == 0) {
                break;
            }
            out.write(buffer, 0, count);
        }
    }

    /** The filtered row (filter type first) with the smallest sum of absolute values. */
    private static byte[] filterRow(byte[] pixels, int row, int above, int stride, int channels, byte[][] candidates) {
        byte[] best = candidates[0];
        long bestSum = Long.MAX_VALUE;
        for (int type = 0; type < 5; type++) {
            byte[] out = candidates[type];
            out[0] = (byte) type;
            long sum = 0;
            for (int i = 0; i < stride; i++) {
                int x = pixels[row + i] & 0xFF;
                int a = i >= channels ? pixels[row + i - channels] & 0xFF : 0;
                int b = above >= 0 ? pixels[above + i] & 0xFF : 0;
                int c = above >= 0 && i >= channels ? pixels[above + i - channels] & 0xFF : 0;
                int predicted =
                        switch (type) {
                            case 0 -> 0;
                            case 1 -> a;
                            case 2 -> b;
                            case 3 -> (a + b) >>> 1;
                            default -> paeth(a, b, c);
                        };
                byte filtered = (byte) (x - predicted);
                out[i + 1] = filtered;
                sum += Math.abs(filtered);
            }
            if (sum < bestSum) {
                bestSum = sum;
                best = out;
            }
        }
        return best;
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) {
            return a;
        }
        return pb <= pc ? b : c;
    }

    private static int colorType(int channels) {
        return switch (channels) {
            case 1 -> 0;
            case 2 -> 4;
            case 3 -> 2;
            default -> 6;
        };
    }

    private static void chunk(ByteArrayOutputStream file, String type, byte[] data, int length) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        byte[] number = new byte[4];
        putInt(number, 0, length);
        file.write(number, 0, number.length);
        file.write(name, 0, name.length);
        file.write(data, 0, length);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data, 0, length);
        putInt(number, 0, (int) crc.getValue());
        file.write(number, 0, number.length);
    }

    private static void putInt(byte[] bytes, int at, int value) {
        bytes[at] = (byte) (value >>> 24);
        bytes[at + 1] = (byte) (value >>> 16);
        bytes[at + 2] = (byte) (value >>> 8);
        bytes[at + 3] = (byte) value;
    }
}
