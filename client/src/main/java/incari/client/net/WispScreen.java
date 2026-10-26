package incari.client.net;

import incari.client.ui.Theme;
import incari.client.ui.Ui;
import incari.net.WebSocketUrl;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A server's Wispcraft choice ({@link WispServers}), opened from the edit-server screen: on or off,
 * and the proxy. Done saves both; it waits for a proxy that's a {@code ws://} or {@code wss://} URL.
 *
 * <p>English only for now; Incari's screens don't have language files yet.
 */
final class WispScreen extends Screen {

    private static final int WIDTH = 200;
    private static final int TEXT_WIDTH = 300;
    private static final int MAX_PROXY_LENGTH = 512;

    private final Screen parent;
    private final Consumer<WispChoice> onDone;
    /** What's chosen so far: kept across a resize, which builds the widgets again. */
    private boolean on;

    private String proxy;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private @Nullable Button done;

    WispScreen(Screen parent, WispChoice initial, Consumer<WispChoice> onDone) {
        super(Component.literal("Wispcraft"));
        this.parent = parent;
        this.on = initial.on();
        this.proxy = initial.proxy();
        this.onDone = onDone;
    }

    @Override
    protected void init() {
        layout.addTitleHeader(this.title, this.font);

        LinearLayout contents = layout.addToContents(LinearLayout.vertical().spacing(Theme.GAP));
        contents.defaultCellSetting().alignHorizontallyCenter();
        contents.addChild(new MultiLineTextWidget(
                        Component.literal("Joins this Java server (host:port) through a Wisp proxy, so a browser can"
                                + " reach it. Everything sent to the server passes through the proxy."),
                        this.font)
                .setMaxWidth(Math.min(TEXT_WIDTH, this.width - 20))
                .setMaxRows(3)
                .setCentered(true));
        contents.addChild(CycleButton.onOffBuilder(on)
                .create(
                        0,
                        0,
                        WIDTH,
                        Theme.CONTROL_HEIGHT,
                        Component.literal("Wispcraft"),
                        (button, value) -> on = value));
        contents.addChild(new StringWidget(Component.literal("Wisp proxy"), this.font));
        EditBox field =
                contents.addChild(Ui.textField(this.font, Component.literal("Wisp proxy"), WIDTH, MAX_PROXY_LENGTH));
        field.setValue(proxy);
        field.setHint(Component.literal(WispChoice.DEFAULT_PROXY).withStyle(EditBox.SEARCH_HINT_STYLE));
        field.setResponder(text -> {
            proxy = text;
            updateDone();
        });

        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        done = footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> {
                    onDone.accept(new WispChoice(on, proxyOrDefault()));
                    onClose();
                })
                .build());
        footer.addChild(
                Button.builder(CommonComponents.GUI_CANCEL, button -> onClose()).build());

        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
        updateDone();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    /** The proxy typed, or Wispcraft's default for an empty field. */
    private String proxyOrDefault() {
        String typed = proxy.strip();
        return typed.isEmpty() ? WispChoice.DEFAULT_PROXY : typed;
    }

    private void updateDone() {
        Button button = done;
        if (button != null) {
            button.active = WebSocketUrl.tryParse(proxyOrDefault()) != null;
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
