package incari.client.ui;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Frost, the client's look: the game's controls redrawn as rounded glass, the backdrops as
 * the world or panorama under a cold tint, blurred in the Normal preset. The game still lays out
 * every screen and draws every label; this changes the parts it draws them on, by sprite and
 * texture name, so a control drawn anywhere in the game takes the look without a hook of its own.
 *
 * <p>Normal adds what costs frames: the blur, hover glows eased in and out ({@link Motion}), and a
 * light sweep across a button as the pointer reaches it. Potato draws the same shapes with every
 * state change at once, and shows a hovered control by an accent edge.
 *
 * <p>Render thread. Hot paths: a sprite is looked up by comparing names, and nothing allocates.
 */
final class FrostSkin implements GuiSkin {

    static final FrostSkin INSTANCE = new FrostSkin();

    // The sprites the skin draws, by kind: every sprite the GUI draws is looked up once in SPRITES,
    // whose miss (the hearts, the hunger bar, items' decorations) is one hash lookup.
    private static final int NONE = 0;
    private static final int BUTTON = 1;
    private static final int BUTTON_HOT = 2;
    private static final int BUTTON_OFF = 3;
    private static final int SLIDER = 4;
    private static final int SLIDER_HOT = 5;
    private static final int HANDLE = 6;
    private static final int HANDLE_HOT = 7;
    private static final int FIELD = 8;
    private static final int FIELD_HOT = 9;
    private static final int CHECKBOX = 10;
    private static final int CHECKBOX_HOT = 11;
    private static final int CHECKBOX_ON = 12;
    private static final int CHECKBOX_ON_HOT = 13;
    private static final int SCROLLER = 14;
    private static final int SCROLLER_TRACK = 15;
    private static final int TAB = 16;
    private static final int TAB_HOT = 17;
    private static final int TAB_ON = 18;
    private static final int TAB_ON_HOT = 19;
    private static final int TOOLTIP = 20;
    private static final int TOOLTIP_FRAME = 21;
    private static final int POPUP = 22;
    private static final int TOAST_ADVANCEMENT = 23;
    private static final int TOAST_RECIPE = 24;
    private static final int TOAST_SYSTEM = 25;
    private static final int TOAST_TUTORIAL = 26;
    private static final int TOAST_NOW_PLAYING = 27;
    private static final int EFFECT = 28;
    private static final int EFFECT_AMBIENT = 29;
    private static final int SLOT_HOT_BACK = 30;
    private static final int SLOT_HOT_FRONT = 31;
    private static final int HOTBAR = 32;
    private static final int HOTBAR_SELECTION = 33;
    private static final int HOTBAR_OFFHAND_LEFT = 34;
    private static final int HOTBAR_OFFHAND_RIGHT = 35;

    private static final Map<Identifier, Integer> SPRITES = sprites();

    private static final Identifier MENU_BACKGROUND = Screen.MENU_BACKGROUND;
    private static final Identifier INWORLD_MENU_BACKGROUND = texture("inworld_menu_background");
    private static final Identifier LIST_BACKGROUND = texture("menu_list_background");
    private static final Identifier INWORLD_LIST_BACKGROUND = AbstractSelectionList.INWORLD_MENU_LIST_BACKGROUND;
    private static final Identifier HEADER_SEPARATOR = Screen.HEADER_SEPARATOR;
    private static final Identifier FOOTER_SEPARATOR = Screen.FOOTER_SEPARATOR;
    private static final Identifier INWORLD_HEADER_SEPARATOR = Screen.INWORLD_HEADER_SEPARATOR;
    private static final Identifier INWORLD_FOOTER_SEPARATOR = Screen.INWORLD_FOOTER_SEPARATOR;

    /** How long a hovered button's light sweep takes to cross it, in milliseconds. */
    private static final int SWEEP_MILLIS = 650;

    /** The slider drawn last: its handle, drawn next, fills the track up to itself. */
    private int trackX;

    private int trackY;
    private int trackWidth;
    private int trackHeight;

    /** The frame last blurred, by its extractor (the GUI makes one a frame). */
    private static @Nullable GuiGraphicsExtractor blurredFrame;

    private FrostSkin() {}

    @Override
    public boolean sprite(
            GuiGraphicsExtractor graphics, Identifier sprite, int x, int y, int width, int height, int color) {
        Integer found = SPRITES.get(sprite);
        int kind = found == null ? NONE : found;
        if (kind == NONE) {
            return false;
        }
        float fade = (color >>> 24) / 255.0F;
        switch (kind) {
            case BUTTON, BUTTON_HOT, BUTTON_OFF ->
                button(graphics, x, y, width, height, kind == BUTTON_OFF, kind == BUTTON_HOT, fade);
            case SLIDER, SLIDER_HOT -> {
                trackX = x;
                trackY = y;
                trackWidth = width;
                trackHeight = height;
                well(graphics, x, y, width, height, Theme.BUTTON, kind == SLIDER_HOT, fade);
            }
            case HANDLE, HANDLE_HOT -> handle(graphics, x, y, width, height, kind == HANDLE_HOT, fade);
            case FIELD, FIELD_HOT -> well(graphics, x, y, width, height, Theme.FIELD, kind == FIELD_HOT, fade);
            case CHECKBOX, CHECKBOX_HOT -> well(graphics, x, y, width, height, Theme.FIELD, kind == CHECKBOX_HOT, fade);
            case CHECKBOX_ON, CHECKBOX_ON_HOT -> {
                well(graphics, x, y, width, height, Theme.FIELD, kind == CHECKBOX_ON_HOT, fade);
                RoundRects.fill(graphics, x + 4, y + 4, width - 8, height - 8, 2, Panels.alpha(Theme.ACCENT, fade));
            }
            case SCROLLER ->
                RoundRects.fill(graphics, x + 2, y, Math.max(2, width - 4), height, 2, Panels.alpha(0xA6FFFFFF, fade));
            case SCROLLER_TRACK ->
                RoundRects.fill(graphics, x + 2, y, Math.max(2, width - 4), height, 2, Panels.alpha(0x1AFFFFFF, fade));
            case TAB, TAB_HOT, TAB_ON, TAB_ON_HOT ->
                tab(
                        graphics,
                        x,
                        y,
                        width,
                        height,
                        kind == TAB_ON || kind == TAB_ON_HOT,
                        kind == TAB_HOT || kind == TAB_ON_HOT,
                        fade);
            // The game's tooltip sprites reach 9 units past the padded text on every side.
            case TOOLTIP ->
                RoundRects.fill(
                        graphics,
                        x + 7,
                        y + 7,
                        width - 14,
                        height - 14,
                        Theme.RADIUS,
                        Panels.alpha(Theme.FLOATING, fade));
            case TOOLTIP_FRAME ->
                RoundRects.outline(
                        graphics,
                        x + 7,
                        y + 7,
                        width - 14,
                        height - 14,
                        Theme.RADIUS,
                        Panels.alpha(Theme.GLASS_EDGE_SOLID, fade));
            // The sprites reach 3 units past the slot's well on every side.
            case SLOT_HOT_BACK ->
                RoundRects.fill(
                        graphics,
                        x + 3,
                        y + 3,
                        width - 6,
                        height - 6,
                        2,
                        Panels.alpha(Theme.ACCENT & 0x33FFFFFF, fade));
            case SLOT_HOT_FRONT ->
                RoundRects.outline(
                        graphics,
                        x + 3,
                        y + 3,
                        width - 6,
                        height - 6,
                        2,
                        Panels.alpha(Theme.ACCENT & 0xB3FFFFFF, fade));
            case HOTBAR -> hotbar(graphics, x, y, width, height);
            // 24 by 23 around a slot whose well is 20 square, 2 in from the sprite's left and 3 from its top.
            case HOTBAR_SELECTION ->
                RoundRects.box(
                        graphics,
                        x + 2,
                        y + 3,
                        20,
                        20,
                        3,
                        Theme.ACCENT & 0x29FFFFFF,
                        Theme.ACCENT,
                        Look.normal() ? 3 : 0,
                        0,
                        Theme.ACCENT & 0x59FFFFFF);
            case HOTBAR_OFFHAND_LEFT -> slotCard(graphics, x, y + 1, 22, 22);
            case HOTBAR_OFFHAND_RIGHT -> slotCard(graphics, x + width - 22, y + 1, 22, 22);
            case TOAST_ADVANCEMENT, TOAST_RECIPE, TOAST_SYSTEM, TOAST_TUTORIAL, TOAST_NOW_PLAYING -> {
                // A toast: a floating card. The game writes recipe and tutorial toasts in dark text, so
                // theirs is light frost; the rest are dark, edged in the accent for an advancement.
                boolean light = kind == TOAST_RECIPE || kind == TOAST_TUTORIAL;
                int edge = kind == TOAST_ADVANCEMENT
                        ? Theme.ACCENT & 0x99FFFFFF
                        : light ? 0xFFFFFFFF : Theme.GLASS_EDGE_SOLID;
                RoundRects.box(
                        graphics,
                        x + 1,
                        y + 1,
                        width - 2,
                        height - 2,
                        4,
                        Panels.alpha(light ? 0xF2E6ECF2 : Theme.FLOATING, fade),
                        Panels.alpha(edge, fade));
            }
            case EFFECT, EFFECT_AMBIENT ->
                RoundRects.box(
                        graphics,
                        x,
                        y,
                        width,
                        height,
                        3,
                        Panels.alpha(Theme.BACKGROUND, fade),
                        Panels.alpha(kind == EFFECT_AMBIENT ? Theme.ACCENT & 0x80FFFFFF : 0x1FFFFFFF, fade));
            case POPUP -> Panels.panel(graphics, x, y, width, height, fade);
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean texture(
            GuiGraphicsExtractor graphics,
            Identifier texture,
            int x,
            int y,
            int width,
            int height,
            float u,
            float v,
            int srcWidth,
            int srcHeight,
            int textureWidth,
            int textureHeight,
            int color) {
        if (ContainerSkin.texture(
                graphics,
                texture,
                x,
                y,
                width,
                height,
                u,
                v,
                srcWidth,
                srcHeight,
                textureWidth,
                textureHeight,
                color)) {
            return true;
        }
        if (texture.equals(LIST_BACKGROUND) || texture.equals(INWORLD_LIST_BACKGROUND)) {
            // A list's well: a shade darker than the backdrop, so rows read as a group.
            graphics.fill(x, y, x + width, y + height, Look.normal() ? 0x1A000000 : 0x2E000000);
        } else if (texture.equals(MENU_BACKGROUND) || texture.equals(INWORLD_MENU_BACKGROUND)) {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (screen != null && x <= 0 && y <= 0 && width >= screen.width && height >= screen.height) {
                // A screen that draws its own backdrop (the pause menu) draws the menu texture over
                // it all: the skin's backdrop instead. It has blurred already if the game's option
                // asks for it.
                backdrop(
                        graphics, screen, false, Minecraft.getInstance().options.getMenuBackgroundBlurriness() >= 1);
            } else {
                // The backdrop is the skin's own already (background): the game's dirt-dark
                // texture behind a part of the screen becomes a faint wash.
                graphics.fill(x, y, x + width, y + height, 0x14000000);
            }
        } else if (texture.equals(HEADER_SEPARATOR) || texture.equals(INWORLD_HEADER_SEPARATOR)) {
            RoundRects.hairline(graphics, x, y + height - 1, width, Theme.GLASS_EDGE_SOLID);
        } else if (texture.equals(FOOTER_SEPARATOR) || texture.equals(INWORLD_FOOTER_SEPARATOR)) {
            RoundRects.hairline(graphics, x, y, width, Theme.GLASS_EDGE_SOLID);
        } else {
            return false;
        }
        return true;
    }

    @Override
    public boolean background(GuiGraphicsExtractor graphics, Screen screen, float a) {
        backdrop(graphics, screen, !screen.isInGameUi() && Minecraft.getInstance().level == null, false);
        return true;
    }

    /**
     * The look's backdrop: the menus' photo out of a world, the world blurred in Normal, a cold
     * tint, and the screen's panels ({@link ScreenPanels}).
     */
    private static void backdrop(GuiGraphicsExtractor graphics, Screen screen, boolean photo, boolean blurred) {
        boolean normal = Look.normal();
        if (photo) {
            // Already blurred: Normal's blur pass over it would cost a frame's worth for nothing.
            MenuBackdrop.extract(graphics, screen.width, screen.height);
        } else if (normal && !blurred) {
            blur(graphics);
        }
        graphics.fillGradient(
                0,
                0,
                screen.width,
                screen.height,
                normal ? Theme.TINT_TOP : Theme.TINT_TOP_SOLID,
                normal ? Theme.TINT_BOTTOM : Theme.TINT_BOTTOM_SOLID);
        ScreenPanels.extract(graphics, screen);
    }

    /**
     * Blurs what's under this stratum, once a frame: the game allows one blur a frame and throws at
     * a second, and two screens' backdrops can be drawn in one (a screen over the pause menu).
     */
    @SuppressWarnings("ReferenceEquality") // the frame's own extractor object
    static void blur(GuiGraphicsExtractor graphics) {
        if (blurredFrame != graphics) {
            blurredFrame = graphics;
            graphics.blurBeforeThisStratum();
        }
    }

    /** A button: glass that lights up toward the pointer, with a glow and a light sweep in Normal. */
    private static void button(
            GuiGraphicsExtractor graphics, int x, int y, int width, int height, boolean off, boolean hot, float fade) {
        if (off) {
            RoundRects.box(
                    graphics,
                    x,
                    y,
                    width,
                    height,
                    Theme.RADIUS,
                    Panels.alpha(Theme.BUTTON_OFF, fade),
                    Panels.alpha(Theme.GLASS_EDGE_SOLID, fade * 0.6F));
            return;
        }
        float t = Motion.ease(Motion.key(x, y, width, height), hot ? 1 : 0);
        boolean normal = Look.normal();
        int edge = normal
                ? Panels.mix(Theme.GLASS_EDGE, Theme.ACCENT & 0x99FFFFFF, t)
                : hot ? Theme.ACCENT : Theme.GLASS_EDGE_SOLID;
        // The glow, the glass and its edge as one shape.
        RoundRects.box(
                graphics,
                x,
                y,
                width,
                height,
                Theme.RADIUS,
                Panels.alpha(Panels.mix(Theme.BUTTON, Theme.BUTTON_HOT, t), fade),
                Panels.alpha(edge, fade),
                normal && t > 0 ? Theme.GLOW : 0,
                0,
                Panels.alpha(Theme.ACCENT, 0.32F * t * fade));
        if (normal && hot) {
            sweep(graphics, x, y, width, height, Motion.since(), fade);
        }
    }

    /** A band of light crossing the button once, left to right, after the pointer reaches it. */
    private static void sweep(
            GuiGraphicsExtractor graphics, int x, int y, int width, int height, long elapsed, float fade) {
        if (elapsed >= SWEEP_MILLIS) {
            return;
        }
        float p = elapsed / (float) SWEEP_MILLIS;
        p = 1.0F - (1.0F - p) * (1.0F - p);
        int band = Math.max(16, width / 3);
        int left = x - band + Math.round(p * (width + band));
        graphics.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1);
        RoundRects.band(graphics, left, y, band, height, Panels.alpha(0x38FFFFFF, fade));
        graphics.disableScissor();
    }

    /**
     * The hotbar: nine wells on a strip of the HUD's tinted glass (182 by 22, the game's slots 20
     * apart from 1 in). Static: the HUD is cached between refreshes in the browser.
     */
    private static void hotbar(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        RoundRects.box(graphics, x, y, width, height, 4, Theme.BACKGROUND, 0x1FFFFFFF);
        for (int i = 0; i < 9; i++) {
            RoundRects.fill(graphics, x + 2 + i * 20, y + 2, 18, 18, 3, 0x14FFFFFF);
        }
    }

    /** A lone slot on the HUD's glass: the offhand's. */
    private static void slotCard(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        RoundRects.box(graphics, x, y, width, height, 4, Theme.BACKGROUND, 0x1FFFFFFF);
        RoundRects.fill(graphics, x + 2, y + 2, width - 4, height - 4, 3, 0x14FFFFFF);
    }

    /** A slider's track or a field's well, edged in the accent while focused or hovered. */
    private static void well(
            GuiGraphicsExtractor graphics, int x, int y, int width, int height, int fill, boolean hot, float fade) {
        int edge = hot ? Theme.ACCENT : Look.normal() ? Theme.GLASS_EDGE : Theme.GLASS_EDGE_SOLID;
        RoundRects.box(graphics, x, y, width, height, Theme.RADIUS, Panels.alpha(fill, fade), Panels.alpha(edge, fade));
    }

    /**
     * A slider's handle, as a line along the track's foot: lit in the accent up to the value, a
     * knob at the value. The game's label sits in the middle of the track, so nothing crosses it.
     */
    private void handle(GuiGraphicsExtractor graphics, int x, int y, int width, int height, boolean hot, float fade) {
        int value = x + width / 2;
        int line = y + height - 4;
        if (trackY == y && trackHeight == height) {
            int left = trackX + 4;
            int right = trackX + trackWidth - 4;
            RoundRects.fill(graphics, left, line, right - left, 1, 0, Panels.alpha(0x33FFFFFF, fade));
            RoundRects.fill(
                    graphics,
                    left,
                    line,
                    Math.max(0, Math.min(right, value) - left),
                    1,
                    0,
                    Panels.alpha(Theme.ACCENT, fade));
        }
        RoundRects.fill(graphics, value - 2, line - 2, 5, 5, 3, Panels.alpha(hot ? 0xFFFFFFFF : Theme.ACCENT, fade));
    }

    /** A tab: the chosen one lit with an accent line under it. */
    private static void tab(
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            int width,
            int height,
            boolean chosen,
            boolean hot,
            float fade) {
        int inset = 2;
        // Lit, or edged in the accent under the pointer in Potato.
        RoundRects.box(
                graphics,
                x + inset,
                y + inset,
                width - 2 * inset,
                height - 2 * inset,
                Theme.RADIUS,
                Panels.alpha(chosen ? Theme.BUTTON_HOT : hot ? Theme.BUTTON : Theme.BUTTON_OFF, fade),
                !chosen && hot && !Look.normal() ? Panels.alpha(Theme.ACCENT, fade) : 0);
        if (chosen) {
            RoundRects.fill(
                    graphics, x + width / 4, y + height - inset - 2, width / 2, 2, 1, Panels.alpha(Theme.ACCENT, fade));
        }
    }

    private static Map<Identifier, Integer> sprites() {
        Map<Identifier, Integer> sprites = new HashMap<>();
        sprites.put(sprite("widget/button"), BUTTON);
        sprites.put(sprite("widget/button_highlighted"), BUTTON_HOT);
        sprites.put(sprite("widget/button_disabled"), BUTTON_OFF);
        sprites.put(sprite("widget/slider"), SLIDER);
        sprites.put(sprite("widget/slider_highlighted"), SLIDER_HOT);
        sprites.put(sprite("widget/slider_handle"), HANDLE);
        sprites.put(sprite("widget/slider_handle_highlighted"), HANDLE_HOT);
        sprites.put(sprite("widget/text_field"), FIELD);
        sprites.put(sprite("widget/text_field_highlighted"), FIELD_HOT);
        sprites.put(sprite("widget/checkbox"), CHECKBOX);
        sprites.put(sprite("widget/checkbox_highlighted"), CHECKBOX_HOT);
        sprites.put(sprite("widget/checkbox_selected"), CHECKBOX_ON);
        sprites.put(sprite("widget/checkbox_selected_highlighted"), CHECKBOX_ON_HOT);
        sprites.put(sprite("widget/scroller"), SCROLLER);
        sprites.put(sprite("widget/scroller_background"), SCROLLER_TRACK);
        sprites.put(sprite("widget/tab"), TAB);
        sprites.put(sprite("widget/tab_highlighted"), TAB_HOT);
        sprites.put(sprite("widget/tab_selected"), TAB_ON);
        sprites.put(sprite("widget/tab_selected_highlighted"), TAB_ON_HOT);
        sprites.put(sprite("tooltip/background"), TOOLTIP);
        sprites.put(sprite("tooltip/frame"), TOOLTIP_FRAME);
        sprites.put(sprite("popup/background"), POPUP);
        sprites.put(sprite("toast/advancement"), TOAST_ADVANCEMENT);
        sprites.put(sprite("toast/recipe"), TOAST_RECIPE);
        sprites.put(sprite("toast/system"), TOAST_SYSTEM);
        sprites.put(sprite("toast/tutorial"), TOAST_TUTORIAL);
        sprites.put(sprite("toast/now_playing"), TOAST_NOW_PLAYING);
        sprites.put(sprite("hud/effect_background"), EFFECT);
        sprites.put(sprite("hud/effect_background_ambient"), EFFECT_AMBIENT);
        sprites.put(sprite("container/slot_highlight_back"), SLOT_HOT_BACK);
        sprites.put(sprite("container/slot_highlight_front"), SLOT_HOT_FRONT);
        sprites.put(sprite("hud/hotbar"), HOTBAR);
        sprites.put(sprite("hud/hotbar_selection"), HOTBAR_SELECTION);
        sprites.put(sprite("hud/hotbar_offhand_left"), HOTBAR_OFFHAND_LEFT);
        sprites.put(sprite("hud/hotbar_offhand_right"), HOTBAR_OFFHAND_RIGHT);
        return sprites;
    }

    private static Identifier sprite(String path) {
        return Identifier.withDefaultNamespace(path);
    }

    private static Identifier texture(String name) {
        return Identifier.withDefaultNamespace("textures/gui/" + name + ".png");
    }
}
