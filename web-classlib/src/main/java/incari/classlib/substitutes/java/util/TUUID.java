package incari.classlib.substitutes.java.util;

import java.io.Serial;
import java.io.Serializable;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.UUID}. Replaces TeaVM's, which lacks the two-long constructor, the bit accessors
 * and name-based UUIDs the game uses throughout (offline players' UUIDs are name-based).
 */
public final class TUUID implements Serializable, Comparable<TUUID> {

    @Serial
    private static final long serialVersionUID = -4856846361193249489L;

    private final long mostSigBits;
    private final long leastSigBits;

    public TUUID(long mostSigBits, long leastSigBits) {
        this.mostSigBits = mostSigBits;
        this.leastSigBits = leastSigBits;
    }

    /**
     * A version 4 UUID. The browser build has no {@code SecureRandom}; the page's random source is
     * enough for identifiers that only have to be unique.
     */
    public static TUUID randomUUID() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long most = (random.nextLong() & ~0xF000L) | 0x4000L;
        long least = (random.nextLong() & ~0xC000_0000_0000_0000L) | 0x8000_0000_0000_0000L;
        return new TUUID(most, least);
    }

    /** A version 3 (MD5, name-based) UUID. */
    public static TUUID nameUUIDFromBytes(byte[] name) {
        byte[] hash = Md5.digest(name);
        hash[6] = (byte) ((hash[6] & 0x0F) | 0x30);
        hash[8] = (byte) ((hash[8] & 0x3F) | 0x80);
        return new TUUID(toLong(hash, 0), toLong(hash, 8));
    }

    public static TUUID fromString(String name) {
        String[] parts = name.split("-", -1);
        if (parts.length != 5 || name.length() > 36) {
            throw new IllegalArgumentException("Invalid UUID string: " + name);
        }
        long[] values = new long[5];
        for (int i = 0; i < 5; i++) {
            if (parts[i].isEmpty()) {
                throw new IllegalArgumentException("Invalid UUID string: " + name);
            }
            values[i] = Long.parseLong(parts[i], 16);
        }
        long most = (values[0] << 32) | ((values[1] & 0xFFFF) << 16) | (values[2] & 0xFFFF);
        long least = ((values[3] & 0xFFFF) << 48) | (values[4] & 0xFFFF_FFFF_FFFFL);
        return new TUUID(most, least);
    }

    public long getLeastSignificantBits() {
        return leastSigBits;
    }

    public long getMostSignificantBits() {
        return mostSigBits;
    }

    public int version() {
        return (int) ((mostSigBits >> 12) & 0xF);
    }

    public int variant() {
        long top = leastSigBits >>> 61;
        if ((top & 0b100) == 0) {
            return 0;
        }
        if ((top & 0b010) == 0) {
            return 2;
        }
        return (int) top;
    }

    public long timestamp() {
        requireTimeBased();
        return ((mostSigBits & 0x0FFFL) << 48) | (((mostSigBits >> 16) & 0xFFFFL) << 32) | (mostSigBits >>> 32);
    }

    public int clockSequence() {
        requireTimeBased();
        return (int) ((leastSigBits & 0x3FFF_0000_0000_0000L) >>> 48);
    }

    public long node() {
        requireTimeBased();
        return leastSigBits & 0x0000_FFFF_FFFF_FFFFL;
    }

    private void requireTimeBased() {
        if (version() != 1) {
            throw new UnsupportedOperationException("Not a time-based UUID");
        }
    }

    @Override
    public String toString() {
        return hex(mostSigBits >>> 32, 8) + "-" + hex(mostSigBits >>> 16, 4) + "-" + hex(mostSigBits, 4) + "-"
                + hex(leastSigBits >>> 48, 4) + "-" + hex(leastSigBits, 12);
    }

    private static String hex(long value, int digits) {
        String text = Long.toHexString(value & (-1L >>> (64 - digits * 4)));
        return "0".repeat(digits - text.length()) + text;
    }

    @Override
    public int hashCode() {
        long bits = mostSigBits ^ leastSigBits;
        return (int) (bits >> 32) ^ (int) bits;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof TUUID uuid && uuid.mostSigBits == mostSigBits && uuid.leastSigBits == leastSigBits;
    }

    @Override
    public int compareTo(TUUID other) {
        int most = Long.compare(mostSigBits, other.mostSigBits);
        return most != 0 ? most : Long.compare(leastSigBits, other.leastSigBits);
    }

    private static long toLong(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[offset + i] & 0xFF);
        }
        return value;
    }

    /**
     * MD5 (RFC 1321), for name-based UUIDs only: the browser build has no {@code MessageDigest}.
     */
    static final class Md5 {
        private static final int[] SHIFTS = {
            7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
            5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
            4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
            6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
        };
        // floor(abs(sin(i + 1)) * 2^32), the table RFC 1321 defines.
        private static final int[] SINES = {
            0xD76AA478, 0xE8C7B756, 0x242070DB, 0xC1BDCEEE,
            0xF57C0FAF, 0x4787C62A, 0xA8304613, 0xFD469501,
            0x698098D8, 0x8B44F7AF, 0xFFFF5BB1, 0x895CD7BE,
            0x6B901122, 0xFD987193, 0xA679438E, 0x49B40821,
            0xF61E2562, 0xC040B340, 0x265E5A51, 0xE9B6C7AA,
            0xD62F105D, 0x02441453, 0xD8A1E681, 0xE7D3FBC8,
            0x21E1CDE6, 0xC33707D6, 0xF4D50D87, 0x455A14ED,
            0xA9E3E905, 0xFCEFA3F8, 0x676F02D9, 0x8D2A4C8A,
            0xFFFA3942, 0x8771F681, 0x6D9D6122, 0xFDE5380C,
            0xA4BEEA44, 0x4BDECFA9, 0xF6BB4B60, 0xBEBFBC70,
            0x289B7EC6, 0xEAA127FA, 0xD4EF3085, 0x04881D05,
            0xD9D4D039, 0xE6DB99E5, 0x1FA27CF8, 0xC4AC5665,
            0xF4292244, 0x432AFF97, 0xAB9423A7, 0xFC93A039,
            0x655B59C3, 0x8F0CCC92, 0xFFEFF47D, 0x85845DD1,
            0x6FA87E4F, 0xFE2CE6E0, 0xA3014314, 0x4E0811A1,
            0xF7537E82, 0xBD3AF235, 0x2AD7D2BB, 0xEB86D391
        };

        private Md5() {}

        static byte[] digest(byte[] message) {
            int blocks = (message.length + 8) / 64 + 1;
            byte[] padded = new byte[blocks * 64];
            System.arraycopy(message, 0, padded, 0, message.length);
            padded[message.length] = (byte) 0x80;
            long bits = (long) message.length * 8;
            for (int i = 0; i < 8; i++) {
                padded[padded.length - 8 + i] = (byte) (bits >>> (8 * i));
            }
            int a0 = 0x67452301;
            int b0 = 0xEFCDAB89;
            int c0 = 0x98BADCFE;
            int d0 = 0x10325476;
            int[] words = new int[16];
            for (int block = 0; block < blocks; block++) {
                for (int i = 0; i < 16; i++) {
                    int at = block * 64 + i * 4;
                    words[i] = (padded[at] & 0xFF)
                            | (padded[at + 1] & 0xFF) << 8
                            | (padded[at + 2] & 0xFF) << 16
                            | (padded[at + 3] & 0xFF) << 24;
                }
                int a = a0;
                int b = b0;
                int c = c0;
                int d = d0;
                for (int i = 0; i < 64; i++) {
                    int f;
                    int g;
                    if (i < 16) {
                        f = (b & c) | (~b & d);
                        g = i;
                    } else if (i < 32) {
                        f = (d & b) | (~d & c);
                        g = (5 * i + 1) % 16;
                    } else if (i < 48) {
                        f = b ^ c ^ d;
                        g = (3 * i + 5) % 16;
                    } else {
                        f = c ^ (b | ~d);
                        g = (7 * i) % 16;
                    }
                    int next = d;
                    d = c;
                    c = b;
                    b = b + Integer.rotateLeft(a + f + SINES[i] + words[g], SHIFTS[i]);
                    a = next;
                }
                a0 += a;
                b0 += b;
                c0 += c;
                d0 += d;
            }
            byte[] digest = new byte[16];
            int[] state = {a0, b0, c0, d0};
            for (int i = 0; i < 16; i++) {
                digest[i] = (byte) (state[i / 4] >>> (8 * (i % 4)));
            }
            return digest;
        }
    }
}
