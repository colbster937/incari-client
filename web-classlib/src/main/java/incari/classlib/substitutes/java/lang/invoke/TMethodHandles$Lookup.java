package incari.classlib.substitutes.java.lang.invoke;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * {@code java.lang.invoke.MethodHandles.Lookup} that finds nothing (see {@link TMethodHandles}).
 * A top-level class named for the nested one, so it takes the name
 * {@code java.lang.invoke.MethodHandles$Lookup}.
 */
@SuppressWarnings({"TypeName", "unused"}) // The JDK's nested name; parameters match JDK signatures.
public final class TMethodHandles$Lookup {

    static final TMethodHandles$Lookup NOTHING = new TMethodHandles$Lookup();

    /** Public, as TeaVM's placeholder had it. */
    public TMethodHandles$Lookup() {}

    public Class<?> lookupClass() {
        return Object.class;
    }

    public TMethodHandles$Lookup in(Class<?> requestedLookupClass) {
        return this;
    }

    public TMethodHandle findStatic(Class<?> owner, String name, TMethodType type)
            throws NoSuchMethodException, IllegalAccessException {
        throw noMethod(owner, name);
    }

    public TMethodHandle findVirtual(Class<?> owner, String name, TMethodType type)
            throws NoSuchMethodException, IllegalAccessException {
        throw noMethod(owner, name);
    }

    public TMethodHandle findConstructor(Class<?> owner, TMethodType type)
            throws NoSuchMethodException, IllegalAccessException {
        throw noMethod(owner, "<init>");
    }

    public TMethodHandle findSpecial(Class<?> owner, String name, TMethodType type, Class<?> caller)
            throws NoSuchMethodException, IllegalAccessException {
        throw noMethod(owner, name);
    }

    public TMethodHandle findGetter(Class<?> owner, String name, Class<?> type)
            throws NoSuchFieldException, IllegalAccessException {
        throw noField(owner, name);
    }

    public TMethodHandle findSetter(Class<?> owner, String name, Class<?> type)
            throws NoSuchFieldException, IllegalAccessException {
        throw noField(owner, name);
    }

    public TMethodHandle findStaticGetter(Class<?> owner, String name, Class<?> type)
            throws NoSuchFieldException, IllegalAccessException {
        throw noField(owner, name);
    }

    public TMethodHandle findStaticSetter(Class<?> owner, String name, Class<?> type)
            throws NoSuchFieldException, IllegalAccessException {
        throw noField(owner, name);
    }

    public TMethodHandle unreflect(Method method) throws IllegalAccessException {
        throw unavailable();
    }

    public TMethodHandle unreflectConstructor(Constructor<?> constructor) throws IllegalAccessException {
        throw unavailable();
    }

    public TMethodHandle unreflectGetter(Field field) throws IllegalAccessException {
        throw unavailable();
    }

    public TMethodHandle unreflectSetter(Field field) throws IllegalAccessException {
        throw unavailable();
    }

    private static NoSuchMethodException noMethod(Class<?> owner, String name) {
        return new NoSuchMethodException(owner.getName() + "." + name + ": no method handles in the browser build");
    }

    private static NoSuchFieldException noField(Class<?> owner, String name) {
        return new NoSuchFieldException(owner.getName() + "." + name + ": no method handles in the browser build");
    }

    private static IllegalAccessException unavailable() {
        return new IllegalAccessException("Method handles aren't available in the browser build");
    }
}
