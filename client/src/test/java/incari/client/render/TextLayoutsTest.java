package incari.client.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;

/** What makes two styles lay text out the same way, for {@link TextLayouts}. */
class TextLayoutsTest {

    @Test
    void eventsDontChangeTheLayout() {
        Style plain = Style.EMPTY.withColor(ChatFormatting.YELLOW);
        Style withEvents = plain.withHoverEvent(new HoverEvent.ShowText(
                        Component.literal(UUID.randomUUID().toString())))
                .withClickEvent(new ClickEvent.SuggestCommand("/msg"));

        assertThat(TextLayouts.sameLayout(plain, withEvents)).isTrue();
        assertThat(TextLayouts.layoutHash(plain)).isEqualTo(TextLayouts.layoutHash(withEvents));
    }

    @Test
    void colourAndWeightDo() {
        Style yellow = Style.EMPTY.withColor(ChatFormatting.YELLOW);
        assertThat(TextLayouts.sameLayout(yellow, Style.EMPTY.withColor(ChatFormatting.RED)))
                .isFalse();
        assertThat(TextLayouts.sameLayout(yellow, yellow.withBold(true))).isFalse();
        assertThat(TextLayouts.sameLayout(yellow, yellow.withObfuscated(true))).isFalse();
        assertThat(TextLayouts.sameLayout(Style.EMPTY, Style.EMPTY.withShadowColor(0xFF000000)))
                .isFalse();
    }
}
