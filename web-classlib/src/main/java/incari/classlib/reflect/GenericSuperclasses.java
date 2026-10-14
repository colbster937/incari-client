package incari.classlib.reflect;

import java.lang.reflect.Type;
import org.jspecify.annotations.Nullable;

/**
 * Generic superclasses the browser build keeps, for {@code Class.getGenericSuperclass}. A
 * compiled page has no generic signatures, but "type capture" relies on them: an anonymous
 * {@code new TypeToken<Map<String, Integer>>() {}} (Guava's and Gson's) reads its type argument
 * from its superclass, and DataFixerUpper and the game use such tokens. {@code tools/webbuild}
 * fills in {@link #of} for the direct subclasses of those bases; other classes answer null.
 */
public final class GenericSuperclasses {

    private GenericSuperclasses() {}

    /**
     * The generic superclass the build recorded for {@code cls}, or null. The web build replaces
     * this body; the web build refers to this class and method by name.
     */
    public static @Nullable Type of(Class<?> cls) {
        return null;
    }
}
