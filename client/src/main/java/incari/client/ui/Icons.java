package incari.client.ui;

import com.mojang.blaze3d.platform.NativeImage;
import incari.client.ui.UiAtlas.Region;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

/**
 * The client's line icons, drawn by Incari: each a few strokes and shapes described as distances,
 * painted anti-aliased into the UI atlas ({@link UiAtlas}) at the size it's shown at in screen pixels the first time it's
 * needed (as {@link RoundRects} does corners), white, and tinted when drawn.
 *
 * <p>Render thread. After an icon's first use at a size, nothing allocates.
 */
public enum Icons {
    /** Singleplayer: a block. */
    CUBE,
    /** Multiplayer: the world. */
    GLOBE,
    /** Modules. */
    GRID,
    /** Options. */
    GEAR,
    /** Quit. */
    POWER,
    /** A step from what goes in to what comes out: crafting, smelting. */
    ARROW,
    /** The HUD's layout: a screen's frame with two panels placed in it. */
    LAYOUT,
    /** Add something new. */
    PLUS,
    /** Edit. */
    PENCIL,
    /** Delete. */
    TRASH,
    /** Shown. */
    EYE,
    /** Hidden: the eye struck through. */
    EYE_OFF,
    /** Where the player is: a target. */
    TARGET,
    /** Screenshots: a camera. */
    CAMERA,
    /** Themes: a painter's palette. */
    PALETTE,
    /** Chat: a speech bubble. */
    CHAT,
    /** Yes, accept: a check mark. */
    CHECK,
    /** No, close: a cross. */
    CLOSE,
    /** Block: a circle struck through. */
    BLOCK,
    /** Report: a flag. */
    FLAG,
    /** A person: friends. */
    PERSON,
    /** Add a friend: a person and a plus. */
    PERSON_ADD,
    /** Send: a paper plane. */
    SEND,
    /** Cosmetics, the wardrobe: a coat hanger. */
    HANGER,
    /** Incari's mark, in its own colours ({@link Logo}): only the colour's alpha applies. */
    INCARI;

    private static final int MAX_PIXELS = 128;

    /** Each icon's images by its size in pixels, made as needed. */
    private static final Map<Icons, @Nullable Region[]> MADE = new EnumMap<>(Icons.class);

    /** Draws the icon {@code size} GUI units square at ({@code x}, {@code y}), in {@code argb}. */
    public void draw(GuiGraphicsExtractor graphics, int x, int y, int size, int argb) {
        if (this == INCARI) {
            // The mark leaves room around the tree for the comet's orbit: drawn a little past the
            // icon's square, so the tree stands as tall as the line icons beside it.
            int grow = Math.max(1, size / 4);
            Logo.draw(graphics, x - grow, y - grow, size + grow * 2, (argb >>> 24) / 255.0F);
            return;
        }
        int scale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        int pixels = Math.min(MAX_PIXELS, size * scale);
        if (pixels <= 0 || (argb >>> 24) == 0) {
            return;
        }
        @Nullable Region[] sizes = MADE.get(this);
        if (sizes == null) {
            sizes = new Region[MAX_PIXELS + 1];
            MADE.put(this, sizes);
        }
        Region region = sizes[pixels];
        if (region == null) {
            region = make(pixels);
            sizes[pixels] = region;
        }
        Matrix3x2f pose = new Matrix3x2f(graphics.pose()).scale(1.0F / scale);
        int px = x * scale;
        int py = y * scale;
        RoundRects.quad(graphics, pose, region, px, py, px + pixels, py + pixels, 0, 0, pixels, pixels, argb);
    }

    private Region make(int pixels) {
        NativeImage image = new NativeImage(pixels, pixels, false);
        // Strokes at least a pixel and a third wide, however small the icon.
        float stroke = Math.max(0.075F, 1.3F / pixels);
        for (int py = 0; py < pixels; py++) {
            for (int px = 0; px < pixels; px++) {
                int inside = 0;
                for (int sy = 0; sy < 4; sy++) {
                    for (int sx = 0; sx < 4; sx++) {
                        float u = (px + (sx + 0.5F) / 4.0F) / pixels;
                        float v = (py + (sy + 0.5F) / 4.0F) / pixels;
                        if (distance(u, v, stroke) < 0) {
                            inside++;
                        }
                    }
                }
                image.setPixel(px, py, Math.round(inside * 255.0F / 16.0F) << 24 | 0xFFFFFF);
            }
        }
        return UiAtlas.add(image);
    }

    /** The icon's shape as a distance from (u, v) in the unit square: negative inside. */
    private float distance(float u, float v, float stroke) {
        float x = u - 0.5F;
        float y = v - 0.5F;
        return switch (this) {
            case CUBE -> {
                float[] p = {0, -0.42F, 0.37F, -0.21F, 0.37F, 0.21F, 0, 0.42F, -0.37F, 0.21F, -0.37F, -0.21F};
                float d = Float.MAX_VALUE;
                for (int i = 0; i < 6; i++) {
                    int j = (i + 1) % 6;
                    d = Math.min(d, segment(x, y, p[i * 2], p[i * 2 + 1], p[j * 2], p[j * 2 + 1]));
                }
                d = Math.min(d, segment(x, y, 0, 0, -0.37F, -0.21F));
                d = Math.min(d, segment(x, y, 0, 0, 0.37F, -0.21F));
                d = Math.min(d, segment(x, y, 0, 0, 0, 0.42F));
                yield d - stroke * 0.5F;
            }
            case GLOBE -> {
                float ring = Math.abs(length(x, y) - 0.4F);
                float meridian = Math.abs(length(x / 0.45F, y) - 0.4F) * 0.45F;
                float equator = segment(x, y, -0.4F, 0, 0.4F, 0);
                yield Math.min(ring, Math.min(meridian, equator)) - stroke * 0.5F;
            }
            case GRID -> {
                float d = Float.MAX_VALUE;
                for (int i = 0; i < 4; i++) {
                    float cx = (i % 2 == 0 ? -0.22F : 0.22F);
                    float cy = (i < 2 ? -0.22F : 0.22F);
                    d = Math.min(d, box(x - cx, y - cy, 0.16F, 0.16F, 0.06F));
                }
                yield d;
            }
            case GEAR -> {
                float r = length(x, y);
                float body = Math.max(r - 0.31F, 0.12F - r);
                float teeth = Float.MAX_VALUE;
                for (int i = 0; i < 8; i++) {
                    double angle = Math.PI / 4 * i;
                    float ax = (float) Math.cos(angle);
                    float ay = (float) Math.sin(angle);
                    teeth = Math.min(teeth, segment(x, y, ax * 0.25F, ay * 0.25F, ax * 0.42F, ay * 0.42F) - 0.065F);
                }
                yield Math.max(Math.min(body, teeth), 0.12F - r);
            }
            case POWER -> {
                float ring = Math.abs(length(x, y) - 0.34F) - stroke * 0.5F;
                // The ring's gap at the top, around the stem.
                boolean gap = y < 0 && Math.abs(x) < -y * 0.75F;
                float stem = segment(x, y, 0, -0.44F, 0, -0.04F) - stroke * 0.5F;
                yield Math.min(gap ? Float.MAX_VALUE : ring, stem);
            }
            case ARROW -> {
                float shaft = segment(x, y, -0.38F, 0, 0.34F, 0);
                float upper = segment(x, y, 0.38F, 0, 0.12F, -0.24F);
                float lower = segment(x, y, 0.38F, 0, 0.12F, 0.24F);
                yield Math.min(shaft, Math.min(upper, lower)) - stroke * 0.6F;
            }
            case LAYOUT -> {
                float frame = Math.abs(box(x, y, 0.44F, 0.36F, 0.1F)) - stroke * 0.5F;
                float upper = box(x + 0.15F, y + 0.15F, 0.16F, 0.075F, 0.04F);
                float lower = box(x - 0.13F, y - 0.15F, 0.18F, 0.075F, 0.04F);
                yield Math.min(frame, Math.min(upper, lower));
            }
            case PLUS ->
                Math.min(segment(x, y, -0.34F, 0, 0.34F, 0), segment(x, y, 0, -0.34F, 0, 0.34F)) - stroke * 0.65F;
            case PENCIL -> {
                float body = segment(x, y, -0.16F, 0.16F, 0.3F, -0.3F) - 0.1F;
                float tip = segment(x, y, -0.4F, 0.4F, -0.2F, 0.2F) - stroke * 0.6F;
                // A notch across the body: where the cap meets it.
                float cap = segment(x, y, 0.1F, -0.28F, 0.28F, -0.1F) - stroke * 0.35F;
                yield Math.min(Math.max(body, -cap), tip);
            }
            case TRASH -> {
                float can = Math.abs(box(x, y - 0.1F, 0.26F, 0.32F, 0.06F)) - stroke * 0.5F;
                float lid = segment(x, y, -0.38F, -0.3F, 0.38F, -0.3F) - stroke * 0.55F;
                float handle = segment(x, y, -0.1F, -0.41F, 0.1F, -0.41F) - stroke * 0.55F;
                float lines = Math.min(
                                segment(x, y, -0.09F, -0.06F, -0.09F, 0.26F),
                                segment(x, y, 0.09F, -0.06F, 0.09F, 0.26F))
                        - stroke * 0.45F;
                yield Math.min(Math.min(can, lid), Math.min(handle, lines));
            }
            case EYE -> eye(x, y, stroke);
            case EYE_OFF -> {
                float slash = segment(x, y, -0.38F, -0.38F, 0.38F, 0.38F) - stroke * 0.55F;
                // A gap either side of the slash, so it reads as struck through.
                float gap = segment(x, y, -0.38F, -0.38F, 0.38F, 0.38F) - stroke * 1.6F;
                yield Math.min(Math.max(eye(x, y, stroke), -gap), slash);
            }
            case TARGET -> {
                float ring = Math.abs(length(x, y) - 0.26F) - stroke * 0.5F;
                float ticks = Math.min(
                                Math.min(segment(x, y, 0, -0.46F, 0, -0.3F), segment(x, y, 0, 0.3F, 0, 0.46F)),
                                Math.min(segment(x, y, -0.46F, 0, -0.3F, 0), segment(x, y, 0.3F, 0, 0.46F, 0)))
                        - stroke * 0.5F;
                yield Math.min(Math.min(ring, ticks), length(x, y) - 0.07F);
            }
            case CAMERA -> {
                float body = Math.abs(box(x, y - 0.05F, 0.42F, 0.27F, 0.07F)) - stroke * 0.5F;
                float lens = Math.abs(length(x, y - 0.05F) - 0.14F) - stroke * 0.5F;
                float hump = box(x - 0.13F, y + 0.27F, 0.11F, 0.05F, 0.03F);
                yield Math.min(Math.min(body, lens), hump);
            }
            case CHAT -> {
                float bubble = Math.abs(box(x, y + 0.06F, 0.4F, 0.27F, 0.12F)) - stroke * 0.5F;
                // The tail, cut out of the bubble's lower edge and drawn as two strokes.
                float tail = Math.min(
                                segment(x, y, -0.2F, 0.33F, -0.28F, 0.46F), segment(x, y, -0.28F, 0.46F, -0.02F, 0.33F))
                        - stroke * 0.5F;
                yield Math.min(bubble, tail);
            }
            case CHECK ->
                Math.min(segment(x, y, -0.34F, 0.02F, -0.1F, 0.26F), segment(x, y, -0.1F, 0.26F, 0.36F, -0.26F))
                        - stroke * 0.7F;
            case CLOSE ->
                Math.min(segment(x, y, -0.28F, -0.28F, 0.28F, 0.28F), segment(x, y, -0.28F, 0.28F, 0.28F, -0.28F))
                        - stroke * 0.7F;
            case BLOCK -> {
                float ring = Math.abs(length(x, y) - 0.38F) - stroke * 0.55F;
                float slash = segment(x, y, -0.27F, -0.27F, 0.27F, 0.27F) - stroke * 0.55F;
                yield Math.min(ring, slash);
            }
            case FLAG -> {
                float pole = segment(x, y, -0.3F, -0.42F, -0.3F, 0.44F) - stroke * 0.55F;
                float[] p = {-0.3F, -0.36F, 0.34F, -0.36F, 0.18F, -0.16F, 0.34F, 0.04F, -0.3F, 0.04F};
                float d = Float.MAX_VALUE;
                for (int i = 0; i < 4; i++) {
                    d = Math.min(d, segment(x, y, p[i * 2], p[i * 2 + 1], p[i * 2 + 2], p[i * 2 + 3]));
                }
                yield Math.min(pole, d - stroke * 0.5F);
            }
            case PERSON -> person(x, y, stroke);
            // Drawn from its image by Logo, never painted here.
            case INCARI -> Float.MAX_VALUE;
            case HANGER -> {
                // A hook over a wide triangle, its bottom bar straight.
                float hook =
                        x < 0 && y > -0.27F ? Float.MAX_VALUE : Math.abs(length(x, y + 0.27F) - 0.1F) - stroke * 0.5F;
                float neck = segment(x, y, 0, -0.17F, 0, -0.1F) - stroke * 0.5F;
                float left = segment(x, y, 0, -0.1F, -0.42F, 0.22F);
                float right = segment(x, y, 0, -0.1F, 0.42F, 0.22F);
                float bar = segment(x, y, -0.42F, 0.22F, 0.42F, 0.22F);
                yield Math.min(Math.min(hook, neck), Math.min(Math.min(left, right), bar) - stroke * 0.55F);
            }
            case PERSON_ADD -> {
                float body = person(x + 0.12F, y, stroke);
                float plus =
                        Math.min(segment(x, y, 0.2F, -0.08F, 0.44F, -0.08F), segment(x, y, 0.32F, -0.2F, 0.32F, 0.04F))
                                - stroke * 0.55F;
                yield Math.min(body, plus);
            }
            case SEND -> {
                float[] p = {-0.38F, -0.34F, 0.4F, 0, -0.38F, 0.34F, -0.22F, 0, -0.38F, -0.34F};
                float d = Float.MAX_VALUE;
                for (int i = 0; i < 4; i++) {
                    d = Math.min(d, segment(x, y, p[i * 2], p[i * 2 + 1], p[i * 2 + 2], p[i * 2 + 3]));
                }
                d = Math.min(d, segment(x, y, -0.22F, 0, 0.1F, 0));
                yield d - stroke * 0.5F;
            }
            case PALETTE -> {
                float rim = Math.abs(length(x, y) - 0.38F) - stroke * 0.5F;
                // The thumb hole, a gap in the rim at the lower right.
                float hole = length(x - 0.2F, y - 0.2F) - 0.1F;
                float wells = Math.min(
                                Math.min(length(x + 0.17F, y + 0.12F), length(x, y + 0.22F)),
                                Math.min(length(x - 0.17F, y + 0.12F), length(x + 0.2F, y - 0.1F)))
                        - 0.065F;
                yield Math.min(Math.max(rim, -hole), Math.min(wells, Math.abs(hole) - stroke * 0.5F));
            }
        };
    }

    /** A person: a head, and shoulders as the top of a ring. */
    private static float person(float x, float y, float stroke) {
        float head = Math.abs(length(x, y + 0.17F) - 0.15F) - stroke * 0.5F;
        float shoulders = Math.max(Math.abs(length(x, y - 0.44F) - 0.32F), y - 0.4F) - stroke * 0.5F;
        return Math.min(head, shoulders);
    }

    /** An eye: an almond's outline and its pupil. */
    private static float eye(float x, float y, float stroke) {
        float almond = Math.max(length(x, y - 0.42F) - 0.6F, length(x, y + 0.42F) - 0.6F);
        return Math.min(Math.abs(almond) - stroke * 0.5F, length(x, y) - 0.12F);
    }

    private static float length(float x, float y) {
        return (float) Math.sqrt(x * x + y * y);
    }

    /** The distance from (x, y) to the segment from a to b. */
    private static float segment(float x, float y, float ax, float ay, float bx, float by) {
        float px = x - ax;
        float py = y - ay;
        float dx = bx - ax;
        float dy = by - ay;
        float t = Math.max(0, Math.min(1, (px * dx + py * dy) / (dx * dx + dy * dy)));
        return length(px - dx * t, py - dy * t);
    }

    /** The distance from (x, y) to a rounded box of half-size (hx, hy) centred on the origin. */
    private static float box(float x, float y, float hx, float hy, float radius) {
        float qx = Math.abs(x) - (hx - radius);
        float qy = Math.abs(y) - (hy - radius);
        return length(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - radius;
    }
}
