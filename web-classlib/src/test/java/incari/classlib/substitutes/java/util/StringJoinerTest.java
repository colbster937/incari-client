package incari.classlib.substitutes.java.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StringJoinerTest {

    @Test
    void joinsWithPrefixAndSuffix() {
        TStringJoiner joiner = new TStringJoiner(", ", "[", "]");
        assertThat(joiner.toString()).isEqualTo("[]");
        joiner.add("a").add("b").add(null);
        assertThat(joiner.toString()).isEqualTo("[a, b, null]");
        assertThat(joiner.length()).isEqualTo("[a, b, null]".length());
    }

    @Test
    void usesTheEmptyValueAndMerges() {
        TStringJoiner first = new TStringJoiner("-").setEmptyValue("none");
        assertThat(first.toString()).isEqualTo("none");
        TStringJoiner second = new TStringJoiner("+", "(", ")").add("x").add("y");
        first.add("a").merge(second).merge(first);
        assertThat(first.toString()).isEqualTo("a-x+y-a-x+y");
    }
}
