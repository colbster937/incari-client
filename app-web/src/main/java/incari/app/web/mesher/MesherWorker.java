package incari.app.web.mesher;

import incari.mesher.Bytes;
import incari.mesher.MeshJobs;
import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * The mesher workers' program (ADR 0023): compiled by TeaVM on its own into
 * {@code mesher.wasm}, which each of the page's mesher workers loads. It holds the mesher and
 * answers the game's messages ({@link MeshJobs}); the worker's bootstrap script
 * ({@code platform-web}'s {@code program-worker.js}) moves the bytes in and out.
 *
 * <p>Worker-side: no game objects, nothing but the mesher. Messages arrive in a direct buffer (the
 * module's linear memory, where the bootstrap can copy them in one go), and replies leave the same
 * way.
 */
public final class MesherWorker {

    private static final MeshJobs JOBS = new MeshJobs();
    private static ByteBuffer input = ByteBuffer.allocateDirect(64 * 1024);
    private static ByteBuffer output = ByteBuffer.allocateDirect(256 * 1024);
    private static byte[] message = new byte[64 * 1024];

    private MesherWorker() {}

    public static void main(String[] args) {
        install(MesherWorker::input, MesherWorker::handle, MesherWorker::output);
    }

    /** A view of {@code length} bytes to copy the next message into. */
    private static Int8Array input(int length) {
        if (input.capacity() < length) {
            input = ByteBuffer.allocateDirect(Math.max(length, input.capacity() * 2));
        }
        return Int8Array.fromJavaBuffer(input.slice(0, length));
    }

    /** Handles the message just copied in; returns its reply's length, or -1 for none. */
    private static int handle(int length) {
        if (message.length < length) {
            message = new byte[Math.max(length, message.length * 2)];
        }
        input.get(0, message, 0, length);
        Bytes.@Nullable Out reply = JOBS.handle(message, 0, length);
        if (reply == null) {
            return -1;
        }
        int size = reply.size();
        if (output.capacity() < size) {
            output = ByteBuffer.allocateDirect(Math.max(size, output.capacity() * 2));
        }
        output.put(0, reply.array(), 0, size);
        return size;
    }

    /** A view of the reply's {@code length} bytes. */
    private static Int8Array output(int length) {
        return Int8Array.fromJavaBuffer(output.slice(0, length));
    }

    @JSFunctor
    interface View extends JSObject {
        Int8Array view(int length);
    }

    @JSFunctor
    interface Handle extends JSObject {
        int handle(int length);
    }

    @JSBody(
            params = {"input", "handle", "output"},
            script = "self.__incariProgram = {input: input, handle: handle, output: output};")
    private static native void install(View input, Handle handle, View output);
}
