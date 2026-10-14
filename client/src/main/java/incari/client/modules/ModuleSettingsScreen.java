package incari.client.modules;

import com.mojang.blaze3d.platform.InputConstants;
import incari.client.ui.ControlList;
import incari.client.ui.ListScreen;
import incari.client.ui.SettingControls;
import incari.client.ui.Theme;
import incari.client.ui.Ui;
import incari.framework.Module;
import incari.framework.ModuleSystem;
import incari.framework.hud.HudElement;
import incari.framework.setting.ActionSetting;
import incari.framework.setting.Key;
import incari.framework.setting.KeySetting;
import incari.framework.setting.Setting;
import incari.framework.setting.Settings;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A module's settings, generated from its schema alone: a row per visible setting with its control
 * ({@link SettingControls}) and a reset button, then each HUD element's placement under a heading,
 * and the HUD editor a click away for a module with HUD elements. Nothing here knows any particular
 * module.
 */
final class ModuleSettingsScreen extends ListScreen implements SettingControls.KeyCapture {

    private final ModuleSystem system;
    private final Module module;
    private @Nullable KeySetting capturing;

    ModuleSettingsScreen(@Nullable Screen parent, ModuleSystem system, Module module) {
        super(Component.literal(module.name()), parent);
        this.system = system;
        this.module = module;
    }

    @Override
    protected Component caption() {
        return Component.literal(module.description());
    }

    @Override
    protected long revision() {
        return system.revision();
    }

    /** The visible settings: one appearing or going rebuilds the rows. */
    @Override
    protected Object shape() {
        List<Setting<?>> visible = new ArrayList<>();
        collect(module.settings(), visible);
        for (HudElement element : module.hudElements()) {
            collect(element.settings(), visible);
        }
        return visible;
    }

    private static void collect(Settings settings, List<Setting<?>> into) {
        for (Setting<?> setting : settings) {
            if (setting.isVisible()) {
                into.add(setting);
            }
        }
    }

    @Override
    protected List<ControlList.Row> rows(ControlList list) {
        SettingControls controls = new SettingControls(this.font, this, IncariModules.keys());
        List<ControlList.Row> rows = new ArrayList<>();
        addRows(list, controls, module.settings(), rows);
        for (HudElement element : module.hudElements()) {
            if (element.settings().anyShown()) {
                rows.add(list.heading(Component.literal("HUD: " + element.name())));
                addRows(list, controls, element.settings(), rows);
            }
        }
        return rows;
    }

    private static void addRows(
            ControlList list, SettingControls controls, Settings settings, List<ControlList.Row> rows) {
        for (Setting<?> setting : settings) {
            if (!setting.isVisible()) {
                continue;
            }
            SettingControls.Control control = controls.control(setting);
            if (setting instanceof ActionSetting) {
                rows.add(list.row(
                        Component.literal(setting.name()),
                        setting.description(),
                        List.of(control.widget()),
                        control.refresh()));
                continue;
            }
            Button reset = Ui.button(Component.literal("Reset"), Theme.SMALL_BUTTON_WIDTH, setting::reset);
            reset.active = !setting.isDefault();
            rows.add(list.row(
                    Component.literal(setting.name()), setting.description(), List.of(control.widget(), reset), () -> {
                        control.refresh().run();
                        reset.active = !setting.isDefault();
                    }));
        }
    }

    @Override
    protected void footer(LinearLayout footer) {
        boolean hud = !module.hudElements().isEmpty();
        // Three buttons share the footer's width when the module has a HUD element to place.
        int width = hud ? Theme.FOOTER_BUTTON_WIDTH * 2 / 3 : Theme.FOOTER_BUTTON_WIDTH;
        if (hud) {
            footer.addChild(Ui.button(Component.literal("Edit HUD"), width, () -> {
                // Back to the editor if that's where this came from, rather than one over another.
                if (parent instanceof HudEditorScreen) {
                    onClose();
                } else {
                    IncariModules.openHudEditor(this);
                }
            }));
        }
        footer.addChild(Ui.button(Component.literal("Reset All"), width, () -> {
            module.settings().resetAll();
            for (HudElement element : module.hudElements()) {
                element.settings().resetAll();
            }
        }));
        footer.addChild(Ui.button(CommonComponents.GUI_DONE, width, this::onClose));
    }

    @Override
    public void capture(KeySetting setting) {
        capturing = setting;
        refreshRows();
    }

    @Override
    public @Nullable KeySetting capturing() {
        return capturing;
    }

    /** While a bind waits for a key, the next key binds it; Escape unbinds it, as in the game's Controls. */
    @Override
    public boolean keyPressed(KeyEvent event) {
        KeySetting setting = capturing;
        if (setting == null) {
            return super.keyPressed(event);
        }
        capturing = null;
        setting.set(event.isEscape() ? Key.NONE : GameKeys.fromGame(InputConstants.getKey(event)));
        refreshRows();
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        KeySetting setting = capturing;
        if (setting == null) {
            return super.mouseClicked(event, doubleClick);
        }
        capturing = null;
        setting.set(Key.mouse(event.button()));
        refreshRows();
        return true;
    }

    @Override
    public void removed() {
        system.flush();
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
