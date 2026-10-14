package incari.client.platform;

/**
 * Which of Mojang's online services the platform offers. The browser build strips Realms (its HTTP
 * client needs raw sockets and Mojang sign-in), so the entry point switches it off and the game
 * answers Realms' availability check without calling out, as it does in offline developer mode.
 */
public final class OnlineServices {

    private static volatile boolean realms = true;

    private OnlineServices() {}

    /** Switches Realms off; call before the game starts. */
    public static void disableRealms() {
        realms = false;
    }

    /** {@code RealmsAvailability.check}: whether to ask the Realms service at all. */
    public static boolean isRealmsAvailable() {
        return realms;
    }
}
