package incari.client.modules;

import com.mojang.blaze3d.platform.InputConstants;
import incari.client.ui.ScreenPanels;
import incari.client.ui.Theme;
import incari.client.ui.Ui;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * A line of text a module asks for ({@code Game.askText}): a title, what to type, a field and Done.
 * In the browser it's also how a module reads the clipboard: the field takes the player's paste,
 * which is the only way a page gets the clipboard's text. Enter is Done, Escape cancels.
 */
final class TextEntryScreen extends Screen {

    private static final int PADDING = 12;
    private static final int MAX_WIDTH = 300;
    private static final int MAX_LENGTH = 256;
    private static final int PROMPT_LINES = 2;

    private final @Nullable Screen parent;
    private final Component prompt;
    private final String initial;
    private final Consumer<String> entered;

    private @Nullable EditBox field;
    private List<FormattedCharSequence> promptLines = List.of();
    private int panelX;
    private int panelY;
    private int panelWidth;

    TextEntryScreen(@Nullable Screen parent, String title, String prompt, String initial, Consumer<String> entered) {
        super(Component.literal(title));
        this.parent = parent;
        this.prompt = Component.literal(prompt);
        this.initial = initial;
        this.entered = entered;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(this.width - 16, MAX_WIDTH);
        int inner = panelWidth - 2 * PADDING;
        promptLines = Ui.wrap(this.font, prompt.getString(), inner, PROMPT_LINES);
        int panelHeight = PADDING
                + 9
                + Theme.LINE_GAP
                + promptLines.size() * 10
                + Theme.LINE_GAP
                + Theme.CONTROL_HEIGHT
                + Theme.GAP * 2
                + Theme.CONTROL_HEIGHT
                + PADDING;
        panelX = (this.width - panelWidth) / 2;
        panelY = Math.max(4, (this.height - panelHeight) / 2);
        ScreenPanels.clear(this);
        ScreenPanels.add(this, panelX, panelY, panelWidth, panelHeight);

        int y = panelY + PADDING + 9 + Theme.LINE_GAP + promptLines.size() * 10 + Theme.LINE_GAP;
        EditBox box = Ui.textField(this.font, this.title, inner, MAX_LENGTH);
        // A field rebuilt by a resize keeps what was pasted into it.
        EditBox old = field;
        box.setValue(old != null ? old.getValue() : initial);
        box.setPosition(panelX + PADDING, y);
        field = box;
        addRenderableWidget(box);
        setInitialFocus(box);

        y += Theme.CONTROL_HEIGHT + Theme.GAP * 2;
        int buttonWidth = (inner - Theme.GAP) / 2;
        addRenderableWidget(Ui.button(CommonComponents.GUI_CANCEL, buttonWidth, this::onClose))
                .setPosition(panelX + PADDING, y);
        addRenderableWidget(Ui.button(CommonComponents.GUI_DONE, buttonWidth, this::done))
                .setPosition(panelX + PADDING + buttonWidth + Theme.GAP, y);
    }

    private void done() {
        EditBox box = field;
        String text = box == null ? "" : box.getValue();
        this.minecraft.gui.setScreen(parent);
        entered.accept(text);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
            done();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        int x = panelX + PADDING;
        int y = panelY + PADDING;
        graphics.text(
                this.font,
                Ui.fit(this.font, this.title.getString(), panelWidth - 2 * PADDING),
                x,
                y,
                Theme.TEXT,
                false);
        y += 9 + Theme.LINE_GAP;
        for (FormattedCharSequence line : promptLines) {
            graphics.text(this.font, line, x, y, Theme.MUTED, false);
            y += 10;
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return parent != null && parent.isPauseScreen();
    }
}
