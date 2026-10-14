package incari.framework.event;

/** Receives {@link Events#SCREENSHOT}. */
@FunctionalInterface
public interface ScreenshotListener {

    /**
     * A screenshot was saved.
     *
     * @param fileName its file's name in the screenshots folder ("2026-10-10_22.41.03.png")
     */
    void onScreenshot(String fileName);
}
