package incari.client.profile;

import incari.client.skins.OwnSkin;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The skin list as Eagler clients show it: a box with the skin worn and an arrow, which opens a
 * list below it (above, if the screen ends first). The list is drawn and clicked through the
 * screen ({@link #extractList}, {@link #listClicked}), so it lies over the widgets under it.
 * Closed, up and down choose the previous and next skin.
 */
final class SkinDropdown extends AbstractWidget {

    private static final int ROW_HEIGHT = 12;
    private static final int VISIBLE_ROWS = 9;
    private static final int BORDER = 0xFFA0A0A0;
    private static final int BORDER_FOCUSED = 0xFFFFFFFF;

    private final Font font;
    private final Consumer<OwnSkin.Choice> onChoose;
    private List<OwnSkin.Choice> choices;
    private OwnSkin.Choice chosen;
    private boolean open;
    /** The first row the open list shows. */
    private int scroll;

    SkinDropdown(
            Font font,
            int x,
            int y,
            int width,
            int height,
            List<OwnSkin.Choice> choices,
            OwnSkin.Choice chosen,
            Consumer<OwnSkin.Choice> onChoose) {
        super(x, y, width, height, Component.literal("Player Skin"));
        this.font = font;
        this.choices = choices;
        this.chosen = chosen;
        this.onChoose = onChoose;
    }

    void setChoices(List<OwnSkin.Choice> choices, OwnSkin.Choice chosen) {
        this.choices = choices;
        this.chosen = chosen;
        scroll = Math.max(0, Math.min(scroll, choices.size() - VISIBLE_ROWS));
    }

    boolean isOpen() {
        return open;
    }

    void close() {
        open = false;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        int border = isHoveredOrFocused() || open ? BORDER_FOCUSED : BORDER;
        int arrowLeft = getRight() - getHeight();
        graphics.fill(getX(), getY(), getRight(), getBottom(), 0xFF000000);
        graphics.outline(getX(), getY(), getWidth(), getHeight(), border);
        graphics.verticalLine(arrowLeft, getY(), getBottom() - 1, border);
        arrow(graphics, arrowLeft + getHeight() / 2, getY() + getHeight() / 2, open, border);
        String label = font.plainSubstrByWidth(chosen.label(), arrowLeft - getX() - 8);
        graphics.text(font, label, getX() + 5, getY() + (getHeight() - 8) / 2, 0xFFFFFFFF);
    }

    /** A chevron centred on {@code (x, y)}: pointing down, or up while the list is open. */
    private static void arrow(GuiGraphicsExtractor graphics, int x, int y, boolean up, int color) {
        for (int i = 0; i < 5; i++) {
            int row = up ? y + 2 - i : y - 2 + i;
            graphics.fill(x - 5 + i, row, x - 3 + i, row + 1, color);
            graphics.fill(x + 3 - i, row, x + 5 - i, row + 1, color);
        }
    }

    /** The open list, over everything the screen drew; the screen calls it last. */
    void extractList(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int screenHeight) {
        if (!open) {
            return;
        }
        int rows = Math.min(VISIBLE_ROWS, choices.size());
        int top = listTop(screenHeight);
        int bottom = top + rows * ROW_HEIGHT + 2;
        int right = getRight() - (choices.size() > rows ? 4 : 0);
        graphics.nextStratum();
        graphics.fill(getX(), top, getRight(), bottom, 0xFF000000);
        graphics.outline(getX(), top, getWidth(), bottom - top, BORDER_FOCUSED);
        int hovered = rowAt(mouseX, mouseY, screenHeight);
        for (int i = 0; i < rows; i++) {
            int index = scroll + i;
            OwnSkin.Choice choice = choices.get(index);
            int y = top + 1 + i * ROW_HEIGHT;
            if (index == hovered) {
                graphics.fill(getX() + 1, y, right - 1, y + ROW_HEIGHT, 0xFF505050);
            } else if (choice.equals(chosen)) {
                graphics.fill(getX() + 1, y, right - 1, y + ROW_HEIGHT, 0xFF2A2A2A);
            }
            String label = font.plainSubstrByWidth(choice.label(), right - getX() - 10);
            graphics.text(font, label, getX() + 5, y + 2, choice.custom() ? 0xFFFFFFA0 : 0xFFFFFFFF);
        }
        if (choices.size() > rows) {
            int track = rows * ROW_HEIGHT;
            int thumb = Math.max(8, track * rows / choices.size());
            int thumbTop = top + 1 + (track - thumb) * scroll / (choices.size() - rows);
            graphics.fill(right - 1, top + 1, getRight() - 1, bottom - 1, 0xFF202020);
            graphics.fill(right - 1, thumbTop, getRight() - 1, thumbTop + thumb, 0xFFA0A0A0);
        }
    }

    /**
     * A click while the list is open: on a row chooses it, anywhere else closes the list. Either
     * way the click goes no further.
     */
    boolean listClicked(double mouseX, double mouseY, int screenHeight) {
        int row = rowAt(mouseX, mouseY, screenHeight);
        if (row >= 0) {
            choose(choices.get(row));
        }
        open = false;
        return true;
    }

    boolean listScrolled(double scrollY) {
        int rows = Math.min(VISIBLE_ROWS, choices.size());
        scroll = Math.max(0, Math.min(choices.size() - rows, scroll - (int) Math.signum(scrollY)));
        return true;
    }

    private int listTop(int screenHeight) {
        int listHeight = Math.min(VISIBLE_ROWS, choices.size()) * ROW_HEIGHT + 2;
        return getBottom() + listHeight <= screenHeight ? getBottom() : getY() - listHeight;
    }

    /** The choice under the pointer in the open list, or -1. */
    private int rowAt(double mouseX, double mouseY, int screenHeight) {
        int rows = Math.min(VISIBLE_ROWS, choices.size());
        int top = listTop(screenHeight) + 1;
        if (mouseX < getX() || mouseX >= getRight() - (choices.size() > rows ? 4 : 0) || mouseY < top) {
            return -1;
        }
        int row = (int) (mouseY - top) / ROW_HEIGHT;
        return row < rows ? scroll + row : -1;
    }

    private void choose(OwnSkin.Choice choice) {
        if (!choice.equals(chosen)) {
            chosen = choice;
            onChoose.accept(choice);
        }
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        toggle();
    }

    private void toggle() {
        open = !open;
        if (open) {
            // Open where the worn skin shows.
            int index = choices.indexOf(chosen);
            int rows = Math.min(VISIBLE_ROWS, choices.size());
            scroll = Math.max(0, Math.min(choices.size() - rows, index - rows / 2));
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!active || !visible) {
            return false;
        }
        if (event.isSelection()) {
            toggle();
            return true;
        }
        if (event.isUp() || event.isDown()) {
            int index = choices.indexOf(chosen) + (event.isUp() ? -1 : 1);
            if (index >= 0 && index < choices.size()) {
                choose(choices.get(index));
            }
            return true;
        }
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    @Override
    protected net.minecraft.network.chat.MutableComponent createNarrationMessage() {
        return Component.translatable("gui.narrate.button", Component.literal("Player Skin: " + chosen.label()));
    }
}
