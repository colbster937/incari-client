package incari.classlib.substitutes.java.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** Checks {@link THexFormat} against the JDK's {@link HexFormat}. */
class HexFormatTest {

    private static final byte[] BYTES = {0, 1, 0x7F, (byte) 0x80, (byte) 0xAB, (byte) 0xFF};

    @Test
    void formatsLikeTheJdk() {
        assertThat(THexFormat.of().formatHex(BYTES)).isEqualTo(HexFormat.of().formatHex(BYTES));
        assertThat(THexFormat.of().withUpperCase().formatHex(BYTES))
                .isEqualTo(HexFormat.of().withUpperCase().formatHex(BYTES));
        assertThat(THexFormat.ofDelimiter(":").withPrefix("#").withSuffix(";").formatHex(BYTES))
                .isEqualTo(HexFormat.ofDelimiter(":")
                        .withPrefix("#")
                        .withSuffix(";")
                        .formatHex(BYTES));
        assertThat(THexFormat.of().formatHex(BYTES, 1, 4))
                .isEqualTo(HexFormat.of().formatHex(BYTES, 1, 4));
    }

    @Test
    void parsesWhatItFormats() {
        THexFormat format = THexFormat.ofDelimiter(", ").withPrefix("0x").withUpperCase();
        assertThat(format.parseHex(format.formatHex(BYTES))).containsExactly(BYTES);
        assertThat(THexFormat.of().parseHex("")).isEmpty();
        assertThatThrownBy(() -> THexFormat.of().parseHex("abc")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void numbersLikeTheJdk() {
        HexFormat jdk = HexFormat.of();
        THexFormat ours = THexFormat.of();
        assertThat(ours.toHexDigits((byte) 0xA5)).isEqualTo(jdk.toHexDigits((byte) 0xA5));
        assertThat(ours.toHexDigits('Z')).isEqualTo(jdk.toHexDigits('Z'));
        assertThat(ours.toHexDigits((short) -2)).isEqualTo(jdk.toHexDigits((short) -2));
        assertThat(ours.toHexDigits(0x12AB)).isEqualTo(jdk.toHexDigits(0x12AB));
        assertThat(ours.toHexDigits(-1L)).isEqualTo(jdk.toHexDigits(-1L));
        assertThat(ours.toHexDigits(0xABCDEFL, 4)).isEqualTo(jdk.toHexDigits(0xABCDEFL, 4));
        assertThat(ours.withUpperCase().toHexDigits(0xBEEF))
                .isEqualTo(jdk.withUpperCase().toHexDigits(0xBEEF));
        assertThat(THexFormat.fromHexDigits("7fFf")).isEqualTo(HexFormat.fromHexDigits("7fFf"));
        assertThat(THexFormat.fromHexDigitsToLong("ffffffffffffffff", 2, 16))
                .isEqualTo(HexFormat.fromHexDigitsToLong("ffffffffffffffff", 2, 16));
        assertThat(THexFormat.isHexDigit('g')).isFalse();
        assertThatThrownBy(() -> THexFormat.fromHexDigit('g')).isInstanceOf(NumberFormatException.class);
    }
}
