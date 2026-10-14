package incari.via;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Via's settings for a client. Via keeps its settings in YAML files it writes next to a server
 * plugin; a client has no one to edit those, and the browser build has no YAML parser, so each
 * config here starts from Via's defaults plus the few choices below, in memory, and saves nothing.
 */
final class ViaSettings {

    private ViaSettings() {}

    /** ViaVersion's choices for a client, keyed as in its {@code config.yml}. */
    static Map<String, Object> viaVersion() {
        Map<String, Object> values = new HashMap<>();
        // A client has no update notices to show, and no console to show them on.
        values.put("check-for-updates", false);
        // 1.8 servers expect a movement packet every tick; newer clients skip idle ones.
        values.put("simulate-pt", true);
        // Chat reports and signing don't apply across versions.
        values.put("enforce-secure-chat", false);
        // Rate limits protect a server from its players; a client has no one to limit.
        values.put("packet-limiter", section("enabled", false));
        values.put("packet-size-limiter", section("enabled", false));
        values.put("logging", new HashMap<String, Object>());
        return values;
    }

    /** ViaBackwards' choices: its defaults (the sections it reads must exist). */
    static Map<String, Object> viaBackwards() {
        Map<String, Object> values = new HashMap<>();
        values.put("dialog-style", new HashMap<String, Object>());
        return values;
    }

    private static Map<String, Object> section(String key, Object value) {
        Map<String, Object> section = new HashMap<>();
        section.put(key, value);
        return section;
    }

    static final class ViaVersionConfig extends com.viaversion.viaversion.configuration.AbstractViaConfig {

        ViaVersionConfig(File file, java.util.logging.Logger logger) {
            super(file, logger);
        }

        @Override
        public Map<String, Object> loadConfig(File location) {
            return viaVersion();
        }

        @Override
        public void save(File location, Map<String, Object> config) {
            // In memory only.
        }

        @Override
        public List<String> getUnsupportedOptions() {
            return List.of();
        }

        @Override
        public boolean isCheckForUpdates() {
            return false;
        }
    }

    static final class ViaBackwardsConfig extends com.viaversion.viabackwards.ViaBackwardsConfig {

        ViaBackwardsConfig(File file, java.util.logging.Logger logger) {
            super(file, logger);
        }

        @Override
        public Map<String, Object> loadConfig(File location) {
            return viaBackwards();
        }

        @Override
        public void save(File location, Map<String, Object> config) {
            // In memory only.
        }
    }
}
