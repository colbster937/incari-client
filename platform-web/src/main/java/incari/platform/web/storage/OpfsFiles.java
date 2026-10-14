package incari.platform.web.storage;

import java.io.IOException;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * In-place files in singleplayer's server worker (ADR 0041): the world's region files opened
 * straight from the origin private file system, under the same paths the storage worker keeps
 * them at, with synchronous access handles (which only workers get). Reads and writes are calls
 * on the handle; only opening suspends.
 *
 * <p>Worker-side: the page has no synchronous handles, and must not touch these files while the
 * worker has the world (the storage worker's own writes open handles of their own, which a
 * held handle would refuse).
 */
final class OpfsFiles implements InPlaceStore {

    private final boolean inPlace;

    /**
     * @param inPlace whether files are opened where they are; else (no synchronous handles here,
     *     or the page kept the world in memory) they're read whole
     */
    OpfsFiles(boolean inPlace) {
        this.inPlace = inPlace;
    }

    /** Whether this worker can open files where they are. */
    @JSBody(
            script = "return typeof FileSystemFileHandle !== 'undefined'"
                    + " && typeof FileSystemFileHandle.prototype.createSyncAccessHandle === 'function'"
                    + " && !!(navigator.storage && navigator.storage.getDirectory);")
    static native boolean available();

    @Override
    public boolean opensInPlace() {
        return inPlace;
    }

    @Override
    public byte[] read(String path) throws IOException {
        JSObject result = readWhole(path);
        String error = errorOf(result);
        if (error != null) {
            throw new IOException("Couldn't read " + path + ": " + error);
        }
        ArrayBuffer data = dataOf(result);
        return data == null ? new byte[0] : new Int8Array(data).copyToJavaArray();
    }

    @Override
    public Handle open(String path) throws IOException {
        JSObject result = openHandle(path);
        String error = errorOf(result);
        if (error != null) {
            throw new IOException("Couldn't open " + path + " in place: " + error);
        }
        return new OpenFile(handleOf(result));
    }

    /** A synchronous access handle on one file. */
    private static final class OpenFile implements Handle {
        private @Nullable JSObject handle;

        OpenFile(JSObject handle) {
            this.handle = handle;
        }

        private JSObject handle() throws IOException {
            JSObject open = handle;
            if (open == null) {
                throw new IOException("File closed");
            }
            return open;
        }

        @Override
        public int read(byte[] buffer, int offset, int length, int at) throws IOException {
            Int8Array read = readAt(handle(), length, at);
            int count = read.getLength();
            if (count > 0) {
                System.arraycopy(read.copyToJavaArray(), 0, buffer, offset, count);
            }
            return count;
        }

        @Override
        public void write(byte[] buffer, int offset, int length, int at) throws IOException {
            byte[] part = offset == 0 && length == buffer.length
                    ? buffer
                    : Arrays.copyOfRange(buffer, offset, offset + length);
            int written = writeAt(handle(), Int8Array.copyFromJavaArray(part), at);
            if (written != length) {
                throw new IOException("Wrote " + written + " of " + length + " bytes");
            }
        }

        @Override
        public int size() throws IOException {
            return sizeOf(handle());
        }

        @Override
        public void truncate(int size) throws IOException {
            truncateTo(handle(), size);
        }

        @Override
        public void flush() throws IOException {
            flushHandle(handle());
        }

        @Override
        public void close() {
            JSObject open = handle;
            handle = null;
            if (open != null) {
                closeHandle(open);
            }
        }
    }

    // --- JavaScript -------------------------------------------------------------------------

    @JSFunctor
    interface Done extends JSObject {
        void done(JSObject result);
    }

    /** The folder of {@code path}'s file, made if missing, then {@code then(folder, name)}. */
    private static final String FOLDER = "function folderOf(path, create) {"
            + "  var names = path.split('/').filter(function(p) { return p.length > 0; });"
            + "  var name = names.pop();"
            + "  var step = navigator.storage.getDirectory();"
            + "  names.forEach(function(n) { step = step.then(function(d) { return d.getDirectoryHandle(n, {create: create}); }); });"
            + "  return step.then(function(d) { return {folder: d, name: name}; }); }";

    /** Opens the file at {@code path} (made if missing) with a synchronous access handle; suspends. */
    @Async
    private static native JSObject openHandle(String path);

    private static void openHandle(String path, AsyncCallback<JSObject> callback) {
        startOpen(path, callback::complete);
    }

    @JSBody(
            params = {"path", "done"},
            script =
                    FOLDER
                            + "folderOf(path, true)"
                            + ".then(function(at) { return at.folder.getFileHandle(at.name, {create: true}); })"
                            + ".then(function(file) { return file.createSyncAccessHandle(); })"
                            + ".then(function(handle) { done({handle: handle}); },"
                            + "  function(error) { done({error: String(error && error.name ? error.name + ': ' + error.message : error)}); });")
    private static native void startOpen(String path, Done done);

    /** The file at {@code path}, read whole ({@code data}, null if it isn't there); suspends. */
    @Async
    private static native JSObject readWhole(String path);

    private static void readWhole(String path, AsyncCallback<JSObject> callback) {
        startRead(path, callback::complete);
    }

    @JSBody(
            params = {"path", "done"},
            script =
                    FOLDER
                            + "folderOf(path, false)"
                            + ".then(function(at) { return at.folder.getFileHandle(at.name); })"
                            + ".then(function(file) { return file.getFile(); })"
                            + ".then(function(file) { return file.arrayBuffer(); })"
                            + ".then(function(data) { done({data: data}); },"
                            + "  function(error) { if (error && error.name === 'NotFoundError') done({data: null});"
                            + "    else done({error: String(error && error.name ? error.name + ': ' + error.message : error)}); });")
    private static native void startRead(String path, Done done);

    @JSBody(params = "result", script = "return result.error === undefined ? null : String(result.error);")
    private static native @Nullable String errorOf(JSObject result);

    @JSBody(params = "result", script = "return result.handle;")
    private static native JSObject handleOf(JSObject result);

    @JSBody(params = "result", script = "return result.data || null;")
    private static native @Nullable ArrayBuffer dataOf(JSObject result);

    @JSBody(
            params = {"handle", "length", "at"},
            script = "var view = new Int8Array(length); var read = handle.read(view, {at: at});"
                    + "return read === length ? view : view.subarray(0, read);")
    private static native Int8Array readAt(JSObject handle, int length, int at);

    @JSBody(
            params = {"handle", "data", "at"},
            script = "return handle.write(data, {at: at});")
    private static native int writeAt(JSObject handle, Int8Array data, int at);

    @JSBody(params = "handle", script = "return handle.getSize();")
    private static native int sizeOf(JSObject handle);

    @JSBody(
            params = {"handle", "size"},
            script = "handle.truncate(size);")
    private static native void truncateTo(JSObject handle, int size);

    @JSBody(params = "handle", script = "handle.flush();")
    private static native void flushHandle(JSObject handle);

    @JSBody(params = "handle", script = "handle.flush(); handle.close();")
    private static native void closeHandle(JSObject handle);
}
