package incari.classlib.substitutes.java.security;

import incari.classlib.security.Digest;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * {@code java.security.MessageDigest}, which TeaVM lacks, for MD5, SHA-1 and SHA-256
 * ({@link Digest}). The survey deletes {@code java.security}'s signing and encryption (offline
 * and Eagler modes don't use them), but hashing isn't that: Guava's {@code Hashing.sha1()}, which
 * names a server's icon in the server list, got a null digest, so any listed server broke the
 * multiplayer screen; and once one digest exists, Guava's MD5 holder (the game's seed
 * mixing, at startup) needs its own. Only what Guava and the game call is here.
 */
public abstract class TMessageDigest implements Cloneable {

    private final String algorithm;

    protected TMessageDigest(String algorithm) {
        this.algorithm = algorithm;
    }

    public static TMessageDigest getInstance(String algorithm) throws NoSuchAlgorithmException {
        Digest digest = Digest.create(algorithm);
        if (digest == null) {
            throw new NoSuchAlgorithmException(algorithm + " MessageDigest not available");
        }
        return new Hashed(algorithm, digest);
    }

    public final String getAlgorithm() {
        return algorithm;
    }

    public abstract int getDigestLength();

    public abstract void update(byte input);

    public abstract void update(byte[] input, int offset, int length);

    public void update(byte[] input) {
        update(input, 0, input.length);
    }

    public final void update(ByteBuffer input) {
        byte[] bytes = new byte[input.remaining()];
        input.get(bytes);
        update(bytes, 0, bytes.length);
    }

    /** The hash of everything given since the last reset; resets afterwards. */
    public abstract byte[] digest();

    public byte[] digest(byte[] input) {
        update(input);
        return digest();
    }

    public abstract void reset();

    @Override
    public abstract Object clone() throws CloneNotSupportedException;

    public static boolean isEqual(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }

    @Override
    public String toString() {
        return algorithm + " Message Digest from Incari";
    }

    /** MD5, SHA-1 or SHA-256. */
    private static final class Hashed extends TMessageDigest {
        private final Digest digest;

        Hashed(String algorithm, Digest digest) {
            super(algorithm);
            this.digest = digest;
        }

        @Override
        public int getDigestLength() {
            return digest.length();
        }

        @Override
        public void update(byte input) {
            digest.update(new byte[] {input}, 0, 1);
        }

        @Override
        public void update(byte[] input, int offset, int length) {
            digest.update(input, offset, length);
        }

        @Override
        public byte[] digest() {
            return digest.finish();
        }

        @Override
        public void reset() {
            digest.reset();
        }

        @Override
        @SuppressWarnings("NoClone") // The JDK's contract: a copy with the state so far.
        public Object clone() {
            return new Hashed(getAlgorithm(), digest.copy());
        }
    }
}
