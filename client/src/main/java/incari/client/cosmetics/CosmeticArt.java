package incari.client.cosmetics;

/**
 * The cosmetics' pictures, painted in code (ADR 0061): Incari's own art, so nothing is shipped as
 * an image and nothing is Mojang's. Capes are painted into the game's 64x32 cape layout (the outer
 * side, the inner side, the edges and an elytra), some again and again as time passes; the cards in
 * the wardrobe get small icons. Pixels are ARGB.
 *
 * <p>Any thread: pure functions of their arguments.
 */
final class CosmeticArt {

    static final int CAPE_WIDTH = 64;
    static final int CAPE_HEIGHT = 32;

    /** A wardrobe card's icon, square. */
    static final int ICON = 24;

    private CosmeticArt() {}

    /** Whether the cape {@code id} moves: it's painted again as time passes. */
    static boolean animated(String id) {
        return switch (id) {
            case "cape.aurora", "cape.midnight", "cape.ember", "cape.tide" -> true;
            default -> false;
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Capes
    // ---------------------------------------------------------------------------------------------

    /**
     * Paints the cape {@code id} at time {@code t} (seconds) into {@code out}, 64x32: the outer side
     * at (1, 1), 10x16, as the game's cape model reads it; the inner side darker; the edges; and the
     * elytra's wing at (22, 0), so an elytra worn with it matches.
     */
    static void cape(String id, float t, int[] out) {
        java.util.Arrays.fill(out, 0);
        for (int py = 0; py < 16; py++) {
            for (int px = 0; px < 10; px++) {
                int color = design(id, px + 0.5F, py + 0.5F, t);
                out[(py + 1) * CAPE_WIDTH + px + 1] = color;
                // The inner side faces the player: the same, mirrored and in shade.
                out[(py + 1) * CAPE_WIDTH + 12 + (9 - px)] = shade(color, 0.55F);
            }
        }
        int edge = shade(design(id, 5, 8, t), 0.7F);
        for (int py = 1; py <= 16; py++) {
            out[py * CAPE_WIDTH] = edge;
            out[py * CAPE_WIDTH + 11] = edge;
        }
        for (int px = 1; px <= 20; px++) {
            out[px] = edge;
        }
        for (int y = 0; y < 22; y++) {
            for (int x = 22; x < 46; x++) {
                out[y * CAPE_WIDTH + x] = design(id, (x - 22 + 0.5F) * 10 / 24.0F, (y + 0.5F) * 16 / 22.0F, t);
            }
        }
    }

    /** One cape's picture at (px, py) in its 10x16 face, at time {@code t}: opaque. */
    private static int design(String id, float px, float py, float t) {
        float v = py / 16;
        return 0xFF000000
                | switch (id) {
                    case "cape.frost" -> frost(px, py, v);
                    case "cape.aurora" -> aurora(px, py, v, t);
                    case "cape.midnight" -> midnight(px, py, v, t);
                    case "cape.ember" -> ember(px, py, v, t);
                    case "cape.sakura" -> sakura(px, py, v);
                    case "cape.tide" -> tide(px, py, v, t);
                    case "cape.mono" -> mono(px, py);
                    default -> 0x808080;
                };
    }

    private static int frost(float px, float py, float v) {
        int color = mix(0x9FD3F5, 0x1E3F66, v);
        if (px < 1 || px > 9 || py < 1 || py > 15) {
            color = mix(color, 0xFFFFFF, 0.35F);
        }
        // A snowflake in the middle: three strokes and a branch at each arm's end.
        float dx = px - 5;
        float dy = py - 6.5F;
        boolean flake = (Math.abs(dx) < 0.6F && Math.abs(dy) < 3.4F)
                || (Math.abs(dy) < 0.6F && Math.abs(dx) < 3.4F)
                || (Math.abs(dx - dy) < 0.75F && Math.abs(dx) < 2.4F)
                || (Math.abs(dx + dy) < 0.75F && Math.abs(dx) < 2.4F);
        if (flake) {
            return 0xF4FBFF;
        }
        // Frost glinting further down.
        if (py > 11 && hash((int) px, (int) py, 3) < 0.12F) {
            return mix(color, 0xFFFFFF, 0.6F);
        }
        return color;
    }

    private static int aurora(float px, float py, float v, float t) {
        int color = mix(0x0E1B33, 0x050A15, v);
        int[] bands = {0x3CF2A0, 0x3CC8F2, 0xA05CF2};
        for (int k = 0; k < bands.length; k++) {
            float centre = 3.5F + k * 4.2F + 1.6F * (float) Math.sin(px * 0.7F + t * 1.3F + k * 2.1F);
            float d = (py - centre) / 1.7F;
            float glow = (float) Math.exp(-d * d) * (0.65F + 0.35F * (float) Math.sin(t * 0.9F + px * 0.5F + k));
            color = add(color, bands[k], glow * 0.85F);
        }
        if (hash((int) px, (int) py, 11) < 0.05F) {
            color = mix(color, 0xFFFFFF, 0.5F + 0.5F * (float) Math.sin(t * 4 + px));
        }
        return color;
    }

    private static int midnight(float px, float py, float v, float t) {
        int color = mix(0x1F2A5C, 0x05081A, v);
        // A crescent moon in the top right.
        float mx = px - 7;
        float my = py - 3.2F;
        float ox = px - 7.9F;
        float oy = py - 2.5F;
        if (mx * mx + my * my < 4.4F && ox * ox + oy * oy > 3.0F) {
            return 0xF2EFD0;
        }
        float star = hash((int) px, (int) py, 7);
        if (star < 0.1F) {
            float phase = hash((int) px, (int) py, 13) * 6.283F;
            float twinkle = 0.5F + 0.5F * (float) Math.sin(t * 2.6F + phase);
            return mix(color, 0xFFFFFF, 0.35F + 0.65F * twinkle);
        }
        return color;
    }

    private static int ember(float px, float py, float v, float t) {
        float flame = noise(px * 0.55F, py * 0.45F + t * 2.2F);
        float heat = clamp(v * 1.25F + (flame - 0.5F) * 0.9F - 0.2F);
        int color;
        if (heat < 0.33F) {
            color = mix(0x140806, 0x7A1708, heat / 0.33F);
        } else if (heat < 0.66F) {
            color = mix(0x7A1708, 0xE5481A, (heat - 0.33F) / 0.33F);
        } else {
            color = mix(0xE5481A, 0xFFC94A, (heat - 0.66F) / 0.34F);
        }
        // Sparks rising.
        int cell = (int) Math.floor(py + t * 5);
        if (hash((int) px, cell, 5) < 0.045F) {
            return 0xFFE38A;
        }
        return color;
    }

    private static int sakura(float px, float py, float v) {
        int color = mix(0xFCDCE8, 0xE597B6, v);
        // A branch across, from the bottom left to the top right.
        float branch = Math.abs((py - 12.5F) + (px * 0.8F)) / 1.28F;
        if (branch < 0.55F && px < 9.5F) {
            color = 0x5E3B3B;
        }
        float[][] blossoms = {{2, 10.5F}, {5, 8}, {8, 5.5F}, {3, 4}, {7.5F, 12.5F}, {1.5F, 1.8F}};
        for (float[] b : blossoms) {
            float dx = px - b[0];
            float dy = py - b[1];
            float d = dx * dx + dy * dy;
            if (d < 0.5F) {
                return 0xFFE9A8;
            }
            if (d < 2.1F) {
                return 0xF4F0F2;
            }
        }
        return color;
    }

    private static int tide(float px, float py, float v, float t) {
        int color = mix(0x3D9FE0, 0x0A2552, v);
        for (int k = 0; k < 5; k++) {
            float crest = 2.5F + k * 3 + 0.9F * (float) Math.sin(px * 0.9F + t * 2.0F + k * 1.7F);
            if (Math.abs(py - crest) < 0.55F) {
                return mix(color, 0xD8F1FF, 0.75F);
            }
        }
        if (hash((int) px, (int) Math.floor(py + t), 9) < 0.04F) {
            return mix(color, 0xFFFFFF, 0.5F);
        }
        return color;
    }

    private static int mono(float px, float py) {
        if (px < 1 || px > 9 || py < 1 || py > 15 || (px > 4 && px < 6)) {
            return 0xEDEDED;
        }
        return 0x151515;
    }

    // ---------------------------------------------------------------------------------------------
    // Wings
    // ---------------------------------------------------------------------------------------------

    /**
     * A wing at (sx, sy) on its panel: sx from the root (0) to the tip (1), sy from the top (0) to
     * the bottom (1). Transparent outside the wing.
     */
    static int wing(String id, float sx, float sy) {
        return switch (id) {
            case "back.bat_wings" -> batWing(sx, sy);
            case "back.frost_wings" -> frostWing(sx, sy);
            default -> angelWing(sx, sy);
        };
    }

    private static float wingTop(float sx) {
        return 0.34F - 0.3F * (float) Math.sin(sx * Math.PI * 0.5);
    }

    private static float featherBottom(float sx) {
        float scallop = 0.06F * (float) Math.abs(Math.sin(sx * Math.PI * 5));
        return 0.97F - 0.62F * (float) Math.pow(sx, 1.15) - scallop;
    }

    private static int angelWing(float sx, float sy) {
        float top = wingTop(sx);
        float bottom = featherBottom(sx);
        if (sy < top || sy > bottom) {
            return 0;
        }
        int color = mix(0xFFFFFF, 0xDCE3EC, clamp((sy - top) / Math.max(0.05F, bottom - top)) * 0.6F);
        // Rows of feathers: lines across, and their tips toward the bottom.
        float row = (sy - top) / Math.max(0.05F, bottom - top) * 4;
        if (row - (float) Math.floor(row) < 0.12F && row > 0.8F) {
            color = 0xC9D3E0;
        }
        if (sx < 0.12F) {
            color = mix(color, 0xB8C3D2, 0.5F);
        }
        return 0xFF000000 | color;
    }

    private static int frostWing(float sx, float sy) {
        float top = wingTop(sx);
        float bottom = featherBottom(sx);
        if (sy < top || sy > bottom) {
            return 0;
        }
        float facet = sx * 4 + sy * 2.5F;
        boolean edge = facet - (float) Math.floor(facet) < 0.09F || sy - top < 0.03F || bottom - sy < 0.03F;
        if (edge) {
            return 0xF0F4FBFF;
        }
        int color = mix(0xCDEBFF, 0x7FB8E8, clamp(sy));
        return 0xA8000000 | color;
    }

    private static int batWing(float sx, float sy) {
        // The arm along the top, the fingers fanning down from the wrist, the skin scalloped
        // between them.
        float top = 0.12F + 0.12F * sx;
        float fingers = sx * 3;
        float between = fingers - (float) Math.floor(fingers);
        float bottom = 0.9F - 0.5F * sx - 0.22F * (float) Math.sin(between * Math.PI);
        if (sy < top || sy > bottom) {
            return 0;
        }
        if (sy - top < 0.07F) {
            return 0xFF3A2A40;
        }
        for (int finger = 1; finger <= 3; finger++) {
            float endX = finger / 3.0F;
            float endY = 0.9F - 0.5F * endX;
            float along = sx / endX;
            if (along <= 1 && Math.abs(sy - (top + (endY - top) * along)) < 0.035F) {
                return 0xFF22182A;
            }
        }
        return 0xFF000000 | mix(0x62486E, 0x3E2D47, clamp(sy));
    }

    // ---------------------------------------------------------------------------------------------
    // Icons
    // ---------------------------------------------------------------------------------------------

    /** The wardrobe card's icon for a hat or a back cosmetic, {@link #ICON} square, into {@code out}. */
    static void icon(String id, int[] out) {
        java.util.Arrays.fill(out, 0);
        for (int y = 0; y < ICON; y++) {
            for (int x = 0; x < ICON; x++) {
                out[y * ICON + x] = iconPixel(id, x + 0.5F, y + 0.5F);
            }
        }
    }

    private static int iconPixel(String id, float x, float y) {
        return switch (id) {
            case "hat.crown" -> crownIcon(x, y);
            case "hat.halo" -> haloIcon(x, y);
            case "hat.top_hat" -> topHatIcon(x, y);
            case "hat.beanie" -> beanieIcon(x, y);
            case "hat.horns" -> hornsIcon(x, y);
            case "hat.cat_ears" -> earsIcon(x, y);
            case "back.backpack" -> backpackIcon(x, y);
            case "back.angel_wings", "back.frost_wings", "back.bat_wings" -> wingsIcon(id, x, y);
            default -> 0;
        };
    }

    private static int crownIcon(float x, float y) {
        boolean band = y >= 13 && y < 19 && x >= 4 && x < 20;
        boolean points = false;
        for (float peak : new float[] {5, 12, 19}) {
            float dx = Math.abs(x - peak);
            if (y < 13 && y >= 6 + dx * 2.2F && dx < 3) {
                points = true;
            }
        }
        if (Math.abs(x - 12) < 1.6F && Math.abs(y - 16) < 1.6F) {
            return 0xFFE0344B;
        }
        if (band || points) {
            return 0xFF000000 | mix(0xFFE58A, 0xD69A1E, (y - 6) / 13);
        }
        return 0;
    }

    private static int haloIcon(float x, float y) {
        float dx = (x - 12) / 9;
        float dy = (y - 12) / 4;
        float d = dx * dx + dy * dy;
        if (d < 1 && d > 0.45F) {
            return 0xFFFFF0B0;
        }
        if (d < 1.4F && d > 0.3F) {
            return 0x60FFE68A;
        }
        return 0;
    }

    private static int topHatIcon(float x, float y) {
        if (y >= 17 && y < 19 && x >= 2 && x < 22) {
            return 0xFF1C1C22;
        }
        if (y >= 4 && y < 17 && x >= 6 && x < 18) {
            return y >= 13 ? 0xFFC0263A : 0xFF26262E;
        }
        return 0;
    }

    private static int beanieIcon(float x, float y) {
        float dx = (x - 12) / 9;
        float dy = (y - 17) / 11;
        if (y < 17 && dx * dx + dy * dy < 1) {
            int row = (int) (y / 2.5F);
            return (row & 1) == 0 ? 0xFF7FC3F2 : 0xFFEAF6FF;
        }
        if (y >= 17 && y < 20 && x >= 3 && x < 21) {
            return 0xFF4F8FC7;
        }
        float px = x - 12;
        float py = y - 5;
        if (px * px + py * py < 6) {
            return 0xFFFFFFFF;
        }
        return 0;
    }

    private static int hornsIcon(float x, float y) {
        for (int side = -1; side <= 1; side += 2) {
            float baseX = 12 + side * 5;
            float t = (20 - y) / 15;
            if (t < 0 || t > 1) {
                continue;
            }
            float centre = baseX + side * 5 * t * t;
            float half = 2.6F * (1 - t) + 0.4F;
            if (Math.abs(x - centre) < half) {
                return 0xFF000000 | mix(0xF2E6CC, 0x9C8A6A, t);
            }
        }
        return 0;
    }

    private static int earsIcon(float x, float y) {
        for (int side = -1; side <= 1; side += 2) {
            float cx = 12 + side * 6;
            float t = (19 - y) / 13;
            if (t < 0 || t > 1) {
                continue;
            }
            float half = 4.5F * (1 - t);
            float d = Math.abs(x - cx);
            if (d < half) {
                if (d < half - 1.6F && t < 0.7F) {
                    return 0xFFF2A6BC;
                }
                // A light rim, so dark ears read on a dark card.
                return d > half - 0.8F || t > 0.92F ? 0xFF9A9AAE : 0xFF4A4A58;
            }
        }
        return 0;
    }

    private static int backpackIcon(float x, float y) {
        if (x >= 6 && x < 18 && y >= 4 && y < 21) {
            if (y < 9) {
                return 0xFF7FC3F2;
            }
            if (x >= 8 && x < 16 && y >= 12 && y < 18) {
                return (x < 8.8F || x > 15.2F || y < 12.8F || y > 17.2F) ? 0xFFA9DCFF : 0xFF2C4A72;
            }
            return 0xFF22385A;
        }
        return 0;
    }

    private static int wingsIcon(String id, float x, float y) {
        // Both wings, spread from the middle.
        float sx = Math.abs(x - 12) / 11;
        float sy = (y - 2) / 20;
        if (Math.abs(x - 12) < 1 || sy < 0 || sy > 1 || sx > 1) {
            return 0;
        }
        int color = wing(id, sx, sy);
        return (color >>> 24) == 0 ? 0 : 0xFF000000 | color;
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** A colour between {@code a} and {@code b} (RGB), {@code t} of the way. */
    static int mix(int a, int b, float t) {
        float k = clamp(t);
        int r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * k);
        int g = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * k);
        int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * k);
        return r << 16 | g << 8 | bl;
    }

    private static int add(int base, int light, float amount) {
        int r = Math.min(255, ((base >> 16) & 255) + Math.round(((light >> 16) & 255) * amount));
        int g = Math.min(255, ((base >> 8) & 255) + Math.round(((light >> 8) & 255) * amount));
        int b = Math.min(255, (base & 255) + Math.round((light & 255) * amount));
        return r << 16 | g << 8 | b;
    }

    /** {@code argb} darker by {@code factor}, alpha kept. */
    static int shade(int argb, float factor) {
        int r = Math.round(((argb >> 16) & 255) * factor);
        int g = Math.round(((argb >> 8) & 255) * factor);
        int b = Math.round((argb & 255) * factor);
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }

    static float clamp(float value) {
        return Math.max(0, Math.min(1, value));
    }

    /** A number in [0, 1) for a cell: the same for the same cell and salt. */
    static float hash(int x, int y, int salt) {
        int h = x * 374761393 + y * 668265263 + salt * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    /** Smooth value noise in [0, 1). */
    private static float noise(float x, float y) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        float fx = x - ix;
        float fy = y - iy;
        float sx = fx * fx * (3 - 2 * fx);
        float sy = fy * fy * (3 - 2 * fy);
        float a = hash(ix, iy, 1);
        float b = hash(ix + 1, iy, 1);
        float c = hash(ix, iy + 1, 1);
        float d = hash(ix + 1, iy + 1, 1);
        return a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy;
    }
}
