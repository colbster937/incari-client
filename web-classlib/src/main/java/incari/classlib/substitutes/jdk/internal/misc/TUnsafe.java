package incari.classlib.substitutes.jdk.internal.misc;

import java.lang.reflect.Field;

/**
 * {@code jdk.internal.misc.Unsafe}, present so libraries link, but never available: {@link #getUnsafe} throws,
 * and the libraries that probe for it (JOML, Netty, Guava) take their safe fallbacks. The members
 * are the ones those libraries use; none can be reached without an instance.
 */
@SuppressWarnings("unused") // The parameters exist to match the JDK signatures.
public final class TUnsafe {

    private TUnsafe() {}

    public static TUnsafe getUnsafe() {
        throw new SecurityException("jdk.internal.misc.Unsafe isn't available in the browser");
    }

    public double getDouble(long arg0) {
        throw unavailable();
    }

    public double getDouble(Object arg0, long arg1) {
        throw unavailable();
    }

    public float getFloat(long arg0) {
        throw unavailable();
    }

    public float getFloat(Object arg0, long arg1) {
        throw unavailable();
    }

    public int getIntUnaligned(Object arg0, long arg1) {
        throw unavailable();
    }

    public long getLong(Object arg0, long arg1) {
        throw unavailable();
    }

    public long getLongUnaligned(Object arg0, long arg1) {
        throw unavailable();
    }

    public long objectFieldOffset(Class<?> arg0, String arg1) {
        throw unavailable();
    }

    public long objectFieldOffset(Field arg0) {
        throw unavailable();
    }

    public void putDouble(Object arg0, long arg1, double arg2) {
        throw unavailable();
    }

    public void putFloat(Object arg0, long arg1, float arg2) {
        throw unavailable();
    }

    public void putIntUnaligned(Object arg0, long arg1, int arg2) {
        throw unavailable();
    }

    public void putLong(Object arg0, long arg1, long arg2) {
        throw unavailable();
    }

    public void putLongUnaligned(Object arg0, long arg1, long arg2) {
        throw unavailable();
    }

    private static UnsupportedOperationException unavailable() {
        return new UnsupportedOperationException("jdk.internal.misc.Unsafe isn't available in the browser");
    }
}
