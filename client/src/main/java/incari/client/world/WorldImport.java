package incari.client.world;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FileUtil;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Worlds dropped on the world list come into the saves folder. On the desktop a player copies a
 * world's folder into {@code saves/}; a page has no folder to copy into, so a world arrives as a
 * zip (what Edit World's folder button downloads, a backup, or any zip of a world folder) or as a
 * folder dropped on the window. Each becomes a new world under a name of its own, never over an
 * existing one.
 */
public final class WorldImport {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String LEVEL_DAT = "level.dat";
    private static final String SESSION_LOCK = "session.lock";
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId();

    private WorldImport() {}

    /**
     * Hook ({@code SelectWorldScreen.onFilesDrop}): imports the dropped worlds on the background
     * pool, then refreshes the list and says what came in.
     */
    public static void dropped(Minecraft minecraft, @Nullable WorldSelectionList list, List<Path> files) {
        Path saves = minecraft.getLevelSource().getBaseDir();
        Util.backgroundExecutor().execute(() -> {
            List<String> imported = new ArrayList<>();
            int failed = 0;
            for (Path file : files) {
                try {
                    String name = importOne(file, saves);
                    if (name != null) {
                        imported.add(name);
                    } else {
                        failed++;
                    }
                } catch (IOException | UncheckedIOException | IllegalArgumentException e) {
                    LOGGER.warn("Couldn't import a world from {}", file, e);
                    failed++;
                }
            }
            int notWorlds = failed;
            minecraft.execute(() -> {
                if (list != null) {
                    list.reloadWorldList();
                }
                if (!imported.isEmpty()) {
                    SystemToast.addOrUpdate(
                            minecraft.gui.toastManager(),
                            TOAST,
                            Component.literal("World imported"),
                            Component.literal(String.join(", ", imported)));
                }
                if (notWorlds > 0) {
                    SystemToast.onFileDropFailure(minecraft, notWorlds);
                }
            });
        });
    }

    /** Imports one dropped file; the new world's folder name, or null if it held no world. */
    static @Nullable String importOne(Path file, Path saves) throws IOException {
        if (Files.isDirectory(file)) {
            Path root = worldRoot(file);
            if (root == null) {
                return null;
            }
            Path target = newWorldFolder(
                    saves,
                    root.getFileName() == null ? "World" : root.getFileName().toString());
            copyFolder(root, target);
            return target.getFileName().toString();
        }
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return null;
        }
        String prefix = zipWorldPrefix(file);
        if (prefix == null) {
            return null;
        }
        String baseName = prefix.isEmpty()
                ? fileName.substring(0, fileName.length() - ".zip".length())
                : prefix.substring(prefix.lastIndexOf('/', prefix.length() - 2) + 1, prefix.length() - 1);
        Path target = newWorldFolder(saves, baseName);
        extract(file, prefix, target);
        return target.getFileName().toString();
    }

    /**
     * The path inside the zip of the shallowest folder with a {@code level.dat} ("" for the zip's
     * root, else ending in "/"), or null if it holds no world.
     */
    private static @Nullable String zipWorldPrefix(Path zip) throws IOException {
        String best = null;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !(name.equals(LEVEL_DAT) || name.endsWith("/" + LEVEL_DAT))) {
                    continue;
                }
                String prefix = name.substring(0, name.length() - LEVEL_DAT.length());
                if (best == null || prefix.length() < best.length()) {
                    best = prefix;
                }
            }
        }
        return best;
    }

    /** Writes the zip's entries under {@code prefix} into {@code target}, refusing any outside it. */
    private static void extract(Path zip, String prefix, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                String name = entry.getName().replace('\\', '/');
                if (!name.startsWith(prefix) || name.length() == prefix.length()) {
                    continue;
                }
                Path out = root.resolve(name.substring(prefix.length())).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("A zip entry points outside its world: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else if (!out.getFileName().toString().equals(SESSION_LOCK)) {
                    Path parent = out.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.write(out, in.readAllBytes());
                }
            }
        }
    }

    /** The shallowest folder under {@code folder} (itself included) with a {@code level.dat}. */
    private static @Nullable Path worldRoot(Path folder) throws IOException {
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(path -> path.getFileName() != null
                            && path.getFileName().toString().equals(LEVEL_DAT)
                            && Files.isRegularFile(path))
                    .map(Path::getParent)
                    .min((a, b) -> Integer.compare(a.getNameCount(), b.getNameCount()))
                    .orElse(null);
        }
    }

    private static void copyFolder(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Path out = to.resolve(from.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(out);
                } else if (!file.getFileName().toString().equals(SESSION_LOCK)) {
                    try (InputStream in = Files.newInputStream(file)) {
                        Files.write(out, in.readAllBytes());
                    }
                }
            }
        }
    }

    /** A new, empty folder in {@code saves} named after {@code name}, as world creation names one. */
    private static Path newWorldFolder(Path saves, String name) throws IOException {
        Files.createDirectories(saves);
        String folder = FileUtil.findAvailableName(saves, name.isBlank() ? "World" : name, "");
        Path target = saves.resolve(folder);
        Files.createDirectories(target);
        return target;
    }
}
