package incari.platform.web;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Files dropped on the page, copied into the page's file system so the game can open them by path,
 * as it opens files dropped on a desktop window (the resource and data pack screens copy them into
 * their folders). A page gets the files' contents, never a path on the player's disk.
 *
 * <p>Each drop goes into a folder of its own under the root, and only the latest drop is kept:
 * the root is scratch space that storage never keeps (ADR 0011), the files take memory, and the
 * game copies what it wants (a pack into its resource pack folder, which is kept) while it handles
 * the drop.
 */
final class DroppedFiles {

    /** One drop's entries, as the page read them. */
    interface Source {
        int count();

        /** The entry's path relative to the drop, "/" between folders; the first part is what was dropped. */
        String path(int index);

        boolean isFolder(int index);

        byte[] bytes(int index);
    }

    private final File root;
    private @Nullable File latest;

    DroppedFiles(File root) {
        this.root = root;
    }

    /**
     * Copies a drop into the file system.
     *
     * @param id the drop's id, unique within the page's life
     * @return the paths of the dropped files and folders themselves (not their contents), in the
     *     order they were dropped
     */
    List<String> store(int id, Source source) throws IOException {
        File previous = latest;
        if (previous != null) {
            deleteRecursively(previous);
        }
        File directory = new File(root, Integer.toString(id));
        latest = directory;
        Set<String> dropped = new LinkedHashSet<>();
        for (int i = 0; i < source.count(); i++) {
            String relative = safePath(source.path(i));
            if (relative == null) {
                continue;
            }
            File file = new File(directory, relative);
            if (source.isFolder(i)) {
                makeDirectory(file);
            } else {
                File parent = file.getParentFile();
                if (parent != null) {
                    makeDirectory(parent);
                }
                try (OutputStream out = new FileOutputStream(file)) {
                    out.write(source.bytes(i));
                }
            }
            int slash = relative.indexOf('/');
            dropped.add(new File(directory, slash < 0 ? relative : relative.substring(0, slash)).getPath());
        }
        return new ArrayList<>(dropped);
    }

    /**
     * A dropped entry's path made safe to put under the drop's folder: no empty, "." or ".." parts
     * (a page mustn't be able to write outside it), and no characters file systems reject. Null if
     * nothing is left.
     */
    static @Nullable String safePath(String path) {
        StringBuilder safe = new StringBuilder(path.length());
        int start = 0;
        while (start <= path.length()) {
            int end = path.indexOf('/', start);
            if (end < 0) {
                end = path.length();
            }
            String part = path.substring(start, end);
            if (!part.isEmpty() && !part.equals(".") && !part.equals("..")) {
                if (safe.length() > 0) {
                    safe.append('/');
                }
                for (int i = 0; i < part.length(); i++) {
                    char c = part.charAt(i);
                    safe.append(c < 0x20 || c == 0x7F || "\\:*?\"<>|".indexOf(c) >= 0 ? '_' : c);
                }
            }
            start = end + 1;
        }
        return safe.length() == 0 ? null : safe.toString();
    }

    private static void makeDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Can't create " + directory);
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        // Best effort: a file left behind costs memory, nothing else.
        file.delete();
    }
}
