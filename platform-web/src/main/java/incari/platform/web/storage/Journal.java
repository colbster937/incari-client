package incari.platform.web.storage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What changed in the file tree since the last flush, and so what the persistent store must be
 * told. It keeps no log of operations, only which paths changed shape (created, deleted, moved)
 * and which files had bytes written: a flush reads the tree as it is then, so a file rewritten
 * a hundred times in a frame is sent once, and a temporary file created and renamed within one
 * is never sent under its temporary name.
 *
 * <p>Only paths under the persistent root, and outside its transient folders, are recorded.
 */
final class Journal {

    private final String persistentRoot;
    private final List<String> transientRoots;
    private final Set<String> reshaped = new HashSet<>();
    private final List<DataFile> written = new ArrayList<>();

    /**
     * @param persistentRoot the folder whose contents survive a reload
     * @param transientRoots folders inside it that don't (scratch space, mounts)
     */
    Journal(String persistentRoot, List<String> transientRoots) {
        this.persistentRoot = persistentRoot;
        this.transientRoots = List.copyOf(transientRoots);
    }

    String persistentRoot() {
        return persistentRoot;
    }

    boolean persists(String path) {
        if (!within(path, persistentRoot)) {
            return false;
        }
        for (String transientRoot : transientRoots) {
            if (within(path, transientRoot)) {
                return false;
            }
        }
        return true;
    }

    boolean isEmpty() {
        return reshaped.isEmpty() && written.isEmpty();
    }

    /** Something was created, deleted or moved at {@code path}. */
    void reshaped(@Nullable String path) {
        if (path != null && persists(path)) {
            reshaped.add(path);
        }
    }

    /** {@code file} had bytes written, its size changed or its time set. Hot path: no allocation. */
    void written(DataFile file) {
        if (!file.queued) {
            file.queued = true;
            written.add(file);
        }
    }

    /**
     * The changes since the last call, in the order the store should apply them: folders before
     * what's in them, writes before deletes (so a file replaced by a rename is never missing in
     * between), and nothing twice.
     */
    List<StoreOp> collect(TreeFileSystem tree) {
        List<StoreOp> ops = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        Set<String> sent = new HashSet<>();
        String[] paths = reshaped.toArray(new String[0]);
        // Shallow paths first: a folder's whole contents are sent with it.
        Arrays.sort(paths, Comparator.comparingInt(Journal::depth).thenComparing(Comparator.naturalOrder()));
        for (String path : paths) {
            if (sent.contains(path)) {
                continue;
            }
            Node node = tree.find(path);
            if (node == null) {
                deleted.add(path);
            } else {
                sendWhole(node, path, ops, sent);
            }
        }
        for (DataFile file : written) {
            String path = file.path();
            if (path != null && !sent.contains(path) && persists(path)) {
                // A write op for each range still inside the file; one without bytes if none is
                // (the size or the time changed).
                boolean wrote = false;
                for (int i = 0; i < file.dirtyCount; i++) {
                    int from = file.dirty[2 * i];
                    int end = Math.min(file.dirty[2 * i + 1], file.size);
                    if (from < end) {
                        ops.add(StoreOp.write(
                                path, file.size, from, Arrays.copyOfRange(file.data, from, end), file.lastModified));
                        wrote = true;
                    }
                }
                if (!wrote) {
                    ops.add(StoreOp.write(path, file.size, 0, null, file.lastModified));
                }
            }
            file.clearDirty();
        }
        // A deleted folder takes what was in it; don't delete its contents one by one as well.
        Set<String> deletedSet = new HashSet<>(deleted);
        for (String path : deleted) {
            if (!hasDeletedAncestor(path, deletedSet)) {
                ops.add(StoreOp.delete(path));
            }
        }
        reshaped.clear();
        written.clear();
        return ops;
    }

    private void sendWhole(Node node, String path, List<StoreOp> ops, Set<String> sent) {
        if (!persists(path) || !sent.add(path)) {
            return;
        }
        if (node instanceof Directory directory) {
            ops.add(StoreOp.mkdir(path, directory.lastModified));
            for (String name : directory.names()) {
                Node child = directory.child(name);
                if (child != null) {
                    sendWhole(child, path + "/" + name, ops, sent);
                }
            }
        } else if (node instanceof DataFile file) {
            // All its bytes; if it was also written to, the loop over written files skips it.
            ops.add(StoreOp.write(path, file.size, 0, Arrays.copyOf(file.data, file.size), file.lastModified));
        }
        // A mounted file can't be moved or copied into place by the tree, so none is here. An
        // in-place file (ADR 0041) is in the store already: its writes went there, and the tree
        // moves none (it reads them into memory first).
    }

    private static boolean hasDeletedAncestor(String path, Set<String> deleted) {
        for (int slash = path.lastIndexOf('/'); slash > 0; slash = path.lastIndexOf('/', slash - 1)) {
            if (deleted.contains(path.substring(0, slash))) {
                return true;
            }
        }
        return false;
    }

    private static int depth(String path) {
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }

    static boolean within(String path, String folder) {
        return path.equals(folder)
                || (path.startsWith(folder) && (folder.endsWith("/") || path.charAt(folder.length()) == '/'));
    }
}
