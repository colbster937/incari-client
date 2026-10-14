package incari.client.render;

import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;

/**
 * Blank sign lines left out (ADR 0031). Each frame the game measures and submits every line of both
 * sides of every sign in view, blank ones too; most signs' backs are blank, and so are many front
 * lines. A blank line draws nothing (no glyph, no background: signs have none, and its outline is
 * the outline of no glyphs), so leaving it out changes nothing on screen.
 *
 * <p>Render thread only.
 */
public final class SignLines {

    /** Refuses the first character: {@code accept} then reports that a text had one. */
    private static final FormattedCharSink ANY = (position, style, codepoint) -> false;

    private static volatile boolean enabled = true;

    private SignLines() {}

    /** Whether blank lines are left out (the page's {@code ?signs=game} turns it off, to compare). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** Hook ({@code AbstractSignRenderer.submitSignText}): whether {@code line} can be left out. */
    @SuppressWarnings("ReferenceEquality") // The game's shared empty sequence.
    public static boolean skip(FormattedCharSequence line) {
        return enabled && (line == FormattedCharSequence.EMPTY || line.accept(ANY));
    }
}
