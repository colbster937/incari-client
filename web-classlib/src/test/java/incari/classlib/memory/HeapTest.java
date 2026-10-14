package incari.classlib.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import incari.classlib.substitutes.org.lwjgl.system.TMemoryStack;
import incari.classlib.substitutes.org.lwjgl.system.TMemoryUtil;
import incari.classlib.substitutes.org.lwjgl.util.shaderc.TShadercIncludeResult;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.junit.jupiter.api.Test;

class HeapTest {

    @Test
    void addressesAndBuffersSeeTheSameLittleEndianMemory() {
        long address = TMemoryUtil.nmemCalloc(4, 4);
        assertThat(address).isNotZero();
        TMemoryUtil.memPutInt(address + 4, 0x11223344);
        ByteBuffer view = TMemoryUtil.memByteBuffer(address, 16);
        assertThat(view.getInt(4)).isEqualTo(0x11223344);
        assertThat(view.get(4)).isEqualTo((byte) 0x44);
        view.putInt(8, -2);
        assertThat(TMemoryUtil.memGetInt(address + 8)).isEqualTo(-2);
        TMemoryUtil.memPutFloat(address + 12, 1.5f);
        assertThat(view.getFloat(12)).isEqualTo(1.5f);
        TMemoryUtil.nmemFree(address);
    }

    @Test
    void buffersFindTheirAddressAgain() {
        ByteBuffer buffer = TMemoryUtil.memAlloc(32);
        long address = TMemoryUtil.memAddress(buffer);
        buffer.position(8);
        assertThat(TMemoryUtil.memAddress(buffer)).isEqualTo(address + 8);
        ByteBuffer slice = TMemoryUtil.memSlice(buffer, 4, 8);
        assertThat(TMemoryUtil.memAddress(slice)).isEqualTo(address + 12);
        buffer.position(0);
        TMemoryUtil.memFree(buffer);
        assertThatThrownBy(() -> TMemoryUtil.memGetByte(address)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reallocationKeepsContentsAndPosition() {
        ByteBuffer buffer = TMemoryUtil.memAlloc(4);
        buffer.putInt(0, 42).position(2);
        ByteBuffer grown = TMemoryUtil.memRealloc(buffer, 16);
        assertThat(grown.capacity()).isEqualTo(16);
        assertThat(grown.position()).isEqualTo(2);
        assertThat(grown.getInt(0)).isEqualTo(42);
        TMemoryUtil.memFree(grown);
    }

    @Test
    void copiesAndStrings() {
        ByteBuffer text = TMemoryUtil.memUTF8("héllo");
        assertThat(text.remaining()).isEqualTo(6);
        long copy = TMemoryUtil.nmemAlloc(7);
        TMemoryUtil.memCopy(TMemoryUtil.memAddress(text), copy, 7);
        assertThat(TMemoryUtil.memUTF8(copy)).isEqualTo("héllo");
        TMemoryUtil.memFree(text);
        TMemoryUtil.nmemFree(copy);
    }

    @Test
    void intBuffersRoundTrip() {
        IntBuffer ints = TMemoryUtil.memAllocInt(3);
        ints.put(1, 7);
        assertThat(TMemoryUtil.memByteBuffer(ints).getInt(4)).isEqualTo(7);
        TMemoryUtil.memFree(ints);
    }

    @Test
    void stackFramesReleaseTheirMemory() {
        TMemoryStack stack = TMemoryStack.stackGet();
        int free = stack.getPointer();
        try (TMemoryStack frame = TMemoryStack.stackPush()) {
            IntBuffer ints = frame.callocInt(4);
            assertThat(ints.get(3)).isZero();
            ByteBuffer name = frame.UTF8("abc");
            assertThat(name.remaining()).isEqualTo(3);
            assertThat(TMemoryUtil.memUTF8(TMemoryUtil.memAddress(name))).isEqualTo("abc");
            assertThat(stack.getPointer()).isLessThan(free);
        }
        assertThat(stack.getPointer()).isEqualTo(free);
    }

    @Test
    void mallocLeavesMemoryAndCallocClearsIt() {
        long junk = TMemoryUtil.nmemAlloc(64);
        long zero = TMemoryUtil.nmemCalloc(8, 8);
        for (int i = 0; i < 64; i++) {
            // On a JVM, malloc's memory is junk on purpose: reused linear memory would be too.
            assertThat(TMemoryUtil.memGetByte(junk + i)).isEqualTo(Heap.JUNK);
            assertThat(TMemoryUtil.memGetByte(zero + i)).isZero();
        }
        TMemoryUtil.memSet(junk + 8, 7, 4);
        assertThat(TMemoryUtil.memGetInt(junk + 8)).isEqualTo(0x07070707);
        TMemoryUtil.nmemFree(junk);
        TMemoryUtil.nmemFree(zero);
    }

    @Test
    void viewsInsideABlockShareItsMemory() {
        long address = TMemoryUtil.nmemCalloc(1, 256);
        ByteBuffer inner = TMemoryUtil.memByteBuffer(address + 100, 16);
        inner.putFloat(4, 2.5f).putShort(8, (short) -3).putLong(8, inner.getLong(8));
        assertThat(TMemoryUtil.memGetFloat(address + 104)).isEqualTo(2.5f);
        assertThat(TMemoryUtil.memGetShort(address + 108)).isEqualTo((short) -3);
        assertThat(TMemoryUtil.memAddress(inner)).isEqualTo(address + 100);
        assertThatThrownBy(() -> TMemoryUtil.memByteBuffer(address + 250, 16))
                .isInstanceOf(IllegalArgumentException.class);
        TMemoryUtil.nmemFree(address);
    }

    @Test
    void overlappingCopiesMoveLikeMemmove() {
        long address = TMemoryUtil.nmemCalloc(1, 16);
        for (int i = 0; i < 16; i++) {
            TMemoryUtil.memPutByte(address + i, (byte) i);
        }
        TMemoryUtil.memCopy(address, address + 2, 10);
        assertThat(TMemoryUtil.memGetByte(address + 11)).isEqualTo((byte) 9);
        assertThat(TMemoryUtil.memGetByte(address + 2)).isEqualTo((byte) 0);
        TMemoryUtil.memCopy(address + 4, address, 8);
        assertThat(TMemoryUtil.memGetByte(address)).isEqualTo((byte) 2);
        TMemoryUtil.nmemFree(address);
    }

    @Test
    void onlyABlockStartCanBeFreed() {
        long address = TMemoryUtil.nmemAlloc(32);
        assertThatThrownBy(() -> TMemoryUtil.nmemFree(address + 4)).isInstanceOf(IllegalArgumentException.class);
        TMemoryUtil.nmemFree(address);
        TMemoryUtil.nmemFree(0);
    }

    @Test
    void stackIntsHaveAddresses() {
        try (TMemoryStack frame = TMemoryStack.stackPush()) {
            IntBuffer ints = frame.callocInt(3);
            ints.put(2, 99);
            assertThat(TMemoryUtil.memGetInt(TMemoryUtil.memAddress(ints) + 8)).isEqualTo(99);
        }
    }

    @Test
    void bufferUtilsGivesZeroedNativeOrderBuffers() {
        ByteBuffer buffer = incari.classlib.substitutes.org.lwjgl.TBufferUtils.createByteBuffer(32);
        assertThat(buffer.order()).isEqualTo(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertThat(buffer.getLong(0)).isZero();
    }

    /** What {@code ShaderSource.CachedIncludeSource} does with an include: fill it, then free it. */
    @Test
    void shaderIncludesFillAndFree() {
        TShadercIncludeResult result = TShadercIncludeResult.calloc();
        result.source_name(TMemoryUtil.memUTF8("minecraft:fog.glsl", false));
        result.content(TMemoryUtil.memUTF8("float fog;", false));
        long address = result.address();
        long content = TMemoryUtil.memGetAddress(address + TShadercIncludeResult.CONTENT);
        assertThat(TMemoryUtil.memByteBuffer(content, 10).get(0)).isEqualTo((byte) 'f');
        TMemoryUtil.nmemFree(TMemoryUtil.memGetAddress(address + TShadercIncludeResult.SOURCE_NAME));
        TMemoryUtil.nmemFree(content);
        TMemoryUtil.nmemFree(address);
    }
}
