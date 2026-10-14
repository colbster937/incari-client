import java.nio.ByteBuffer;
import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Measures how bytes cross between Java arrays and JavaScript buffers in the WebAssembly GC build:
 * TeaVM's {@code Int8Array.copyFromJavaArray} / {@code copyToJavaArray}, against a route through a
 * direct buffer in linear memory, which JavaScript can view and slice. Every message between the
 * page and singleplayer's server worker (packets, file changes) crosses this way. Prints the times
 * and "probe passed" when both routes agree on the bytes.
 */
public final class ByteCopyProbe {

    private ByteCopyProbe() {}

    public static void main(String[] args) {
        int size = 1 << 20;
        byte[] source = new byte[size];
        for (int i = 0; i < size; i++) {
            source[i] = (byte) (i * 31 + (i >>> 8));
        }
        ByteBuffer direct = ByteBuffer.allocateDirect(size);
        int rounds = 20;
        // Warm up both routes.
        for (int i = 0; i < 3; i++) {
            Int8Array.copyFromJavaArray(source);
            viaLinear(source, direct);
        }

        double start = now();
        Int8Array teavm = null;
        for (int i = 0; i < rounds; i++) {
            teavm = Int8Array.copyFromJavaArray(source);
        }
        double teavmOut = (now() - start) / rounds;

        start = now();
        ArrayBuffer linear = null;
        for (int i = 0; i < rounds; i++) {
            linear = viaLinear(source, direct);
        }
        double linearOut = (now() - start) / rounds;

        start = now();
        byte[] back = null;
        for (int i = 0; i < rounds; i++) {
            back = teavm.copyToJavaArray();
        }
        double teavmIn = (now() - start) / rounds;

        start = now();
        byte[] backLinear = null;
        for (int i = 0; i < rounds; i++) {
            backLinear = fromLinear(linear, direct);
        }
        double linearIn = (now() - start) / rounds;

        boolean same = java.util.Arrays.equals(back, source) && java.util.Arrays.equals(backLinear, source)
                && same(teavm.getBuffer(), linear);
        System.out.println("ByteCopyProbe: 1 MiB to JavaScript: copyFromJavaArray " + teavmOut
                + " ms, through linear memory " + linearOut + " ms; back: copyToJavaArray " + teavmIn
                + " ms, through linear memory " + linearIn + " ms");
        System.out.println(same ? "probe passed" : "probe failed: the bytes differ");
    }

    private static ArrayBuffer viaLinear(byte[] source, ByteBuffer direct) {
        direct.clear();
        direct.put(source, 0, source.length);
        return sliceOf(Int8Array.fromJavaBuffer(direct), source.length);
    }

    private static byte[] fromLinear(ArrayBuffer buffer, ByteBuffer direct) {
        int length = buffer.getByteLength();
        setInto(Int8Array.fromJavaBuffer(direct), buffer);
        byte[] out = new byte[length];
        direct.get(0, out, 0, length);
        return out;
    }

    @JSBody(
            params = {"view", "length"},
            script = "return view.buffer.slice(view.byteOffset, view.byteOffset + length);")
    private static native ArrayBuffer sliceOf(Int8Array view, int length);

    @JSBody(
            params = {"view", "buffer"},
            script = "view.set(new Int8Array(buffer));")
    private static native void setInto(Int8Array view, ArrayBuffer buffer);

    @JSBody(
            params = {"a", "b"},
            script = "var x = new Int8Array(a), y = new Int8Array(b); if (x.length !== y.length) return false;"
                    + "for (var i = 0; i < x.length; i++) if (x[i] !== y[i]) return false; return true;")
    private static native boolean same(ArrayBuffer a, ArrayBuffer b);

    @JSBody(script = "return performance.now();")
    private static native double now();
}
