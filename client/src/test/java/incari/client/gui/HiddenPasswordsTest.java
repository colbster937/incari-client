package incari.client.gui;

import static org.assertj.core.api.Assertions.assertThat;

import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.Test;

class HiddenPasswordsTest {

    @Test
    void loginCommandsHideTheirArguments() {
        assertThat(HiddenPasswords.hiddenFrom("/login hunter2")).isEqualTo(7);
        assertThat(HiddenPasswords.hiddenFrom("/L hunter2")).isEqualTo(3);
        assertThat(HiddenPasswords.hiddenFrom("/register a a")).isEqualTo(10);
        assertThat(HiddenPasswords.hiddenFrom("/authme:login hunter2")).isEqualTo(14);
    }

    @Test
    void otherLinesHideNothing() {
        assertThat(HiddenPasswords.hiddenFrom("login hunter2")).isEqualTo(-1);
        assertThat(HiddenPasswords.hiddenFrom("/login")).isEqualTo(-1);
        assertThat(HiddenPasswords.hiddenFrom("/logout now")).isEqualTo(-1);
        assertThat(HiddenPasswords.hiddenFrom("/tp 0 64 0")).isEqualTo(-1);
    }

    @Test
    void onlyLinesWithAPasswordStayOutOfTheHistory() {
        assertThat(HiddenPasswords.hasPassword("/login hunter2")).isTrue();
        assertThat(HiddenPasswords.hasPassword("/changepassword old new")).isTrue();
        assertThat(HiddenPasswords.hasPassword("/login")).isFalse();
        assertThat(HiddenPasswords.hasPassword("/home")).isFalse();
        assertThat(HiddenPasswords.hasPassword("hello")).isFalse();
    }

    @Test
    void starsReplaceTheHiddenPartButNotSpaces() {
        // The box draws its value in pieces; the second starts at offset 4.
        assertThat(drawn(HiddenPasswords.starred("in pässwörd 𝄞", 4, 7))).isEqualTo("in ******** *");
        assertThat(drawn(HiddenPasswords.starred("/log", 0, 7))).isEqualTo("/log");
    }

    @Test
    void passwordLabelsAreFoundInAnyCase() {
        assertThat(HiddenPasswords.isPasswordLabel("Password")).isTrue();
        assertThat(HiddenPasswords.isPasswordLabel("Confirm your PASSWORD")).isTrue();
        assertThat(HiddenPasswords.isPasswordLabel("Passwort")).isTrue();
        assertThat(HiddenPasswords.isPasswordLabel("Username")).isFalse();
    }

    private static String drawn(FormattedCharSequence sequence) {
        StringBuilder text = new StringBuilder();
        sequence.accept((position, style, codePoint) -> {
            text.appendCodePoint(codePoint);
            return true;
        });
        return text.toString();
    }
}
