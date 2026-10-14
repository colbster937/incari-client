package incari.client.modules;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import incari.client.gui.BackdropCache;
import incari.client.ui.Icons;
import incari.client.ui.Look;
import incari.client.ui.MenuBackdrop;
import incari.client.ui.Panels;
import incari.client.ui.RoundRects;
import incari.client.ui.Theme;
import incari.client.ui.Ui;
import incari.framework.Module;
import incari.framework.ModuleSystem;
import incari.framework.hud.Anchor;
import incari.framework.hud.HudCanvas;
import incari.framework.hud.HudElement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The HUD editor: every enabled module's HUD elements, drawn as they are in play, over the
 * world and the game's own HUD blurred behind them, so what can be moved stands out from what
 * can't. Drag an element to move it; it snaps to the screen's sides and middle and to the other
 * elements (Shift, or the Snap switch, places it freely), and takes the anchor of the third of the
 * screen it's left in, so it keeps its place as the window changes. Scroll over it or drag its
 * corner to resize it; right-click it for its module's settings; arrows nudge the chosen one; Ctrl+Z
 * undoes. Opened from the module menu.
 *
 * <p>The blur is the editor's in either GUI preset. In Potato it's drawn a few times a second and
 * kept in between ({@link BackdropCache}), while the elements and the toolbar keep the full frame
 * rate. Placements are the elements' own settings, saved with the profile as any change is.
 */
final class HudEditorScreen extends Screen {

    /** The margin the screen's sides snap at, in GUI units: the game's own HUD keeps about this much. */
    private static final int EDGE = 2;

    /** How close an edge must come to a line to snap to it. */
    private static final int SNAP = 4;

    /** The space kept between two elements snapped side by side. */
    private static final int GAP = 2;

    /** How far an element's frame stands out around it. */
    private static final int FRAME = 2;

    /** The resize handle's size, on the frame's bottom right corner. */
    private static final int HANDLE = 6;

    private static final int BAR_HEIGHT = 28;
    private static final int BAR_PADDING = 9;
    private static final int BUTTON_WIDTH = 54;
    private static final int TAG_HEIGHT = 11;
    private static final int ANCHOR_BAR = 14;
    private static final int EMPTY_WIDTH = 224;
    private static final int EMPTY_HEIGHT = 96;

    /** Undo steps kept. */
    private static final int UNDO_LIMIT = 64;

    /** Scrolls or nudges of one element this close together undo as one step. */
    private static final long MERGE_MILLIS = 700;

    private static final int NO_GUIDE = Integer.MIN_VALUE;

    private static final Component TITLE = Component.literal("HUD Layout");
    private static final Component RESET_ONE = Component.literal("Reset");
    private static final Component RESET_ALL = Component.literal("Reset all");
    private static final String HINT =
            "Drag to move  ·  Scroll to resize  ·  Shift places freely  ·  Right-click for settings";
    private static final String SHORT_HINT = "Drag to move  ·  Scroll to resize";

    /** Whether dragging snaps; kept between visits. */
    private static boolean snapping = true;

    private enum Drag {
        NONE,
        MOVE,
        SCALE
    }

    /** An element's placement, to undo back to. */
    private record Placement(HudElement element, Anchor anchor, int x, int y, float scale) {

        static Placement of(HudElement element) {
            return new Placement(
                    element,
                    element.anchor().get(),
                    element.offsetX().get(),
                    element.offsetY().get(),
                    element.scale().get());
        }

        void restore() {
            element.anchor().set(anchor);
            element.offsetX().set(x);
            element.offsetY().set(y);
            element.scale().set(scale);
        }
    }

    private final @Nullable Screen parent;
    private final ModuleSystem system;
    private final List<HudElement> elements = new ArrayList<>();
    private final List<Module> owners = new ArrayList<>();
    private final ArrayDeque<List<Placement>> undo = new ArrayDeque<>();
    private final int[] bounds = new int[4];

    /** Each element's left, top, width and height as last drawn, scaled. */
    private int[] rects = new int[0];

    /** Each element's width and height unscaled. */
    private int[] sizes = new int[0];

    /** Whether each element was drawn (its module on, the element placeable). */
    private boolean[] shown = new boolean[0];

    private int selected = -1;
    private int hovered = -1;
    private Drag drag = Drag.NONE;
    private double grabX;
    private double grabY;
    private int startX;
    private int startY;
    private int startWidth;
    private int startHeight;
    private int guideX = NO_GUIDE;
    private int guideY = NO_GUIDE;

    /** What the last undo step was for, so a run of scrolls or nudges is one step. */
    private int mergeIndex = -1;

    private @Nullable Drag mergeKind;
    private long mergeAt;

    private int barX;
    private int barY;
    private int barWidth;
    private boolean empty;
    private @Nullable Button reset;
    private final List<AbstractButton> barWidgets = new ArrayList<>();

    HudEditorScreen(@Nullable Screen parent, ModuleSystem system) {
        super(TITLE);
        this.parent = parent;
        this.system = system;
    }

    @Override
    protected void init() {
        Look.frost(true);
        BackdropCache.hold(true);
        ModuleHud.hide(true);

        elements.clear();
        owners.clear();
        for (Module module : system.modules()) {
            for (HudElement element : module.hudElements()) {
                elements.add(element);
                owners.add(module);
            }
        }
        rects = new int[elements.size() * 4];
        sizes = new int[elements.size() * 2];
        shown = new boolean[elements.size()];
        if (selected >= elements.size()) {
            selected = -1;
        }
        empty = true;
        for (int i = 0; i < elements.size(); i++) {
            if (owners.get(i).isEnabled() && elements.get(i).isPlaceable()) {
                empty = false;
            }
        }

        // The toolbar: the title, the snap switch, Reset and Done on one glass bar at the top.
        barWidgets.clear();
        SnapSwitch snap = new SnapSwitch();
        snap.setTooltip(Ui.tooltip("Snap to the screen's sides and middle and to other elements. "
                + "Hold Shift while dragging to place freely."));
        Button resetButton = Ui.button(RESET_ALL, BUTTON_WIDTH, this::reset);
        Button done = Ui.button(CommonComponents.GUI_DONE, BUTTON_WIDTH, this::onClose);
        int titleWidth = 12 + 5 + font.width(TITLE);
        barWidth = BAR_PADDING + titleWidth + 14 + snap.getWidth() + 10 + BUTTON_WIDTH + Theme.GAP + BUTTON_WIDTH + 4;
        barX = (width - barWidth) / 2;
        barY = 6;
        int controlY = barY + (BAR_HEIGHT - Theme.CONTROL_HEIGHT) / 2;
        int x = barX + barWidth - 4 - BUTTON_WIDTH;
        done.setPosition(x, controlY);
        x -= Theme.GAP + BUTTON_WIDTH;
        resetButton.setPosition(x, controlY);
        x -= 10 + snap.getWidth();
        snap.setPosition(x, controlY);
        reset = resetButton;
        for (AbstractButton widget : List.of(snap, resetButton, done)) {
            barWidgets.add(widget);
            addRenderableWidget(widget);
        }

        if (empty) {
            Button modules = Ui.button(Component.literal("Modules"), 96, this::backToModules);
            modules.setPosition(
                    (width - 96) / 2, (height - EMPTY_HEIGHT) / 2 + EMPTY_HEIGHT - 10 - Theme.CONTROL_HEIGHT);
            addRenderableWidget(modules);
        }
    }

    @Override
    public void removed() {
        Look.frost(false);
        BackdropCache.hold(false);
        ModuleHud.hide(false);
        system.flush();
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        // As the module menu: singleplayer pauses, and a still world is all the backdrop needs.
        return true;
    }

    private void backToModules() {
        if (parent instanceof ModuleListScreen) {
            onClose();
        } else {
            IncariModules.openMenu(parent);
        }
    }

    // Drawing.

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // The world (or the menus' photo) and the game's HUD, blurred in either preset: they're
        // the backdrop the elements are placed against, not what's being placed.
        if (minecraft.level == null && !BackdropCache.reusing()) {
            MenuBackdrop.extract(graphics, width, height);
        }
        Panels.blurBehind(graphics);
        graphics.fillGradient(0, 0, width, height, Theme.EDITOR_TINT_TOP, Theme.EDITOR_TINT_BOTTOM);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        HudCanvas canvas = ModuleHud.begin(graphics);
        try {
            measure(canvas);
            hovered = drag == Drag.NONE ? elementAt(mouseX, mouseY) : selected;
            extractGuides(graphics);
            for (int i = 0; i < elements.size(); i++) {
                if (shown[i]) {
                    extractFrame(graphics, i);
                    system.drawHud(canvas, owners.get(i), elements.get(i));
                    extractEdge(graphics, i, mouseX, mouseY);
                }
            }
        } finally {
            ModuleHud.end();
        }
        if (selected >= 0 && shown[selected]) {
            extractAnchor(graphics, selected);
            extractTag(graphics, selected, true);
        }
        if (hovered >= 0 && hovered != selected && shown[hovered]) {
            extractTag(graphics, hovered, false);
        }

        // The toolbar fades away while an element is dragged across it.
        float fade = drag != Drag.NONE && selected >= 0 && overBar(selected) ? 0.15F : 1.0F;
        Panels.panel(graphics, barX, barY, barWidth, BAR_HEIGHT, fade);
        int textY = barY + (BAR_HEIGHT - 7) / 2;
        Icons.LAYOUT.draw(
                graphics, barX + BAR_PADDING, barY + (BAR_HEIGHT - 12) / 2, 12, Panels.alpha(Theme.ACCENT, fade));
        graphics.text(font, TITLE, barX + BAR_PADDING + 17, textY, Panels.alpha(Theme.TEXT, fade), false);
        for (AbstractButton widget : barWidgets) {
            widget.setAlpha(fade);
        }
        Button resetButton = reset;
        if (resetButton != null) {
            resetButton.setMessage(selected >= 0 ? RESET_ONE : RESET_ALL);
        }
        if (empty) {
            extractEmpty(graphics);
        }
        super.extractRenderState(graphics, mouseX, mouseY, a);
        extractCaption(graphics, fade);

        if (drag != Drag.NONE || hovered >= 0) {
            boolean handle = drag == Drag.SCALE
                    || (drag == Drag.NONE && selected >= 0 && shown[selected] && overHandle(selected, mouseX, mouseY));
            graphics.requestCursor(handle ? CursorTypes.POINTING_HAND : CursorTypes.RESIZE_ALL);
        }
    }

    /** Where each element is this frame, and which are shown. */
    private void measure(HudCanvas canvas) {
        for (int i = 0; i < elements.size(); i++) {
            HudElement element = elements.get(i);
            shown[i] = owners.get(i).isEnabled() && element.isPlaceable();
            if (!shown[i]) {
                continue;
            }
            element.bounds(canvas, bounds);
            System.arraycopy(bounds, 0, rects, i * 4, 4);
            sizes[i * 2] = Math.max(1, element.width(canvas));
            sizes[i * 2 + 1] = Math.max(1, element.height(canvas));
        }
        if (selected >= 0 && !shown[selected]) {
            selected = -1;
            drag = Drag.NONE;
        }
    }

    /** The lines an element being dragged has snapped to, and the screen's middle, faintly. */
    private void extractGuides(GuiGraphicsExtractor graphics) {
        if (drag != Drag.MOVE) {
            return;
        }
        int faint = 0x1FFFFFFF;
        vertical(graphics, width / 2, faint);
        horizontal(graphics, height / 2, faint);
        int strong = Panels.alpha(Theme.ACCENT, 0.8F);
        if (guideX != NO_GUIDE) {
            vertical(graphics, guideX, strong);
        }
        if (guideY != NO_GUIDE) {
            horizontal(graphics, guideY, strong);
        }
        // Where it was picked up from.
        RoundRects.outline(
                graphics,
                startX - FRAME,
                startY - FRAME,
                startWidth + 2 * FRAME,
                startHeight + 2 * FRAME,
                Theme.RADIUS,
                0x2EFFFFFF);
    }

    /** A line one screen pixel wide down the whole screen at GUI column {@code x}. */
    private void vertical(GuiGraphicsExtractor graphics, int x, int argb) {
        int scale = RoundRects.pixels(graphics);
        int px = Math.min(x * scale, width * scale - 1);
        graphics.fill(px, 0, px + 1, height * scale, argb);
        graphics.pose().popMatrix();
    }

    /** A line one screen pixel high across the whole screen at GUI row {@code y}. */
    private void horizontal(GuiGraphicsExtractor graphics, int y, int argb) {
        int scale = RoundRects.pixels(graphics);
        int py = Math.min(y * scale, height * scale - 1);
        graphics.fill(0, py, width * scale, py + 1, argb);
        graphics.pose().popMatrix();
    }

    /** The glass under an element: faint at rest, lit toward the accent when pointed at or chosen. */
    private void extractFrame(GuiGraphicsExtractor graphics, int i) {
        int x = rects[i * 4] - FRAME;
        int y = rects[i * 4 + 1] - FRAME;
        int w = rects[i * 4 + 2] + 2 * FRAME;
        int h = rects[i * 4 + 3] + 2 * FRAME;
        int fill;
        int glow = 0;
        if (i == selected) {
            glow = Look.normal() ? Theme.GLOW : 0;
            fill = Panels.alpha(Theme.ACCENT, 0.16F);
        } else if (i == hovered) {
            fill = Panels.alpha(Theme.ACCENT, 0.1F);
        } else {
            fill = 0x1FFFFFFF;
        }
        RoundRects.box(graphics, x, y, w, h, Theme.RADIUS, fill, 0, glow, 0, Panels.alpha(Theme.ACCENT, 0.3F));
    }

    /** The element's edge, over it, and the chosen one's resize handle. */
    private void extractEdge(GuiGraphicsExtractor graphics, int i, int mouseX, int mouseY) {
        int x = rects[i * 4] - FRAME;
        int y = rects[i * 4 + 1] - FRAME;
        int w = rects[i * 4 + 2] + 2 * FRAME;
        int h = rects[i * 4 + 3] + 2 * FRAME;
        int edge = i == selected ? Theme.ACCENT : i == hovered ? Panels.alpha(Theme.ACCENT, 0.6F) : 0x59FFFFFF;
        RoundRects.outline(graphics, x, y, w, h, Theme.RADIUS, edge);
        if (i == selected) {
            int hx = handleX(i);
            int hy = handleY(i);
            boolean hot = drag == Drag.SCALE || (drag == Drag.NONE && overHandle(i, mouseX, mouseY));
            RoundRects.box(
                    graphics,
                    hx,
                    hy,
                    HANDLE,
                    HANDLE,
                    HANDLE / 2,
                    hot ? Theme.ACCENT : Theme.TEXT,
                    hot ? Theme.TEXT : Theme.ACCENT);
        }
    }

    /**
     * Where the chosen element's anchor is, the corner, side or middle it keeps its place from: a
     * short accent bar along that side (two at a corner), a dot in the middle.
     */
    private void extractAnchor(GuiGraphicsExtractor graphics, int i) {
        Anchor anchor = elements.get(i).anchor().get();
        int column = anchor.column();
        int row = anchor.row();
        int color = Panels.alpha(Theme.ACCENT, 0.9F);
        int length = ANCHOR_BAR;
        if (column == 1 && row == 1) {
            RoundRects.fill(graphics, width / 2 - 2, height / 2 - 2, 4, 4, 2, color);
            return;
        }
        // Across: along the top or bottom; down: along the left or right.
        int barX = column == 0 ? 0 : column == 1 ? (width - length) / 2 : width - length;
        int barY = row == 0 ? 0 : row == 1 ? (height - length) / 2 : height - length;
        if (row != 1) {
            graphics.fill(barX, row == 0 ? 0 : height - 1, barX + length, row == 0 ? 1 : height, color);
        }
        if (column != 1) {
            graphics.fill(column == 0 ? 0 : width - 1, barY, column == 0 ? 1 : width, barY + length, color);
        }
    }

    /** The element's name on a pill above it (below, at the top of the screen): accent when chosen. */
    private void extractTag(GuiGraphicsExtractor graphics, int i, boolean chosen) {
        String name = tag(i);
        int w = font.width(name) + 10;
        int ex = rects[i * 4];
        int ey = rects[i * 4 + 1];
        int ew = rects[i * 4 + 2];
        int eh = rects[i * 4 + 3];
        int x = Math.max(2, Math.min(width - w - 2, ex + (ew - w) / 2));
        int y = ey - FRAME - 3 - TAG_HEIGHT;
        if (y < 2) {
            y = ey + eh + FRAME + 3;
        }
        RoundRects.fill(graphics, x, y, w, TAG_HEIGHT, TAG_HEIGHT / 2, chosen ? Theme.ACCENT : Theme.FLOATING);
        graphics.text(font, name, x + 5, y + 2, chosen ? Theme.ON_ACCENT : Theme.TEXT, false);
    }

    private String tag(int i) {
        Module owner = owners.get(i);
        HudElement element = elements.get(i);
        String name = owner.hudElements().size() > 1 ? owner.name() + " · " + element.name() : owner.name();
        float scale = element.scale().get();
        return scale == 1.0F ? name : name + "  " + element.scale().format(scale);
    }

    /** A line under the toolbar: how to use the editor, or where the element in hand is going. */
    private void extractCaption(GuiGraphicsExtractor graphics, float fade) {
        String text;
        if (drag == Drag.MOVE && selected >= 0) {
            HudElement element = elements.get(selected);
            text = element.anchor().label() + "  ·  " + element.offsetX().get() + ", "
                    + element.offsetY().get();
        } else if (drag == Drag.SCALE && selected >= 0) {
            HudElement element = elements.get(selected);
            text = "Size " + element.scale().format(element.scale().get());
        } else if (selected >= 0) {
            HudElement element = elements.get(selected);
            text = tag(selected) + "  ·  " + element.anchor().label() + "  ·  Arrows nudge  ·  Ctrl+Z undoes";
        } else {
            text = font.width(HINT) + 24 <= width ? HINT : SHORT_HINT;
        }
        int w = font.width(text) + 16;
        int x = (width - w) / 2;
        int y = barY + BAR_HEIGHT + 5;
        RoundRects.fill(graphics, x, y, w, 14, 7, Panels.alpha(Theme.BACKGROUND, fade));
        graphics.text(font, text, x + 8, y + 3, Panels.alpha(Theme.MUTED, fade), false);
    }

    /** With nothing to place: a card that says so, and a way back to turn something on. */
    private void extractEmpty(GuiGraphicsExtractor graphics) {
        int x = (width - EMPTY_WIDTH) / 2;
        int y = (height - EMPTY_HEIGHT) / 2;
        Panels.panel(graphics, x, y, EMPTY_WIDTH, EMPTY_HEIGHT, 1.0F);
        Icons.LAYOUT.draw(graphics, x + 12, y + 11, 12, Theme.ACCENT);
        graphics.text(font, "Nothing on the HUD yet", x + 30, y + 14, Theme.TEXT, false);
        int lineY = y + 31;
        for (FormattedCharSequence line : font.split(
                Component.literal("Turn on a HUD module, like Toggle Sprint, to place it here."), EMPTY_WIDTH - 24)) {
            graphics.text(font, line, x + 12, lineY, Theme.MUTED, false);
            lineY += 10;
        }
    }

    // Geometry.

    /** The topmost element under the pointer, by its frame, or -1. */
    private int elementAt(double mouseX, double mouseY) {
        for (int i = elements.size() - 1; i >= 0; i--) {
            if (shown[i]
                    && mouseX >= rects[i * 4] - FRAME
                    && mouseX < rects[i * 4] + rects[i * 4 + 2] + FRAME
                    && mouseY >= rects[i * 4 + 1] - FRAME
                    && mouseY < rects[i * 4 + 1] + rects[i * 4 + 3] + FRAME) {
                return i;
            }
        }
        return -1;
    }

    // On the frame's bottom right corner, kept on screen for an element against the side.
    private int handleX(int i) {
        return Math.min(width - HANDLE, rects[i * 4] + rects[i * 4 + 2] + FRAME - HANDLE / 2 - 1);
    }

    private int handleY(int i) {
        return Math.min(height - HANDLE, rects[i * 4 + 1] + rects[i * 4 + 3] + FRAME - HANDLE / 2 - 1);
    }

    /** Whether the pointer is on element {@code i}'s resize handle, give or take a unit. */
    private boolean overHandle(int i, double mouseX, double mouseY) {
        int x = handleX(i);
        int y = handleY(i);
        return mouseX >= x - 1 && mouseX < x + HANDLE + 1 && mouseY >= y - 1 && mouseY < y + HANDLE + 1;
    }

    /** Whether element {@code i} is under the toolbar or its caption. */
    private boolean overBar(int i) {
        int bottom = barY + BAR_HEIGHT + 20;
        return rects[i * 4] < barX + barWidth && rects[i * 4] + rects[i * 4 + 2] > barX && rects[i * 4 + 1] < bottom;
    }

    // Input.

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        double mouseX = event.x();
        double mouseY = event.y();
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT
                && selected >= 0
                && shown[selected]
                && overHandle(selected, mouseX, mouseY)) {
            pickUp(Drag.SCALE, selected, mouseX, mouseY);
            return true;
        }
        int at = elementAt(mouseX, mouseY);
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (at >= 0) {
                IncariModules.openSettings(this, owners.get(at));
                return true;
            }
            return false;
        }
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }
        selected = at;
        if (at >= 0) {
            pickUp(Drag.MOVE, at, mouseX, mouseY);
        }
        return true;
    }

    private void pickUp(Drag kind, int i, double mouseX, double mouseY) {
        drag = kind;
        selected = i;
        startX = rects[i * 4];
        startY = rects[i * 4 + 1];
        startWidth = rects[i * 4 + 2];
        startHeight = rects[i * 4 + 3];
        grabX = mouseX - startX;
        grabY = mouseY - startY;
        remember(i, null);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (drag == Drag.NONE || selected < 0) {
            return super.mouseDragged(event, dx, dy);
        }
        int i = selected;
        if (drag == Drag.MOVE) {
            moveTo(
                    i,
                    (int) Math.round(event.x() - grabX),
                    (int) Math.round(event.y() - grabY),
                    startWidth,
                    startHeight,
                    !snapping || event.hasShiftDown());
        } else {
            // The corner follows the pointer; the top left stays put.
            HudElement element = elements.get(i);
            float across = (float) (event.x() - startX) / sizes[i * 2];
            float down = (float) (event.y() - startY) / sizes[i * 2 + 1];
            element.scale().set(Math.max(across, down));
            resized(i, startX, startY);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (drag != Drag.NONE) {
            drag = Drag.NONE;
            guideX = NO_GUIDE;
            guideY = NO_GUIDE;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int at = drag == Drag.NONE ? elementAt(mouseX, mouseY) : -1;
        if (at < 0 || scrollY == 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        selected = at;
        remember(at, Drag.SCALE);
        HudElement element = elements.get(at);
        element.scale()
                .set(element.scale().get()
                        + (scrollY > 0 ? 1 : -1) * element.scale().step());
        // Grows and shrinks about its middle.
        int centerX = rects[at * 4] + rects[at * 4 + 2] / 2;
        int centerY = rects[at * 4 + 1] + rects[at * 4 + 3] / 2;
        float scale = element.scale().get();
        int w = Math.round(sizes[at * 2] * scale);
        int h = Math.round(sizes[at * 2 + 1] * scale);
        resized(at, centerX - w / 2, centerY - h / 2);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.shortcutKey() == 'z' && event.hasControlDownWithQuirk()) {
            undo();
            return true;
        }
        if (selected >= 0 && shown[selected] && drag == Drag.NONE) {
            int step = event.hasShiftDown() ? 10 : 1;
            int dx = event.isLeft() ? -step : event.isRight() ? step : 0;
            int dy = event.isUp() ? -step : event.isDown() ? step : 0;
            if (dx != 0 || dy != 0) {
                remember(selected, Drag.MOVE);
                int i = selected;
                moveTo(i, rects[i * 4] + dx, rects[i * 4 + 1] + dy, rects[i * 4 + 2], rects[i * 4 + 3], true);
                return true;
            }
        }
        if (event.isCycleFocus() && !empty) {
            cycle(event.hasShiftDown() ? -1 : 1);
            return true;
        }
        return super.keyPressed(event);
    }

    /** Chooses the next shown element, or the one before. */
    private void cycle(int direction) {
        int n = elements.size();
        int i = selected < 0 ? (direction > 0 ? -1 : 0) : selected;
        for (int k = 0; k < n; k++) {
            i = Math.floorMod(i + direction, n);
            if (shown[i]) {
                selected = i;
                return;
            }
        }
    }

    // Placing.

    /**
     * Puts element {@code i}, {@code w} by {@code h}, with its top left at {@code x, y} on the
     * screen, snapped unless {@code free}.
     */
    private void moveTo(int i, int x, int y, int w, int h, boolean free) {
        x = Math.max(0, Math.min(x, width - w));
        y = Math.max(0, Math.min(y, height - h));
        guideX = NO_GUIDE;
        guideY = NO_GUIDE;
        if (!free) {
            x = snap(i, x, w, width, true);
            y = snap(i, y, h, height, false);
        }
        elements.get(i).place(x, y, w, h, width, height);
        rects[i * 4] = x;
        rects[i * 4 + 1] = y;
    }

    /** After a new scale: element {@code i} placed with its top left near {@code x, y}, kept on screen. */
    private void resized(int i, int x, int y) {
        HudElement element = elements.get(i);
        float scale = element.scale().get();
        int w = Math.round(sizes[i * 2] * scale);
        int h = Math.round(sizes[i * 2 + 1] * scale);
        x = Math.max(0, Math.min(x, width - w));
        y = Math.max(0, Math.min(y, height - h));
        element.place(x, y, w, h, width, height);
        rects[i * 4] = x;
        rects[i * 4 + 1] = y;
        rects[i * 4 + 2] = w;
        rects[i * 4 + 3] = h;
    }

    private int snapBest;
    private int snapDistance;
    private int snapGuide;

    /**
     * Element {@code i}'s start along one axis, snapped to the nearest line within {@link #SNAP}:
     * the screen's sides (at {@link #EDGE}) and middle, the other elements' edges and middles, and
     * {@link #GAP} beside them. The line it snapped to becomes that axis's guide.
     */
    private int snap(int i, int start, int size, int screen, boolean across) {
        snapBest = start;
        snapDistance = SNAP + 1;
        snapGuide = NO_GUIDE;
        consider(start, EDGE, EDGE);
        consider(start, screen - EDGE - size, screen - EDGE);
        consider(start, (screen - size) / 2, screen / 2);
        int offset = across ? 0 : 1;
        for (int j = 0; j < elements.size(); j++) {
            if (j == i || !shown[j]) {
                continue;
            }
            int other = rects[j * 4 + offset];
            int otherSize = rects[j * 4 + 2 + offset];
            consider(start, other, other);
            consider(start, other + otherSize - size, other + otherSize);
            consider(start, other + (otherSize - size) / 2, other + otherSize / 2);
            consider(start, other + otherSize + GAP, other + otherSize + GAP / 2);
            consider(start, other - GAP - size, other - GAP / 2);
        }
        if (snapGuide != NO_GUIDE) {
            if (across) {
                guideX = snapGuide;
            } else {
                guideY = snapGuide;
            }
        }
        return snapBest;
    }

    private void consider(int start, int candidate, int guide) {
        int distance = Math.abs(candidate - start);
        if (distance < snapDistance) {
            snapDistance = distance;
            snapBest = candidate;
            snapGuide = guide;
        }
    }

    /** Back to the defaults: the chosen element, or every shown one. */
    private void reset() {
        List<Placement> step = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            if (shown[i] && (selected < 0 || i == selected)) {
                step.add(Placement.of(elements.get(i)));
                elements.get(i).resetPlacement();
            }
        }
        push(step);
        mergeKind = null;
    }

    /** Saves element {@code i}'s placement to undo to, unless it continues the last step. */
    private void remember(int i, @Nullable Drag kind) {
        long now = Util.getMillis();
        boolean merges = kind != null && kind == mergeKind && i == mergeIndex && now - mergeAt < MERGE_MILLIS;
        mergeKind = kind;
        mergeIndex = i;
        mergeAt = now;
        if (!merges) {
            push(List.of(Placement.of(elements.get(i))));
        }
    }

    private void push(List<Placement> step) {
        if (step.isEmpty()) {
            return;
        }
        undo.push(step);
        while (undo.size() > UNDO_LIMIT) {
            undo.removeLast();
        }
    }

    private void undo() {
        List<Placement> step = undo.poll();
        if (step != null) {
            for (Placement placement : step) {
                placement.restore();
            }
        }
        mergeKind = null;
    }

    /** The toolbar's switch: whether dragging snaps. */
    private static final class SnapSwitch extends AbstractButton {

        private static final Component LABEL = Component.literal("Snap");

        SnapSwitch() {
            super(
                    0,
                    0,
                    net.minecraft.client.Minecraft.getInstance().font.width(LABEL) + 6 + Panels.SWITCH_WIDTH + 8,
                    Theme.CONTROL_HEIGHT,
                    LABEL);
        }

        @Override
        public void onPress(InputWithModifiers input) {
            snapping = !snapping;
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            var font = net.minecraft.client.Minecraft.getInstance().font;
            if (isHoveredOrFocused()) {
                RoundRects.fill(
                        graphics,
                        getX(),
                        getY(),
                        getWidth(),
                        getHeight(),
                        Theme.RADIUS,
                        Panels.alpha(Theme.BUTTON, alpha));
            }
            int textY = getY() + (getHeight() - 7) / 2;
            graphics.text(
                    font,
                    getMessage(),
                    getX() + 4,
                    textY,
                    Panels.alpha(snapping ? Theme.TEXT : Theme.MUTED, alpha),
                    false);
            Panels.toggle(
                    graphics,
                    getRight() - 4 - Panels.SWITCH_WIDTH,
                    getY() + (getHeight() - Panels.SWITCH_HEIGHT) / 2,
                    snapping,
                    alpha);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
