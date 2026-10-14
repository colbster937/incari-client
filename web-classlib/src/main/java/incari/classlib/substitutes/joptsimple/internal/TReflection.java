package incari.classlib.substitutes.joptsimple.internal;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Function;
import joptsimple.ValueConversionException;
import joptsimple.ValueConverter;
import org.jspecify.annotations.Nullable;

/**
 * jopt-simple's {@code joptsimple.internal.Reflection} for the browser build, which has no
 * reflective lookup of {@code valueOf(String)} methods or {@code String} constructors: converters
 * for the value types the game's command line uses, written out. Any other type is refused as
 * jopt-simple refuses one without a converter.
 */
public final class TReflection {

    private TReflection() {}

    @SuppressWarnings("unchecked") // Each branch converts to exactly the requested class.
    public static <V> ValueConverter<V> findConverter(Class<V> clazz) {
        if (clazz == String.class) {
            return (ValueConverter<V>) converter(String.class, value -> value);
        }
        if (clazz == Integer.class) {
            return (ValueConverter<V>) converter(Integer.class, Integer::valueOf);
        }
        if (clazz == Long.class) {
            return (ValueConverter<V>) converter(Long.class, Long::valueOf);
        }
        if (clazz == Double.class) {
            return (ValueConverter<V>) converter(Double.class, Double::valueOf);
        }
        if (clazz == Float.class) {
            return (ValueConverter<V>) converter(Float.class, Float::valueOf);
        }
        if (clazz == Boolean.class) {
            return (ValueConverter<V>) converter(Boolean.class, Boolean::valueOf);
        }
        if (clazz == File.class) {
            return (ValueConverter<V>) converter(File.class, File::new);
        }
        throw new IllegalArgumentException(clazz + " is not a value type this browser build can convert");
    }

    private static <V> ValueConverter<V> converter(Class<V> type, Function<String, V> parse) {
        return new ValueConverter<>() {
            @Override
            public V convert(String value) {
                try {
                    return parse.apply(value);
                } catch (RuntimeException e) {
                    throw new ValueConversionException("Could not convert '" + value + "' to " + type.getName(), e);
                }
            }

            @Override
            public Class<? extends V> valueType() {
                return type;
            }

            @Override
            public @Nullable String valuePattern() {
                return null;
            }
        };
    }

    public static <T> T instantiate(Constructor<T> constructor, Object... args) {
        try {
            return constructor.newInstance(args);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
    }

    public static @Nullable Object invoke(Method method, Object... args) {
        try {
            return method.invoke(null, args);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked") // As jopt-simple's: without a converter the value is the string.
    public static <V> V convertWith(@Nullable ValueConverter<V> converter, String raw) {
        return converter == null ? (V) raw : converter.convert(raw);
    }
}
