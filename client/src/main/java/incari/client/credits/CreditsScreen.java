package incari.client.credits;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Incari's credits and the licenses of what it adds to the game ({@link Credits}), reached from
 * the vanilla Credits & Attribution screen. Each entry links to its source and its license, which
 * is what GPL-3.0 asks a build to come with.
 *
 * <p>English only for now; Incari's screens don't have language files yet.
 */
public final class CreditsScreen extends Screen {

    private static final Component TITLE = Component.literal("Incari & Open Source");
    private static final Component NOTICE = Component.literal(
            "Incari Client is free software that runs on Minecraft: Java Edition. It is not an official Minecraft"
                    + " product, and is not approved by or associated with Mojang or Microsoft. This build contains"
                    + " the software below, each under its own license.");
    private static final int BODY_WIDTH = 300;
    private static final int BUTTON_WIDTH = 103;
    private static final int BUTTON_SPACING = 4;

    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private @Nullable ScrollableLayout body;

    public CreditsScreen(Screen parent) {
        super(TITLE);
        this.parent = parent;
    }

    /** The button that opens this screen from {@code parent}, for the vanilla screen's list. */
    public static Button button(Screen parent, int width) {
        return Button.builder(TITLE, button -> Minecraft.getInstance().gui.setScreen(new CreditsScreen(parent)))
                .width(width)
                .build();
    }

    @Override
    protected void init() {
        this.layout.addTitleHeader(TITLE, this.font);
        LinearLayout content = LinearLayout.vertical().spacing(12);
        content.defaultCellSetting().alignHorizontallyCenter();
        content.addChild(new MultiLineTextWidget(NOTICE, this.font)
                .setMaxWidth(BODY_WIDTH)
                .setCentered(true));
        for (Credit credit : Credits.all()) {
            content.addChild(entry(credit));
        }
        ScrollableLayout scroll =
                new ScrollableLayout(Minecraft.getInstance(), content, this.layout.getContentHeight());
        body = scroll;
        this.layout.addToContents(scroll);
        this.layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .width(200)
                .build());
        this.layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    private LinearLayout entry(Credit credit) {
        LinearLayout entry = LinearLayout.vertical().spacing(2);
        entry.defaultCellSetting().alignHorizontallyCenter();
        entry.addChild(new StringWidget(Component.literal(credit.title()), this.font));
        entry.addChild(new MultiLineTextWidget(
                        Component.literal(credit.authors() + " · " + credit.license())
                                .withStyle(ChatFormatting.GRAY),
                        this.font)
                .setMaxWidth(BODY_WIDTH)
                .setCentered(true));
        LinearLayout links = entry.addChild(LinearLayout.horizontal().spacing(BUTTON_SPACING));
        links.addChild(Button.builder(Component.literal("Source"), ConfirmLinkScreen.confirmLink(this, credit.source()))
                .width(BUTTON_WIDTH)
                .build());
        links.addChild(
                Button.builder(Component.literal("License"), ConfirmLinkScreen.confirmLink(this, credit.licenseText()))
                        .width(BUTTON_WIDTH)
                        .build());
        return entry;
    }

    @Override
    protected void repositionElements() {
        ScrollableLayout scroll = body;
        if (scroll != null) {
            scroll.arrangeElements();
            scroll.setMaxHeight(this.layout.getContentHeight());
        }
        this.layout.arrangeElements();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
