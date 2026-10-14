package incari.classlib.image;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decodes PNG images to 8-bit RGBA, for the browser build's stand-in of LWJGL's STB image
 * decoder. Every color type and bit depth, palette and {@code tRNS} transparency, and Adam7
 * interlacing; gamma and color-space chunks are ignored, as STB ignores them.
 */
public final class PngDecoder {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final int[][] ADAM7 = {
        {0, 0, 8, 8}, {4, 0, 8, 8}, {0, 4, 4, 8}, {2, 0, 4, 4}, {0, 2, 2, 4}, {1, 0, 2, 2}, {0, 1, 1, 2}
    };

    /** A decoded image: {@code width * height * 4} RGBA bytes, and how many channels the file had. */
    public static final class Image {
        private final int width;
        private final int height;
        private final int channels;
        private final byte[] rgba;

        Image(int width, int height, int channels, byte[] rgba) {
            this.width = width;
            this.height = height;
            this.channels = channels;
            this.rgba = rgba;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public int channels() {
            return channels;
        }

        /** The pixels, not copied. */
        public byte[] rgba() {
            return rgba;
        }
    }

    private final byte[] data;
    private int width;
    private int height;
    private int bitDepth;
    private int colorType;
    private boolean interlaced;
    private byte[] palette = new byte[0];
    private byte[] paletteAlpha = new byte[0];
    private int transparentGray = -1;
    private int transparentRed = -1;
    private int transparentGreen = -1;
    private int transparentBlue = -1;

    private PngDecoder(byte[] data) {
        this.data = data;
    }

    public static Image decode(byte[] data) throws IOException {
        return new PngDecoder(data).decode();
    }

    private Image decode() throws IOException {
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (data.length < SIGNATURE.length || data[i] != SIGNATURE[i]) {
                StringBuilder start = new StringBuilder();
                for (int j = 0; j < Math.min(8, data.length); j++) {
                    start.append(j == 0 ? "" : " ").append(Integer.toHexString(data[j] & 0xFF));
                }
                throw new IOException("Not a PNG file (" + data.length + " bytes, starting " + start + ")");
            }
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        int position = SIGNATURE.length;
        boolean header = false;
        while (true) {
            if (position + 8 > data.length) {
                throw new IOException("Truncated PNG file");
            }
            int length = readInt(position);
            String type = new String(data, position + 4, 4, StandardCharsets.US_ASCII);
            int start = position + 8;
            if (length < 0 || start + length + 4 > data.length) {
                throw new IOException("Truncated PNG chunk " + type);
            }
            switch (type) {
                case "IHDR" -> {
                    readHeader(start, length);
                    header = true;
                }
                case "PLTE" -> palette = copy(start, length);
                case "tRNS" -> readTransparency(start, length);
                case "IDAT" -> compressed.write(data, start, length);
                case "IEND" -> {
                    if (!header) {
                        throw new IOException("PNG file without a header");
                    }
                    return new Image(width, height, channelsInFile(), toRgba(inflate(compressed.toByteArray())));
                }
                default -> {
                    // Ancillary chunks (gamma, text, times) don't change the pixels STB returns.
                }
            }
            position = start + length + 4;
        }
    }

    private void readHeader(int start, int length) throws IOException {
        if (length < 13) {
            throw new IOException("Bad PNG header");
        }
        width = readInt(start);
        height = readInt(start + 4);
        bitDepth = data[start + 8] & 0xFF;
        colorType = data[start + 9] & 0xFF;
        interlaced = data[start + 12] == 1;
        if (width <= 0 || height <= 0 || (long) width * height > (1 << 28)) {
            throw new IOException("Unsupported PNG size " + width + "x" + height);
        }
        boolean valid =
                switch (colorType) {
                    case 0 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8 || bitDepth == 16;
                    case 3 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8;
                    case 2, 4, 6 -> bitDepth == 8 || bitDepth == 16;
                    default -> false;
                };
        if (!valid) {
            throw new IOException("Bad PNG color type " + colorType + " with bit depth " + bitDepth);
        }
    }

    private void readTransparency(int start, int length) {
        switch (colorType) {
            case 0 -> transparentGray = length >= 2 ? readShort(start) : -1;
            case 2 -> {
                if (length >= 6) {
                    transparentRed = readShort(start);
                    transparentGreen = readShort(start + 2);
                    transparentBlue = readShort(start + 4);
                }
            }
            case 3 -> paletteAlpha = copy(start, length);
            default -> {}
        }
    }

    private int channelsInFile() {
        boolean keyed = transparentGray >= 0 || transparentRed >= 0 || paletteAlpha.length > 0;
        return switch (colorType) {
            case 0 -> keyed ? 2 : 1;
            case 4 -> 2;
            case 2 -> keyed ? 4 : 3;
            case 3 -> keyed ? 4 : 3;
            default -> 4;
        };
    }

    private int samplesPerPixel() {
        return switch (colorType) {
            case 2 -> 3;
            case 4 -> 2;
            case 6 -> 4;
            default -> 1;
        };
    }

    private byte[] inflate(byte[] compressed) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 4);
            byte[] buffer = new byte[64 * 1024];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new IOException("Truncated PNG image data");
                }
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new IOException("Corrupt PNG image data", e);
        } finally {
            inflater.end();
        }
    }

    private byte[] toRgba(byte[] raw) throws IOException {
        byte[] rgba = new byte[width * height * 4];
        int offset = 0;
        if (!interlaced) {
            offset = pass(raw, offset, rgba, 0, 0, 1, 1);
        } else {
            for (int[] pass : ADAM7) {
                offset = pass(raw, offset, rgba, pass[0], pass[1], pass[2], pass[3]);
            }
        }
        return rgba;
    }

    /** Unfilters one pass of the image and writes its pixels; returns where the next pass starts. */
    private int pass(byte[] raw, int offset, byte[] rgba, int x0, int y0, int dx, int dy) throws IOException {
        int columns = (width - x0 + dx - 1) / dx;
        int rows = (height - y0 + dy - 1) / dy;
        if (columns <= 0 || rows <= 0) {
            return offset;
        }
        int bitsPerPixel = samplesPerPixel() * bitDepth;
        int stride = (columns * bitsPerPixel + 7) / 8;
        int bytesPerPixel = Math.max(1, bitsPerPixel / 8);
        byte[] previous = new byte[stride];
        byte[] current = new byte[stride];
        for (int row = 0; row < rows; row++) {
            if (offset + 1 + stride > raw.length) {
                throw new IOException("Truncated PNG image data");
            }
            int filter = raw[offset] & 0xFF;
            System.arraycopy(raw, offset + 1, current, 0, stride);
            offset += 1 + stride;
            unfilter(filter, current, previous, bytesPerPixel);
            int y = y0 + row * dy;
            for (int column = 0; column < columns; column++) {
                writePixel(current, column, rgba, ((y * width) + x0 + column * dx) * 4);
            }
            byte[] swap = previous;
            previous = current;
            current = swap;
        }
        return offset;
    }

    private static void unfilter(int filter, byte[] line, byte[] above, int bpp) throws IOException {
        switch (filter) {
            case 0 -> {}
            case 1 -> {
                for (int i = bpp; i < line.length; i++) {
                    line[i] += line[i - bpp];
                }
            }
            case 2 -> {
                for (int i = 0; i < line.length; i++) {
                    line[i] += above[i];
                }
            }
            case 3 -> {
                for (int i = 0; i < line.length; i++) {
                    int left = i >= bpp ? line[i - bpp] & 0xFF : 0;
                    line[i] += (byte) ((left + (above[i] & 0xFF)) >>> 1);
                }
            }
            case 4 -> {
                for (int i = 0; i < line.length; i++) {
                    int a = i >= bpp ? line[i - bpp] & 0xFF : 0;
                    int b = above[i] & 0xFF;
                    int c = i >= bpp ? above[i - bpp] & 0xFF : 0;
                    int p = a + b - c;
                    int pa = Math.abs(p - a);
                    int pb = Math.abs(p - b);
                    int pc = Math.abs(p - c);
                    line[i] += (byte) (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
                }
            }
            default -> throw new IOException("Bad PNG filter " + filter);
        }
    }

    private void writePixel(byte[] line, int column, byte[] rgba, int target) {
        switch (colorType) {
            case 0 -> {
                int gray = sample(line, column, 0, 1);
                int level = scale(gray);
                rgba[target] = (byte) level;
                rgba[target + 1] = (byte) level;
                rgba[target + 2] = (byte) level;
                rgba[target + 3] = (byte) (gray == transparentGray ? 0 : 255);
            }
            case 2 -> {
                int r = sample(line, column, 0, 3);
                int g = sample(line, column, 1, 3);
                int b = sample(line, column, 2, 3);
                rgba[target] = (byte) scale(r);
                rgba[target + 1] = (byte) scale(g);
                rgba[target + 2] = (byte) scale(b);
                boolean clear = r == transparentRed && g == transparentGreen && b == transparentBlue;
                rgba[target + 3] = (byte) (clear ? 0 : 255);
            }
            case 3 -> {
                int index = sample(line, column, 0, 1);
                if (index * 3 + 2 < palette.length) {
                    rgba[target] = palette[index * 3];
                    rgba[target + 1] = palette[index * 3 + 1];
                    rgba[target + 2] = palette[index * 3 + 2];
                }
                rgba[target + 3] = index < paletteAlpha.length ? paletteAlpha[index] : (byte) 255;
            }
            case 4 -> {
                int level = scale(sample(line, column, 0, 2));
                rgba[target] = (byte) level;
                rgba[target + 1] = (byte) level;
                rgba[target + 2] = (byte) level;
                rgba[target + 3] = (byte) scale(sample(line, column, 1, 2));
            }
            default -> {
                for (int channel = 0; channel < 4; channel++) {
                    rgba[target + channel] = (byte) scale(sample(line, column, channel, 4));
                }
            }
        }
    }

    /** One sample, at its bit depth (not yet scaled to 8 bits). */
    private int sample(byte[] line, int column, int channel, int samples) {
        int index = column * samples + channel;
        return switch (bitDepth) {
            case 16 -> ((line[index * 2] & 0xFF) << 8) | (line[index * 2 + 1] & 0xFF);
            case 8 -> line[index] & 0xFF;
            default -> {
                int bit = index * bitDepth;
                int shift = 8 - bitDepth - (bit & 7);
                yield (line[bit >> 3] >> shift) & ((1 << bitDepth) - 1);
            }
        };
    }

    /** A sample scaled to 8 bits; palette indices are never scaled. */
    private int scale(int sample) {
        return switch (bitDepth) {
            case 16 -> sample >>> 8;
            case 8 -> sample;
            default -> colorType == 3 ? sample : sample * 255 / ((1 << bitDepth) - 1);
        };
    }

    private int readInt(int at) {
        return (data[at] & 0xFF) << 24
                | (data[at + 1] & 0xFF) << 16
                | (data[at + 2] & 0xFF) << 8
                | (data[at + 3] & 0xFF);
    }

    private int readShort(int at) {
        return (data[at] & 0xFF) << 8 | (data[at + 1] & 0xFF);
    }

    private byte[] copy(int start, int length) {
        byte[] result = new byte[length];
        System.arraycopy(data, start, result, 0, length);
        return result;
    }
}
