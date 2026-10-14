package incari.client.world;

import com.mojang.logging.LogUtils;
import incari.client.platform.ClientPlatform;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.util.Util;
import org.slf4j.Logger;

/**
 * The game's "open folder" buttons on a platform that can't show folders (the browser): a world's
 * folder, the backups, crash reports. Each is handed to the player as a zip download, the page's
 * way of letting them see and keep its files. Folders the player adds files to (resource and data
 * packs) keep the file picker the platform opens for them.
 */
public final class FolderDownloads {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Folders the game opens so the player can put files in them, not take files out. */
    private static final List<String> INTAKE_FOLDERS = List.of("resourcepacks", "datapacks");

    /** A world's lock, held open while the world plays; nothing to keep. */
    private static final String SESSION_LOCK = "session.lock";

    private FolderDownloads() {}

    /**
     * Whether {@code path} should be downloaded rather than opened: a folder, on a platform that
     * can't show one, that the player doesn't add files to.
     */
    public static boolean shouldDownload(Path path) {
        if (ClientPlatform.get().shell().showsFolders() || !Files.isDirectory(path)) {
            return false;
        }
        Path name = path.getFileName();
        return name == null || !INTAKE_FOLDERS.contains(name.toString().toLowerCase(Locale.ROOT));
    }

    /**
     * Zips the folder on the background pool (a world takes a while; the render thread mustn't
     * wait) and hands it to the player as {@code <folder>.zip}, its files under the folder's name
     * as a world backup has them.
     */
    public static void download(Path folder) {
        Path name = folder.getFileName();
        String root = name == null ? "files" : name.toString();
        Util.backgroundExecutor().execute(() -> {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                zip(folder, root, bytes);
                ClientPlatform.get().shell().download(root + ".zip", bytes.toByteArray());
            } catch (IOException | UncheckedIOException e) {
                LOGGER.warn("Couldn't zip {} to download it", folder, e);
                Minecraft minecraft = Minecraft.getInstance();
                minecraft.execute(() -> SystemToast.onWorldAccessFailure(minecraft, root));
            }
        });
    }

    /** Writes every file under {@code folder} into a zip, under {@code root}. */
    static void zip(Path folder, String root, OutputStream out) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out);
                Stream<Path> files = Files.walk(folder)) {
            for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                if (!Files.isRegularFile(file) || file.getFileName().toString().equals(SESSION_LOCK)) {
                    continue;
                }
                String entry = root + "/" + folder.relativize(file).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
        }
    }
}
