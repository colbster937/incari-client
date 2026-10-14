package incari.client.online;

import incari.client.platform.ClientPlatform;
import incari.client.ui.Theme;
import incari.platform.api.PlatformShell;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * The ten recovery codes, shown once (ADR 0058), with Copy and Download, and Continue only once the
 * player says they've saved them. Escape doesn't close it: closed by mistake, the codes are gone.
 */
final class RecoveryCodesScreen extends FormScreen {

    private static final String FILE_NAME = "incari-recovery-codes.txt";
    private static final int CODE_ROWS = 5;

    private final List<String> codes;
    private final boolean newAccount;
    private boolean saved;
    private @Nullable Button proceed;
    private int codesY;

    RecoveryCodesScreen(Screen parent, List<String> codes, boolean newAccount) {
        super("Your Recovery Codes", parent);
        this.codes = List.copyOf(codes);
        this.newAccount = newAccount;
    }

    @Override
    protected void build() {
        text(
                (newAccount ? "Account created. " : "Your old codes no longer work. ")
                        + "If you forget your password, use one of these codes to set a new one. Each "
                        + "works once. Save them somewhere other than this device.",
                Theme.MUTED);
        // Room for the codes, in two columns.
        codesY = stackHeight();
        gap(CODE_ROWS * 12 + 6);
        statusLine();
        boolean folders = ClientPlatform.get().shell().showsFolders();
        row(new String[] {"Copy", folders ? "Save to File" : "Download"}, new Runnable[] {this::copy, this::download});
        toggle("I saved my recovery codes", saved, on -> {
            saved = on;
            Button button = proceed;
            if (button != null) {
                button.active = on;
            }
        });
        proceed = button("Continue", this::onClose);
        proceed.active = saved;
    }

    private String asText() {
        StringBuilder text = new StringBuilder("Incari account recovery codes. Each works once.\n\n");
        for (String code : codes) {
            text.append(code).append('\n');
        }
        return text.toString();
    }

    private void copy() {
        this.minecraft.keyboardHandler.setClipboard(asText());
        status("Copied.", Theme.POSITIVE);
    }

    private void download() {
        PlatformShell shell = ClientPlatform.get().shell();
        byte[] bytes = asText().getBytes(StandardCharsets.UTF_8);
        if (!shell.showsFolders()) {
            shell.download(FILE_NAME, bytes);
            status("Downloading " + FILE_NAME, Theme.POSITIVE);
            return;
        }
        Path file = this.minecraft.gameDirectory.toPath().resolve(FILE_NAME);
        try {
            Files.write(file, bytes);
            shell.openPath(file.toAbsolutePath().toString());
            status("Saved as " + FILE_NAME + " in the game folder.", Theme.POSITIVE);
        } catch (IOException e) {
            problem("Couldn't save the file. Copy them instead.");
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        int x = this.width / 2 - CONTENT_WIDTH / 2;
        int y = contentTop() + codesY;
        graphics.fill(x, y - 3, x + CONTENT_WIDTH, y + CODE_ROWS * 12 + 1, Theme.FIELD);
        for (int i = 0; i < codes.size(); i++) {
            int column = i / CODE_ROWS;
            int row = i % CODE_ROWS;
            String label = (i + 1) + ".";
            int codeX = x + 6 + column * (CONTENT_WIDTH / 2);
            graphics.text(this.font, label, codeX, y + row * 12, Theme.MUTED, false);
            graphics.text(this.font, codes.get(i), codeX + 14, y + row * 12, Theme.TEXT, false);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return saved;
    }
}
