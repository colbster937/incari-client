package incari.client.look;

import incari.client.modules.IncariModules;
import incari.client.ui.EditedScreen;
import incari.client.ui.ScreenPanels;
import incari.client.ui.Ui;
import incari.framework.MenuButton;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;

/**
 * The pause menu in the client's look: its buttons gathered on one glass card in the middle
 * of the screen, the title at the card's top left. The game lays the menu out; this moves it, after
 * each layout. The voice panel and an Eagler server's icons keep their places (the icons follow
 * their buttons when drawn).
 */
final class PauseLook {

    private static final int PADDING = 12;
    private static final int HEADER = 9 + 9;
    /** Half the widest row of the game's menu, and a margin: what's further out isn't the menu. */
    private static final int MENU_HALF_WIDTH = 120;

    private PauseLook() {}

    static void edit(EditedScreen<PauseScreen> edited) {
        PauseScreen screen = edited.screen();
        ScreenPanels.clear(screen);
        StringWidget title = null;
        List<AbstractWidget> menu = new ArrayList<>();
        int middle = edited.width() / 2;
        for (AbstractWidget widget : edited.widgets()) {
            if (title == null && widget instanceof StringWidget text) {
                title = text;
            } else if (Math.abs(widget.getX() + widget.getWidth() / 2 - middle) < MENU_HALF_WIDTH
                    && widget.getX() >= middle - MENU_HALF_WIDTH) {
                menu.add(widget);
            }
        }
        if (menu.isEmpty()) {
            // The menu hidden (F3 and Escape): only "Game Paused" at the top.
            return;
        }
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (AbstractWidget widget : menu) {
            left = Math.min(left, widget.getX());
            top = Math.min(top, widget.getY());
            right = Math.max(right, widget.getRight());
            bottom = Math.max(bottom, widget.getBottom());
        }
        // Modules' own buttons (Screenshot Manager's gallery), a row under the game's.
        List<MenuButton> extra = IncariModules.system().menuButtons();
        if (!extra.isEmpty()) {
            int count = Math.min(3, extra.size());
            int gap = 4;
            int each = (right - left - (count - 1) * gap) / count;
            int rowY = bottom + gap;
            for (int i = 0; i < count; i++) {
                MenuButton button = extra.get(i);
                int buttonWidth = i == count - 1 ? right - left - (count - 1) * (each + gap) : each;
                AbstractWidget widget = edited.add(Ui.menuButton(
                                Component.literal(button.label()),
                                MenuIcons.of(button.icon()),
                                buttonWidth,
                                button.action())
                        .centered());
                widget.setPosition(left + i * (each + gap), rowY);
                menu.add(widget);
                bottom = Math.max(bottom, widget.getBottom());
            }
        }
        int width = right - left + 2 * PADDING;
        int height = HEADER + bottom - top + 2 * PADDING;
        int x = (edited.width() - width) / 2;
        int y = Math.max(6, (edited.height() - height) / 2);
        int dx = x + PADDING - left;
        int dy = y + PADDING + HEADER - top;
        for (AbstractWidget widget : menu) {
            widget.setPosition(widget.getX() + dx, widget.getY() + dy);
        }
        if (title != null) {
            title.setPosition(x + PADDING, y + PADDING);
        }
        ScreenPanels.add(screen, x, y, width, height);
    }
}
