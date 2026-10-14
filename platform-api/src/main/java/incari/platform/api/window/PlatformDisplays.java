package incari.platform.api.window;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The connected monitors. Changes are reported as events ({@link PlatformEventListener#displayAdded}
 * and friends); these methods always answer with the current state.
 *
 * <p>The browser reports one display, the screen the page is on.
 */
public interface PlatformDisplays {

    /** Every connected display, primary first. */
    List<DisplayInfo> all();

    /** The display with this id, or null if it's disconnected or can't be queried. */
    @Nullable
    DisplayInfo get(int id);

    /** The id of the primary display, or 0 if there is none. */
    int primaryId();
}
