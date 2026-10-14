package incari.client.online;

import incari.client.ui.Theme;
import incari.online.Credentials;
import incari.online.Failure;
import incari.online.OnlineService;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import org.jspecify.annotations.Nullable;

/**
 * Blocked Players (ADR 0058): the accounts the player blocked, to unblock, and a field to block or
 * report one by its Incari username. Players in the world are blocked and reported from the chat
 * menu (ADR 0059). The service enforces blocks, both ways, and never tells the blocked player.
 */
final class BlockedPlayersScreen extends FormScreen {

    private static final int PAGE = 5;

    private @Nullable List<OnlineService.Block> blocks;
    private int page;
    private boolean loading;
    private @Nullable EditBox username;

    BlockedPlayersScreen(Screen parent) {
        super("Blocked Players", parent);
    }

    @Override
    protected void build() {
        if (blocks == null && !loading) {
            load();
        }
        text(
                "Blocked players can't send you friend requests or messages, and you won't see each "
                        + "other as Incari players. They aren't told.",
                Theme.MUTED);

        username = usernameField("Block by Incari username");
        row(new String[] {"Block", "Report"}, new Runnable[] {this::blockByName, this::reportByName});
        onSubmit(this::blockByName);
        // Laid out again as the list loads: typing stays in the field.
        setInitialFocus(username);

        List<OnlineService.Block> list = blocks;
        if (list == null) {
            centred("Loading...", Theme.MUTED);
        } else if (list.isEmpty()) {
            centred("You haven't blocked anyone.", Theme.MUTED);
        } else {
            int pages = (list.size() + PAGE - 1) / PAGE;
            page = Math.min(page, pages - 1);
            centred("Blocked" + (pages > 1 ? " (" + (page + 1) + " of " + pages + ")" : ""), Theme.TEXT);
            for (OnlineService.Block block : list.subList(page * PAGE, Math.min(list.size(), (page + 1) * PAGE))) {
                item(block.label(), new String[] {"Unblock"}, new Runnable[] {() -> unblock(block)});
            }
            if (pages > 1) {
                row(new String[] {"Previous", "Next"}, new Runnable[] {() -> turn(-1, pages), () -> turn(1, pages)});
            }
        }
        statusLine();
        button(CommonComponents.GUI_DONE.getString(), this::onClose);
    }

    private void turn(int by, int pages) {
        page = Math.floorMod(page + by, pages);
        rebuildWidgets();
    }

    /** Fetches the block list, leaving the status line to whatever asked for it. */
    private void load() {
        loading = true;
        online.blocks(result -> {
            loading = false;
            if (result.ok()) {
                blocks = result.value();
            } else {
                blocks = List.of();
                Failure failure = result.failure();
                problem(failure == null ? "" : failure.message());
            }
            rebuildWidgets();
        });
    }

    private void blockByName() {
        EditBox box = username;
        if (box == null || busy()) {
            return;
        }
        String name = box.getValue();
        if (Credentials.usernameProblem(name) != null) {
            problem("Type an Incari username.");
            return;
        }
        sending("Blocking...");
        online.blockUsername(name, result -> {
            if (answered(result)) {
                box.setValue("");
                load();
                // Said the same whether or not the account exists: the list shows what was blocked.
                status("Done.", Theme.POSITIVE);
            }
        });
    }

    private void reportByName() {
        EditBox box = username;
        if (box == null || Credentials.usernameProblem(box.getValue()) != null) {
            problem("Type the Incari username to report.");
            return;
        }
        show(ReportScreen.username(this, box.getValue()));
    }

    private void unblock(OnlineService.Block block) {
        if (busy()) {
            return;
        }
        sending("Unblocking...");
        online.unblock(block, result -> {
            if (answered(result)) {
                List<OnlineService.Block> list = blocks;
                if (list != null) {
                    List<OnlineService.Block> rest = new ArrayList<>(list);
                    rest.remove(block);
                    blocks = List.copyOf(rest);
                }
                status("Unblocked " + block.label() + ".", Theme.POSITIVE);
                rebuildWidgets();
            }
        });
    }
}
