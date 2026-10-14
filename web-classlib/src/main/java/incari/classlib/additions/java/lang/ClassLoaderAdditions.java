package incari.classlib.additions.java.lang;

import incari.classlib.InstanceMethod;
import incari.classlib.Replaces;
import incari.classlib.resources.ClassPathResources;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.lang.ClassLoader} methods TeaVM lacks. A compiled page has one fixed set of classes,
 * so classes can't be looked up by name; resources come from {@link ClassPathResources}.
 */
public final class ClassLoaderAdditions {

    private ClassLoaderAdditions() {}

    @InstanceMethod
    public static @Nullable URL getResource(ClassLoader self, String name) {
        return ClassPathResources.url(ClassPathResources.find(name));
    }

    public static @Nullable URL getSystemResource(String name) {
        return ClassPathResources.url(ClassPathResources.find(name));
    }

    /** TeaVM's reads resources compiled into the module; the game's are in the file system. */
    @InstanceMethod
    @Replaces
    public static @Nullable InputStream getResourceAsStream(ClassLoader self, String name) {
        return ClassPathResources.open(ClassPathResources.find(name));
    }

    /**
     * Nothing: the game looks for extra resource roots this way and uses only {@code file:} ones,
     * while on desktop it finds the jar's ({@code jar:}), so the answer that matches is none. The
     * jar's own root comes through {@link #getResource}.
     */
    @InstanceMethod
    public static Enumeration<URL> getResources(ClassLoader self, String name) {
        return Collections.emptyEnumeration();
    }

    public static Enumeration<URL> getSystemResources(String name) {
        return Collections.emptyEnumeration();
    }

    @InstanceMethod
    public static Class<?> loadClass(ClassLoader self, String name) throws ClassNotFoundException {
        throw new ClassNotFoundException(name + " (classes can't be loaded by name in the browser build)");
    }
}
