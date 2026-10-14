package incari.client.gui;

import java.util.Locale;
import java.util.Set;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * Passwords shown as stars while they're typed: a login plugin's command in chat ({@code /login},
 * {@code /register} and their kin), and a server dialog's text box whose label names a password.
 * Only what's drawn changes; the box holds and sends the text as typed. The stars take the place of
 * the characters through the box's formatter, so the cursor follows the stars, though a selection
 * or a click lands by the hidden characters' widths.
 */
public final class HiddenPasswords {

    /**
     * Login plugins' commands whose arguments are passwords (AuthMe, nLogin, LibreLogin, Eagler's
     * own and their aliases).
     */
    private static final Set<String> COMMANDS = Set.of(
            "login",
            "l",
            "log",
            "logar",
            "register",
            "reg",
            "registrar",
            "changepassword",
            "changepass",
            "changepw",
            "unregister",
            "unreg");

    /** Words a password box's label holds, in the languages login plugins most often speak. */
    private static final String[] PASSWORD_WORDS = {
        "password", "passwort", "contraseña", "senha", "mot de passe", "пароль"
    };

    private static final int STAR = '*';

    private HiddenPasswords() {}

    /** {@code ChatScreen.init}: the chat box's stars, ahead of its other formatters. */
    public static void hideInChat(EditBox input) {
        input.addFormatter((text, offset) -> {
            int from = hiddenFrom(input.getValue());
            return from < 0 ? null : starred(text, offset, from);
        });
    }

    /** {@code InputControlHandlers}: a dialog's text box, starred if its label names a password. */
    public static void hideInDialog(EditBox box, Component label) {
        if (isPasswordLabel(label.getString())) {
            box.addFormatter((text, offset) -> starred(text, offset, 0));
        }
    }

    /**
     * A password field of Incari's own (the account screens, ADR 0058): every character a star,
     * spaces too. A pass phrase's spaces would show its words' lengths.
     */
    public static void hide(EditBox box) {
        box.addFormatter((text, offset) -> sink -> {
            int i = 0;
            while (i < text.length()) {
                int codePoint = text.codePointAt(i);
                if (!sink.accept(i, Style.EMPTY, STAR)) {
                    return false;
                }
                i += Character.charCount(codePoint);
            }
            return true;
        });
    }

    /**
     * {@code ChatComponent.addRecentChat} and {@code CommandHistory}'s load: whether {@code line}
     * holds a password, so it's kept out of the chat's history and {@code command_history.txt}. The
     * stars only hide it while it's typed; the up arrow and the file would show it whole.
     */
    public static boolean hasPassword(String line) {
        return hiddenFrom(line) >= 0;
    }

    /**
     * Where a chat line's password starts: after the command word, if the line is a login command
     * (namespaced or not, any case); -1 if it isn't one.
     */
    static int hiddenFrom(String line) {
        if (!line.startsWith("/")) {
            return -1;
        }
        int end = line.indexOf(' ');
        if (end < 0) {
            return -1;
        }
        String command = line.substring(1, end).toLowerCase(Locale.ROOT);
        command = command.substring(command.indexOf(':') + 1);
        return COMMANDS.contains(command) ? end + 1 : -1;
    }

    static boolean isPasswordLabel(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        for (String word : PASSWORD_WORDS) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code text}, which starts at {@code offset} in the box's value, with every character from
     * {@code from} on drawn as a star. Spaces stay, so a password and its confirmation show as two.
     */
    static FormattedCharSequence starred(String text, int offset, int from) {
        return sink -> {
            int i = 0;
            while (i < text.length()) {
                int codePoint = text.codePointAt(i);
                boolean hidden = offset + i >= from && codePoint != ' ';
                if (!sink.accept(i, Style.EMPTY, hidden ? STAR : codePoint)) {
                    return false;
                }
                i += Character.charCount(codePoint);
            }
            return true;
        };
    }
}
