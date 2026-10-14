package incari.client.startup;

/**
 * How long the loading screen takes to fade once the game has loaded. The game fades the
 * logo and progress bar out over a second, then itself out over the screen beneath over another,
 * and only then lets that screen have the mouse and keyboard: two seconds of the title screen's
 * start-up spent on the fade. The browser build fades in a quarter of that, so the title screen
 * takes input half a second after the game is ready. On the desktop it's the game's.
 */
public final class LoadingFade {

    /** The game's: a second for each half of the fade. */
    static final float GAME_MILLIS = 1000.0F;

    private static volatile float millis = GAME_MILLIS;

    private LoadingFade() {}

    /** Each half of the fade takes {@code halfMillis} from now on. */
    public static void set(float halfMillis) {
        millis = halfMillis;
    }

    /** Hook: the milliseconds each half of the loading screen's fade-out takes. */
    public static float halfMillis() {
        return millis;
    }
}
