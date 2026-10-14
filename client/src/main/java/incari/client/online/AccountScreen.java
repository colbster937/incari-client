package incari.client.online;

import incari.client.ui.Theme;
import incari.framework.online.Online.Status;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import org.jspecify.annotations.Nullable;

/**
 * Incari Account (ADR 0058), from the title screen's player card: signing in, creating an account
 * and recovering one when signed out; the account's settings, blocked players, password, recovery
 * codes, signing out and deleting it when signed in. Laid out again whenever the connection's
 * status changes.
 */
public final class AccountScreen extends FormScreen {

    private @Nullable Status shown;
    private @Nullable Boolean shownHasSession;

    public AccountScreen(Screen parent) {
        super("Incari Account", parent);
    }

    @Override
    protected void build() {
        Status status = online.status();
        shown = status;
        shownHasSession = online.hasSavedSession();
        switch (status) {
            case OFF -> {
                text("Incari accounts aren't available in this version.", Theme.MUTED);
                done();
            }
            case UPDATE_NEEDED -> {
                text("Update Incari Client to use your account. " + "Everything else still works.", Theme.WARNING);
                done();
            }
            case CONNECTING -> connecting();
            case SIGNED_OUT -> signedOut();
            case SIGNED_IN -> signedIn();
        }
    }

    private void connecting() {
        String name = online.accountName();
        if (name != null) {
            centred("Signing in as " + name + "...", Theme.TEXT);
        } else {
            centred("Connecting to Incari...", Theme.TEXT);
        }
        text(
                "If this takes a while, your network may be blocking Incari. You can still play "
                        + "without an account.",
                Theme.MUTED);
        row(
                new String[] {"Try Again", CommonComponents.GUI_DONE.getString()},
                new Runnable[] {online::retry, this::onClose});
        if (name != null) {
            gap(2);
            button("Sign Out on This Device", () -> online.signOut(false, result -> {}));
        }
        statusLine();
    }

    private void signedOut() {
        text(
                "An Incari account is optional. It lets friends find you and others see your "
                        + "cosmetics on any server. You only need a username and a password, with no email "
                        + "or real name.",
                Theme.MUTED);
        gap(2);
        button("Sign In", () -> show(new SignInScreen(this)));
        button("Create Account", () -> show(new CreateAccountScreen(this)));
        button("Forgot Password", () -> show(new ForgotPasswordScreen(this)));
        statusLine();
        done();
    }

    private void signedIn() {
        String name = online.accountName();
        centred("Signed in as " + (name == null ? "?" : name), Theme.ACCENT);
        gap(2);
        toggle("Show friends which server I'm on", online.sharesServer(), share -> {
            if (!busy()) {
                sending("Saving...");
                online.setShareServer(share, result -> {
                    if (answered(result)) {
                        status("Saved.", Theme.POSITIVE);
                    }
                    // Show what the service holds, whichever way it went.
                    rebuildWidgets();
                });
            }
        });
        button("Blocked Players", () -> show(new BlockedPlayersScreen(this)));
        row(new String[] {"Change Password", "New Recovery Codes"}, new Runnable[] {
            () -> show(new ChangePasswordScreen(this)), () -> show(ConfirmPasswordScreen.newCodes(this))
        });
        row(new String[] {"Sign Out", "Sign Out Everywhere"}, new Runnable[] {() -> signOut(false), () -> signOut(true)
        });
        button("Delete Account", () -> show(ConfirmPasswordScreen.delete(this)));
        statusLine();
        done();
    }

    private void signOut(boolean everywhere) {
        if (busy()) {
            return;
        }
        sending("Signing out...");
        online.signOut(everywhere, result -> {
            if (answered(result)) {
                status(everywhere ? "Signed out on every device." : "Signed out.", Theme.POSITIVE);
            }
        });
    }

    private void done() {
        button(CommonComponents.GUI_DONE.getString(), this::onClose);
    }

    @Override
    public void tick() {
        super.tick();
        if (current()) {
            // A new account's codes the player left Create Account before seeing: shown now, once.
            List<String> codes = IncariOnline.takeRecoveryCodes();
            if (codes != null) {
                show(new RecoveryCodesScreen(this, codes, true));
                return;
            }
        }
        if (online.status() != shown || shownHasSession == null || online.hasSavedSession() != shownHasSession) {
            rebuildWidgets();
        }
    }
}
