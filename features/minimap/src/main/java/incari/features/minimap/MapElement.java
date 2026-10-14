package incari.features.minimap;

import incari.features.minimap.Minimap.MapShape;
import incari.framework.game.PlayerView;
import incari.framework.hud.Anchor;
import incari.framework.hud.HudCanvas;
import incari.framework.hud.HudElement;
import incari.framework.hud.Shape;
import incari.framework.hud.Tone;
import incari.framework.waypoint.Waypoint;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The map on the HUD. Live ({@link #isLive()}): it turns and scrolls with the player every frame,
 * which the HUD's few dozen refreshes a second would show as stutter. Nothing allocates while the
 * player stays in a block; the coordinates' text is made again only when they move to another.
 */
final class MapElement extends HudElement {

    /** The map's size, frame and all, in GUI units at scale 1. */
    static final int SIZE = 96;

    /** The frame's width around the terrain. */
    static final int FRAME = 2;

    /** A waypoint's icon on the map. */
    static final float MARKER = 8;

    /** A waypoint's icon beyond the map's edge, sitting on the edge. */
    static final float MARKER_AT_EDGE = 6;

    /** The player's arrow. */
    static final float ARROW = 10;

    /** The compass letters' badges' radius. */
    static final float BADGE = 5.5F;

    /** The gap between the map and the coordinates below it. */
    static final int COORDINATES_GAP = 3;

    private static final String[] LETTERS = {"N", "E", "S", "W"};

    private final Minimap module;

    private int shownX = Integer.MIN_VALUE;
    private int shownY;
    private int shownZ;
    private String coordinates = "";

    MapElement(Minimap module) {
        super("map", "Map", Anchor.TOP_RIGHT, 4, 4);
        this.module = module;
    }

    @Override
    public boolean isLive() {
        return true;
    }

    @Override
    public boolean isVisible() {
        return module.player() != null;
    }

    @Override
    public int width(HudCanvas canvas) {
        return SIZE;
    }

    @Override
    public int height(HudCanvas canvas) {
        return module.coordinates.get() ? SIZE + COORDINATES_GAP + canvas.lineHeight() + 3 : SIZE;
    }

    @Override
    public void draw(HudCanvas canvas) {
        PlayerView player = module.player();
        boolean round = module.shape.get() == MapShape.ROUND;
        float center = SIZE / 2.0F;
        int inner = SIZE - 2 * FRAME;
        float radius = inner / 2.0F;

        // The frame, and the backdrop where nothing's loaded.
        if (round) {
            canvas.disc(center, center, center, Tone.BACKGROUND);
        } else {
            canvas.fill(0, 0, SIZE, SIZE, Tone.BACKGROUND);
        }
        float heading = player == null ? 0 : player.heading();
        float rotation = module.turn.get() ? heading : 0;
        int blocks = module.blocksAcross();
        if (player != null) {
            canvas.terrain(FRAME, FRAME, inner, round, player.x(), player.z(), blocks, rotation);
        }
        if (round) {
            canvas.ring(center, center, center - 0.5F, 1, Tone.EDGE);
        }

        double radians = Math.toRadians(rotation);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        if (player != null && module.showWaypoints.get()) {
            drawWaypoints(canvas, player, center, radius, round, inner / (float) blocks, cos, sin);
        }
        canvas.shape(Shape.ARROW, center, center, ARROW, module.turn.get() ? 0 : heading, Tone.TEXT);
        if (module.compass.get()) {
            drawCompass(canvas, center, radius, round, cos, sin);
        }
        if (module.coordinates.get()) {
            drawCoordinates(canvas, player);
        }
    }

    /**
     * Each of the dimension's shown waypoints at its place on the map; one beyond the edge on the
     * edge, smaller, the way it lies.
     *
     * @param scale GUI units a block
     */
    private void drawWaypoints(
            HudCanvas canvas,
            PlayerView player,
            float center,
            float radius,
            boolean round,
            float scale,
            float cos,
            float sin) {
        List<Waypoint> all = module.waypoints().all();
        float limit = radius - MARKER / 2;
        for (int i = 0; i < all.size(); i++) {
            Waypoint waypoint = all.get(i);
            if (!waypoint.isVisible() || !waypoint.dimension().equals(player.dimension())) {
                continue;
            }
            float dx = (float) (waypoint.x() + 0.5 - player.x());
            float dz = (float) (waypoint.z() + 0.5 - player.z());
            // East and south in the world, turned so the map's up is the rotation's bearing.
            float x = (dx * cos + dz * sin) * scale;
            float y = (-dx * sin + dz * cos) * scale;
            float reach = round ? (float) Math.sqrt(x * x + y * y) : Math.max(Math.abs(x), Math.abs(y));
            float size = MARKER;
            if (reach > limit) {
                x *= limit / reach;
                y *= limit / reach;
                size = MARKER_AT_EDGE;
            }
            canvas.shape(waypoint.icon(), center + x, center + y, size, 0, waypoint.argb());
        }
    }

    /**
     * N, E, S and W on badges on the frame, where each lies; north in the accent. Every badge, then
     * every letter: drawn in turn, each letter would stack a layer above its badge, and each layer
     * costs the GUI a draw call.
     */
    private static void drawCompass(HudCanvas canvas, float center, float radius, boolean round, float cos, float sin) {
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < LETTERS.length; i++) {
                drawCompassPoint(canvas, i, pass == 0, center, radius, round, cos, sin);
            }
        }
    }

    /** Letter {@code i}'s badge, or the letter on it. */
    private static void drawCompassPoint(
            HudCanvas canvas, int i, boolean badge, float center, float radius, boolean round, float cos, float sin) {
        // Bearing 0, 90, 180, 270 from north: east (1, 0), south (0, 1) in the world turned onto the map.
        float wx = i == 1 ? 1 : i == 3 ? -1 : 0;
        float wz = i == 0 ? -1 : i == 2 ? 1 : 0;
        float x = wx * cos + wz * sin;
        float y = -wx * sin + wz * cos;
        float reach = round ? 1 : Math.max(Math.abs(x), Math.abs(y));
        float at = radius - 1;
        float bx = center + x / reach * at;
        float by = center + y / reach * at;
        if (badge) {
            canvas.disc(bx, by, BADGE, Tone.BACKGROUND);
            return;
        }
        String letter = LETTERS[i];
        canvas.push(bx, by, 0.75F);
        try {
            canvas.text(
                    letter,
                    -Math.round(canvas.textWidth(letter) / 2.0F),
                    -canvas.lineHeight() / 2 + 1,
                    i == 0 ? Tone.ACCENT : Tone.TEXT);
        } finally {
            canvas.pop();
        }
    }

    /** "x, y, z" of the player's block on a tag below the map; in the HUD editor without one, a sample. */
    private void drawCoordinates(HudCanvas canvas, @Nullable PlayerView player) {
        int x = player == null ? 0 : (int) Math.floor(player.x());
        int y = player == null ? 64 : (int) Math.floor(player.y());
        int z = player == null ? 0 : (int) Math.floor(player.z());
        if (x != shownX || y != shownY || z != shownZ) {
            shownX = x;
            shownY = y;
            shownZ = z;
            coordinates = x + ", " + y + ", " + z;
        }
        int width = canvas.textWidth(coordinates) + 10;
        int height = canvas.lineHeight() + 3;
        int left = (SIZE - width) / 2;
        int top = SIZE + COORDINATES_GAP;
        canvas.fill(left, top, width, height, Tone.BACKGROUND);
        canvas.text(coordinates, left + 5, top + 2, Tone.TEXT);
    }
}
