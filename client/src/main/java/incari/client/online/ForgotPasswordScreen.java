package incari.client.online;

import incari.client.ui.Theme;
import incari.online.Credentials;
import incari.online.RecoveryCodes;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import org.jspecify.annotations.Nullable;

/**
 * Forgot Password: the username, one recovery code (used up by this) and a new password. Every other
 * session the account had ends. Without a code there's no way back, and the screen says so.
 */
final class ForgotPasswordScreen extends FormScreen {

    private @Nullable EditBox username;
    private @Nullable EditBox code;
    private @Nullable EditBox password;
    private @Nullable EditBox again;

    ForgotPasswordScreen(Screen parent) {
        super("Forgot Password", parent);
    }

    @Override
    protected void build() {
        text(
                "Enter one of the recovery codes you saved when you made the account. Without one, the "
                        + "account can't be recovered.",
                Theme.MUTED);
        username = usernameField("Incari username");
        code = field("Recovery code (XXXX-XXXX-XXXX)", 20);
        password = passwordField("New password");
        again = passwordField("New password again");
        statusLine();
        row(
                new String[] {CommonComponents.GUI_CANCEL.getString(), "Set Password"},
                new Runnable[] {this::onClose, this::recover});
        onSubmit(this::recover);
        setInitialFocus(username);
    }

    private void recover() {
        EditBox name = username;
        EditBox typedCode = code;
        EditBox secret = password;
        EditBox confirm = again;
        if (name == null || typedCode == null || secret == null || confirm == null || busy()) {
            return;
        }
        if (name.getValue().isEmpty()) {
            problem("Type your username.");
            return;
        }
        if (RecoveryCodes.normalize(typedCode.getValue()) == null) {
            problem("A code is 12 letters and digits, like ABCD-EFGH-JKMN.");
            return;
        }
        String passwordProblem = Credentials.passwordProblem(secret.getValue(), name.getValue());
        if (passwordProblem != null) {
            problem("Password: " + passwordProblem + ".");
            return;
        }
        if (!secret.getValue().equals(confirm.getValue())) {
            problem("The two passwords don't match.");
            return;
        }
        sending("Setting your new password...");
        online.recover(name.getValue(), typedCode.getValue(), secret.getValue(), result -> {
            if (answered(result)) {
                show(parent);
            }
        });
    }
}
