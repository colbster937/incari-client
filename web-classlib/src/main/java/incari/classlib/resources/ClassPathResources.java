package incari.classlib.resources;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The class path's resources in the browser build: files under {@link #ROOT} in the page's file
 * system, then under any root an entry point adds. The build compiles classes only, so whatever
 * the game reads with {@code Class.getResource} (its language file, {@code version.json}, the
 * vanilla resource and data packs) is mounted there from the client jar before the game starts, by
 * the entry point; libraries' resources (Via's mapping data) come from the build's asset pack.
 */
public final class ClassPathResources {

    /** The directory that stands in for the class path's root. */
    public static final String ROOT = "/incari/classpath";

    /** More directories searched after {@link #ROOT}, in order; set up before the game starts. */
    private static final List<String> MORE_ROOTS = new ArrayList<>();

    private ClassPathResources() {}

    /** Makes {@code directory} part of the class path, after the roots before it. */
    public static void addRoot(String directory) {
        MORE_ROOTS.add(directory);
    }

    /**
     * The file for a resource, as {@code Class.getResource} names it: absolute with a leading
     * slash, otherwise relative to the class's package.
     */
    public static @Nullable File find(Class<?> caller, String name) {
        return find(qualified(caller, name));
    }

    /** The file for a resource named from the class path's root, as class loaders name them. */
    public static @Nullable File find(String name) {
        File file = new File(ROOT, name);
        if (file.isFile() || file.isDirectory()) {
            return file;
        }
        for (String root : MORE_ROOTS) {
            File other = new File(root, name);
            if (other.isFile() || other.isDirectory()) {
                return other;
            }
        }
        return null;
    }

    /** A {@code file:} URL for the resource, which {@code Paths.get(URI)} turns into a path. */
    public static @Nullable URL url(@Nullable File file) {
        if (file == null) {
            return null;
        }
        try {
            return file.toURI().toURL();
        } catch (MalformedURLException e) {
            return null;
        }
    }

    public static @Nullable InputStream open(@Nullable File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            return new FileInputStream(file);
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    static String qualified(Class<?> caller, String name) {
        if (name.startsWith("/")) {
            return name.substring(1);
        }
        String className = caller.getName();
        int lastDot = className.lastIndexOf('.');
        return lastDot < 0 ? name : className.substring(0, lastDot).replace('.', '/') + "/" + name;
    }
}
