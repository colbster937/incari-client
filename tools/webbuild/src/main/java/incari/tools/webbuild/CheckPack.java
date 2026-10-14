package incari.tools.webbuild;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import incari.tools.build.CliException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/**
 * A resource pack the browser checks import, made from scratch when the check runs: the
 * repository holds no pack files (the content check rejects game-asset paths, and a pack of our
 * own is a few lines anyway). It renames the title screen's Singleplayer button, so a screenshot
 * shows whether the game loaded it, and has a plain icon.
 */
final class CheckPack {

    /** What the pack calls the title screen's Singleplayer button. */
    static final String BUTTON = "Singleplayer (Incari check pack)";

    private CheckPack() {}

    /** The resource pack format of the game in {@code clientJar}, from its {@code version.json}. */
    static int resourceFormat(Path clientJar) throws IOException {
        try (ZipFile jar = new ZipFile(clientJar.toFile())) {
            ZipEntry version = jar.getEntry("version.json");
            if (version == null) {
                throw new CliException(clientJar + " has no version.json");
            }
            try (InputStream in = jar.getInputStream(version)) {
                JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                return json.getAsJsonObject("pack_version")
                        .get("resource_major")
                        .getAsInt();
            }
        }
    }

    /** Writes the pack, for resource pack format {@code format}, as a zip at {@code zip}. */
    static void write(Path zip, int format) throws IOException {
        Path parent = zip.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String meta = "{\"pack\": {\"description\": \"Incari storage check\", \"min_format\": " + format
                + ", \"max_format\": " + format + "}}\n";
        String language = "{\"menu.singleplayer\": \"" + BUTTON + "\"}\n";
        try (OutputStream out = Files.newOutputStream(zip);
                ZipOutputStream pack = new ZipOutputStream(out)) {
            entry(pack, "pack.mcmeta", meta.getBytes(StandardCharsets.UTF_8));
            entry(pack, "pack.png", icon());
            entry(pack, "assets/minecraft/lang/en_us.json", language.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void entry(ZipOutputStream pack, String name, byte[] data) throws IOException {
        pack.putNextEntry(new ZipEntry(name));
        pack.write(data);
        pack.closeEntry();
    }

    /** A 16x16 icon in two colors, diagonally split. */
    private static byte[] icon() throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, x > y ? 0xFF3B82F6 : 0xFFF59E0B);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        return png.toByteArray();
    }
}
