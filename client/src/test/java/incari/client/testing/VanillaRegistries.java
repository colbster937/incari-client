package incari.client.testing;

import net.minecraft.SharedConstants;
import net.minecraft.commands.Commands;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.Util;
import net.minecraft.world.level.WorldDataConfiguration;
import org.jspecify.annotations.Nullable;

/**
 * The vanilla data pack's registries with their tags, loaded as a server loads a world's: for tests
 * that need the game's own biomes, noise and structures. Loaded once per test run (a few seconds).
 */
public final class VanillaRegistries {

    private static RegistryAccess.@Nullable Frozen loaded;

    private VanillaRegistries() {}

    /** Bootstraps the game (with {@link TestPlatform}) and returns the registries. */
    public static synchronized RegistryAccess.Frozen load() {
        RegistryAccess.Frozen registries = loaded;
        if (registries != null) {
            return registries;
        }
        TestPlatform.install();
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        WorldLoader.InitConfig config = new WorldLoader.InitConfig(
                new WorldLoader.PackConfig(
                        ServerPacksSource.createVanillaTrustedRepository(),
                        WorldDataConfiguration.DEFAULT,
                        false,
                        true),
                Commands.CommandSelection.DEDICATED,
                LevelBasedPermissionSet.OWNER);
        registries = WorldLoader.<RegistryAccess.Frozen, RegistryAccess.Frozen>load(
                        config,
                        context -> new WorldLoader.DataLoadOutput<>(
                                context.datapackDimensions(), context.datapackDimensions()),
                        (resources, managers, layers, cookie) -> layers.compositeAccess(),
                        Util.backgroundExecutor(),
                        Runnable::run)
                .join();
        loaded = registries;
        return registries;
    }
}
