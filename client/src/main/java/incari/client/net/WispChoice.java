package incari.client.net;

/**
 * A server's Wispcraft choice, saved with it ({@link WispServers}): whether to join it through a Wisp
 * proxy, and which. The proxy is kept while it's off, so turning it back on finds it again.
 *
 * @param on whether the server is joined through the proxy
 * @param proxy the proxy's {@code ws://} or {@code wss://} URL
 */
public record WispChoice(boolean on, String proxy) {

    /** Wispcraft's own default proxy, run by Mercury Workshop. */
    public static final String DEFAULT_PROXY = "wss://anura.pro/";

    public static final WispChoice OFF = new WispChoice(false, DEFAULT_PROXY);
}
