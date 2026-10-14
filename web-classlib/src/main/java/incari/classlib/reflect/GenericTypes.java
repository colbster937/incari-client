package incari.classlib.reflect;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Builds {@link Type}s from the compact form {@code tools/webbuild} records them in: a string of
 * one-letter tags, and the classes it names in order of appearance (class literals, since a page
 * can't look classes up by name).
 *
 * <ul>
 *   <li>{@code C}: the next class;
 *   <li>{@code P} owner count args...: a parameterized type of the next class; owner is {@code N}
 *       (none) or a type; count is one digit;
 *   <li>{@code A} component: a generic array;
 *   <li>{@code U} bound / {@code L} bound: a wildcard with that upper / lower bound ({@code ?} is
 *       {@code U} of {@code Object}).
 * </ul>
 *
 * <p>Equality and hash codes follow the JDK's contracts, so these compare equal to the JDK's own
 * (and Guava's, and Gson's) types for the same thing.
 */
public final class GenericTypes {

    private final String code;
    private final Class<?>[] classes;
    private int position;
    private int nextClass;

    private GenericTypes(String code, Class<?>[] classes) {
        this.code = code;
        this.classes = classes;
    }

    /** The type {@code code} describes; the web build calls this from {@link GenericSuperclasses}. */
    public static Type parse(String code, Class<?>[] classes) {
        GenericTypes parser = new GenericTypes(code, classes);
        Type type = parser.type();
        if (parser.position != code.length() || parser.nextClass != classes.length) {
            throw new IllegalArgumentException("Malformed type code " + code);
        }
        return type;
    }

    private Type type() {
        char tag = code.charAt(position++);
        return switch (tag) {
            case 'C' -> classes[nextClass++];
            case 'P' -> {
                Class<?> raw = classes[nextClass++];
                Type owner = code.charAt(position) == 'N' ? skipNone() : type();
                Type[] arguments = new Type[code.charAt(position++) - '0'];
                for (int i = 0; i < arguments.length; i++) {
                    arguments[i] = type();
                }
                yield new Parameterized(raw, owner, arguments);
            }
            case 'A' -> new GenericArray(type());
            case 'U' -> new Wildcard(new Type[] {type()}, new Type[0]);
            case 'L' -> new Wildcard(new Type[] {Object.class}, new Type[] {type()});
            default -> throw new IllegalArgumentException("Malformed type code " + code);
        };
    }

    private @Nullable Type skipNone() {
        position++;
        return null;
    }

    private static String name(Type type) {
        return type instanceof Class<?> cls ? cls.getName() : type.toString();
    }

    private static final class Parameterized implements ParameterizedType {
        private final Class<?> raw;
        private final @Nullable Type owner;
        private final Type[] arguments;

        Parameterized(Class<?> raw, @Nullable Type owner, Type[] arguments) {
            this.raw = raw;
            this.owner = owner;
            this.arguments = arguments;
        }

        @Override
        public Type[] getActualTypeArguments() {
            return arguments.clone();
        }

        @Override
        public Type getRawType() {
            return raw;
        }

        @Override
        public @Nullable Type getOwnerType() {
            return owner;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            return o instanceof ParameterizedType that
                    && raw.equals(that.getRawType())
                    && Objects.equals(owner, that.getOwnerType())
                    && Arrays.equals(arguments, that.getActualTypeArguments());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(arguments) ^ Objects.hashCode(owner) ^ raw.hashCode();
        }

        @Override
        public String toString() {
            StringBuilder text = new StringBuilder(raw.getName()).append('<');
            for (int i = 0; i < arguments.length; i++) {
                text.append(i == 0 ? "" : ", ").append(name(arguments[i]));
            }
            return text.append('>').toString();
        }
    }

    private static final class GenericArray implements GenericArrayType {
        private final Type component;

        GenericArray(Type component) {
            this.component = component;
        }

        @Override
        public Type getGenericComponentType() {
            return component;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            return o instanceof GenericArrayType that && component.equals(that.getGenericComponentType());
        }

        @Override
        public int hashCode() {
            return component.hashCode();
        }

        @Override
        public String toString() {
            return name(component) + "[]";
        }
    }

    private static final class Wildcard implements WildcardType {
        private final Type[] upper;
        private final Type[] lower;

        Wildcard(Type[] upper, Type[] lower) {
            this.upper = upper;
            this.lower = lower;
        }

        @Override
        public Type[] getUpperBounds() {
            return upper.clone();
        }

        @Override
        public Type[] getLowerBounds() {
            return lower.clone();
        }

        @Override
        public boolean equals(@Nullable Object o) {
            return o instanceof WildcardType that
                    && Arrays.equals(upper, that.getUpperBounds())
                    && Arrays.equals(lower, that.getLowerBounds());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(upper) ^ Arrays.hashCode(lower);
        }

        @Override
        public String toString() {
            if (lower.length > 0) {
                return "? super " + name(lower[0]);
            }
            return upper[0] == Object.class ? "?" : "? extends " + name(upper[0]);
        }
    }
}
