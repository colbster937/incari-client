import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;

/**
 * {@code int}s handed to JavaScript arrive as the same Numbers (tools/webbuild's {@code
 * JsoIntrinsics}: an i31 where the value fits, else TeaVM's import): each side of the i31 range's
 * edges, the int range's ends, and values from a loop, so they aren't constants; through a
 * {@code @JSBody} argument and through a JSO interface method's. Prints "probe passed".
 */
public final class WrapIntProbe {

    private WrapIntProbe() {}

    /** A JavaScript object whose method reports what it got, as a JSO interface call reaches it. */
    interface Checker extends JSObject {
        String check(int value, String expected);
    }

    public static void main(String[] args) {
        int[] values = {0, 1, -1, 7, 36160, (1 << 30) - 1, 1 << 30, -(1 << 30), -(1 << 30) - 1,
            Integer.MAX_VALUE, Integer.MIN_VALUE, 0x7fff_fffe, 123_456_789, -987_654_321};
        Checker checker = checker();
        String failure = null;
        for (int round = 0; round < 3 && failure == null; round++) {
            for (int value : values) {
                // Not a constant the compiler could fold: a value read from the array, shifted and back.
                int v = (value ^ round) ^ round;
                String expected = Integer.toString(v);
                String body = describe(v, expected);
                String method = checker.check(v, expected);
                if (body != null || method != null) {
                    failure = body != null ? body : method;
                    break;
                }
            }
        }
        System.out.println(failure == null ? "probe passed" : "probe failed: " + failure);
    }

    @JSBody(
            params = {"value", "expected"},
            script = "return typeof value === 'number' && String(value) === expected ? null"
                    + " : '@JSBody got ' + typeof value + ' ' + value + ', expected ' + expected;")
    private static native String describe(int value, String expected);

    @JSBody(
            script = "return { check: function(value, expected) {"
                    + " return typeof value === 'number' && String(value) === expected ? null"
                    + " : 'a method got ' + typeof value + ' ' + value + ', expected ' + expected; } };")
    private static native Checker checker();
}
