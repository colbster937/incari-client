package incari.classlib.substitutes.java.nio.file.attribute;

import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import org.jspecify.annotations.Nullable;

/** {@code java.nio.file.attribute.BasicFileAttributeView}, which TeaVM lacks. */
public interface TBasicFileAttributeView extends TFileAttributeView {
    @Override
    String name();

    BasicFileAttributes readAttributes() throws IOException;

    void setTimes(@Nullable FileTime lastModifiedTime, @Nullable FileTime lastAccessTime, @Nullable FileTime createTime)
            throws IOException;
}
