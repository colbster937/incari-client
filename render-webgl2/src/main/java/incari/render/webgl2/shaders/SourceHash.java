package incari.render.webgl2.shaders;

/**
 * A fingerprint of a shader's source text, so the browser can tell when the source it was asked to
 * compile isn't the one the build translated (a resource pack replaced it). FNV-1a over the UTF-16
 * code units: trivial to compute the same way in the build tool and under TeaVM, and collisions
 * only matter between vanilla and a pack's edit of the same file.
 */
public final class SourceHash {

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private SourceHash() {}

    public static String of(String source) {
        long hash = OFFSET_BASIS;
        for (int i = 0; i < source.length(); i++) {
            hash ^= source.charAt(i);
            hash *= PRIME;
        }
        String hex = Long.toHexString(hash);
        return "0".repeat(16 - hex.length()) + hex;
    }
}
