package org.teavm.classlib.java.nio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The patched {@link TByteBuffer}'s absolute bulk put, on the JVM: it copies the range it's given
 * from a source whose position is at its end (a buffer read through before), which TeaVM's skipped.
 * The page's decoded sprites came out empty that way.
 */
class AbsoluteBulkPutTest {

    @Test
    void copiesFromASourceReadToItsEnd() {
        TByteBuffer source = TByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5});
        source.position(source.limit());
        TByteBuffer target = TByteBuffer.allocate(4);

        target.put(1, source, 2, 3);

        assertThat(target.array()).containsExactly(0, 3, 4, 5);
        assertThat(source.position()).isEqualTo(5);
    }

    @Test
    void anEmptyRangeCopiesNothing() {
        TByteBuffer target = TByteBuffer.allocate(2);
        target.put(0, TByteBuffer.wrap(new byte[0]), 0, 0);
        assertThat(target.array()).containsExactly(0, 0);
    }
}
