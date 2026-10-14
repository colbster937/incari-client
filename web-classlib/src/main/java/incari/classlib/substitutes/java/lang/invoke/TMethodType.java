package incari.classlib.substitutes.java.lang.invoke;

import java.io.Serializable;
import java.util.List;

/**
 * {@code java.lang.invoke.MethodType}: a return type and parameter types, which libraries build
 * before a lookup (that then finds nothing, see {@link TMethodHandles}), sometimes outside the
 * guard around it.
 */
public final class TMethodType implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Class<?>[] NONE = {};

    private final Class<?> returnType;
    private final Class<?>[] parameterTypes;

    /** Public, as TeaVM's placeholder had it: a {@code void()} type. */
    public TMethodType() {
        this(void.class, NONE);
    }

    private TMethodType(Class<?> returnType, Class<?>[] parameterTypes) {
        this.returnType = returnType;
        this.parameterTypes = parameterTypes;
    }

    public static TMethodType methodType(Class<?> returnType) {
        return new TMethodType(returnType, NONE);
    }

    public static TMethodType methodType(Class<?> returnType, Class<?> parameterType) {
        return new TMethodType(returnType, new Class<?>[] {parameterType});
    }

    public static TMethodType methodType(Class<?> returnType, Class<?>[] parameterTypes) {
        return new TMethodType(returnType, parameterTypes.clone());
    }

    public static TMethodType methodType(Class<?> returnType, Class<?> first, Class<?>... rest) {
        Class<?>[] types = new Class<?>[rest.length + 1];
        types[0] = first;
        System.arraycopy(rest, 0, types, 1, rest.length);
        return new TMethodType(returnType, types);
    }

    public static TMethodType methodType(Class<?> returnType, List<Class<?>> parameterTypes) {
        return new TMethodType(returnType, parameterTypes.toArray(NONE));
    }

    public static TMethodType methodType(Class<?> returnType, TMethodType parameters) {
        return new TMethodType(returnType, parameters.parameterTypes);
    }

    public Class<?> returnType() {
        return returnType;
    }

    public int parameterCount() {
        return parameterTypes.length;
    }

    public Class<?> parameterType(int index) {
        return parameterTypes[index];
    }

    public Class<?>[] parameterArray() {
        return parameterTypes.clone();
    }

    public List<Class<?>> parameterList() {
        return List.of(parameterTypes);
    }
}
