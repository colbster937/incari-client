/**
 * Regression probe for tools/teavm-patches' TThrowable fix: {@code getSuppressed} and
 * {@code addSuppressed} on a fresh exception. TeaVM's class library left the suppressed array
 * null, so both threw NullPointerException; Netty's {@code DefaultPromise.syncUninterruptibly}
 * calls {@code getSuppressed} on every failure it rethrows, which hid why a connection failed.
 * Prints "probe passed" when an exception starts with none and keeps the one added.
 */
public final class SuppressedProbe {

    private SuppressedProbe() {}

    public static void main(String[] args) {
        RuntimeException failure = new RuntimeException("failure");
        int before = failure.getSuppressed().length;
        failure.addSuppressed(new IllegalStateException("suppressed"));
        Throwable[] after = failure.getSuppressed();
        boolean passed = before == 0 && after.length == 1 && after[0].getMessage().equals("suppressed");
        System.out.println(passed ? "probe passed" : "probe failed: " + before + ", " + after.length);
    }
}
