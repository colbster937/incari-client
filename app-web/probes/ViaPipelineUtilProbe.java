import com.viaversion.viaversion.util.PipelineUtil;

/**
 * Regression probe for web-classlib's substitute for Via's {@code PipelineUtil}: Via's own
 * looks up Netty's codec methods reflectively when it loads, which the browser can't do, so the
 * class failed to initialize, and Via's checks for a cancelled packet ({@code containsCause})
 * failed with it: the first packet Via dropped on purpose (the game's Login Acknowledged, for a
 * 1.8 server) ended the connection. Prints "probe passed" when a cause is found inside a wrapper.
 */
public final class ViaPipelineUtilProbe {

    private ViaPipelineUtilProbe() {}

    public static void main(String[] args) {
        Thread thread = new Thread(ViaPipelineUtilProbe::run, "probe");
        thread.start();
    }

    private static void run() {
        try {
            // Via's own cancel exceptions need a running Via; any cause shows the class loads.
            RuntimeException wrapped = new RuntimeException(new java.io.IOException("cancelled"));
            boolean found = PipelineUtil.containsCause(wrapped, java.io.IOException.class);
            System.out.println(found ? "probe passed" : "probe failed: the cancel wasn't found");
        } catch (Throwable t) {
            System.out.println("probe failed: " + t);
        }
    }
}
