package incari.client.online;

import incari.client.ui.Icons;
import incari.client.ui.RoundRects;
import incari.client.ui.Theme;
import incari.online.Social;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;

/**
 * A message or a friend request that arrived while the chat menu was closed (ADR 0059): who, and
 * the start of what they said, on a glass card in the corner for five seconds. One card a sender
 * (and kind): more from them while it's up or waiting to show update it, with a count, instead of
 * queueing a card each.
 */
final class ChatToast implements Toast {

    private static final long SHOWN_MILLIS = 5000;

    private Social.Notice notice;
    private final String token;
    /** How many notices the card stands for. */
    private int count = 1;
    /** Whether a notice joined since the last update: the five seconds start again. */
    private boolean changed;
    /** The card's visible time when its five seconds last started. */
    private long shownFrom;

    private Visibility visibility = Visibility.SHOW;

    ChatToast(Social.Notice notice) {
        this.notice = notice;
        this.token = token(notice);
    }

    /** What tells one sender's card from another's: who, and whether it's about friends or a message. */
    static String token(Social.Notice notice) {
        return (notice.request() ? "request:" : "message:") + notice.who();
    }

    /** One more from the same sender: the card shows the newest and counts them. */
    void merge(Social.Notice next) {
        notice = next;
        count++;
        changed = true;
    }

    @Override
    public Object getToken() {
        return token;
    }

    @Override
    public Visibility getWantedVisibility() {
        return visibility;
    }

    @Override
    public void update(ToastManager manager, long fullyVisibleForMs) {
        if (changed) {
            changed = false;
            shownFrom = fullyVisibleForMs;
        }
        visibility = fullyVisibleForMs - shownFrom >= SHOWN_MILLIS * manager.getNotificationDisplayTimeMultiplier()
                ? Visibility.HIDE
                : Visibility.SHOW;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long fullyVisibleForMs) {
        RoundRects.box(
                graphics, 0, 0, width(), height(), Theme.PANEL_RADIUS, Theme.FLOATING, Theme.GLASS_EDGE, 0, 0, 0);
        Avatars.draw(graphics, font, 8, 8, 16, notice.who(), null);
        (notice.request() ? Icons.PERSON_ADD : Icons.CHAT).draw(graphics, width() - 16, 7, 9, Theme.ACCENT);
        String suffix = count > 1 ? " (" + count + ")" : "";
        int titleWidth = width() - 52 - font.width(suffix);
        graphics.text(font, font.plainSubstrByWidth(notice.title(), titleWidth) + suffix, 31, 7, Theme.TEXT, false);
        graphics.text(font, font.plainSubstrByWidth(notice.text(), width() - 38), 31, 18, Theme.MUTED, false);
    }
}
