package incari.client.net;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * RGBA pixels as a PNG file's bytes, for the server list's icons (it keeps PNGs). The game writes
 * PNGs only to files; this writes the one kind it needs in memory: 8-bit RGBA, no filtering.
 */
final class PngBytes {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private PngBytes() {}

    /** {@code rgba} is {@code width * height} pixels, four bytes each, rows top to bottom. */
    static byte[] encode(int width, int height, byte[] rgba) {
        byte[] raw = new byte[height * (width * 4 + 1)];
        for (int y = 0; y < height; y++) {
            // Each row starts with its filter type: 0, none.
            System.arraycopy(rgba, y * width * 4, raw, y * (width * 4 + 1) + 1, width * 4);
        }
        Deflater deflater = new Deflater();
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            compressed.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(SIGNATURE, 0, SIGNATURE.length);
        byte[] header = new byte[13];
        putInt(header, 0, width);
        putInt(header, 4, height);
        header[8] = 8; // bits per channel
        header[9] = 6; // RGBA
        chunk(png, "IHDR", header);
        chunk(png, "IDAT", compressed.toByteArray());
        chunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] length = new byte[4];
        putInt(length, 0, data.length);
        out.write(length, 0, 4);
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        out.write(name, 0, 4);
        out.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        byte[] checksum = new byte[4];
        putInt(checksum, 0, (int) crc.getValue());
        out.write(checksum, 0, 4);
    }

    private static void putInt(byte[] into, int offset, int value) {
        into[offset] = (byte) (value >>> 24);
        into[offset + 1] = (byte) (value >>> 16);
        into[offset + 2] = (byte) (value >>> 8);
        into[offset + 3] = (byte) value;
    }
}
