package incari.features.minimap;

import incari.framework.Category;
import incari.framework.Module;
import incari.framework.game.PlayerView;
import incari.framework.setting.BooleanSetting;
import incari.framework.setting.ChoiceSetting;
import incari.framework.setting.IntSetting;
import incari.framework.setting.Key;
import incari.framework.setting.KeySetting;
import incari.framework.waypoint.Waypoints;
import org.jspecify.annotations.Nullable;

/**
 * Minimap: the loaded world around the player seen from above, in a round or square frame on the
 * HUD, turning with them or with north up. The player is an arrow in the middle, the compass's
 * letters ride the frame, and the world's waypoints are marked in their colors and shapes; one
 * beyond the map's edge sits on the edge, the way it lies. The coordinates go below. A key opens
 * the world map, which shows what the map has seen; with Remember explored map on, that's kept for
 * each world, so it shows where the player has been on other days too.
 */
public final class Minimap extends Module {

    /** The map's frame. */
    public enum MapShape {
        ROUND("Round"),
        SQUARE("Square");

        private final String label;

        MapShape(String label) {
            this.label = label;
        }

        /** What the settings screen calls it. */
        public String label() {
            return label;
        }
    }

    /** How many blocks the map's width shows at each zoom, out to in. */
    static final int[] ZOOMS = {256, 192, 128, 96, 64, 48, 32};

    /** The zoom a new player starts at: 96 blocks, two thirds of a render distance of 8 in view. */
    static final int DEFAULT_ZOOM = 4;

    /** The = key, to zoom in. */
    private static final int KEY_EQUALS = 46;

    /** The - key, to zoom out. */
    private static final int KEY_MINUS = 45;

    /** M, for the map. */
    private static final int KEY_M = 16;

    final ChoiceSetting<MapShape> shape =
            settings().choice("shape", "Shape", MapShape.values(), MapShape.ROUND, MapShape::label);
    final BooleanSetting turn = settings()
            .bool("turn", "Turn with you", true)
            .describedAs("On: the way you face is up. Off: north is up and your arrow turns.");
    final IntSetting zoom = settings()
            .integer("zoom", "Zoom", 1, ZOOMS.length, DEFAULT_ZOOM)
            .formattedAs(level -> blocksAcross(level) + " blocks across")
            .describedAs("How much of the world the map shows.");
    final KeySetting zoomIn =
            settings().key("zoom-in", "Zoom in key", Key.keyboard(KEY_EQUALS)).onPress(() -> zoomBy(1));
    final KeySetting zoomOut =
            settings().key("zoom-out", "Zoom out key", Key.keyboard(KEY_MINUS)).onPress(() -> zoomBy(-1));
    final KeySetting worldMap = settings()
            .key("world-map", "World map key", Key.keyboard(KEY_M))
            .describedAs("Opens the world map. Drag to move, scroll to zoom.")
            .onPress(() -> system().game().openWorldMap());
    final BooleanSetting remember = settings()
            .bool("remember", "Remember explored map", true)
            .describedAs("Keeps the land the map has seen, for each world, so the world map shows where you've been.");
    final BooleanSetting showWaypoints = settings()
            .bool("waypoints", "Show waypoints", true)
            .describedAs("Marks this world's waypoints on the map.");
    final BooleanSetting compass =
            settings().bool("compass", "Compass letters", true).describedAs("N, E, S and W around the map's edge.");
    final BooleanSetting coordinates =
            settings().bool("coordinates", "Coordinates", true).describedAs("Where you stand, below the map.");

    /** Minimap, off until the player turns it on. */
    public Minimap() {
        super("minimap", "Minimap", "A map of the world around you, with your waypoints on it.", Category.HUD);
        hud(new MapElement(this));
        remember.onChange(() -> {
            if (isEnabled()) {
                system().game().rememberMap(remember.get());
            }
        });
    }

    @Override
    protected void onEnable() {
        system().game().rememberMap(remember.get());
    }

    /** The player, or null outside a world. */
    @Nullable
    PlayerView player() {
        return system().game().player();
    }

    /** The world's waypoints. */
    Waypoints waypoints() {
        return system().waypoints();
    }

    /** How many blocks the map's width shows now. */
    int blocksAcross() {
        return blocksAcross(zoom.get());
    }

    private static int blocksAcross(int level) {
        return ZOOMS[Math.max(1, Math.min(ZOOMS.length, level)) - 1];
    }

    private void zoomBy(int steps) {
        zoom.set(Math.max(1, Math.min(ZOOMS.length, zoom.get() + steps)));
    }
}
