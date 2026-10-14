package incari.platform.web.storage;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A folder in the page's file system. */
@SuppressWarnings("ReferenceEquality") // Nodes are identities: the same file is the same object.
final class Directory extends Node {

    private final boolean root;
    private final Map<String, Node> children = new HashMap<>();

    Directory(String name, long lastModified, boolean root) {
        super(name, lastModified);
        this.root = root;
    }

    boolean isRoot() {
        return root;
    }

    @Override
    boolean isDirectory() {
        return true;
    }

    @Nullable
    Node child(String childName) {
        return children.get(childName);
    }

    boolean isEmpty() {
        return children.isEmpty();
    }

    /** The children's names, sorted: listings come out the same after a reload as before it. */
    String[] names() {
        String[] names = children.keySet().toArray(new String[0]);
        Arrays.sort(names);
        return names;
    }

    Iterable<Node> children() {
        return children.values();
    }

    /** Puts {@code node} here as {@code childName}, replacing whatever had that name. */
    void attach(String childName, Node node) {
        Node replaced = children.put(childName, node);
        if (replaced != null && replaced != node) {
            replaced.parent = null;
        }
        node.name = childName;
        node.parent = this;
    }

    void detach(Node node) {
        if (children.get(node.name) == node) {
            children.remove(node.name);
        }
        node.parent = null;
    }
}
