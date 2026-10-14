package incari.client.online;

import incari.client.ui.Theme;
import incari.online.OnlineService;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import org.jspecify.annotations.Nullable;

/**
 * An account action that needs the password again: new recovery codes, or deleting the account. The
 * warning says what it does before the player types anything.
 */
final class ConfirmPasswordScreen extends FormScreen {

    /** What the screen does with the password, and what it shows after. */
    private interface Action {
        void run(ConfirmPasswordScreen screen, OnlineService online, String password);
    }

    private final String warning;
    private final int warningColor;
    private final String confirm;
    private final String waiting;
    private final Action action;
    private @Nullable EditBox password;

    private ConfirmPasswordScreen(
            String title,
            Screen parent,
            String warning,
            int warningColor,
            String confirm,
            String waiting,
            Action action) {
        super(title, parent);
        this.warning = warning;
        this.warningColor = warningColor;
        this.confirm = confirm;
        this.waiting = waiting;
        this.action = action;
    }

    /** New Recovery Codes: ten new ones, shown once; the old ten stop working. */
    static ConfirmPasswordScreen newCodes(Screen parent) {
        return new ConfirmPasswordScreen(
                "New Recovery Codes",
                parent,
                "You'll get ten new recovery codes, and the ones you have now will stop working.",
                Theme.MUTED,
                "Get New Codes",
                "Making new codes...",
                (screen, online, password) -> online.newRecoveryCodes(password, result -> {
                    if (screen.answered(result)) {
                        screen.show(new RecoveryCodesScreen(screen.parent, result.value(), false));
                    }
                }));
    }

    /** Delete Account: the account and everything tied to it, at once and for good. */
    static ConfirmPasswordScreen delete(Screen parent) {
        return new ConfirmPasswordScreen(
                "Delete Account",
                parent,
                "This deletes your Incari account with its friends, blocks and cosmetics. "
                        + "It can't be undone, and someone else may take the username.",
                Theme.NEGATIVE,
                "Delete Account",
                "Deleting your account...",
                (screen, online, password) -> online.deleteAccount(password, result -> {
                    if (screen.answered(result)) {
                        screen.show(screen.parent);
                    }
                }));
    }

    @Override
    protected void build() {
        text(warning, warningColor);
        password = passwordField("Your password");
        statusLine();
        row(new String[] {CommonComponents.GUI_CANCEL.getString(), confirm}, new Runnable[] {this::onClose, this::run});
        onSubmit(this::run);
        setInitialFocus(password);
    }

    private void run() {
        EditBox box = password;
        if (box == null || busy()) {
            return;
        }
        if (box.getValue().isEmpty()) {
            problem("Type your password.");
            return;
        }
        sending(waiting);
        action.run(this, online, box.getValue());
        box.setValue("");
    }
}
