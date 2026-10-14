/**
 * The browser build's file system (ADR 0011): an in-memory tree behind TeaVM's
 * {@code java.io} and {@code java.nio.file}, persisted by a storage worker to OPFS or IndexedDB.
 */
@NullMarked
package incari.platform.web.storage;

import org.jspecify.annotations.NullMarked;
