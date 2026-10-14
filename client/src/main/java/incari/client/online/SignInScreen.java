package incari.client.online;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import org.jspecify.annotations.Nullable;

/** Sign In: an Incari username and password. A failure never says which of the two was wrong. */
final class SignInScreen extends FormScreen {

    private @Nullable EditBox username;
    private @Nullable EditBox password;

    SignInScreen(Screen parent) {
        super("Sign In", parent);
    }

    @Override
    protected void build() {
        username = usernameField("Incari username");
        password = passwordField("Password");
        statusLine();
        row(
                new String[] {CommonComponents.GUI_CANCEL.getString(), "Sign In"},
                new Runnable[] {this::onClose, this::signIn});
        button("Forgot Password", () -> show(new ForgotPasswordScreen(parent)));
        onSubmit(this::signIn);
        setInitialFocus(username.getValue().isEmpty() ? username : password);
    }

    private void signIn() {
        EditBox name = username;
        EditBox secret = password;
        if (name == null || secret == null || busy()) {
            return;
        }
        if (name.getValue().isEmpty() || secret.getValue().isEmpty()) {
            problem("Type your username and password.");
            return;
        }
        sending("Signing in...");
        online.signIn(name.getValue(), secret.getValue(), result -> {
            if (answered(result)) {
                show(parent);
            } else {
                secret.setValue("");
            }
        });
    }

    @Override
    public void tick() {
        super.tick();
        // Signed in elsewhere meanwhile (a resumed session): nothing to do here.
        if (online.signedIn()) {
            show(parent);
        }
    }
}
