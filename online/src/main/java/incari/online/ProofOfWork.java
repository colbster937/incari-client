package incari.online;

/**
 * Sign-up's proof of work (ADR 0058): the service hands out a 16-byte challenge and a number of
 * bits, and the client finds a nonce such that SHA-256 of the challenge followed by the nonce (8
 * bytes, big-endian) starts with that many zero bits. The two fit one SHA-256 block, so each try is
 * one compression with nothing allocated.
 *
 * <p>Solved in steps ({@link #step}) so the caller can yield between them: in the page, the
 * background pool runs it between frames. One thread at a time.
 */
final class ProofOfWork {

    /** The largest nonce tried: JSON numbers are exact up to 2^53, and no answer takes that long. */
    static final long MAX_NONCE = (1L << 53) - 1;

    private final int bits;
    private final int[] block = new int[64];
    private final int[] state = new int[8];
    private long nonce;
    private long found = -1;

    /**
     * @param challenge the service's 16 bytes
     * @param bits how many leading zero bits the hash needs, 0 to 64
     */
    ProofOfWork(byte[] challenge, int bits) {
        if (challenge.length != 16) {
            throw new IllegalArgumentException("A challenge is 16 bytes, not " + challenge.length);
        }
        if (bits < 0 || bits > 64) {
            throw new IllegalArgumentException("Bits out of range: " + bits);
        }
        this.bits = bits;
        for (int i = 0; i < 4; i++) {
            block[i] = (challenge[i * 4] & 0xFF) << 24
                    | (challenge[i * 4 + 1] & 0xFF) << 16
                    | (challenge[i * 4 + 2] & 0xFF) << 8
                    | (challenge[i * 4 + 3] & 0xFF);
        }
    }

    /**
     * Tries up to {@code tries} more nonces. Returns true once one is found ({@link #nonce}).
     *
     * @throws IllegalStateException if every nonce up to {@link #MAX_NONCE} failed (never, for a fair challenge)
     */
    boolean step(int tries) {
        if (found >= 0) {
            return true;
        }
        for (int i = 0; i < tries; i++) {
            if (nonce > MAX_NONCE) {
                throw new IllegalStateException("No nonce found");
            }
            if (passes(nonce)) {
                found = nonce;
                return true;
            }
            nonce++;
        }
        return false;
    }

    /** The nonce found; -1 until {@link #step} returns true. */
    long nonce() {
        return found;
    }

    /** How many nonces have been tried. */
    long tried() {
        return found >= 0 ? found + 1 : nonce;
    }

    /** Whether {@code candidate} answers the challenge. Hot path: allocates nothing. */
    boolean passes(long candidate) {
        // Words 0-3 are the challenge; 4-5 the nonce; then the padding: a 1 bit, zeros, and the
        // message's length in bits (24 bytes = 192).
        block[4] = (int) (candidate >>> 32);
        block[5] = (int) candidate;
        block[6] = 0x80000000;
        for (int i = 7; i < 15; i++) {
            block[i] = 0;
        }
        block[15] = 192;
        System.arraycopy(Sha256.INITIAL, 0, state, 0, 8);
        Sha256.compress(state, block);
        return leadingZeros(state) >= bits;
    }

    private static int leadingZeros(int[] state) {
        int zeros = Integer.numberOfLeadingZeros(state[0]);
        return zeros < 32 ? zeros : 32 + Integer.numberOfLeadingZeros(state[1]);
    }

    /**
     * Base64url without padding (RFC 4648 section 5), as the service writes its challenges. Not
     * {@code java.util.Base64}: its padding rules differ between the JDK and TeaVM.
     *
     * @throws IllegalArgumentException for a character outside the alphabet or a bad length
     */
    static byte[] base64Url(String text) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int length = text.length();
        while (length > 0 && text.charAt(length - 1) == '=') {
            length--;
        }
        if (length % 4 == 1) {
            throw new IllegalArgumentException("Not base64url: bad length");
        }
        byte[] out = new byte[length * 3 / 4];
        int bits = 0;
        int count = 0;
        int at = 0;
        for (int i = 0; i < length; i++) {
            int value = alphabet.indexOf(text.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("Not base64url: " + text.charAt(i));
            }
            bits = bits << 6 | value;
            count += 6;
            if (count >= 8) {
                count -= 8;
                out[at++] = (byte) (bits >> count);
            }
        }
        return out;
    }
}
