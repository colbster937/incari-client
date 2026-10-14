package incari.client.singleplayer;

import com.mojang.logging.LogUtils;
import incari.client.platform.ClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Singleplayer for the benchmark runner, without its screens: a world created from a fixed seed as
 * soon as the title screen shows (the page's {@code ?world=<name>&seed=<seed>}), and the world left
 * as Save and Quit to Title leaves it when the runner asks ({@code leaveWorld}). Back at the title
 * screen after leaving, it marks {@code leftWorld}, so the runner can time saving. Game thread.
 */
public final class QuickWorlds {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static @Nullable String pendingName;
    /** A shared world to join at the title screen ({@code ?shared=<relay>|<code>}): relay and code. */
    private static String @Nullable [] pendingShared;

    private static long pendingSeed;
    private static boolean leaving;

    private QuickWorlds() {}

    /**
     * Creates the world {@code name} from {@code seed} once the title screen shows: survival,
     * commands allowed, structures, the default world type. A world of that name already there is
     * opened instead.
     */
    public static void createAtTitle(String name, long seed) {
        pendingName = name;
        pendingSeed = seed;
    }

    /** The page's {@code ?shared=<relay>|<code>} (ADR 0062's check): joins that shared world at the title screen. */
    public static void joinSharedAtTitle(String relay, String code) {
        pendingShared = new String[] {relay, code};
    }

    /** Once a client tick, from {@code IncariTicks}. */
    public static void tick(Minecraft minecraft) {
        if (minecraft.level != null && ClientPlatform.get().profiling().takeRequest("leaveWorld")) {
            LOGGER.info("Leaving the world, as asked");
            leaving = true;
            minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
            return;
        }
        boolean atTitle = minecraft.gui.overlay() == null && minecraft.gui.screen() instanceof TitleScreen;
        if (leaving && atTitle) {
            leaving = false;
            ClientPlatform.get().profiling().mark("leftWorld");
        }
        String[] shared = pendingShared;
        if (shared != null && atTitle) {
            pendingShared = null;
            LOGGER.info("Joining the shared world {} on {}", shared[1], shared[0]);
            minecraft.gui.setScreen(
                    new incari.client.relay.RelayConnectScreen(new TitleScreen(), shared[0], shared[1]));
            return;
        }
        String name = pendingName;
        if (name == null || !atTitle) {
            return;
        }
        pendingName = null;
        // As the world list would: the server's worker starts while the page opens the world.
        WorkerWorlds.prepare();
        if (minecraft.getLevelSource().levelExists(name)) {
            LOGGER.info("Opening the world {}", name);
            minecraft.createWorldOpenFlows().openWorld(name, () -> minecraft.gui.setScreen(new TitleScreen()));
            return;
        }
        LOGGER.info("Creating the world {} from seed {}", name, pendingSeed);
        LevelSettings settings = new LevelSettings(
                name,
                GameType.SURVIVAL,
                LevelSettings.DifficultySettings.DEFAULT,
                true,
                WorldDataConfiguration.DEFAULT);
        minecraft
                .createWorldOpenFlows()
                .createFreshLevel(
                        name,
                        settings,
                        new WorldOptions(pendingSeed, true, false),
                        WorldPresets::createNormalWorldDimensions,
                        new TitleScreen());
    }
}
