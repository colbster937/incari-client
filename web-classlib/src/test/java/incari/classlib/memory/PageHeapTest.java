package incari.classlib.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PageHeapTest {

    private static final long MIB = 1024L * 1024L;

    @Test
    void reportsWhatTheBrowserReports() {
        assertThat(PageHeap.figures(4096.0 * MIB, 300.0 * MIB, 180.0 * MIB))
                .containsExactly(4096 * MIB, 300 * MIB, 120 * MIB);
    }

    @Test
    void keepsFreeWithinTotalAndTotalWithinMax() {
        // Chromium rounds the figures into buckets, so they can disagree slightly.
        assertThat(PageHeap.figures(100.0, 200.0, 250.0)).containsExactly(200L, 200L, 0L);
        assertThat(PageHeap.figures(100.0, 200.0, 0.0)).containsExactly(200L, 200L, 200L);
    }

    @Test
    void fallsBackToTeaVmsFiguresWithoutAReport() {
        long[] unknown = {PageHeap.UNKNOWN, PageHeap.UNKNOWN, PageHeap.UNKNOWN};
        assertThat(PageHeap.figures(-1, -1, -1)).containsExactly(unknown);
        assertThat(PageHeap.figures(4096.0 * MIB, -1, 10)).containsExactly(unknown);
        assertThat(PageHeap.figures(0, 100, 10)).containsExactly(unknown);
        assertThat(PageHeap.figures(Double.NaN, 100, 10)).containsExactly(unknown);
        assertThat(PageHeap.figures(100, 100, Double.NaN)).containsExactly(unknown);
    }
}
