package incari.classlib.memory;

import incari.classlib.substitutes.java.nio.TNativeBuffer;
import incari.classlib.substitutes.java.nio.file.TAddressBasedBuffer;
import java.nio.Buffer;
import org.teavm.interop.Address;

/**
 * {@link Heap}'s browser backend: the WebAssembly module's linear memory, read and written through
 * TeaVM's {@link Address} intrinsics, which compile to single loads and stores (little-endian, and
 * unaligned access is allowed). Only reached when {@link Heap#linear()} holds, so a JVM never loads
 * it. Hot path: nothing here allocates.
 */
final class LinearMemory {

    private LinearMemory() {}

    /** Where a direct buffer's contents start in linear memory (its position isn't counted). */
    static long address(Buffer buffer) {
        // WebAssembly addresses are 32-bit and unsigned.
        return ((TAddressBasedBuffer) (Object) buffer).getDataAddress().toInt() & 0xFFFFFFFFL;
    }

    /** Frees a direct buffer's memory now, and stops the collector from freeing it again later. */
    static void release(Buffer buffer) {
        ((TNativeBuffer) (Object) buffer).release();
    }

    static void copy(long source, long target, int count) {
        Address.moveMemoryBlock(at(source), at(target), count);
    }

    static void fill(long address, byte value, int count) {
        Address.fill(at(address), value, count);
    }

    static byte getByte(long address) {
        return at(address).getByte();
    }

    static void putByte(long address, byte value) {
        at(address).putByte(value);
    }

    static short getShort(long address) {
        return at(address).getShort();
    }

    static void putShort(long address, short value) {
        at(address).putShort(value);
    }

    static int getInt(long address) {
        return at(address).getInt();
    }

    static void putInt(long address, int value) {
        at(address).putInt(value);
    }

    static long getLong(long address) {
        return at(address).getLong();
    }

    static void putLong(long address, long value) {
        at(address).putLong(value);
    }

    static float getFloat(long address) {
        return at(address).getFloat();
    }

    static void putFloat(long address, float value) {
        at(address).putFloat(value);
    }

    static double getDouble(long address) {
        return at(address).getDouble();
    }

    static void putDouble(long address, double value) {
        at(address).putDouble(value);
    }

    private static Address at(long address) {
        return Address.fromInt((int) address);
    }
}
