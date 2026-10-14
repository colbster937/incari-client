package incari.client.eagler;

import incari.net.eagler.EaglerPassword;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * Asks for the password an Eagler server's auth plugin wants (ADR 0019), then joins again
 * with it. The server's own words are shown above the field. A server that wants the password
 * unhashed gets it only if the player agrees on this screen: anyone between the player and the
 * server could read it.
 *
 * <p>English only for now; Incari's screens don't have language files yet.
 */
public final class PasswordScreen extends Screen {

    /** Below the title (drawn at 17): where the server's message may start. */
    private static final int TITLE_BOTTOM = 32;

    private final Screen parent;
    private final ServerData server;
    private final ServerAddress address;
    private final EaglerAuth.Request request;

    private boolean plaintextAllowed;
    private @Nullable EditBox password;
    private @Nullable Button logIn;

    public PasswordScreen(Screen parent, ServerData server, ServerAddress address, EaglerAuth.Request request) {
        super(Component.literal("Password"));
        this.parent = parent;
        this.server = server;
        this.address = address;
        this.request = request;
    }

    private boolean plaintext() {
        return request.method() == EaglerPassword.PLAINTEXT;
    }

    /** Where the plain-text notice is drawn: just above the password field. */
    private int noticeY() {
        return this.height / 2 - 20 - 14;
    }

    @Override
    protected void init() {
        int x = this.width / 2 - 100;
        int y = this.height / 2 - 20;
        String said = request.message().isBlank() ? "This server needs a password." : request.message();
        // The server's message sits just above the field (and the plain-text notice), as many lines
        // as fit below the title: a long one is cut, never drawn over the notice or the field.
        int bottom = noticeY() - (plaintext() ? 4 : -5);
        int rows = Math.max(1, (bottom - TITLE_BOTTOM) / this.font.lineHeight);
        MultiLineTextWidget message = new MultiLineTextWidget(Component.literal(said), this.font)
                .setMaxWidth(300)
                .setMaxRows(rows)
                .setCentered(true);
        // The widget is as wide as its longest line: centred over the field, not left-aligned to it.
        message.setPosition(this.width / 2 - message.getWidth() / 2, bottom - message.getHeight());
        this.addRenderableWidget(message);
        EditBox box = new EditBox(this.font, x, y, 200, 20, password, Component.literal("Password"));
        box.setMaxLength(127);
        // Shown as stars; the text itself stays in the box.
        box.addFormatter((text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        box.setResponder(text -> updateLogIn());
        password = this.addRenderableWidget(box);
        int buttons = y + 30;
        if (plaintext()) {
            this.addRenderableWidget(
                    CycleButton.<Boolean>builder(allowed -> Component.literal(allowed ? "Yes" : "No"), plaintextAllowed)
                            .withValues(List.of(false, true))
                            .create(x, buttons, 200, 20, Component.literal("Send as plain text"), (button, allowed) -> {
                                plaintextAllowed = allowed;
                                updateLogIn();
                            }));
            buttons += 24;
        }
        logIn = this.addRenderableWidget(Button.builder(Component.literal("Log In"), button -> logIn())
                .bounds(x, buttons, 98, 20)
                .build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                .bounds(x + 102, buttons, 98, 20)
                .build());
        this.setInitialFocus(box);
        updateLogIn();
    }

    private void updateLogIn() {
        Button button = logIn;
        if (button != null) {
            button.active = !typed().isEmpty() && (!plaintext() || plaintextAllowed);
        }
    }

    private String typed() {
        EditBox box = password;
        return box == null ? "" : box.getValue();
    }

    private void logIn() {
        EaglerAuth.remember(request.server(), new EaglerPassword(typed(), plaintextAllowed));
        Minecraft minecraft = Minecraft.getInstance();
        ConnectScreen.startConnecting(parent, minecraft, address, server, false, null);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        Button button = logIn;
        if (event.isConfirmation() && button != null && button.active) {
            logIn();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 17, 0xFFFFFFFF);
        if (plaintext()) {
            graphics.centeredText(
                    this.font,
                    Component.literal("This server wants your password as plain text.")
                            .withStyle(ChatFormatting.YELLOW),
                    this.width / 2,
                    noticeY(),
                    0xFFFFFFFF);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
