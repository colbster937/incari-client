package incari.client.modules;

import com.mojang.blaze3d.platform.InputConstants;
import incari.client.ui.SettingControls;
import incari.framework.KeyInput;
import incari.framework.Module;
import incari.framework.ModuleSystem;
import incari.framework.setting.Key;
import incari.framework.setting.KeySetting;
import incari.framework.setting.Setting;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Module keybinds through the game's own key handling. Each {@link KeySetting} gets a key mapping
 * of its own that isn't in the options' list: the game counts its presses and holds it down as it
 * does for its own binds (none with a screen open, all released when one opens), but doesn't show
 * it in Controls or save it to {@code options.txt}. The profile saves the bind; this keeps the
 * mapping on the same key. Game thread.
 */
final class GameKeys implements KeyInput, SettingControls.KeyNames {

    /** By the setting itself: settings don't define equality. */
    private final IdentityHashMap<KeySetting, Mapping> mappings = new IdentityHashMap<>();

    private final ModuleSystem system;

    GameKeys(ModuleSystem system) {
        this.system = system;
    }

    @Override
    public int presses(KeySetting key) {
        KeyMapping mapping = mapping(key);
        if (mapping == null) {
            return 0;
        }
        int presses = 0;
        while (mapping.consumeClick()) {
            presses++;
        }
        return presses;
    }

    @Override
    public boolean isDown(KeySetting key) {
        KeyMapping mapping = mapping(key);
        return mapping != null && mapping.isDown();
    }

    @Override
    public Component name(Key key) {
        return toGame(key).getDisplayName();
    }

    @Override
    public List<String> conflicts(KeySetting setting) {
        List<String> names = new ArrayList<>();
        Key key = setting.get();
        if (!key.isBound()) {
            return names;
        }
        InputConstants.Key gameKey = toGame(key);
        for (KeyMapping other : Minecraft.getInstance().options.keyMappings) {
            if (other.matches(gameKey)) {
                names.add(Component.translatable(other.getName()).getString());
            }
        }
        for (Module module : system.modules()) {
            for (Setting<?> each : module.settings()) {
                if (each instanceof KeySetting other
                        && !other.equals(setting)
                        && other.get().equals(key)) {
                    names.add(module.name() + ": " + other.name());
                }
            }
        }
        return names;
    }

    /** The game's key for one of the framework's. */
    static InputConstants.Key toGame(Key key) {
        return switch (key.device()) {
            case KEYBOARD -> InputConstants.Type.KEYBOARD.getOrCreate(key.code());
            case MOUSE -> InputConstants.Type.MOUSE.getOrCreate(key.code());
        };
    }

    /** The framework's key for one of the game's. */
    static Key fromGame(InputConstants.Key key) {
        return key.getType() == InputConstants.Type.MOUSE ? Key.mouse(key.getValue()) : Key.keyboard(key.getValue());
    }

    /**
     * The setting's mapping, made on first use; null while it's unbound. An unbound key would be
     * the game's unknown key, which every key the platform can't name presses.
     */
    private @Nullable KeyMapping mapping(KeySetting setting) {
        Mapping mapping = mappings.get(setting);
        if (mapping == null) {
            Key key = setting.get();
            if (!key.isBound()) {
                return null;
            }
            // Unique, and not a translation key: the game never shows these names.
            String name = "incari.modules.key." + mappings.size();
            mapping = new Mapping(new KeyMapping(name, typeOf(key), key.code(), KeyMapping.Category.MISC), key);
            mappings.put(setting, mapping);
        } else if (!mapping.bound.equals(setting.get())) {
            mapping.bound = setting.get();
            mapping.mapping.setKey(toGame(mapping.bound));
            // The game's key lookup is rebuilt from every mapping; only when a bind changed.
            KeyMapping.resetMapping();
            // Presses from before the change, or from the unknown key while it was unbound,
            // aren't presses of the new bind.
            mapping.mapping.setDown(false);
            while (mapping.mapping.consumeClick()) {
                // drained
            }
        }
        return mapping.bound.isBound() ? mapping.mapping : null;
    }

    private static InputConstants.Type typeOf(Key key) {
        return key.device() == Key.Device.MOUSE ? InputConstants.Type.MOUSE : InputConstants.Type.KEYBOARD;
    }

    private static final class Mapping {

        final KeyMapping mapping;
        Key bound;

        Mapping(KeyMapping mapping, Key bound) {
            this.mapping = mapping;
            this.bound = bound;
        }
    }
}
