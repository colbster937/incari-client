package incari.features.waypoints;

import incari.features.waypoints.WaypointsModule.Labels;
import incari.framework.event.OverlayListener;
import incari.framework.game.PlayerView;
import incari.framework.hud.Shape;
import incari.framework.hud.Tone;
import incari.framework.hud.WorldOverlay;
import incari.framework.waypoint.Waypoint;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The waypoints over the world, drawn every frame. On screen: the icon in place, with a glass tag
 * of name and distance above. Off screen: an arrow at the screen's edge pointing the way to turn,
 * beside the icon and distance. Farther ones are drawn first, so nearer tags are on top; one the
 * player stands at isn't drawn.
 *
 * <p>A hot path: the arrays grow only when there are more waypoints than before, and a distance's
 * text is made only when the number it says changes.
 */
final class WaypointOverlay implements OverlayListener {

    /** An icon's size over the world at Size 1, in GUI units. */
    static final float ICON = 9;

    /** How far in from the screen's edge the arrows sit. */
    static final float EDGE_MARGIN = 22;

    /** How far up from the screen's bottom: above the hotbar, hearts and hunger. */
    static final float BOTTOM_MARGIN = 62;

    /** The least room between two edge arrows on the left or right side, at Size 1. */
    static final float EDGE_GAP_SIDE = 16;

    /** And along the top or bottom, where each one's distance is written beside the next. */
    static final float EDGE_GAP_ACROSS = 36;

    /** Within this many blocks across (and a few up or down), the player is at the waypoint. */
    static final double HERE = 1.5;

    /** With "When looked at", a waypoint this near the crosshair (GUI units, at Size 1) shows its name. */
    static final float LOOK_RADIUS = 36;

    private final WaypointsModule module;
    private final float[] point = new float[3];
    /** By waypoint (each its own object: a waypoint is equal only to itself). */
    private final Map<Waypoint, DistanceText> texts = new HashMap<>();

    private long textsRevision = -1;

    /** This frame's waypoints to draw, with where each shows and its distance, farthest first. */
    private Waypoint[] order = new Waypoint[0];

    private float[] screenX = new float[0];
    private float[] screenY = new float[0];
    private float[] distances = new float[0];
    private boolean[] onScreen = new boolean[0];

    /** This frame's waypoints off screen: how far round the edges each sits, the way it lies, and its index above. */
    private float[] edgeAlong = new float[0];

    private float[] edgeDx = new float[0];
    private float[] edgeDy = new float[0];
    private int[] edgeIndex = new int[0];

    /** This frame's rectangle the edge arrows sit on. */
    private float left;

    private float right;
    private float top;
    private float bottom;

    WaypointOverlay(WaypointsModule module) {
        this.module = module;
    }

    @Override
    public void onOverlay(WorldOverlay overlay) {
        PlayerView player = module.player();
        if (player == null) {
            return;
        }
        List<Waypoint> all = module.store().all();
        long revision = module.store().revision();
        if (revision != textsRevision) {
            // Forget the texts of waypoints that are gone.
            textsRevision = revision;
            texts.keySet().retainAll(all);
        }
        if (order.length < all.size()) {
            int capacity = Math.max(all.size(), order.length * 2);
            order = new Waypoint[capacity];
            screenX = new float[capacity];
            screenY = new float[capacity];
            distances = new float[capacity];
            onScreen = new boolean[capacity];
        }
        int width = overlay.screenWidth();
        int height = overlay.screenHeight();
        int count = 0;
        for (int i = 0; i < all.size(); i++) {
            Waypoint waypoint = all.get(i);
            if (!WaypointsModule.shown(waypoint, player) || isHere(waypoint, player)) {
                continue;
            }
            boolean front = overlay.project(waypoint.x() + 0.5, waypoint.y() + 0.5, waypoint.z() + 0.5, point);
            float distance = (float) Math.sqrt(waypoint.distanceSquared(player.x(), player.y(), player.z()));
            // Insertion by distance, farthest first: a handful of waypoints, no sort's allocation.
            int at = count;
            while (at > 0 && distances[at - 1] < distance) {
                order[at] = order[at - 1];
                screenX[at] = screenX[at - 1];
                screenY[at] = screenY[at - 1];
                distances[at] = distances[at - 1];
                onScreen[at] = onScreen[at - 1];
                at--;
            }
            order[at] = waypoint;
            screenX[at] = point[0];
            screenY[at] = point[1];
            distances[at] = distance;
            onScreen[at] = front && point[0] >= 0 && point[0] <= width && point[1] >= 0 && point[1] <= height;
            count++;
        }
        float scale = module.size.get();
        for (int i = 0; i < count; i++) {
            if (onScreen[i]) {
                drawOnScreen(overlay, order[i], screenX[i], screenY[i], distances[i], scale);
            }
        }
        if (module.arrows.get()) {
            drawAtEdges(overlay, count, scale);
        }
    }

    /** Whether the player stands at {@code waypoint}: drawn over their view, it would only be in the way. */
    private static boolean isHere(Waypoint waypoint, PlayerView player) {
        double dx = waypoint.x() + 0.5 - player.x();
        double dz = waypoint.z() + 0.5 - player.z();
        double dy = waypoint.y() - player.y();
        return dx * dx + dz * dz < HERE * HERE && Math.abs(dy) < 3;
    }

    private void drawOnScreen(WorldOverlay overlay, Waypoint waypoint, float x, float y, float distance, float scale) {
        overlay.push(x, y, scale);
        try {
            overlay.shape(waypoint.icon(), 0, 0, ICON, 0, waypoint.argb());
            if (!named(overlay, x, y, scale)) {
                return;
            }
            String name = waypoint.name();
            String far = module.distance.get() ? text(waypoint).of(distance) : "";
            int nameWidth = overlay.textWidth(name);
            int farWidth = far.isEmpty() ? 0 : overlay.textWidth(far);
            int width = 8 + nameWidth + (farWidth == 0 ? 0 : 5 + farWidth);
            int height = overlay.lineHeight() + 4;
            int left = -width / 2;
            int top = -Math.round(ICON / 2) - 3 - height;
            overlay.fill(left, top, width, height, Tone.BACKGROUND);
            overlay.text(name, left + 4, top + 3, Tone.TEXT);
            if (farWidth > 0) {
                overlay.text(far, left + 4 + nameWidth + 5, top + 3, Tone.MUTED);
            }
        } finally {
            overlay.pop();
        }
    }

    /** Whether a waypoint shown at {@code x, y} has its name written, by the Names setting. */
    private boolean named(WorldOverlay overlay, float x, float y, float scale) {
        Labels labels = module.labels.get();
        if (labels == Labels.LOOKED_AT) {
            float dx = x - overlay.screenWidth() / 2.0F;
            float dy = y - overlay.screenHeight() / 2.0F;
            float radius = LOOK_RADIUS * scale;
            return dx * dx + dy * dy < radius * radius;
        }
        return labels == Labels.ALWAYS;
    }

    /**
     * For each waypoint off screen, an arrow at the screen's edge pointing the way to it, with its
     * icon and distance just inside. Arrows that would overlap are spread along the edge, in order.
     */
    private void drawAtEdges(WorldOverlay overlay, int count, float scale) {
        if (edgeAlong.length < count) {
            edgeAlong = new float[count];
            edgeDx = new float[count];
            edgeDy = new float[count];
            edgeIndex = new int[count];
        }
        float centerX = overlay.screenWidth() / 2.0F;
        float centerY = overlay.screenHeight() / 2.0F;
        // The edges' inset rectangle, kept above the hotbar and hearts at the bottom.
        float margin = EDGE_MARGIN * scale;
        left = margin;
        right = Math.max(left + 1, overlay.screenWidth() - margin);
        top = margin;
        bottom = Math.max(top + 1, overlay.screenHeight() - Math.max(margin, BOTTOM_MARGIN));
        int edges = 0;
        for (int i = 0; i < count; i++) {
            if (onScreen[i]) {
                continue;
            }
            float dx = screenX[i] - centerX;
            float dy = screenY[i] - centerY;
            float length = (float) Math.sqrt(dx * dx + dy * dy);
            if (length < 1.0E-3F) {
                // Straight behind: point down, the way to turn round.
                dx = 0;
                dy = 1;
                length = 1;
            }
            dx /= length;
            dy /= length;
            float reach = Math.max(
                    0,
                    Math.min(
                            Math.abs(dx) < 1.0E-4F ? Float.MAX_VALUE : ((dx > 0 ? right : left) - centerX) / dx,
                            Math.abs(dy) < 1.0E-4F ? Float.MAX_VALUE : ((dy > 0 ? bottom : top) - centerY) / dy));
            float along = along(centerX + dx * reach, centerY + dy * reach);
            // Insertion by the place along the edge.
            int at = edges;
            while (at > 0 && edgeAlong[at - 1] > along) {
                edgeAlong[at] = edgeAlong[at - 1];
                edgeDx[at] = edgeDx[at - 1];
                edgeDy[at] = edgeDy[at - 1];
                edgeIndex[at] = edgeIndex[at - 1];
                at--;
            }
            edgeAlong[at] = along;
            edgeDx[at] = dx;
            edgeDy[at] = dy;
            edgeIndex[at] = i;
            edges++;
        }
        for (int k = 1; k < edges; k++) {
            // Room for the icon beside the last; along the top and bottom, for its distance too.
            float gap = (onSide(edgeAlong[k - 1]) ? EDGE_GAP_SIDE : EDGE_GAP_ACROSS) * scale;
            edgeAlong[k] = Math.max(edgeAlong[k], edgeAlong[k - 1] + gap);
        }
        for (int k = 0; k < edges; k++) {
            int i = edgeIndex[k];
            drawAtEdge(overlay, order[i], edgeAlong[k], edgeDx[k], edgeDy[k], distances[i], scale);
        }
    }

    /** How far round the edges' rectangle {@code x, y} on it is, clockwise from its top left. */
    private float along(float x, float y) {
        float width = right - left;
        float height = bottom - top;
        if (y <= top + 0.5F) {
            return x - left;
        }
        if (x >= right - 0.5F) {
            return width + (y - top);
        }
        if (y >= bottom - 0.5F) {
            return width + height + (right - x);
        }
        return 2 * width + height + (bottom - y);
    }

    /** Whether the place {@code along} the edges is on the left or right side. */
    private boolean onSide(float along) {
        float width = right - left;
        float height = bottom - top;
        float at = along % (2 * (width + height));
        return (at > width && at < width + height) || at > 2 * width + height;
    }

    private void drawAtEdge(
            WorldOverlay overlay, Waypoint waypoint, float along, float dx, float dy, float distance, float scale) {
        float width = right - left;
        float height = bottom - top;
        float at = along % (2 * (width + height));
        float x;
        float y;
        if (at < width) {
            x = left + at;
            y = top;
        } else if (at < width + height) {
            x = right;
            y = top + (at - width);
        } else if (at < 2 * width + height) {
            x = right - (at - width - height);
            y = bottom;
        } else {
            x = left;
            y = bottom - (at - 2 * width - height);
        }
        float turn = (float) Math.toDegrees(Math.atan2(dx, -dy));
        overlay.push(x, y, scale);
        try {
            overlay.shape(Shape.ARROW, dx * 7, dy * 7, 8, turn, waypoint.argb());
            overlay.shape(waypoint.icon(), -dx * 4, -dy * 4, ICON - 2, 0, waypoint.argb());
            if (module.distance.get() && module.labels.get() != Labels.NEVER) {
                String far = text(waypoint).of(distance);
                int farWidth = overlay.textWidth(far);
                // Inside the icon, on the side toward the middle, centred on the line to it.
                int textX = Math.round(-dx * 14 - farWidth / 2.0F - dx * farWidth / 2.0F);
                int textY = Math.round(-dy * 14 - overlay.lineHeight() / 2.0F - dy * overlay.lineHeight() / 2.0F);
                overlay.text(far, textX, textY, Tone.TEXT);
            }
        } finally {
            overlay.pop();
        }
    }

    private DistanceText text(Waypoint waypoint) {
        DistanceText text = texts.get(waypoint);
        if (text == null) {
            text = new DistanceText();
            texts.put(waypoint, text);
        }
        return text;
    }

    /** A waypoint's distance as text, made again only when what it says changes. */
    static final class DistanceText {

        /** Whole meters below a kilometer; tenths of a kilometer from there, as negative numbers. */
        private int said = Integer.MIN_VALUE;

        private String text = "";

        /** "12 m", "999 m", "1.2 km", "34.5 km". */
        String of(float blocks) {
            int key = blocks < 1000 ? (int) blocks : -(int) (blocks / 100);
            if (key != said) {
                said = key;
                text = key >= 0 ? key + " m" : (-key / 10) + "." + (-key % 10) + " km";
            }
            return text;
        }
    }
}
