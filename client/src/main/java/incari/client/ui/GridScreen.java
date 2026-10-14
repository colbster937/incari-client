package incari.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import incari.framework.setting.GridSetting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Edits a {@link GridSetting} cell by cell: a click turns a cell on or off, and a drag paints what
 * the click did; the right button erases. The middle row and column are marked, and the grid is
 * shown beside at the size it's drawn. Changes are the setting's at once, as any control's are.
 */
public final class GridScreen extends Screen {

    private static final int LARGEST_CELL = 12;
    private static final int PAD = 8;

    private final @Nullable Screen parent;
    private final GridSetting setting;

    private int cell;
    private int gridX;
    private int gridY;
    /** What a drag paints: on, or off; null while not dragging. */
    private @Nullable Boolean painting;

    public GridScreen(@Nullable Screen parent, GridSetting setting) {
        super(Component.literal(setting.name()));
        this.parent = parent;
        this.setting = setting;
    }

    @Override
    protected void init() {
        int size = setting.size();
        int room = Math.min(this.width - 120, this.height - 90);
        cell = Math.max(3, Math.min(LARGEST_CELL, room / size));
        int side = cell * size;
        gridX = (this.width - side) / 2;
        gridY = 36;
        int buttonsY = gridY + side + PAD + 4;
        int buttonWidth = Theme.SMALL_BUTTON_WIDTH + 10;
        int left = this.width / 2 - (buttonWidth * 3 + Theme.GAP * 2) / 2;
        addRenderableWidget(Ui.button(Component.literal("Clear"), buttonWidth, setting::clear))
                .setPosition(left, buttonsY);
        addRenderableWidget(Ui.button(Component.literal("Reset"), buttonWidth, setting::reset))
                .setPosition(left + buttonWidth + Theme.GAP, buttonsY);
        Button done = Ui.button(CommonComponents.GUI_DONE, buttonWidth, this::onClose);
        done.setPosition(left + (buttonWidth + Theme.GAP) * 2, buttonsY);
        addRenderableWidget(done);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        int size = setting.size();
        int side = cell * size;
        Ui.title(graphics, this.font, this.title, this.width / 2, 12);
        Ui.caption(
                graphics,
                this.font,
                Component.literal("Click or drag to draw, right-click to erase"),
                this.width / 2,
                22);
        Panels.panel(graphics, gridX - PAD, gridY - PAD, side + PAD * 2, side + PAD * 2, 1);
        int middle = size / 2;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int px = gridX + x * cell;
                int py = gridY + y * cell;
                boolean on = setting.get(x, y);
                int color = on ? Theme.ACCENT : x == middle || y == middle ? 0x30FFFFFF : 0x18FFFFFF;
                graphics.fill(px, py, px + cell - 1, py + cell - 1, color);
            }
        }
        int hx = (mouseX - gridX) / cell;
        int hy = (mouseY - gridY) / cell;
        if (mouseX >= gridX && mouseY >= gridY && hx < size && hy < size) {
            int px = gridX + hx * cell;
            int py = gridY + hy * cell;
            RoundRects.outline(graphics, px - 1, py - 1, cell + 1, cell + 1, 0, Theme.TEXT);
        }
        // Beside the grid, at the size it's drawn: one cell a GUI unit.
        int previewX = gridX + side + PAD * 3;
        int previewY = gridY + side / 2 - size / 2;
        Panels.darkPanel(graphics, previewX - 6, previewY - 6, size + 12, size + 12, 1);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (setting.get(x, y)) {
                    graphics.fill(previewX + x, previewY + y, previewX + x + 1, previewY + y + 1, Theme.TEXT);
                }
            }
        }
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        int x = cellX(event.x());
        int y = cellY(event.y());
        if (x < 0 || y < 0) {
            return false;
        }
        boolean on = event.button() != InputConstants.MOUSE_BUTTON_RIGHT && !setting.get(x, y);
        painting = on;
        setting.set(x, y, on);
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        Boolean paint = painting;
        if (paint == null) {
            return super.mouseDragged(event, dx, dy);
        }
        int x = cellX(event.x());
        int y = cellY(event.y());
        if (x >= 0 && y >= 0) {
            setting.set(x, y, paint);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        painting = null;
        return super.mouseReleased(event);
    }

    private int cellX(double mouseX) {
        int x = (int) Math.floor((mouseX - gridX) / cell);
        return x >= 0 && x < setting.size() ? x : -1;
    }

    private int cellY(double mouseY) {
        int y = (int) Math.floor((mouseY - gridY) / cell);
        return y >= 0 && y < setting.size() ? y : -1;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
