package incari.classlib.substitutes.java.util.zip;

import java.util.zip.Checksum;

/** {@code java.util.zip.CRC32C} (the Castagnoli polynomial), which TeaVM lacks. */
public final class TCRC32C implements Checksum {

    // The reflected Castagnoli polynomial.
    private static final int POLYNOMIAL = 0x82F63B78;
    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int crc = i;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ POLYNOMIAL : crc >>> 1;
            }
            TABLE[i] = crc;
        }
    }

    private int crc = 0xFFFF_FFFF;

    public TCRC32C() {}

    @Override
    public void update(int b) {
        crc = (crc >>> 8) ^ TABLE[(crc ^ b) & 0xFF];
    }

    @Override
    public void update(byte[] bytes, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            crc = (crc >>> 8) ^ TABLE[(crc ^ bytes[i]) & 0xFF];
        }
    }

    @Override
    public long getValue() {
        return ~crc & 0xFFFF_FFFFL;
    }

    @Override
    public void reset() {
        crc = 0xFFFF_FFFF;
    }
}
