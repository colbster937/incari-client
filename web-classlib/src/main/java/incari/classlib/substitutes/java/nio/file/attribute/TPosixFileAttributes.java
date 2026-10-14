package incari.classlib.substitutes.java.nio.file.attribute;

import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/** {@code java.nio.file.attribute.PosixFileAttributes}; the browser's storage has none. */
public interface TPosixFileAttributes extends BasicFileAttributes {
    TUserPrincipal owner();

    TGroupPrincipal group();

    Set<TPosixFilePermission> permissions();
}
