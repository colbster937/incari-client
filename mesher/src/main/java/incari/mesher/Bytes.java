package incari.mesher;

import java.util.Arrays;

/**
 * Little-endian bytes for the formats that cross into a worker: the tables, snapshots and results.
 * Plain arrays rather than {@code ByteBuffer}s, so the worker's module needs nothing from the class
 * library but arrays.
 */
public final class Bytes {

    private Bytes() {}

    /** A growable byte array written from the front. */
    public static final class Out {

        private byte[] data;
        private int size;

        public Out(int capacity) {
            data = new byte[Math.max(16, capacity)];
        }

        public int size() {
            return size;
        }

        /** The bytes written so far; valid until the next write. */
        public byte[] array() {
            return data;
        }

        public byte[] toArray() {
            return Arrays.copyOf(data, size);
        }

        public void clear() {
            size = 0;
        }

        private void ensure(int more) {
            if (size + more > data.length) {
                data = Arrays.copyOf(data, Math.max(size + more, data.length * 2));
            }
        }

        /** Makes room for {@code count} bytes and returns where they start in {@link #array}. */
        public int reserve(int count) {
            ensure(count);
            int at = size;
            size += count;
            return at;
        }

        public void putByte(int value) {
            ensure(1);
            data[size++] = (byte) value;
        }

        public void putShort(int value) {
            ensure(2);
            data[size] = (byte) value;
            data[size + 1] = (byte) (value >> 8);
            size += 2;
        }

        public void putInt(int value) {
            ensure(4);
            putIntAt(data, size, value);
            size += 4;
        }

        public void putLong(long value) {
            putInt((int) value);
            putInt((int) (value >>> 32));
        }

        public void putFloat(float value) {
            putInt(Float.floatToRawIntBits(value));
        }

        public void putBytes(byte[] source, int offset, int length) {
            ensure(length);
            System.arraycopy(source, offset, data, size, length);
            size += length;
        }

        public void putInts(int[] values, int count) {
            putInt(count);
            ensure(count * 4);
            for (int i = 0; i < count; i++) {
                putIntAt(data, size, values[i]);
                size += 4;
            }
        }

        public void putFloats(float[] values, int count) {
            putInt(count);
            ensure(count * 4);
            for (int i = 0; i < count; i++) {
                putIntAt(data, size, Float.floatToRawIntBits(values[i]));
                size += 4;
            }
        }

        public void putShorts(short[] values, int count) {
            putInt(count);
            ensure(count * 2);
            for (int i = 0; i < count; i++) {
                data[size] = (byte) values[i];
                data[size + 1] = (byte) (values[i] >> 8);
                size += 2;
            }
        }

        public void putChars(char[] values, int count) {
            putInt(count);
            ensure(count * 2);
            for (int i = 0; i < count; i++) {
                data[size] = (byte) values[i];
                data[size + 1] = (byte) (values[i] >> 8);
                size += 2;
            }
        }

        public void putByteArray(byte[] values, int count) {
            putInt(count);
            putBytes(values, 0, count);
        }

        public void putLongs(long[] values, int count) {
            putInt(count);
            for (int i = 0; i < count; i++) {
                putLong(values[i]);
            }
        }
    }

    /** Reads what {@link Out} wrote. */
    public static final class In {

        private final byte[] data;
        private int position;
        private final int end;

        public In(byte[] data, int offset, int length) {
            this.data = data;
            this.position = offset;
            this.end = offset + length;
        }

        public int position() {
            return position;
        }

        private void need(int count) {
            if (position + count > end) {
                throw new IllegalStateException("Read past the end: " + (position + count) + " > " + end);
            }
        }

        public int getByte() {
            need(1);
            return data[position++];
        }

        public int getUnsignedByte() {
            return getByte() & 0xFF;
        }

        public int getShort() {
            need(2);
            int value = (data[position] & 0xFF) | data[position + 1] << 8;
            position += 2;
            return value;
        }

        public int getInt() {
            need(4);
            int value = intAt(data, position);
            position += 4;
            return value;
        }

        public long getLong() {
            long low = getInt() & 0xFFFFFFFFL;
            long high = getInt() & 0xFFFFFFFFL;
            return low | high << 32;
        }

        public float getFloat() {
            return Float.intBitsToFloat(getInt());
        }

        public void getBytes(byte[] target, int offset, int length) {
            need(length);
            System.arraycopy(data, position, target, offset, length);
            position += length;
        }

        public int[] getInts() {
            int count = getInt();
            need(count * 4);
            int[] values = new int[count];
            for (int i = 0; i < count; i++) {
                values[i] = intAt(data, position);
                position += 4;
            }
            return values;
        }

        public float[] getFloats() {
            int count = getInt();
            need(count * 4);
            float[] values = new float[count];
            for (int i = 0; i < count; i++) {
                values[i] = Float.intBitsToFloat(intAt(data, position));
                position += 4;
            }
            return values;
        }

        public short[] getShorts() {
            int count = getInt();
            need(count * 2);
            short[] values = new short[count];
            for (int i = 0; i < count; i++) {
                values[i] = (short) ((data[position] & 0xFF) | (data[position + 1] << 8));
                position += 2;
            }
            return values;
        }

        /** Reads chars into {@code target}, which must be exactly as long as what was written. */
        public void getChars(char[] target) {
            int count = getInt();
            if (count != target.length) {
                throw new IllegalStateException("Expected " + target.length + " chars, found " + count);
            }
            need(count * 2);
            for (int i = 0; i < count; i++) {
                target[i] = (char) ((data[position] & 0xFF) | (data[position + 1] & 0xFF) << 8);
                position += 2;
            }
        }

        public byte[] getByteArray() {
            int count = getInt();
            byte[] values = new byte[count];
            getBytes(values, 0, count);
            return values;
        }

        /** Reads bytes into {@code target}, which must be exactly as long as what was written. */
        public void getByteArray(byte[] target) {
            int count = getInt();
            if (count != target.length) {
                throw new IllegalStateException("Expected " + target.length + " bytes, found " + count);
            }
            getBytes(target, 0, count);
        }

        public long[] getLongs() {
            int count = getInt();
            long[] values = new long[count];
            for (int i = 0; i < count; i++) {
                values[i] = getLong();
            }
            return values;
        }
    }

    static void putIntAt(byte[] data, int at, int value) {
        data[at] = (byte) value;
        data[at + 1] = (byte) (value >> 8);
        data[at + 2] = (byte) (value >> 16);
        data[at + 3] = (byte) (value >> 24);
    }

    static void putShortAt(byte[] data, int at, int value) {
        data[at] = (byte) value;
        data[at + 1] = (byte) (value >> 8);
    }

    static void putFloatAt(byte[] data, int at, float value) {
        putIntAt(data, at, Float.floatToRawIntBits(value));
    }

    static int intAt(byte[] data, int at) {
        return (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8 | (data[at + 2] & 0xFF) << 16 | data[at + 3] << 24;
    }
}
