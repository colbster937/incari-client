package incari.framework.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import incari.framework.Module;
import incari.framework.hud.HudElement;
import incari.framework.setting.ActionSetting;
import incari.framework.setting.BooleanSetting;
import incari.framework.setting.ChoiceSetting;
import incari.framework.setting.ColorSetting;
import incari.framework.setting.FloatSetting;
import incari.framework.setting.GridSetting;
import incari.framework.setting.IntSetting;
import incari.framework.setting.Key;
import incari.framework.setting.KeySetting;
import incari.framework.setting.Setting;
import incari.framework.setting.SettingVisitor;
import incari.framework.setting.Settings;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A profile as JSON: which modules are on and every setting's value, by id.
 *
 * <pre>{@code
 * {
 *   "format": 1,
 *   "modules": {
 *     "togglesprint": {
 *       "enabled": true,
 *       "settings": { "toggle": "none", "sprint": "toggle", "color": "#FFFFFFFF" },
 *       "hud": { "status": { "anchor": "top_left", "x": 2, "y": 2, "scale": 1.0 } }
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>Reading is forgiving: a value that doesn't fit its setting leaves the default, and a module
 * this build doesn't have is kept as it was and written back, so a profile survives a build
 * without that module.
 */
public final class ProfileCodec {

    /** The format's version; a newer one is read as far as it's understood. */
    public static final int FORMAT = 1;

    private ProfileCodec() {}

    /**
     * Writes the modules' state.
     *
     * @param unknown what {@link #read} kept of modules this build doesn't have, written back as it was
     */
    public static JsonObject write(List<Module> modules, JsonObject unknown) {
        JsonObject entries = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : unknown.entrySet()) {
            entries.add(entry.getKey(), entry.getValue());
        }
        for (Module module : modules) {
            JsonObject entry = new JsonObject();
            entry.addProperty("enabled", module.isEnabled());
            entry.add("settings", writeSettings(module.settings()));
            if (!module.hudElements().isEmpty()) {
                JsonObject hud = new JsonObject();
                for (HudElement element : module.hudElements()) {
                    hud.add(element.id(), writeSettings(element.settings()));
                }
                entry.add("hud", hud);
            }
            entries.add(module.id(), entry);
        }
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.add("modules", entries);
        return root;
    }

    /**
     * Sets every module's settings from {@code root} (the default where it has none), then turns
     * each on or off as it says (off where it doesn't say).
     *
     * @return the entries of modules this build doesn't have, to write back later
     */
    public static JsonObject read(JsonObject root, List<Module> modules) {
        JsonObject entries = object(root, "modules");
        JsonObject unknown = new JsonObject();
        if (entries != null) {
            for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
                if (find(modules, entry.getKey()) == null) {
                    unknown.add(entry.getKey(), entry.getValue());
                }
            }
        }
        // Settings first, so a module's onEnable sees the profile's values.
        boolean[] enable = new boolean[modules.size()];
        for (int i = 0; i < modules.size(); i++) {
            Module module = modules.get(i);
            JsonObject entry = entries == null ? null : object(entries, module.id());
            readSettings(module.settings(), entry == null ? null : object(entry, "settings"));
            JsonObject hud = entry == null ? null : object(entry, "hud");
            for (HudElement element : module.hudElements()) {
                readSettings(element.settings(), hud == null ? null : object(hud, element.id()));
            }
            enable[i] = module.isAlwaysOn() || (entry != null && bool(entry, "enabled"));
        }
        for (int i = 0; i < modules.size(); i++) {
            modules.get(i).setEnabled(enable[i]);
        }
        return unknown;
    }

    private static JsonObject writeSettings(Settings settings) {
        JsonObject values = new JsonObject();
        for (Setting<?> setting : settings) {
            JsonElement value = setting.accept(WRITER);
            // A button has no value to keep.
            if (!value.isJsonNull()) {
                values.add(setting.id(), value);
            }
        }
        return values;
    }

    /**
     * Sets {@code setting} from its config text, as a profile would have it ({@code "true"},
     * {@code "7"}, {@code "always"}). False, and the setting left as it was, if the text doesn't fit.
     */
    public static boolean setFromText(Setting<?> setting, String text) {
        JsonPrimitive value;
        if (text.equals("true") || text.equals("false")) {
            value = new JsonPrimitive(Boolean.parseBoolean(text));
        } else {
            try {
                value = new JsonPrimitive(Double.parseDouble(text));
            } catch (NumberFormatException notANumber) {
                value = new JsonPrimitive(text);
            }
        }
        return setting.accept(new Reader(value));
    }

    private static void readSettings(Settings settings, @Nullable JsonObject values) {
        for (Setting<?> setting : settings) {
            JsonElement value = values == null ? null : values.get(setting.id());
            if (value == null || !value.isJsonPrimitive() || !setting.accept(new Reader(value.getAsJsonPrimitive()))) {
                setting.reset();
            }
        }
    }

    private static @Nullable Module find(List<Module> modules, String id) {
        for (Module module : modules) {
            if (module.id().equals(id)) {
                return module;
            }
        }
        return null;
    }

    private static @Nullable JsonObject object(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static boolean bool(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null
                && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isBoolean()
                && element.getAsBoolean();
    }

    /** {@code 0xAARRGGBB} as {@code #AARRGGBB}. */
    static String colorText(int argb) {
        String hex = Integer.toHexString(argb).toUpperCase(Locale.ROOT);
        return "#" + "00000000".substring(hex.length()) + hex;
    }

    /** {@code #AARRGGBB} or {@code #RRGGBB} (opaque) as {@code 0xAARRGGBB}, or null. */
    static @Nullable Integer parseColor(String text) {
        if (!text.startsWith("#") || (text.length() != 7 && text.length() != 9)) {
            return null;
        }
        try {
            long value = Long.parseLong(text.substring(1), 16);
            return text.length() == 7 ? (int) value | 0xFF000000 : (int) value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final SettingVisitor<JsonElement> WRITER = new SettingVisitor<>() {
        @Override
        public JsonElement visit(BooleanSetting setting) {
            return new JsonPrimitive(setting.get());
        }

        @Override
        public JsonElement visit(IntSetting setting) {
            return new JsonPrimitive(setting.get());
        }

        @Override
        public JsonElement visit(FloatSetting setting) {
            return new JsonPrimitive(setting.get());
        }

        @Override
        public JsonElement visit(ChoiceSetting<?> setting) {
            return new JsonPrimitive(setting.key());
        }

        @Override
        public JsonElement visit(ColorSetting setting) {
            return new JsonPrimitive(colorText(setting.get()));
        }

        @Override
        public JsonElement visit(KeySetting setting) {
            return new JsonPrimitive(setting.get().encode());
        }

        @Override
        public JsonElement visit(ActionSetting setting) {
            return JsonNull.INSTANCE;
        }

        @Override
        public JsonElement visit(GridSetting setting) {
            StringBuilder text = new StringBuilder();
            for (int y = 0; y < setting.size(); y++) {
                if (y > 0) {
                    text.append(',');
                }
                text.append(Long.toHexString(setting.row(y)));
            }
            return new JsonPrimitive(text.toString());
        }
    };

    /** Sets a setting from one JSON value; false if the value doesn't fit it. */
    private static final class Reader implements SettingVisitor<Boolean> {

        private final JsonPrimitive value;

        Reader(JsonPrimitive value) {
            this.value = value;
        }

        @Override
        public Boolean visit(BooleanSetting setting) {
            if (!value.isBoolean()) {
                return false;
            }
            setting.set(value.getAsBoolean());
            return true;
        }

        @Override
        public Boolean visit(IntSetting setting) {
            if (!value.isNumber()) {
                return false;
            }
            setting.set(value.getAsInt());
            return true;
        }

        @Override
        public Boolean visit(FloatSetting setting) {
            if (!value.isNumber()) {
                return false;
            }
            setting.set(value.getAsFloat());
            return true;
        }

        @Override
        public Boolean visit(ChoiceSetting<?> setting) {
            return value.isString() && choose(setting, value.getAsString());
        }

        private static <E extends Enum<E>> boolean choose(ChoiceSetting<E> setting, String key) {
            E choice = setting.fromKey(key);
            if (choice == null) {
                return false;
            }
            setting.set(choice);
            return true;
        }

        @Override
        public Boolean visit(ColorSetting setting) {
            Integer color = value.isString() ? parseColor(value.getAsString()) : null;
            if (color == null) {
                return false;
            }
            setting.set(color);
            return true;
        }

        @Override
        public Boolean visit(KeySetting setting) {
            Key key = value.isString() ? Key.decode(value.getAsString()) : null;
            if (key == null) {
                return false;
            }
            setting.set(key);
            return true;
        }

        @Override
        public Boolean visit(ActionSetting setting) {
            return true;
        }

        @Override
        public Boolean visit(GridSetting setting) {
            if (!value.isString()) {
                return false;
            }
            String[] parts = value.getAsString().split(",", -1);
            if (parts.length != setting.size()) {
                return false;
            }
            long[] rows = new long[parts.length];
            try {
                for (int i = 0; i < parts.length; i++) {
                    rows[i] = Long.parseUnsignedLong(parts[i], 16);
                }
            } catch (NumberFormatException e) {
                return false;
            }
            setting.setRows(rows);
            return true;
        }
    }
}
