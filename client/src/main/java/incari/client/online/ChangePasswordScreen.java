package incari.client.online;

import incari.client.ui.Theme;
import incari.online.Credentials;
import java.util.Objects;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import org.jspecify.annotations.Nullable;

/** Change Password: the current one and a new one. Every other session the account has ends. */
final class ChangePasswordScreen extends FormScreen {

    private @Nullable EditBox current;
    private @Nullable EditBox password;
    private @Nullable EditBox again;
    private boolean changed;

    ChangePasswordScreen(Screen parent) {
        super("Change Password", parent);
    }

    @Override
    protected void build() {
        if (changed) {
            text("Your password is changed. Every other device was signed out.", Theme.POSITIVE);
            button(CommonComponents.GUI_DONE.getString(), this::onClose);
            onSubmit(this::onClose);
            return;
        }
        current = passwordField("Current password");
        password = passwordField("New password");
        again = passwordField("New password again");
        statusLine();
        row(
                new String[] {CommonComponents.GUI_CANCEL.getString(), "Change Password"},
                new Runnable[] {this::onClose, this::change});
        onSubmit(this::change);
        setInitialFocus(current);
    }

    private void change() {
        EditBox old = current;
        EditBox secret = password;
        EditBox confirm = again;
        if (old == null || secret == null || confirm == null || busy()) {
            return;
        }
        String passwordProblem =
                Credentials.passwordProblem(secret.getValue(), Objects.requireNonNullElse(online.accountName(), ""));
        if (passwordProblem != null) {
            problem("New password: " + passwordProblem + ".");
            return;
        }
        if (!secret.getValue().equals(confirm.getValue())) {
            problem("The two new passwords don't match.");
            return;
        }
        sending("Changing your password...");
        online.changePassword(old.getValue(), secret.getValue(), result -> {
            if (answered(result)) {
                changed = true;
                rebuildWidgets();
            } else {
                old.setValue("");
            }
        });
    }
}
