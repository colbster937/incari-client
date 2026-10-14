package incari.classlib.substitutes.java.nio.file.attribute;

import java.nio.file.attribute.BasicFileAttributes;

/** {@code java.nio.file.attribute.DosFileAttributes}; the browser's storage has none. */
public interface TDosFileAttributes extends BasicFileAttributes {
    boolean isReadOnly();

    boolean isHidden();

    boolean isArchive();

    boolean isSystem();
}
