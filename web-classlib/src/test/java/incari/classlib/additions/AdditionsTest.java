package incari.classlib.additions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import incari.classlib.additions.java.io.ReaderAdditions;
import incari.classlib.additions.java.lang.CharacterAdditions;
import incari.classlib.additions.java.lang.IntegerAdditions;
import incari.classlib.additions.java.lang.LongAdditions;
import incari.classlib.additions.java.lang.MathAdditions;
import incari.classlib.additions.java.lang.StringAdditions;
import incari.classlib.additions.java.lang.reflect.ArrayAdditions;
import incari.classlib.additions.java.nio.ByteBufferAdditions;
import incari.classlib.additions.java.util.LocaleAdditions;
import incari.classlib.additions.java.util.concurrent.TimeUnitAdditions;
import incari.classlib.additions.java.util.regex.PatternAdditions;
import incari.classlib.additions.java.util.stream.StreamSupportAdditions;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Spliterators;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The additions against the JDK methods they stand in for, where the JDK has them. */
class AdditionsTest {

    @Test
    void parsesUnsignedNumbersLikeTheJdk() {
        for (String text : new String[] {"0", "4294967295", "2147483648"}) {
            assertThat(IntegerAdditions.parseUnsignedInt(text, 10)).isEqualTo(Integer.parseUnsignedInt(text, 10));
        }
        assertThat(LongAdditions.parseUnsignedLong("ffffffffffffffff", 16))
                .isEqualTo(Long.parseUnsignedLong("ffffffffffffffff", 16));
        assertThatThrownBy(() -> IntegerAdditions.parseUnsignedInt("4294967296", 10))
                .isInstanceOf(NumberFormatException.class);
        assertThatThrownBy(() -> LongAdditions.parseUnsignedLong("-1", 10)).isInstanceOf(NumberFormatException.class);
    }

    @Test
    void splitsLinesLikeTheJdk() {
        for (String text : new String[] {"", "a", "a\n", "a\r\nb\rc\n\nd", "\n\n"}) {
            assertThat(StringAdditions.lines(text).toList())
                    .isEqualTo(text.lines().toList());
        }
    }

    @Test
    @SuppressWarnings("deprecation") // The JDK constructor the addition stands in for.
    void buildsStringsFromHighAndLowBytesLikeTheJdk() {
        byte[] bytes = {'a', (byte) 0xE9, 0, (byte) 0xFF, 'z'};
        for (int hibyte : new int[] {0, 1, 0x1F6, -1}) {
            assertThat(new String(StringAdditions.characters(bytes, hibyte, 1, 3)))
                    .isEqualTo(new String(bytes, hibyte, 1, 3));
        }
        assertThatThrownBy(() -> StringAdditions.characters(bytes, 0, 3, 3))
                .isInstanceOf(StringIndexOutOfBoundsException.class);
    }

    @Test
    void smallJdkMethods() {
        assertThat(CharacterAdditions.toString(0x1F600)).isEqualTo(Character.toString(0x1F600));
        assertThat(MathAdditions.powExact(3, 4)).isEqualTo(81);
        assertThatThrownBy(() -> MathAdditions.powExact(10, 10)).isInstanceOf(ArithmeticException.class);
        assertThat(MathAdditions.fma(2.0F, 3.0F, 1.0F)).isEqualTo(7.0F);
        assertThat(PatternAdditions.asPredicate(Pattern.compile("b+")).test("abc"))
                .isTrue();
        assertThat(PatternAdditions.asMatchPredicate(Pattern.compile("b+")).test("abc"))
                .isFalse();
    }

    @Test
    void readerTransfersAndReadsAll() throws Exception {
        StringWriter out = new StringWriter();
        assertThat(ReaderAdditions.transferTo(new StringReader("hello"), out)).isEqualTo(5);
        assertThat(out.toString()).isEqualTo("hello");
        assertThat(ReaderAdditions.readAllAsString(new StringReader("x".repeat(20_000))))
                .hasSize(20_000);
    }

    @Test
    void slicesAByteBufferLikeTheJdk() {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5});
        buffer.position(4);
        ByteBuffer ours = ByteBufferAdditions.slice(buffer, 1, 3);
        ByteBuffer jdk = buffer.slice(1, 3);
        assertThat(ours).isEqualTo(jdk);
        assertThat(buffer.position()).isEqualTo(4);
    }

    @Test
    void makesMultiDimensionalArrays() {
        int[][][] array = (int[][][]) ArrayAdditions.newInstance(int.class, 2, 3, 4);
        assertThat(array).hasDimensions(2, 3);
        assertThat(array[1][2]).hasSize(4);
    }

    @Test
    void streamsPrimitiveSpliterators() {
        assertThat(StreamSupportAdditions.intStream(Spliterators.spliterator(new int[] {1, 2, 3}, 0), false)
                        .sum())
                .isEqualTo(6);
        assertThat(StreamSupportAdditions.longStream(Spliterators.spliterator(new long[] {4, 5}, 0), true)
                        .boxed()
                        .toList())
                .isEqualTo(List.of(4L, 5L));
    }

    @Test
    void readsLanguageTags() {
        for (String tag : new String[] {"en-US", "en", "pt-BR", "zh-Hant-TW", "es-419", "und", "de-DE-1996"}) {
            Locale ours = LocaleAdditions.forLanguageTag(tag);
            Locale jdk = Locale.forLanguageTag(tag);
            assertThat(ours.getLanguage()).as(tag).isEqualTo(jdk.getLanguage());
            assertThat(ours.getCountry()).as(tag).isEqualTo(jdk.getCountry());
            assertThat(ours.getVariant()).as(tag).isEqualTo(jdk.getVariant());
        }
    }

    @Test
    void convertsDurationsLikeTheJdk() {
        Duration[] durations = {
            Duration.ZERO,
            Duration.ofMinutes(1),
            Duration.ofNanos(1_500_000),
            Duration.ofNanos(-1_500_000),
            Duration.ofSeconds(-3, 999_999_999),
            Duration.ofSeconds(Long.MAX_VALUE, 999_999_999),
            Duration.ofSeconds(Long.MIN_VALUE),
        };
        for (TimeUnit unit : TimeUnit.values()) {
            for (Duration duration : durations) {
                assertThat(TimeUnitAdditions.convert(unit, duration))
                        .as("%s in %s", duration, unit)
                        .isEqualTo(unit.convert(duration));
            }
        }
    }
}
