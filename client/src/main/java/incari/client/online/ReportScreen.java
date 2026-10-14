package incari.client.online;

import incari.client.ui.Theme;
import incari.client.ui.Ui;
import incari.framework.online.IncariPlayer;
import incari.online.OnlineService;
import incari.online.OnlineService.ReportReason;
import incari.online.Result;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Report a player (ADR 0058): a reason from the service's fixed list and an optional note. The
 * service keeps the report with the reported account's recent messages to the player.
 */
final class ReportScreen extends FormScreen {

    private static final int MAX_NOTE = 500;

    /** Sends the report: by the player's id in this world, or by an Incari username. */
    private interface Send {
        void send(OnlineService online, ReportReason reason, String note, Consumer<Result<Void>> done);
    }

    private final String who;
    private final Send send;
    private ReportReason reason = ReportReason.HARASSMENT;
    private @Nullable EditBox note;
    private boolean sent;

    private ReportScreen(Screen parent, String who, Send send) {
        super("Report " + who, parent);
        this.who = who;
        this.send = send;
    }

    /** Reports the Incari player called {@code name} in this world. */
    static ReportScreen player(Screen parent, String name, IncariPlayer player) {
        return new ReportScreen(
                parent, name, (online, reason, note, done) -> online.reportPlayer(player, reason, note, done));
    }

    /** Reports the account with this Incari username. */
    static ReportScreen username(Screen parent, String username) {
        return new ReportScreen(
                parent, username, (online, reason, note, done) -> online.reportUsername(username, reason, note, done));
    }

    @Override
    protected void build() {
        if (sent) {
            text("Report about " + who + " sent. Block them to stop hearing from them.", Theme.POSITIVE);
            button(CommonComponents.GUI_DONE.getString(), this::onClose);
            onSubmit(this::onClose);
            return;
        }
        centred("What's wrong?", Theme.MUTED);
        widget(Ui.choice(
                Component.literal("Reason"),
                List.of(ReportReason.values()),
                reason,
                each -> Component.literal(each.label()),
                CONTENT_WIDTH,
                chosen -> reason = chosen));
        note = field("More about it (optional)", MAX_NOTE);
        statusLine();
        row(
                new String[] {CommonComponents.GUI_CANCEL.getString(), "Send Report"},
                new Runnable[] {this::onClose, this::report});
        onSubmit(this::report);
    }

    private void report() {
        EditBox box = note;
        if (box == null || busy()) {
            return;
        }
        sending("Sending...");
        send.send(online, reason, box.getValue(), result -> {
            if (answered(result)) {
                sent = true;
                rebuildWidgets();
            }
        });
    }
}
