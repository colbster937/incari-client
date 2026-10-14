package incari.classlib.reflect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GenericTypesTest {

    /** A stand-in for a type-capture base: the JDK records its subclasses' type arguments. */
    @SuppressWarnings("unused")
    private abstract static class Capture<T> {}

    private static Type captured(Capture<?> capture) {
        return ((ParameterizedType) capture.getClass().getGenericSuperclass()).getActualTypeArguments()[0];
    }

    @Test
    void matchesTheJdksTypes() {
        Type jdk = captured(new Capture<Map<String, List<? extends Number>>>() {});
        Type ours = GenericTypes.parse("PN2CPN1UC", new Class<?>[] {Map.class, String.class, List.class, Number.class});
        assertThat(ours).isEqualTo(jdk);
        assertThat(jdk).isEqualTo(ours);
        assertThat(ours.hashCode()).isEqualTo(jdk.hashCode());
        assertThat(ours.toString())
                .isEqualTo("java.util.Map<java.lang.String, java.util.List<? extends java.lang.Number>>");
    }

    @Test
    void ownersArraysAndLowerBounds() {
        Type jdk = captured(new Capture<Map.Entry<List<String>[], ? super Integer>>() {});
        Type ours = GenericTypes.parse(
                "PC2APN1CLC", new Class<?>[] {Map.Entry.class, Map.class, List.class, String.class, Integer.class});
        assertThat(ours).isEqualTo(jdk);
        assertThat(jdk).isEqualTo(ours);
        assertThat(ours.hashCode()).isEqualTo(jdk.hashCode());
    }

    @Test
    void plainClassesAreThemselves() {
        assertThat(GenericTypes.parse("C", new Class<?>[] {String.class})).isEqualTo(String.class);
    }

    @Test
    void malformedCodesFail() {
        assertThatThrownBy(() -> GenericTypes.parse("CC", new Class<?>[] {String.class, String.class, String.class}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
