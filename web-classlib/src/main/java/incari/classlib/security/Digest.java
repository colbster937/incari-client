package incari.classlib.security;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * MD5 (RFC 1321), SHA-1 and SHA-256 (FIPS 180-4), written from their specifications, behind the
 * browser build's {@code MessageDigest}. All three work on 64-byte blocks padded with a bit length
 * at the end (little-endian for MD5, big-endian for SHA); only the state, the byte order and the
 * compression function differ.
 */
public final class Digest {

    private static final int MD5 = 0;
    private static final int SHA1 = 1;
    private static final int SHA256 = 2;

    // MD5's per-round shifts and its constants (the integer part of 2^32 * |sin(i + 1)|).
    private static final int[] MD5_SHIFT = {7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21};
    private static final int[] MD5_K = new int[64];

    static {
        for (int i = 0; i < 64; i++) {
            MD5_K[i] = (int) (long) Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296.0);
        }
    }

    private static final int[] SHA256_K = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    };

    private final int kind;
    private final int[] state;
    // The message schedule: 80 words for SHA-1, 64 for SHA-256.
    private final int[] words = new int[80];
    private final byte[] block = new byte[64];
    private int buffered;
    private long length;

    private Digest(int kind) {
        this.kind = kind;
        this.state = new int[kind == SHA256 ? 8 : kind == SHA1 ? 5 : 4];
        reset();
    }

    /** A digest for {@code algorithm} ("MD5", "SHA-1", "SHA-256", with or without the dash), or null. */
    public static @Nullable Digest create(String algorithm) {
        return switch (algorithm.toUpperCase(Locale.ROOT).replace("-", "")) {
            case "MD5" -> new Digest(MD5);
            case "SHA1", "SHA" -> new Digest(SHA1);
            case "SHA256" -> new Digest(SHA256);
            default -> null;
        };
    }

    public int length() {
        return kind == SHA256 ? 32 : kind == SHA1 ? 20 : 16;
    }

    public void reset() {
        int[] initial = kind == SHA256
                ? new int[] {
                    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
                }
                : kind == SHA1
                        ? new int[] {0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476, 0xc3d2e1f0}
                        : new int[] {0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476};
        System.arraycopy(initial, 0, state, 0, state.length);
        buffered = 0;
        length = 0;
    }

    public void update(byte[] input, int offset, int count) {
        length += count;
        for (int i = 0; i < count; i++) {
            block[buffered++] = input[offset + i];
            if (buffered == 64) {
                compress();
                buffered = 0;
            }
        }
    }

    /** The hash of everything given since the last reset; resets afterwards. */
    public byte[] finish() {
        long bits = length * 8;
        byte[] one = {(byte) 0x80};
        update(one, 0, 1);
        byte[] zero = {0};
        while (buffered != 56) {
            update(zero, 0, 1);
        }
        byte[] tail = new byte[8];
        for (int i = 0; i < 8; i++) {
            tail[i] = (byte) (kind == MD5 ? bits >>> (8 * i) : bits >>> (56 - 8 * i));
        }
        update(tail, 0, 8);
        byte[] out = new byte[length()];
        for (int i = 0; i < out.length; i++) {
            int shift = kind == MD5 ? 8 * (i % 4) : 24 - 8 * (i % 4);
            out[i] = (byte) (state[i / 4] >>> shift);
        }
        reset();
        return out;
    }

    public Digest copy() {
        Digest copy = new Digest(kind);
        System.arraycopy(state, 0, copy.state, 0, state.length);
        System.arraycopy(block, 0, copy.block, 0, block.length);
        copy.buffered = buffered;
        copy.length = length;
        return copy;
    }

    private void compress() {
        if (kind == MD5) {
            compressMd5();
            return;
        }
        for (int t = 0; t < 16; t++) {
            words[t] = (block[4 * t] & 0xff) << 24
                    | (block[4 * t + 1] & 0xff) << 16
                    | (block[4 * t + 2] & 0xff) << 8
                    | (block[4 * t + 3] & 0xff);
        }
        if (kind == SHA256) {
            compress256();
        } else {
            compress1();
        }
    }

    private void compressMd5() {
        for (int t = 0; t < 16; t++) {
            words[t] = (block[4 * t] & 0xff)
                    | (block[4 * t + 1] & 0xff) << 8
                    | (block[4 * t + 2] & 0xff) << 16
                    | (block[4 * t + 3] & 0xff) << 24;
        }
        int a = state[0];
        int b = state[1];
        int c = state[2];
        int d = state[3];
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
            int rotated = Integer.rotateLeft(a + f + MD5_K[i] + words[g], MD5_SHIFT[(i / 16) * 4 + i % 4]);
            a = d;
            d = c;
            c = b;
            b = b + rotated;
        }
        state[0] += a;
        state[1] += b;
        state[2] += c;
        state[3] += d;
    }

    private void compress1() {
        for (int t = 16; t < 80; t++) {
            words[t] = Integer.rotateLeft(words[t - 3] ^ words[t - 8] ^ words[t - 14] ^ words[t - 16], 1);
        }
        int a = state[0];
        int b = state[1];
        int c = state[2];
        int d = state[3];
        int e = state[4];
        for (int t = 0; t < 80; t++) {
            int f;
            int k;
            if (t < 20) {
                f = (b & c) | (~b & d);
                k = 0x5a827999;
            } else if (t < 40) {
                f = b ^ c ^ d;
                k = 0x6ed9eba1;
            } else if (t < 60) {
                f = (b & c) | (b & d) | (c & d);
                k = 0x8f1bbcdc;
            } else {
                f = b ^ c ^ d;
                k = 0xca62c1d6;
            }
            int temp = Integer.rotateLeft(a, 5) + f + e + k + words[t];
            e = d;
            d = c;
            c = Integer.rotateLeft(b, 30);
            b = a;
            a = temp;
        }
        state[0] += a;
        state[1] += b;
        state[2] += c;
        state[3] += d;
        state[4] += e;
    }

    private void compress256() {
        for (int t = 16; t < 64; t++) {
            int s0 = Integer.rotateRight(words[t - 15], 7)
                    ^ Integer.rotateRight(words[t - 15], 18)
                    ^ (words[t - 15] >>> 3);
            int s1 = Integer.rotateRight(words[t - 2], 17)
                    ^ Integer.rotateRight(words[t - 2], 19)
                    ^ (words[t - 2] >>> 10);
            words[t] = words[t - 16] + s0 + words[t - 7] + s1;
        }
        int a = state[0];
        int b = state[1];
        int c = state[2];
        int d = state[3];
        int e = state[4];
        int f = state[5];
        int g = state[6];
        int h = state[7];
        for (int t = 0; t < 64; t++) {
            int sum1 = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25);
            int choose = (e & f) ^ (~e & g);
            int temp1 = h + sum1 + choose + SHA256_K[t] + words[t];
            int sum0 = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22);
            int majority = (a & b) ^ (a & c) ^ (b & c);
            int temp2 = sum0 + majority;
            h = g;
            g = f;
            f = e;
            e = d + temp1;
            d = c;
            c = b;
            b = a;
            a = temp1 + temp2;
        }
        state[0] += a;
        state[1] += b;
        state[2] += c;
        state[3] += d;
        state[4] += e;
        state[5] += f;
        state[6] += g;
        state[7] += h;
    }
}
