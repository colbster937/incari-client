package incari.client.datafix;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.SharedConstants;
import net.minecraft.util.datafix.DataFixers;

/**
 * Whether the game builds its data fixer at start or when it's first needed.
 *
 * <p>The game builds every schema since 2013's at start-up (the class initializer of
 * {@code DataFixers}), optimizes the world list's types on a thread of their own, and waits for that
 * before its window opens. A browser client almost never needs any of it: its options, servers and
 * worlds were written by this version, and a multiplayer client upgrades nothing. Lazily, the game
 * keeps a stand-in fixer that passes data already at the current version through untouched (what
 * the real fixer does with it) and builds the real one the first time something older arrives: an
 * imported world, an old options file.
 */
public final class DataFixing {

    private static volatile boolean lazy;

    private DataFixing() {}

    /**
     * Builds the data fixer only when it's first needed. Also turns off the game's check, at
     * bootstrap, that every entity and block entity type has a schema: a development check whose
     * answer is discarded, which would build the fixer to ask.
     */
    public static void buildLazily() {
        lazy = true;
        SharedConstants.CHECK_DATA_FIXER_SCHEMA = false;
    }

    /** The fixer {@code Minecraft} keeps: the game's, or the stand-in that builds it on first use. */
    public static DataFixer fixer() {
        return lazy ? LazyDataFixer.INSTANCE : DataFixers.getDataFixer();
    }

    /** {@code Main}'s start-up optimization of {@code types}: none when the fixer may never be built. */
    public static CompletableFuture<?> optimizeAtStart(Set<DSL.TypeReference> types) {
        return lazy ? CompletableFuture.completedFuture(null) : DataFixers.optimize(types);
    }
}
