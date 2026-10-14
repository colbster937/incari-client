package incari.client.modules;

import incari.client.ui.Icons;
import incari.client.ui.Panels;
import incari.client.ui.RoundRects;
import incari.client.ui.ScreenPanels;
import incari.client.ui.Shapes;
import incari.client.ui.Theme;
import incari.client.ui.Ui;
import incari.framework.Module;
import incari.framework.ModuleSystem;
import incari.framework.game.PlayerView;
import incari.framework.hud.Shape;
import incari.framework.setting.Setting;
import incari.framework.waypoint.Waypoint;
import incari.framework.waypoint.Waypoints;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The world's waypoints (ADR 0051): one glass panel with a search field and New waypoint at the top,
 * and a row for each waypoint, those in the player's dimension first and nearest first, then the
 * other dimensions' under their names. A row shows the waypoint's icon in its color, its name, where
 * it is and how far; its eye shows or hides it, its pencil (or a click on the row) opens the editor,
 * and its bin deletes it, after a second click to be sure. Opened by the Waypoints module's key or
 * its settings.
 */
final class WaypointListScreen extends Screen {

    private static final int PADDING = 10;
    private static final int ROW = 30;
    private static final int GAP = 4;
    private static final int HEADING = 18;
    private static final int BUTTON = 16;
    private static final int SCROLL_STEP = 24;
    /** How long a bin waits for its second click. */
    private static final long CONFIRM_MILLIS = 3000;

    private static final Component NEW = Component.literal("New waypoint");

    private final @Nullable Screen parent;
    private final ModuleSystem system;
    private final Waypoints waypoints;
    private final List<Object> lines = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();

    private String query = "";
    private double scroll;
    private long shownRevision = -1;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int listX;
    private int listY;
    private int listWidth;
    private int listHeight;
    private int contentHeight;

    WaypointListScreen(@Nullable Screen parent, ModuleSystem system) {
        super(Component.literal("Waypoints"));
        this.parent = parent;
        this.system = system;
        this.waypoints = system.waypoints();
    }

    @Override
    protected void init() {
        panelWidth = Math.min(width - 24, 380);
        panelHeight = Math.min(height - 20, 300);
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        ScreenPanels.clear(this);
        ScreenPanels.add(this, panelX, panelY, panelWidth, panelHeight);

        int top = panelY + PADDING + 14;
        int newWidth = 24 + font.width(NEW) + 10;
        var add = Ui.menuButton(NEW, Icons.PLUS, newWidth, this::newWaypoint);
        add.setPosition(panelX + panelWidth - PADDING - newWidth, top);
        add.active = player() != null;
        addRenderableWidget(add);
        EditBox search =
                Ui.textField(font, Component.literal("Search"), panelWidth - 2 * PADDING - newWidth - Theme.GAP, 64);
        search.setPosition(panelX + PADDING, top);
        search.setHint(Component.literal("Search waypoints"));
        search.setValue(query);
        search.setResponder(text -> {
            query = text;
            scroll = 0;
            rebuild();
        });
        addRenderableWidget(search);

        var done = Ui.button(CommonComponents.GUI_DONE, 120, this::onClose);
        done.setPosition(panelX + (panelWidth - 120) / 2, panelY + panelHeight - PADDING - Theme.CONTROL_HEIGHT);
        addRenderableWidget(done);

        listX = panelX + PADDING;
        listY = top + Theme.CONTROL_HEIGHT + 8;
        listWidth = panelWidth - 2 * PADDING;
        listHeight = done.getY() - 8 - listY;
        rebuild();
    }

    private @Nullable PlayerView player() {
        return system.game().player();
    }

    /** The rows again, from the waypoints as they are now. */
    private void rebuild() {
        for (Row row : rows) {
            removeWidget(row);
        }
        rows.clear();
        lines.clear();
        shownRevision = waypoints.revision();
        PlayerView player = player();
        String here = player == null ? PlayerView.OVERWORLD : player.dimension();
        String needle = query.strip().toLowerCase(Locale.ROOT);
        List<Waypoint> matching = new ArrayList<>();
        for (Waypoint waypoint : waypoints.all()) {
            if (needle.isEmpty() || waypoint.name().toLowerCase(Locale.ROOT).contains(needle)) {
                matching.add(waypoint);
            }
        }
        // This dimension's first, nearest first; then each other dimension's, by name.
        List<Waypoint> local = new ArrayList<>();
        List<Waypoint> elsewhere = new ArrayList<>();
        for (Waypoint waypoint : matching) {
            (waypoint.dimension().equals(here) ? local : elsewhere).add(waypoint);
        }
        if (player != null) {
            local.sort(Comparator.comparingDouble(w -> w.distanceSquared(player.x(), player.y(), player.z())));
        }
        elsewhere.sort(Comparator.comparing(Waypoint::dimension).thenComparing(Waypoint::name));
        for (Waypoint waypoint : local) {
            addRow(waypoint, player);
        }
        String dimension = null;
        for (Waypoint waypoint : elsewhere) {
            if (!waypoint.dimension().equals(dimension)) {
                dimension = waypoint.dimension();
                lines.add("In " + dimensionName(dimension));
            }
            addRow(waypoint, null);
        }
        layOut();
    }

    private void addRow(Waypoint waypoint, @Nullable PlayerView player) {
        Row row = new Row(waypoint, player);
        rows.add(row);
        lines.add(row);
        // Drawn by this screen inside the list's clip, not with the other widgets.
        addWidget(row);
    }

    /** Places the rows and headings, scrolled. */
    private void layOut() {
        int y = listY - (int) scroll;
        for (Object line : lines) {
            if (line instanceof Row row) {
                row.setRectangle(listWidth, ROW, listX, y);
                y += ROW + GAP;
            } else {
                y += HEADING;
            }
        }
        contentHeight = y + (int) scroll - listY;
        double max = Math.max(0, contentHeight - listHeight);
        if (scroll > max) {
            scroll = max;
            layOut();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (waypoints.revision() != shownRevision) {
            rebuild();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= listX && mouseX < listX + listWidth && mouseY >= listY && mouseY < listY + listHeight) {
            scroll = Math.max(0, Math.min(Math.max(0, contentHeight - listHeight), scroll - scrollY * SCROLL_STEP));
            layOut();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.text(font, title, panelX + PADDING, panelY + PADDING, Theme.TEXT, false);
        String count =
                waypoints.all().size() == 1 ? "1 waypoint" : waypoints.all().size() + " waypoints";
        graphics.text(
                font, count, panelX + panelWidth - PADDING - font.width(count), panelY + PADDING, Theme.MUTED, false);
        super.extractRenderState(graphics, mouseX, mouseY, a);

        graphics.enableScissor(listX - 4, listY, listX + listWidth + 4, listY + listHeight);
        int y = listY - (int) scroll;
        for (Object line : lines) {
            if (line instanceof Row row) {
                if (row.getBottom() > listY && row.getY() < listY + listHeight) {
                    row.extractRenderState(graphics, mouseX, mouseY, a);
                }
                y += ROW + GAP;
            } else {
                graphics.text(font, (String) line, listX + 2, y + HEADING - 13, Theme.ACCENT, false);
                y += HEADING;
            }
        }
        graphics.disableScissor();
        if (lines.isEmpty()) {
            drawEmpty(graphics);
        }
        if (contentHeight > listHeight) {
            int thumb = Math.max(16, listHeight * listHeight / contentHeight);
            int top = listY + (int) ((listHeight - thumb) * scroll / (contentHeight - listHeight));
            RoundRects.fill(graphics, listX + listWidth + 3, top, 2, thumb, 1, 0x80FFFFFF);
        }
    }

    /** What an empty list says: how to make the first waypoint, or that the search found none. */
    private void drawEmpty(GuiGraphicsExtractor graphics) {
        int centerX = listX + listWidth / 2;
        int y = listY + listHeight / 2 - 16;
        if (!waypoints.all().isEmpty()) {
            graphics.centeredText(
                    font, Component.literal("No waypoints match \"" + query.strip() + "\""), centerX, y, Theme.MUTED);
            return;
        }
        Shapes.draw(graphics, Shape.DIAMOND, centerX, y - 10, 14, 0, Theme.ACCENT);
        graphics.centeredText(font, Component.literal("No waypoints in this world yet"), centerX, y + 6, Theme.TEXT);
        String key = newWaypointKey();
        graphics.centeredText(
                font,
                Component.literal(
                        key == null
                                ? "Press New waypoint to mark your spot."
                                : "Press " + key + " in a world, or New waypoint, to mark your spot."),
                centerX,
                y + 20,
                Theme.MUTED);
    }

    /** What the Waypoints module's New waypoint key is called, or null if it has none. */
    private @Nullable String newWaypointKey() {
        Module module = system.module("waypoints");
        Setting<?> setting = module == null ? null : module.settings().get("new");
        if (setting instanceof incari.framework.setting.KeySetting key
                && key.get().isBound()) {
            return IncariModules.keys().name(key.get()).getString();
        }
        return null;
    }

    /** A new waypoint where the player stands, in the editor; added if they save it. */
    private void newWaypoint() {
        PlayerView player = player();
        if (player == null) {
            return;
        }
        Waypoint draft = draft(
                system,
                (int) Math.floor(player.x()),
                (int) Math.floor(player.y()),
                (int) Math.floor(player.z()),
                player.dimension());
        minecraft.gui.setScreen(new WaypointEditScreen(this, system, draft, true));
    }

    /** A new waypoint at a block, not yet added: the next free "Waypoint N", the palette's next color. */
    static Waypoint draft(ModuleSystem system, int x, int y, int z, String dimension) {
        List<Waypoint> all = system.waypoints().all();
        int n = all.size() + 1;
        String name = "Waypoint " + n;
        while (taken(all, name)) {
            name = "Waypoint " + ++n;
        }
        return new Waypoint(
                name, x, y, z, dimension, Waypoint.COLORS.get(all.size() % Waypoint.COLORS.size()), Shape.CIRCLE);
    }

    private static boolean taken(List<Waypoint> all, String name) {
        for (Waypoint waypoint : all) {
            if (waypoint.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** "the Overworld", "the Nether", "the End", or a modded dimension's path. */
    static String dimensionName(String dimension) {
        return switch (dimension) {
            case PlayerView.OVERWORLD -> "the Overworld";
            case PlayerView.NETHER -> "the Nether";
            case PlayerView.END -> "the End";
            default -> {
                String path = dimension.substring(dimension.indexOf(':') + 1).replace('_', ' ');
                yield path.isEmpty() ? dimension : Character.toUpperCase(path.charAt(0)) + path.substring(1);
            }
        };
    }

    /** "12 m", "1.2 km". */
    static String distance(double blocks) {
        if (blocks < 1000) {
            return (int) blocks + " m";
        }
        int tenths = (int) (blocks / 100);
        return tenths / 10 + "." + tenths % 10 + " km";
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void removed() {
        waypoints.flush();
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    /** One waypoint's row. */
    private final class Row extends AbstractButton {

        private final Waypoint waypoint;
        private final String detail;
        private long confirmUntil;

        Row(Waypoint waypoint, @Nullable PlayerView player) {
            super(0, 0, 0, ROW, Component.literal(waypoint.name()));
            this.waypoint = waypoint;
            String where = waypoint.x() + ", " + waypoint.y() + ", " + waypoint.z();
            this.detail = player == null
                    ? where
                    : where + "  ·  "
                            + distance(Math.sqrt(waypoint.distanceSquared(player.x(), player.y(), player.z())));
        }

        /** Which of the row's buttons is at {@code x}: 0 the eye, 1 the pencil, 2 the bin, or -1. */
        private int button(double x, double y) {
            if (y < getY() + (ROW - BUTTON) / 2.0 || y >= getY() + (ROW + BUTTON) / 2.0) {
                return -1;
            }
            for (int i = 0; i < 3; i++) {
                int left = buttonX(i);
                if (x >= left && x < left + BUTTON) {
                    return i;
                }
            }
            return -1;
        }

        private int buttonX(int i) {
            return getRight() - 8 - (3 - i) * BUTTON - (2 - i) * 4;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            edit();
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            switch (button(event.x(), event.y())) {
                case 0 -> waypoint.setVisible(!waypoint.isVisible());
                case 2 -> {
                    long now = Util.getMillis();
                    if (now < confirmUntil) {
                        waypoints.remove(waypoint);
                    } else {
                        confirmUntil = now + CONFIRM_MILLIS;
                    }
                }
                default -> edit();
            }
        }

        private void edit() {
            minecraft.gui.setScreen(new WaypointEditScreen(WaypointListScreen.this, system, waypoint, false));
        }

        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            return super.isMouseOver(mouseX, mouseY) && mouseY >= listY && mouseY < listY + listHeight;
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            boolean shown = waypoint.isVisible();
            boolean confirming = Util.getMillis() < confirmUntil;
            int hover = isHovered() ? button(mouseX, mouseY) : -1;
            Panels.card(
                    graphics, getX(), getY(), getWidth(), getHeight(), isHoveredOrFocused() && hover < 0, false, 1.0F);
            int color = shown ? waypoint.argb() : ARGB.multiplyAlpha(waypoint.argb(), 0.4F);
            Shapes.draw(graphics, waypoint.icon(), getX() + 15, getY() + ROW / 2.0F, 13, 0, color);
            Font font = Minecraft.getInstance().font;
            int textX = getX() + 30;
            int nameRight = buttonX(0) - 8;
            String name = font.plainSubstrByWidth(waypoint.name(), nameRight - textX);
            graphics.text(font, name, textX, getY() + 6, shown ? Theme.TEXT : Theme.MUTED, false);
            if (waypoint.hasBeam() && shown) {
                // A sliver of the beam's color after the name: this one has a beam.
                int beamX = textX + font.width(name) + 5;
                if (beamX + 2 < nameRight) {
                    RoundRects.fill(graphics, beamX, getY() + 5, 2, 9, 1, ARGB.multiplyAlpha(waypoint.argb(), 0.7F));
                }
            }
            String second = confirming ? "Click the bin again to delete" : shown ? detail : detail + "  ·  hidden";
            graphics.text(
                    font,
                    font.plainSubstrByWidth(second, nameRight - textX),
                    textX,
                    getY() + 17,
                    confirming ? Theme.NEGATIVE : Theme.MUTED,
                    false);
            icon(graphics, 0, shown ? Icons.EYE : Icons.EYE_OFF, hover == 0, Theme.TEXT);
            icon(graphics, 1, Icons.PENCIL, hover == 1, Theme.TEXT);
            icon(graphics, 2, Icons.TRASH, hover == 2 || confirming, confirming ? Theme.NEGATIVE : Theme.TEXT);
            setTooltip(
                    switch (hover) {
                        case 0 -> TOOLTIPS[shown ? 0 : 1];
                        case 1 -> TOOLTIPS[2];
                        case 2 -> TOOLTIPS[3];
                        default -> null;
                    });
        }

        private void icon(GuiGraphicsExtractor graphics, int i, Icons icon, boolean hot, int hotColor) {
            int x = buttonX(i);
            int y = getY() + (ROW - BUTTON) / 2;
            if (hot) {
                RoundRects.fill(graphics, x, y, BUTTON, BUTTON, Theme.RADIUS, Theme.BUTTON_HOT);
            }
            int size = 10;
            icon.draw(
                    graphics,
                    x + (BUTTON - size) / 2,
                    y + (BUTTON - size) / 2,
                    size,
                    hot ? (hotColor == Theme.TEXT ? Theme.ACCENT : hotColor) : ARGB.multiplyAlpha(Theme.MUTED, 0.9F));
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private static final @Nullable Tooltip[] TOOLTIPS = {
        Tooltip.create(Component.literal("Hide it")),
        Tooltip.create(Component.literal("Show it")),
        Tooltip.create(Component.literal("Edit")),
        Tooltip.create(Component.literal("Delete")),
    };
}
