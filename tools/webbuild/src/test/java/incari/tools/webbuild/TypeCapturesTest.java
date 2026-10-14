package incari.tools.webbuild;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.classfile.Signature;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.teavm.model.ValueType;

/** The codes here are the ones web-classlib's GenericTypesTest checks against the JDK's types. */
class TypeCapturesTest {

    private static TypeCaptures.@org.jspecify.annotations.Nullable Capture encode(String signature) {
        return TypeCaptures.encode(Signature.parseFrom(signature));
    }

    @Test
    void parameterizedTypesAndWildcards() {
        assertThat(encode("Ljava/util/Map<Ljava/lang/String;Ljava/util/List<+Ljava/lang/Number;>;>;"))
                .isEqualTo(new TypeCaptures.Capture(
                        "PN2CPN1UC",
                        List.of(
                                ValueType.object("java.util.Map"),
                                ValueType.object("java.lang.String"),
                                ValueType.object("java.util.List"),
                                ValueType.object("java.lang.Number"))));
    }

    @Test
    void nestedOwnersGenericArraysAndLowerBounds() {
        assertThat(encode("Ljava/util/Map$Entry<[Ljava/util/List<Ljava/lang/String;>;-Ljava/lang/Integer;>;"))
                .isEqualTo(new TypeCaptures.Capture(
                        "PC2APN1CLC",
                        List.of(
                                ValueType.object("java.util.Map$Entry"),
                                ValueType.object("java.util.Map"),
                                ValueType.object("java.util.List"),
                                ValueType.object("java.lang.String"),
                                ValueType.object("java.lang.Integer"))));
    }

    @Test
    void arraysOfPlainClassesAreClasses() {
        assertThat(encode("Ljava/util/List<[Ljava/lang/String;>;"))
                .isEqualTo(new TypeCaptures.Capture(
                        "PN1C",
                        List.of(
                                ValueType.object("java.util.List"),
                                ValueType.arrayOf(ValueType.object("java.lang.String")))));
        assertThat(encode("Ljava/util/List<[I>;"))
                .isEqualTo(new TypeCaptures.Capture(
                        "PN1C", List.of(ValueType.object("java.util.List"), ValueType.parse("[I"))));
    }

    @Test
    void unboundedWildcardsAreObject() {
        assertThat(encode("Ljava/util/List<*>;"))
                .isEqualTo(new TypeCaptures.Capture(
                        "PN1UC", List.of(ValueType.object("java.util.List"), ValueType.object("java.lang.Object"))));
    }

    @Test
    void typeVariablesAreNotKept() {
        assertThat(encode("Ljava/util/List<TT;>;")).isNull();
    }

    @Test
    void nettyKeepsTheRawClassOfAGenericArgument() {
        Signature.ClassTypeSig encoder = (Signature.ClassTypeSig)
                Signature.parseFrom("Lio/netty/handler/codec/MessageToByteEncoder<Lnet/example/Packet<TT;>;>;");
        assertThat(TypeCaptures.encode(encoder)).isNull();
        assertThat(TypeCaptures.encode(TypeCaptures.rawArguments(encoder)))
                .isEqualTo(new TypeCaptures.Capture(
                        "PN1C",
                        List.of(
                                ValueType.object("io.netty.handler.codec.MessageToByteEncoder"),
                                ValueType.object("net.example.Packet"))));
    }

    @Test
    void nettyStillSkipsATypeVariableArgument() {
        Signature.ClassTypeSig encoder =
                (Signature.ClassTypeSig) Signature.parseFrom("Lio/netty/handler/codec/MessageToByteEncoder<TT;>;");
        assertThat(TypeCaptures.encode(TypeCaptures.rawArguments(encoder))).isNull();
    }
}
