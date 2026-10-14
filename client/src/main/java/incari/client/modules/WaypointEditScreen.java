package incari.client.modules;

import incari.client.ui.Icons;
import incari.client.ui.Panels;
import incari.client.ui.RoundRects;
import incari.client.ui.ScreenPanels;
import incari.client.ui.Shapes;
import incari.client.ui.Theme;
import incari.client.ui.Ui;
import incari.framework.ModuleSystem;
import incari.framework.game.PlayerView;
import incari.framework.hud.Shape;
import incari.framework.waypoint.Waypoint;
import incari.framework.waypoint.Waypoints;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import org.jspecify.annotations.Nullable;

/**
 * A waypoint's editor (ADR 0051): its name, its color from the palette (or any, by hex), its icon,
 * where it is (with a button for where the player stands), whether it's shown and whether it has a
 * beam. A preview at the top shows it as it will look over the world. Changes are made to a copy and
 * kept only on Save; a new waypoint is added only then. Enter saves, Escape cancels.
 */
final class WaypointEditScreen extends Screen {

    private static final int PADDING = 12;
    private static final int LABEL = 52;
    private static final int LINE = Theme.CONTROL_HEIGHT;
    private static final int LINE_GAP = 6;
    private static final int SWATCH = 14;
    private static final int SWATCH_GAP = 4;
    private static final int SHAPE_WIDTH = 26;
    private static final int SHAPE_GAP = 5;
    /** The widest a coordinate is: the world border's, and a little. */
    private static final int MAX_COORDINATE = 30_000_000;

    private final @Nullable Screen parent;
    private final ModuleSystem system;
    private final Waypoints waypoints;
    /** The waypoint in the world's list (or the draft, if adding), and the copy being edited. */
    private final Waypoint original;

    private final Waypoint edited;
    private final boolean adding;

    private final List<Row> labels = new ArrayList<>();
    private Ui.@Nullable ColorField hex;
    private final EditBox[] coordinates = new EditBox[3];
    private @Nullable Button save;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int previewY;

    /** A label on the left of a line of controls. */
    private record Row(String text, int y) {}

    WaypointEditScreen(@Nullable Screen parent, ModuleSystem system, Waypoint waypoint, boolean adding) {
        super(Component.literal(adding ? "New waypoint" : "Edit waypoint"));
        this.parent = parent;
        this.system = system;
        this.waypoints = system.waypoints();
        this.original = waypoint;
        this.edited = adding ? waypoint : waypoint.copy();
        this.adding = adding;
    }

    @Override
    protected void init() {
        labels.clear();
        int content = SWATCH * Waypoint.COLORS.size() + SWATCH_GAP * (Waypoint.COLORS.size() - 1) + 6 + 76;
        panelWidth = Math.min(width - 16, 2 * PADDING + LABEL + content);
        panelHeight = Math.min(height - 12, 2 * PADDING + 14 + 34 + 5 * LINE + 4 * LINE_GAP + 12 + LINE);
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        ScreenPanels.clear(this);
        ScreenPanels.add(this, panelX, panelY, panelWidth, panelHeight);
        int left = panelX + PADDING + LABEL;
        int right = panelX + panelWidth - PADDING;
        previewY = panelY + PADDING + 14;
        int y = previewY + 34;

        // Name.
        labels.add(new Row("Name", y));
        EditBox field = Ui.textField(font, Component.literal("Name"), right - left, Waypoint.MAX_NAME);
        field.setPosition(left, y);
        field.setValue(edited.name());
        field.setHint(Component.literal("Name this place").withColor(Theme.MUTED));
        field.setResponder(text -> {
            edited.setName(text);
            validate();
        });
        addRenderableWidget(field);
        y += LINE + LINE_GAP;

        // Color: the palette, then any color by hex.
        labels.add(new Row("Color", y));
        int x = left;
        for (int rgb : Waypoint.COLORS) {
            addRenderableWidget(new Swatch(x, y + (LINE - SWATCH) / 2, rgb));
            x += SWATCH + SWATCH_GAP;
        }
        Ui.ColorField color =
                Ui.colorField(font, Component.literal("Color"), edited.argb(), false, right - x - 2, rgb -> {
                    edited.setRgb(rgb);
                });
        color.setPosition(x + 2, y);
        hex = color;
        addRenderableWidget(color);
        y += LINE + LINE_GAP;

        // Icon.
        labels.add(new Row("Icon", y));
        x = left;
        for (Shape shape : Shape.MARKERS) {
            addRenderableWidget(new ShapeButton(x, y, shape));
            x += SHAPE_WIDTH + SHAPE_GAP;
        }
        y += LINE + LINE_GAP;

        // Position: x, y and z, and where the player stands.
        labels.add(new Row("Position", y));
        int hereWidth = 58;
        int fieldWidth = (right - left - hereWidth - 3 * 14) / 3;
        int[] values = {edited.x(), edited.y(), edited.z()};
        x = left;
        for (int i = 0; i < 3; i++) {
            EditBox coordinate = Ui.textField(font, Component.literal("XYZ".substring(i, i + 1)), fieldWidth, 9);
            coordinate.setPosition(x + 10, y);
            coordinate.setValue(String.valueOf(values[i]));
            coordinate.setResponder(text -> validate());
            coordinates[i] = coordinate;
            addRenderableWidget(coordinate);
            x += 10 + fieldWidth + 4;
        }
        var here = Ui.menuButton(Component.literal("Here"), Icons.TARGET, right - x, this::useHere);
        here.setPosition(x, y);
        here.setTooltip(Tooltip.create(Component.literal("Where you stand now")));
        here.active = system.game().player() != null;
        addRenderableWidget(here);
        y += LINE + LINE_GAP;

        // Shown, and the beam.
        labels.add(new Row("Show", y));
        int half = (right - left - Theme.GAP) / 2;
        addRenderableWidget(
                new Switch(left, y, half, "On the map and screen", edited::isVisible, on -> edited.setVisible(on)));
        addRenderableWidget(
                new Switch(left + half + Theme.GAP, y, half, "Beacon beam", edited::hasBeam, on -> edited.setBeam(on)));

        // Delete (an existing one), Cancel, Save.
        int bottom = panelY + panelHeight - PADDING - LINE;
        int buttonWidth = 76;
        Button keep = Ui.button(Component.literal(adding ? "Add" : "Save"), buttonWidth, this::save);
        keep.setPosition(right - buttonWidth, bottom);
        save = keep;
        addRenderableWidget(keep);
        Button cancel = Ui.button(CommonComponents.GUI_CANCEL, buttonWidth, this::onClose);
        cancel.setPosition(right - 2 * buttonWidth - Theme.GAP, bottom);
        addRenderableWidget(cancel);
        if (!adding) {
            Button delete = Ui.menuButton(Component.literal("Delete"), Icons.TRASH, buttonWidth, this::delete);
            delete.setPosition(panelX + PADDING, bottom);
            addRenderableWidget(delete);
        }
        validate();
        if (adding) {
            // Typing names it at once: the suggested name is selected.
            setInitialFocus(field);
            field.moveCursorToEnd(false);
            field.setHighlightPos(0);
        }
    }

    /** The coordinates typed, or null if one isn't a number in range; fields that aren't go red. */
    private int @Nullable [] typedPosition() {
        int[] values = new int[3];
        boolean valid = true;
        for (int i = 0; i < 3; i++) {
            EditBox field = coordinates[i];
            if (field == null) {
                return null;
            }
            try {
                values[i] = Integer.parseInt(field.getValue().strip());
                boolean inRange = Math.abs(values[i]) <= MAX_COORDINATE;
                field.setTextColor(inRange ? Theme.TEXT : Theme.NEGATIVE);
                valid &= inRange;
            } catch (NumberFormatException e) {
                field.setTextColor(Theme.NEGATIVE);
                valid = false;
            }
        }
        return valid ? values : null;
    }

    private void validate() {
        int[] position = typedPosition();
        Button keep = save;
        if (keep != null) {
            keep.active = position != null && !edited.name().isEmpty();
        }
    }

    private void useHere() {
        PlayerView player = system.game().player();
        if (player == null) {
            return;
        }
        int[] here = {(int) Math.floor(player.x()), (int) Math.floor(player.y()), (int) Math.floor(player.z())};
        for (int i = 0; i < 3; i++) {
            EditBox field = coordinates[i];
            if (field != null) {
                field.setValue(String.valueOf(here[i]));
            }
        }
        if (!edited.dimension().equals(player.dimension())) {
            // Somewhere else now: the same name and look, in the player's dimension.
            Waypoint moved = new Waypoint(
                    edited.name(), here[0], here[1], here[2], player.dimension(), edited.rgb(), edited.icon());
            moved.setVisible(edited.isVisible());
            moved.setBeam(edited.hasBeam());
            edited.set(moved);
        }
    }

    private void save() {
        int[] position = typedPosition();
        if (position == null || edited.name().isEmpty()) {
            return;
        }
        edited.setPosition(position[0], position[1], position[2]);
        if (adding) {
            waypoints.add(edited);
        } else {
            original.set(edited);
        }
        waypoints.flush();
        onClose();
    }

    private void delete() {
        waypoints.remove(original);
        waypoints.flush();
        onClose();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isConfirmation() && !(getFocused() instanceof Button)) {
            Button keep = save;
            if (keep != null && keep.active) {
                save();
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void tick() {
        super.tick();
        Ui.ColorField field = hex;
        if (field != null) {
            field.show(edited.argb());
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.text(font, title, panelX + PADDING, panelY + PADDING, Theme.TEXT, false);
        String where = "In " + WaypointListScreen.dimensionName(edited.dimension());
        graphics.text(
                font, where, panelX + panelWidth - PADDING - font.width(where), panelY + PADDING, Theme.MUTED, false);
        drawPreview(graphics);
        for (Row row : labels) {
            graphics.text(font, row.text(), panelX + PADDING, row.y() + (LINE - 8) / 2, Theme.MUTED, false);
        }
        for (int i = 0; i < 3; i++) {
            EditBox field = coordinates[i];
            if (field != null) {
                graphics.text(font, "XYZ".substring(i, i + 1), field.getX() - 9, field.getY() + 6, Theme.MUTED, false);
            }
        }
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    /** The waypoint as the world will show it: its icon, and its name and distance on a glass tag. */
    private void drawPreview(GuiGraphicsExtractor graphics) {
        int left = panelX + PADDING;
        int wellWidth = panelWidth - 2 * PADDING;
        RoundRects.fill(graphics, left, previewY, wellWidth, 28, Theme.RADIUS + 1, Theme.FIELD);
        String shownName = edited.name().isEmpty() ? "Name this place" : edited.name();
        PlayerView player = system.game().player();
        int[] position = typedPosition();
        String far = "";
        if (player != null && position != null && edited.dimension().equals(player.dimension())) {
            double dx = position[0] + 0.5 - player.x();
            double dy = position[1] + 0.5 - player.y();
            double dz = position[2] + 0.5 - player.z();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            far = distance < 2 ? "here" : WaypointListScreen.distance(distance);
        }
        Font font = this.font;
        int nameWidth = font.width(shownName);
        int farWidth = far.isEmpty() ? 0 : font.width(far) + 5;
        int tagWidth = 8 + nameWidth + farWidth;
        int iconSize = 12;
        int total = iconSize + 6 + tagWidth;
        int x = left + (wellWidth - total) / 2;
        int centerY = previewY + 14;
        int color = edited.isVisible() ? edited.argb() : ARGB.multiplyAlpha(edited.argb(), 0.4F);
        Shapes.draw(graphics, edited.icon(), x + iconSize / 2.0F, centerY, iconSize, 0, color);
        int tagX = x + iconSize + 6;
        RoundRects.fill(graphics, tagX, centerY - 7, tagWidth, 14, Theme.RADIUS, Theme.BACKGROUND);
        graphics.text(font, shownName, tagX + 4, centerY - 4, edited.name().isEmpty() ? Theme.MUTED : Theme.TEXT, true);
        if (!far.isEmpty()) {
            graphics.text(font, far, tagX + 4 + nameWidth + 5, centerY - 4, Theme.MUTED, true);
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    /** One of the palette's colors: a dot, ringed in the accent while chosen. */
    private final class Swatch extends AbstractButton {

        private final int rgb;

        Swatch(int x, int y, int rgb) {
            super(x, y, SWATCH, SWATCH, Component.literal("#" + Integer.toHexString(rgb)));
            this.rgb = rgb;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            edited.setRgb(rgb);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            boolean chosen = edited.rgb() == rgb;
            float center = SWATCH / 2.0F;
            if (chosen || isHoveredOrFocused()) {
                incari.client.ui.Circles.ring(
                        graphics, getX() + center, getY() + center, center, 1.5F, chosen ? Theme.ACCENT : Theme.MUTED);
            }
            incari.client.ui.Circles.disc(graphics, getX() + center, getY() + center, center - 3, 0xFF000000 | rgb);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** One of the icons: the shape in the waypoint's color on a card, edged in the accent while chosen. */
    private final class ShapeButton extends AbstractButton {

        private final Shape shape;

        ShapeButton(int x, int y, Shape shape) {
            super(x, y, SHAPE_WIDTH, LINE, Component.literal(shape.label()));
            this.shape = shape;
            setTooltip(Tooltip.create(Component.literal(shape.label())));
        }

        @Override
        public void onPress(InputWithModifiers input) {
            edited.setIcon(shape);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            boolean chosen = edited.icon() == shape;
            // The chosen one lit as if under the pointer, and edged in the accent.
            Panels.card(
                    graphics, getX(), getY(), getWidth(), getHeight(), chosen || isHoveredOrFocused(), chosen, 1.0F);
            if (chosen) {
                RoundRects.outline(graphics, getX(), getY(), getWidth(), getHeight(), Theme.RADIUS + 1, Theme.ACCENT);
            }
            Shapes.draw(
                    graphics,
                    shape,
                    getX() + getWidth() / 2.0F,
                    getY() + getHeight() / 2.0F,
                    12,
                    0,
                    chosen ? edited.argb() : ARGB.multiplyAlpha(edited.argb(), 0.75F));
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** A label and an on/off switch on one card; a click anywhere flips it. */
    private static final class Switch extends AbstractButton {

        private final BooleanSupplier value;
        private final Consumer<Boolean> set;

        Switch(int x, int y, int width, String label, BooleanSupplier value, Consumer<Boolean> set) {
            super(x, y, width, LINE, Component.literal(label));
            this.value = value;
            this.set = set;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            set.accept(!value.getAsBoolean());
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            boolean on = value.getAsBoolean();
            Panels.card(graphics, getX(), getY(), getWidth(), getHeight(), isHoveredOrFocused(), false, 1.0F);
            Font font = Minecraft.getInstance().font;
            graphics.text(font, getMessage(), getX() + 8, getY() + (getHeight() - 8) / 2, Theme.TEXT, false);
            Panels.toggle(
                    graphics,
                    getRight() - 7 - Panels.SWITCH_WIDTH,
                    getY() + (getHeight() - Panels.SWITCH_HEIGHT) / 2,
                    on,
                    1.0F);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
