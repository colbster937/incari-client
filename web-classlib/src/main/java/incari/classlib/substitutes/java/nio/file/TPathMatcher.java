package incari.classlib.substitutes.java.nio.file;

import java.nio.file.Path;

/** {@code java.nio.file.PathMatcher}, which TeaVM lacks. */
@FunctionalInterface
public interface TPathMatcher {
    boolean matches(Path path);
}
