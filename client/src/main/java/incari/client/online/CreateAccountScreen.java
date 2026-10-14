package incari.client.online;

import incari.client.ui.Theme;
import incari.online.Credentials;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import org.jspecify.annotations.Nullable;

/**
 * Create Account: a username, a password typed twice, and a plain warning that the account can't be
 * recovered without the password or a recovery code. Sending it solves the service's proof of work
 * first (a few seconds on a Chromebook, on the background pool), then shows the recovery codes.
 */
final class CreateAccountScreen extends FormScreen {

    private @Nullable EditBox username;
    private @Nullable EditBox password;
    private @Nullable EditBox again;

    CreateAccountScreen(Screen parent) {
        super("Create Account", parent);
    }

    @Override
    protected void build() {
        text(
                "Your Incari username is separate from your Minecraft name, and only friends you add "
                        + "will see it. Don't use your real name.",
                Theme.MUTED);
        username = usernameField("Incari username (3-16 letters, digits, _)");
        password = passwordField("Password (10 or more characters)");
        again = passwordField("Password again");
        text(
                "There's no email. If you forget your password, you'll need a recovery code. "
                        + "You'll get 10 on the next screen.",
                Theme.WARNING);
        statusLine();
        row(
                new String[] {CommonComponents.GUI_CANCEL.getString(), "Create Account"},
                new Runnable[] {this::onClose, this::create});
        onSubmit(this::create);
        setInitialFocus(username);
    }

    private void create() {
        EditBox name = username;
        EditBox secret = password;
        EditBox confirm = again;
        if (name == null || secret == null || confirm == null || busy()) {
            return;
        }
        String nameProblem = Credentials.usernameProblem(name.getValue());
        if (nameProblem != null) {
            problem(nameProblem + ".");
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
        sending("Creating your account (a few seconds)...");
        online.signUp(name.getValue(), secret.getValue(), result -> {
            if (!current()) {
                // Gone elsewhere once the form was out, and the account was made all the same:
                // its codes wait for the account screen rather than covering what's shown now.
                if (result.ok()) {
                    IncariOnline.keepRecoveryCodes(result.value());
                }
                return;
            }
            if (answered(result)) {
                show(new RecoveryCodesScreen(parent, result.value(), true));
            }
        });
    }

    @Override
    public void onClose() {
        if (busy() && !online.cancelSignUp()) {
            // The form is out and the account may already exist: its codes are moments away.
            status("Almost done. Your account is being created...", Theme.MUTED);
            return;
        }
        super.onClose();
    }

    @Override
    public void removed() {
        if (busy()) {
            // Still solving the challenge: nothing is made. Past that, the answer is kept (above).
            online.cancelSignUp();
        }
    }
}
