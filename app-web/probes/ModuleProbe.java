/**
 * Regression probe for web-classlib's {@code Module} and {@code ModuleLayer}: the check the
 * game's {@code JvmProfiler} makes in its static initializer, which the first packet sent reaches.
 * Without them the survey's rules turned the calls into nulls and the initializer threw, so the
 * handshake never left. Prints "probe passed" when the check runs and finds no {@code jdk.jfr}.
 */
public final class ModuleProbe {

    private ModuleProbe() {}

    public static void main(String[] args) {
        boolean jfr = Runtime.class.getModule().getLayer().findModule("jdk.jfr").isPresent();
        System.out.println(!jfr ? "probe passed" : "probe failed: jdk.jfr found");
    }
}
