package incari.framework.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import incari.framework.hud.Shape;
import incari.framework.waypoint.Waypoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * A world's waypoints as JSON.
 *
 * <pre>{@code
 * {
 *   "format": 1,
 *   "waypoints": [
 *     { "name": "Base", "x": 120, "y": 64, "z": -48, "dimension": "minecraft:overworld",
 *       "color": "#5B8CFF", "icon": "circle", "visible": true, "beam": true, "death": false }
 *   ]
 * }
 * }</pre>
 *
 * <p>Reading is forgiving: a waypoint without a name or a position is left out (and reported), and
 * a value missing or out of place takes its default.
 */
public final class WaypointCodec {

    /** The format's version; a newer one is read as far as it's understood. */
    public static final int FORMAT = 1;

    private WaypointCodec() {}

    /** The waypoints as the file's text. */
    public static String write(List<Waypoint> waypoints) {
        JsonArray array = new JsonArray();
        for (Waypoint waypoint : waypoints) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", waypoint.name());
            entry.addProperty("x", waypoint.x());
            entry.addProperty("y", waypoint.y());
            entry.addProperty("z", waypoint.z());
            entry.addProperty("dimension", waypoint.dimension());
            // #RRGGBB: colorText's #AARRGGBB less the alpha, which a waypoint hasn't.
            entry.addProperty(
                    "color", "#" + ProfileCodec.colorText(waypoint.rgb()).substring(3));
            entry.addProperty("icon", waypoint.icon().name().toLowerCase(Locale.ROOT));
            entry.addProperty("visible", waypoint.isVisible());
            entry.addProperty("beam", waypoint.hasBeam());
            entry.addProperty("death", waypoint.isDeath());
            array.add(entry);
        }
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.add("waypoints", array);
        return root.toString();
    }

    /**
     * The waypoints in a file's text.
     *
     * @param problems told about each waypoint left out
     */
    public static List<Waypoint> read(String text, Consumer<String> problems) {
        List<Waypoint> waypoints = new ArrayList<>();
        JsonElement parsed = JsonParser.parseString(text);
        if (!parsed.isJsonObject()) {
            problems.accept("not a waypoints file");
            return waypoints;
        }
        JsonElement entries = parsed.getAsJsonObject().get("waypoints");
        if (entries == null || !entries.isJsonArray()) {
            return waypoints;
        }
        for (JsonElement element : entries.getAsJsonArray()) {
            Waypoint waypoint = element.isJsonObject() ? waypoint(element.getAsJsonObject()) : null;
            if (waypoint == null) {
                problems.accept("left out a waypoint that couldn't be read: " + element);
            } else {
                waypoints.add(waypoint);
            }
        }
        return waypoints;
    }

    private static @Nullable Waypoint waypoint(JsonObject entry) {
        String name = string(entry, "name");
        JsonPrimitive x = number(entry, "x");
        JsonPrimitive y = number(entry, "y");
        JsonPrimitive z = number(entry, "z");
        if (name == null || x == null || y == null || z == null) {
            return null;
        }
        String dimension = string(entry, "dimension");
        String colorText = string(entry, "color");
        Integer color = colorText == null ? null : ProfileCodec.parseColor(colorText);
        Waypoint waypoint = new Waypoint(
                name,
                x.getAsInt(),
                y.getAsInt(),
                z.getAsInt(),
                dimension == null ? "minecraft:overworld" : dimension,
                color == null ? Waypoint.COLORS.get(0) : color,
                shape(string(entry, "icon")));
        waypoint.setVisible(bool(entry, "visible", true));
        waypoint.setBeam(bool(entry, "beam", true));
        waypoint.setDeath(bool(entry, "death", false));
        return waypoint;
    }

    private static Shape shape(@Nullable String key) {
        if (key != null) {
            for (Shape shape : Shape.MARKERS) {
                if (shape.name().toLowerCase(Locale.ROOT).equals(key)) {
                    return shape;
                }
            }
        }
        return Shape.CIRCLE;
    }

    private static @Nullable String string(JsonObject entry, String key) {
        JsonElement element = entry.get(key);
        boolean fits = element != null
                && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString();
        return fits ? element.getAsString() : null;
    }

    private static @Nullable JsonPrimitive number(JsonObject entry, String key) {
        JsonElement element = entry.get(key);
        boolean fits = element != null
                && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isNumber();
        return fits ? element.getAsJsonPrimitive() : null;
    }

    private static boolean bool(JsonObject entry, String key, boolean otherwise) {
        JsonElement element = entry.get(key);
        boolean fits = element != null
                && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isBoolean();
        return fits ? element.getAsBoolean() : otherwise;
    }
}
