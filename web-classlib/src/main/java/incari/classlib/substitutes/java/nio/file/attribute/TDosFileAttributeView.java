package incari.classlib.substitutes.java.nio.file.attribute;

import java.io.IOException;

/** {@code java.nio.file.attribute.DosFileAttributeView}; the browser's storage has none. */
public interface TDosFileAttributeView extends TBasicFileAttributeView {
    @Override
    String name();

    @Override
    TDosFileAttributes readAttributes() throws IOException;

    void setReadOnly(boolean value) throws IOException;

    void setHidden(boolean value) throws IOException;

    void setSystem(boolean value) throws IOException;

    void setArchive(boolean value) throws IOException;
}
