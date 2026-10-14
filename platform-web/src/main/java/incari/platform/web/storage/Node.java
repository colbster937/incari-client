package incari.platform.web.storage;

import org.jspecify.annotations.Nullable;

/**
 * A file or folder in the page's file system ({@link TreeFileSystem}). Game thread only: the page has
 * one thread, and nothing here suspends (must not block: coroutines).
 */
@SuppressWarnings("ReferenceEquality") // Nodes are identities: the same file is the same object.
abstract class Node {

    String name;

    @Nullable
    Directory parent;

    long lastModified;

    /** Read-only nodes refuse writes: the mounted client jar, and whatever the game marks so. */
    boolean readOnly;

    Node(String name, long lastModified) {
        this.name = name;
        this.lastModified = lastModified;
    }

    abstract boolean isDirectory();

    /** The node's absolute path ("/" for the root), or null once it's been deleted. */
    final @Nullable String path() {
        if (this instanceof Directory directory && directory.isRoot()) {
            return "/";
        }
        StringBuilder path = new StringBuilder();
        return appendPath(path) ? path.toString() : null;
    }

    /** Appends "/name" for each folder down to this node; false if it isn't in the tree. */
    final boolean appendPath(StringBuilder path) {
        Directory up = parent;
        if (up == null) {
            // Only the root has no parent and is still in the tree; it adds nothing.
            return this instanceof Directory directory && directory.isRoot();
        }
        if (!up.appendPath(path)) {
            return false;
        }
        path.append('/').append(name);
        return true;
    }

    /** Whether {@code ancestor} is this node or contains it. */
    final boolean isWithin(Node ancestor) {
        for (Node node = this; node != null; node = node.parent) {
            if (node == ancestor) {
                return true;
            }
        }
        return false;
    }
}
