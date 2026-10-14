package incari.tools.survey;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Knows where each class comes from: the Minecraft client jar, one of its libraries, TeaVM's
 * browser class library, or (the case worth flagging) the JDK running the survey.
 *
 * <p>That last one matters because TeaVM compiles a {@code java.*} class straight from the
 * host JDK when its own class library has no replacement, dragging in JDK internals
 * ({@code jdk.internal.*}, {@code sun.*}) that were never meant to run in a browser. The
 * survey hides those classes from TeaVM and reports each use instead.
 */
public final class Origins {

    public static final String MINECRAFT = "minecraft";
    public static final String TEAVM = "teavm-classlib";
    public static final String HOST_JDK = "host-jdk";
    /** Nowhere: a library the rules leave off the classpath, or a class nobody ships. */
    public static final String ABSENT = "absent";

    private final Map<String, String> appClasses = new HashMap<>();
    private final Map<String, Boolean> classlibCache = new HashMap<>();
    private final Map<String, Boolean> jdkCache = new HashMap<>();
    private final ClassLoader teavmLoader;

    /**
     * @param jars      each app jar (client and kept libraries) mapped to its origin name
     * @param teavmLoader a loader that can see TeaVM's class library
     */
    public Origins(Map<Path, String> jars, ClassLoader teavmLoader) throws IOException {
        this.teavmLoader = teavmLoader;
        for (Map.Entry<Path, String> jar : jars.entrySet()) {
            try (JarFile file = new JarFile(jar.getKey().toFile())) {
                Enumeration<JarEntry> entries = file.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
                        appClasses.putIfAbsent(
                                name.substring(0, name.length() - 6).replace('/', '.'), jar.getValue());
                    }
                }
            }
        }
    }

    /** {@link #MINECRAFT}, a library name, {@link #TEAVM}, {@link #HOST_JDK} or {@link #ABSENT}. */
    public String of(String className) {
        String app = appClasses.get(appClass(className));
        if (app != null) {
            return app;
        }
        if (isInClasslib(className)) {
            return TEAVM;
        }
        return isInJdk(className) ? HOST_JDK : ABSENT;
    }

    /** True for code Incari compiles as part of the game: the client and its kept libraries. */
    public boolean isApp(String className) {
        return appClasses.containsKey(appClass(className));
    }

    /**
     * The jar class behind a name. TeaVM compiles lambdas into synthetic classes named after
     * their enclosing class ({@code net.minecraft.util.Util$makeIoExecutor$lambda$_20_0}) that
     * exist in no jar; those belong to the enclosing class.
     */
    public String appClass(String className) {
        if (appClasses.containsKey(className)) {
            return className;
        }
        int lambda = className.indexOf("$lambda$");
        if (lambda > 0) {
            String outer = className.substring(0, lambda);
            for (int dollar = outer.length(); dollar > 0; dollar = outer.lastIndexOf('$', dollar - 1)) {
                String candidate = outer.substring(0, dollar);
                if (appClasses.containsKey(candidate)) {
                    return candidate;
                }
            }
        }
        return className;
    }

    private boolean isInJdk(String className) {
        return jdkCache.computeIfAbsent(
                className,
                name -> ClassLoader.getPlatformClassLoader().getResource(name.replace('.', '/') + ".class") != null);
    }

    private boolean isInClasslib(String className) {
        if (className.startsWith("org.teavm.")) {
            return true;
        }
        String outer = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
        return classlibCache.computeIfAbsent(outer, name -> {
            // TeaVM's naming scheme: java.util.ArrayList -> org.teavm.classlib.java.util.TArrayList.
            // Internal helpers skip the T (org.teavm.classlib.java.util.TemplateCollections).
            int dot = name.lastIndexOf('.');
            String dir = "org/teavm/classlib/" + name.substring(0, dot + 1).replace('.', '/');
            String simple = name.substring(dot + 1);
            return teavmLoader.getResource(dir + "T" + simple + ".class") != null
                    || teavmLoader.getResource(dir + simple + ".class") != null;
        });
    }
}
