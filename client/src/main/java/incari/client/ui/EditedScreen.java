package incari.client.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jspecify.annotations.Nullable;

/**
 * One of the game's screens as a {@link ScreenEdit} sees it, just after a layout: its widgets, found
 * by the translation key of their label, and ways to remove them or add Incari's own. A widget is
 * moved, resized or relabelled through its own setters ({@code setPosition}, {@code setWidth},
 * {@code setMessage}). Render thread; valid only during {@link ScreenEdit#edit}.
 *
 * @param <S> the screen's class
 */
public final class EditedScreen<S extends Screen> {

    private final S screen;
    private final List<GuiEventListener> added;
    private final Consumer<AbstractWidget> add;
    private final Consumer<GuiEventListener> remove;

    EditedScreen(
            S screen, List<GuiEventListener> added, Consumer<AbstractWidget> add, Consumer<GuiEventListener> remove) {
        this.screen = screen;
        this.added = added;
        this.add = add;
        this.remove = remove;
    }

    /** The screen. */
    public S screen() {
        return screen;
    }

    /** The screen's width in GUI units. */
    public int width() {
        return screen.width;
    }

    /** The screen's height in GUI units. */
    public int height() {
        return screen.height;
    }

    /** The screen's widgets, in the order they were added; a copy. */
    public List<AbstractWidget> widgets() {
        List<AbstractWidget> widgets = new ArrayList<>();
        for (GuiEventListener child : screen.children()) {
            if (child instanceof AbstractWidget widget) {
                widgets.add(widget);
            }
        }
        return Collections.unmodifiableList(widgets);
    }

    /**
     * The first widget labelled with {@code translationKey}, such as {@code "menu.options"}, or
     * with it as the name in a "name: value" label, such as a cycle button's. Keys hold in every
     * language, where the labels' text doesn't.
     */
    public @Nullable AbstractWidget find(String translationKey) {
        for (GuiEventListener child : screen.children()) {
            if (child instanceof AbstractWidget widget && labelled(widget.getMessage(), translationKey, 0)) {
                return widget;
            }
        }
        return null;
    }

    /** Removes the widget labelled with {@code translationKey}; whether there was one. */
    public boolean remove(String translationKey) {
        AbstractWidget widget = find(translationKey);
        if (widget != null) {
            remove(widget);
        }
        return widget != null;
    }

    /** Removes {@code widget} from the screen. */
    public void remove(GuiEventListener widget) {
        remove.accept(widget);
    }

    /**
     * Adds {@code widget}, which should come from {@link Ui}. It's removed again before the next
     * layout's edits, which add it afresh.
     */
    public <W extends AbstractWidget> W add(W widget) {
        add.accept(widget);
        added.add(widget);
        return widget;
    }

    private static boolean labelled(Component label, String key, int depth) {
        if (!(label.getContents() instanceof TranslatableContents translatable)) {
            return false;
        }
        if (translatable.getKey().equals(key)) {
            return true;
        }
        if (depth == 0) {
            for (Object argument : translatable.getArgs()) {
                if (argument instanceof Component part && labelled(part, key, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }
}
