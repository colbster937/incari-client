package incari.classlib.substitutes.sun.misc;

import java.lang.reflect.Field;

/**
 * {@code sun.misc.Unsafe}, present so libraries link, but never available: {@link #getUnsafe} throws,
 * and the libraries that probe for it (JOML, Netty, Guava) take their safe fallbacks. The members
 * are the ones those libraries use; none can be reached without an instance.
 */
@SuppressWarnings("unused") // The parameters exist to match the JDK signatures.
public final class TUnsafe {

    private TUnsafe() {}

    public static TUnsafe getUnsafe() {
        throw new SecurityException("sun.misc.Unsafe isn't available in the browser");
    }

    public int addressSize() {
        throw unavailable();
    }

    public long allocateMemory(long arg0) {
        throw unavailable();
    }

    public int arrayBaseOffset(Class<?> arg0) {
        throw unavailable();
    }

    public int arrayIndexScale(Class<?> arg0) {
        throw unavailable();
    }

    public boolean compareAndSwapInt(Object arg0, long arg1, int arg2, int arg3) {
        throw unavailable();
    }

    public boolean compareAndSwapLong(Object arg0, long arg1, long arg2, long arg3) {
        throw unavailable();
    }

    public boolean compareAndSwapObject(Object arg0, long arg1, Object arg2, Object arg3) {
        throw unavailable();
    }

    public void copyMemory(long arg0, long arg1, long arg2) {
        throw unavailable();
    }

    public void copyMemory(Object arg0, long arg1, Object arg2, long arg3, long arg4) {
        throw unavailable();
    }

    public void freeMemory(long arg0) {
        throw unavailable();
    }

    public int getAndAddInt(Object arg0, long arg1, int arg2) {
        throw unavailable();
    }

    public Object getAndSetObject(Object arg0, long arg1, Object arg2) {
        throw unavailable();
    }

    public boolean getBoolean(Object arg0, long arg1) {
        throw unavailable();
    }

    public byte getByte(long arg0) {
        throw unavailable();
    }

    public byte getByte(Object arg0, long arg1) {
        throw unavailable();
    }

    public char getChar(Object arg0, long arg1) {
        throw unavailable();
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

    public int getInt(long arg0) {
        throw unavailable();
    }

    public int getInt(Object arg0, long arg1) {
        throw unavailable();
    }

    public int getIntVolatile(Object arg0, long arg1) {
        throw unavailable();
    }

    public long getLong(long arg0) {
        throw unavailable();
    }

    public long getLong(Object arg0, long arg1) {
        throw unavailable();
    }

    public long getLongVolatile(Object arg0, long arg1) {
        throw unavailable();
    }

    public Object getObject(Object arg0, long arg1) {
        throw unavailable();
    }

    public Object getObjectVolatile(Object arg0, long arg1) {
        throw unavailable();
    }

    public short getShort(long arg0) {
        throw unavailable();
    }

    public short getShort(Object arg0, long arg1) {
        throw unavailable();
    }

    public long objectFieldOffset(Field arg0) {
        throw unavailable();
    }

    public void putByte(long arg0, byte arg1) {
        throw unavailable();
    }

    public void putByte(Object arg0, long arg1, byte arg2) {
        throw unavailable();
    }

    public void putDouble(Object arg0, long arg1, double arg2) {
        throw unavailable();
    }

    public void putFloat(Object arg0, long arg1, float arg2) {
        throw unavailable();
    }

    public void putInt(long arg0, int arg1) {
        throw unavailable();
    }

    public void putInt(Object arg0, long arg1, int arg2) {
        throw unavailable();
    }

    public void putLong(long arg0, long arg1) {
        throw unavailable();
    }

    public void putLong(Object arg0, long arg1, long arg2) {
        throw unavailable();
    }

    public void putObject(Object arg0, long arg1, Object arg2) {
        throw unavailable();
    }

    public void putOrderedInt(Object arg0, long arg1, int arg2) {
        throw unavailable();
    }

    public void putOrderedLong(Object arg0, long arg1, long arg2) {
        throw unavailable();
    }

    public void putOrderedObject(Object arg0, long arg1, Object arg2) {
        throw unavailable();
    }

    public void putShort(long arg0, short arg1) {
        throw unavailable();
    }

    public void putShort(Object arg0, long arg1, short arg2) {
        throw unavailable();
    }

    public long reallocateMemory(long arg0, long arg1) {
        throw unavailable();
    }

    public void setMemory(long arg0, long arg1, byte arg2) {
        throw unavailable();
    }

    public void setMemory(Object arg0, long arg1, long arg2, byte arg3) {
        throw unavailable();
    }

    public Object staticFieldBase(Field arg0) {
        throw unavailable();
    }

    public long staticFieldOffset(Field arg0) {
        throw unavailable();
    }

    public void storeFence() {
        throw unavailable();
    }

    private static UnsupportedOperationException unavailable() {
        return new UnsupportedOperationException("sun.misc.Unsafe isn't available in the browser");
    }
}
