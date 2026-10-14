package incari.platform.web.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.BitSet;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** A file's written ranges cover every byte written, in few ranges kept apart. */
class DataFileTest {

    @Test
    void rangesCoverEveryWriteAndStayFewAndApart() {
        Random random = new Random(41);
        for (int round = 0; round < 500; round++) {
            DataFile file = new DataFile("f", 0);
            BitSet written = new BitSet();
            int writes = 1 + random.nextInt(40);
            for (int i = 0; i < writes; i++) {
                int from = random.nextInt(200_000);
                int to = from + 1 + random.nextInt(random.nextBoolean() ? 64 : 8_000);
                file.markWritten(from, to);
                written.set(from, to);
                check(file, written);
            }
        }
    }

    @Test
    void writesInOrderMakeOneRange() {
        DataFile file = new DataFile("f", 0);
        for (int at = 0; at < 100_000; at += 100) {
            file.markWritten(at, at + 100);
        }
        assertThat(file.dirtyCount).isEqualTo(1);
        assertThat(file.dirty[0]).isZero();
        assertThat(file.dirty[1]).isEqualTo(100_000);
    }

    private static void check(DataFile file, BitSet written) {
        assertThat(file.dirtyCount).isBetween(1, DataFile.MAX_DIRTY_RANGES);
        BitSet covered = new BitSet();
        for (int i = 0; i < file.dirtyCount; i++) {
            int from = file.dirty[2 * i];
            int to = file.dirty[2 * i + 1];
            assertThat(from).isLessThan(to);
            if (i > 0) {
                assertThat(from).isGreaterThan(file.dirty[2 * i - 1] + DataFile.MERGE_GAP);
            }
            covered.set(from, to);
        }
        BitSet missed = (BitSet) written.clone();
        missed.andNot(covered);
        assertThat(missed.isEmpty()).isTrue();
    }
}
