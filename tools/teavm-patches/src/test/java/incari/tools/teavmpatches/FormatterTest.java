package incari.tools.teavmpatches;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.teavm.classlib.java.util.TFormatter;

/**
 * Runs the patched {@link TFormatter} on the JVM and checks it against the JDK's own
 * {@code String.format}. The browser build's {@code String.format} is this class.
 */
class FormatterTest {

    private static final long MIB = 1024L * 1024L;

    /** Integer conversions: widths, zero padding, the ',' flag, signs, and extreme values. */
    static Object[][] decimalCases() {
        return new Object[][] {
            {"%d", new Object[] {Long.MIN_VALUE}},
            {"%d", new Object[] {Long.MAX_VALUE}},
            {"%d", new Object[] {Integer.MIN_VALUE}},
            {"%d", new Object[] {Integer.MAX_VALUE}},
            {"%d", new Object[] {(short) -32768}},
            {"%d", new Object[] {(byte) -128}},
            {"%d", new Object[] {-7L}},
            {"%d", new Object[] {0}},
            {"%25d", new Object[] {Long.MIN_VALUE}},
            {"%-25d|", new Object[] {Long.MIN_VALUE}},
            {"%025d", new Object[] {Long.MIN_VALUE}},
            {"%(d", new Object[] {Long.MIN_VALUE}},
            {"%+d %+d", new Object[] {5L, -5L}},
            {"% d % d", new Object[] {5, -5}},
            {"%03d", new Object[] {7L}},
            {"%03d", new Object[] {-7L}},
            {"%03d", new Object[] {123456L}},
            {"%2d", new Object[] {100L}},
            {"%,d", new Object[] {Long.MIN_VALUE}},
            {"%,d", new Object[] {Long.MAX_VALUE}},
            {"%,d", new Object[] {Integer.MIN_VALUE}},
            {"%,d", new Object[] {-1234567}},
            {"%,d", new Object[] {999}},
            {"%,d", new Object[] {1000}},
            {"%,15d|", new Object[] {-1234567L}},
            {"%-,15d|", new Object[] {1234567L}},
            {"%(,d", new Object[] {-1234567L}},
            // Memory figures as a heap reports them, in bytes and in MiB.
            {"%d %d", new Object[] {4_294_967_296L, 2_147_483_647L}},
            {"%03d/%03d", new Object[] {4_294_967_296L / MIB, 2_147_483_647L / MIB}},
            {"%03d", new Object[] {Long.MAX_VALUE / MIB}},
        };
    }

    @ParameterizedTest
    @MethodSource("decimalCases")
    void formatsIntegersLikeTheJdk(String format, Object[] args) {
        assertThat(teavm(format, args)).isEqualTo(String.format(Locale.ROOT, format, args));
    }

    /** '%%' and '%n' take no argument: the conversions after them read the right one. */
    static Object[][] literalCases() {
        return new Object[][] {
            {"%d%%", new Object[] {50L}},
            {"%2d%% %03d/%03d", new Object[] {3L, 120L, 4096L}},
            {"%2d%% %03d", new Object[] {100L, 2048L}},
            {"%%%d%%%s%%", new Object[] {1, "x"}},
            {"%5%|%-5%|", new Object[] {}},
            {"%d%n%d", new Object[] {1, 2}},
            {"%s %<s %% %s", new Object[] {"a", "b"}},
        };
    }

    @ParameterizedTest
    @MethodSource("literalCases")
    void literalsTakeNoArgument(String format, Object[] args) {
        assertThat(teavm(format, args)).isEqualTo(String.format(Locale.ROOT, format, args));
    }

    @Test
    void aMemoryLineFromRuntimeFiguresFormats() {
        // What the debug screen computes from Runtime's figures, including the degenerate ones
        // a heap without real numbers reports (everything free, nothing used).
        long[][] figures = {
            {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE},
            {4_294_705_152L, 268_435_456L, 12_582_912L},
            {Long.MAX_VALUE, Long.MAX_VALUE, 0L},
        };
        for (long[] f : figures) {
            long max = f[0];
            long total = f[1];
            long used = total - f[2];
            Object[] args = {used * 100L / max, used / MIB, max / MIB};
            String format = "Mem: %2d%% %03d/%03dMiB";
            assertThat(teavm(format, args)).isEqualTo(String.format(Locale.ROOT, format, args));
        }
    }

    @Test
    void tooFewArgumentsStillFails() {
        assertThatThrownBy(() -> teavm("%d %d", 1)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void percentRejectsPrecision() {
        assertThatThrownBy(() -> teavm("%.2%")).isInstanceOf(RuntimeException.class);
    }

    private static String teavm(String format, Object... args) {
        return new TFormatter(new StringBuilder(), Locale.ROOT)
                .format(format, args)
                .toString();
    }
}
