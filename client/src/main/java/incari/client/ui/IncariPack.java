package incari.client.ui;

import com.mojang.logging.LogUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.flag.FeatureFlagSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Incari's own resources, as a resource pack that's always on, above the game's and any the
 * player picks: the client's font, and whatever else the look needs from the game's resource system.
 * Its files are class-path resources under {@value #ROOT}, listed in {@code files.txt} there (the
 * browser's class path can't be listed); a line {@code <path> = <file>} serves one of Incari's files
 * at another path, which is how the game's default font is replaced without a file under {@code
 * assets/minecraft/} in the repo. Every file in it is Incari's own work or openly licensed
 * (the font's OFL beside it), never the game's.
 */
public final class IncariPack implements RepositorySource {

    /** For {@code PackRepository}'s sources ({@code Minecraft}'s constructor). */
    public static final IncariPack SOURCE = new IncariPack();

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ROOT = "/incari-pack/";
    private static final PackLocationInfo LOCATION =
            new PackLocationInfo("incari", Component.literal("Incari"), PackSource.BUILT_IN, Optional.empty());

    /** The pack's paths, each with the class-path file served for it. */
    private static @Nullable Map<String, String> files;

    private IncariPack() {}

    @Override
    public void loadPacks(Consumer<Pack> result) {
        Pack.Metadata metadata = new Pack.Metadata(
                Component.literal("The client's look"), PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of());
        Pack.ResourcesSupplier resources = new Pack.ResourcesSupplier() {
            @Override
            public Resources openMetadata(PackLocationInfo location) {
                return new Resources();
            }

            @Override
            public Stream<PackResources> openResources(PackLocationInfo location, Pack.Metadata meta) {
                return Stream.of(new Resources());
            }
        };
        result.accept(new Pack(LOCATION, resources, metadata, new PackSelectionConfig(true, Pack.Position.TOP, true)));
    }

    /** The pack's files, by path from the pack's root ({@code assets/...}), to the file served for each. */
    private static synchronized Map<String, String> files() {
        Map<String, String> listed = files;
        if (listed == null) {
            listed = new LinkedHashMap<>();
            try (InputStream in = IncariPack.class.getResourceAsStream(ROOT + "files.txt")) {
                if (in != null) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                        String entry = line.trim();
                        if (entry.isEmpty() || entry.startsWith("#")) {
                            continue;
                        }
                        int equals = entry.indexOf('=');
                        if (equals < 0) {
                            listed.put(entry, entry);
                        } else {
                            listed.put(
                                    entry.substring(0, equals).trim(),
                                    entry.substring(equals + 1).trim());
                        }
                    }
                } else {
                    LOGGER.warn("Incari's pack has no files.txt");
                }
            } catch (IOException e) {
                LOGGER.warn("Couldn't list Incari's pack: {}", e.toString());
            }
            files = listed;
        }
        return listed;
    }

    private static IoSupplier<InputStream> open(String path) {
        return () -> {
            InputStream in = IncariPack.class.getResourceAsStream(ROOT + path);
            if (in == null) {
                throw new IOException("Incari's pack has no " + path);
            }
            return in;
        };
    }

    private static final class Resources implements PackResources {

        @Override
        public @Nullable IoSupplier<InputStream> getRootResource(String... path) {
            return null;
        }

        @Override
        public @Nullable IoSupplier<InputStream> getResource(PackType type, Identifier location) {
            if (type != PackType.CLIENT_RESOURCES) {
                return null;
            }
            String file = files().get("assets/" + location.getNamespace() + "/" + location.getPath());
            return file == null ? null : open(file);
        }

        @Override
        public void listResources(PackType type, String namespace, String directory, ResourceOutput output) {
            if (type != PackType.CLIENT_RESOURCES) {
                return;
            }
            String prefix = "assets/" + namespace + "/";
            String under = prefix + directory + "/";
            for (Map.Entry<String, String> file : files().entrySet()) {
                String path = file.getKey();
                if (path.startsWith(under)) {
                    output.accept(
                            Identifier.fromNamespaceAndPath(namespace, path.substring(prefix.length())),
                            open(file.getValue()));
                }
            }
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            Set<String> namespaces = new LinkedHashSet<>();
            if (type == PackType.CLIENT_RESOURCES) {
                for (String path : files().keySet()) {
                    int end = path.indexOf('/', "assets/".length());
                    if (path.startsWith("assets/") && end > 0) {
                        namespaces.add(path.substring("assets/".length(), end));
                    }
                }
            }
            return namespaces;
        }

        @Override
        public <T> @Nullable T getMetadataSection(MetadataSectionType<T> type) {
            return null;
        }

        @Override
        public PackLocationInfo location() {
            return LOCATION;
        }

        @Override
        public void close() {}
    }
}
