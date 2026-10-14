package incari.client.modules;

import incari.client.ui.ControlList;
import incari.client.ui.ListScreen;
import incari.client.ui.Theme;
import incari.client.ui.Ui;
import incari.framework.ModuleSystem;
import incari.framework.config.Profiles;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The profiles: switch to one, delete one, or make a new one as a copy of the active one. Each is a
 * whole set of module states and settings. Below them, the presets ({@link Presets}): each turns on
 * a ready-made set of modules in a profile of its own.
 */
final class ProfilesScreen extends ListScreen {

    private final ModuleSystem system;
    private @Nullable EditBox name;
    private @Nullable Button create;

    ProfilesScreen(@Nullable Screen parent, ModuleSystem system) {
        super(Component.literal("Profiles"), parent);
        this.system = system;
    }

    @Override
    protected Component caption() {
        return Component.literal("A profile saves which modules are on and their settings.");
    }

    @Override
    protected long revision() {
        return system.revision();
    }

    @Override
    protected Object shape() {
        return List.copyOf(system.profiles()) + system.activeProfile();
    }

    @Override
    protected List<ControlList.Row> rows(ControlList list) {
        List<ControlList.Row> rows = new ArrayList<>();
        for (String profile : system.profiles()) {
            boolean active = profile.equals(system.activeProfile());
            Button use = Ui.button(
                    Component.literal(active ? "Active" : "Use"),
                    Theme.SMALL_BUTTON_WIDTH,
                    () -> system.switchProfile(profile));
            use.active = !active;
            Button delete =
                    Ui.button(Component.literal("Delete"), Theme.SMALL_BUTTON_WIDTH, () -> confirmDelete(profile));
            delete.active = system.profiles().size() > 1;
            Component label = active ? Component.literal(profile).withColor(Theme.ACCENT) : Component.literal(profile);
            rows.add(list.row(label, "", List.of(use, delete), () -> {}));
        }
        rows.add(list.heading(Component.literal("Presets")));
        for (Presets.Preset preset : Presets.ALL) {
            boolean active = preset.name().equalsIgnoreCase(system.activeProfile());
            Button apply = Ui.button(
                    Component.literal(active ? "Reset" : "Use"),
                    Theme.SMALL_BUTTON_WIDTH * 2 + Theme.GAP,
                    () -> system.applyPreset(preset.name(), preset.modules(), preset.settings()));
            rows.add(list.row(Component.literal(preset.name()), preset.description(), List.of(apply), () -> {}));
        }
        return rows;
    }

    private void confirmDelete(String profile) {
        minecraft.gui.setScreen(new ConfirmScreen(
                yes -> {
                    if (yes) {
                        system.deleteProfile(profile);
                    }
                    minecraft.gui.setScreen(this);
                },
                Component.literal("Delete profile " + profile + "?"),
                Component.literal("Its module settings will be lost.")));
    }

    @Override
    protected void footer(LinearLayout footer) {
        EditBox field = Ui.textField(this.font, Component.literal("New profile name"), Theme.CONTROL_WIDTH - 30, 24);
        field.setHint(Component.literal("New profile").withColor(Theme.MUTED));
        Button button = Ui.button(Component.literal("Copy"), Theme.SMALL_BUTTON_WIDTH + 10, this::createProfile);
        field.setResponder(text -> button.active = Profiles.validName(text.strip()));
        button.active = false;
        name = field;
        create = button;
        footer.addChild(field);
        footer.addChild(button);
        footer.addChild(Ui.button(CommonComponents.GUI_DONE, Theme.SMALL_BUTTON_WIDTH * 2, this::onClose));
    }

    private void createProfile() {
        EditBox field = name;
        if (field == null) {
            return;
        }
        if (system.createProfile(field.getValue().strip())) {
            field.setValue("");
            Button button = create;
            if (button != null) {
                button.active = false;
            }
        }
    }
}
