package incari.classlib.substitutes.java.nio.file;

import java.io.Serial;
import java.nio.file.FileSystemException;
import org.jspecify.annotations.Nullable;

/** {@code java.nio.file.AtomicMoveNotSupportedException}, which TeaVM lacks. */
public class TAtomicMoveNotSupportedException extends FileSystemException {

    @Serial
    private static final long serialVersionUID = 5402760225333135579L;

    public TAtomicMoveNotSupportedException(@Nullable String source, @Nullable String target, @Nullable String reason) {
        super(source, target, reason);
    }
}
