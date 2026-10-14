package incari.client.look;

import incari.client.ui.RoundRects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Util;

/**
 * Snow falling over the menus in the Normal preset: a hundred or so soft flakes drifting down and
 * swaying, larger ones nearer and faster. Positions are fractions of the screen, so a resize keeps
 * the snow where it was.
 *
 * <p>Render thread. Hot path: fixed arrays, nothing allocates.
 */
public final class Snowfall {

    private static final int FLAKES = 110;

    private final float[] x = new float[FLAKES];
    private final float[] y = new float[FLAKES];
    private final float[] size = new float[FLAKES];
    private final float[] speed = new float[FLAKES];
    private final float[] phase = new float[FLAKES];
    /** This frame's flakes for {@link RoundRects#dots}: x, y, size, color each. */
    private final int[] dots = new int[FLAKES * 4];

    private long last;
    private int seed = 0x2F6E2B1;

    public Snowfall() {
        for (int i = 0; i < FLAKES; i++) {
            x[i] = random();
            y[i] = random();
            size[i] = 0.25F + random() * 0.75F;
            // A screen's height in 9 to 26 seconds; the nearest, largest flakes fastest.
            speed[i] = 0.038F + size[i] * 0.07F;
            phase[i] = random() * 6.28F;
        }
    }

    /** Moves the flakes on by the time since the last frame and draws them over the screen. */
    public void extract(GuiGraphicsExtractor graphics, int width, int height) {
        long now = Util.getMillis();
        float dt = last == 0 ? 0 : Math.min(0.1F, (now - last) / 1000.0F);
        last = now;
        float seconds = (now % 600_000L) / 1000.0F;
        int scale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        float w = width * scale;
        float h = height * scale;
        for (int i = 0; i < FLAKES; i++) {
            y[i] += speed[i] * dt;
            if (y[i] > 1.02F) {
                y[i] = -0.02F;
                x[i] = random();
            }
            float sway = (float) Math.sin(seconds * (0.5F + size[i] * 0.4F) + phase[i]) * 0.008F;
            int alpha = Math.round(90 + size[i] * 130);
            dots[i * 4] = Math.round((x[i] + sway) * w);
            dots[i * 4 + 1] = Math.round(y[i] * h);
            dots[i * 4 + 2] = Math.max(2, Math.round((1.5F + size[i] * 3.5F) * scale / 2.0F));
            dots[i * 4 + 3] = alpha << 24 | 0xFFFFFF;
        }
        // Every flake in one render state: the snow is a hundred quads, not a hundred states.
        RoundRects.dots(graphics, dots, FLAKES);
    }

    private float random() {
        seed ^= seed << 13;
        seed ^= seed >>> 17;
        seed ^= seed << 5;
        return (seed >>> 8) / (float) (1 << 24);
    }
}
