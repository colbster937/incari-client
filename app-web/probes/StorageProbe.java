import incari.platform.web.storage.WebFileSystem;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * Regression probe for platform-web's file system (ADR 0011) under TeaVM's own java.io and
 * java.nio.file: installs it, as WebMain does, and uses files the ways the game does (directories
 * made with Files.createDirectories, the server list's temporary file and rename, a channel opened
 * with CREATE, RandomAccessFile keeping its bytes, listing and walking), then flushes to the
 * storage worker. Run it twice: the second run also finds what the first stored. Prints
 * "probe passed" when everything reads back as written.
 */
public final class StorageProbe {

    private StorageProbe() {}

    public static void main(String[] args) throws IOException {
        WebFileSystem files = WebFileSystem.install(List.of("/incari/dropped"));
        StringBuilder problems = new StringBuilder();
        Path game = Path.of("/incari/game");

        Path marker = game.resolve("probe-runs.txt");
        int runs = Files.exists(marker) ? Integer.parseInt(Files.readString(marker).trim()) : 0;
        System.out.println("[probe] earlier runs stored: " + runs + " (backend " + files.backend() + ")");

        Path downloads = game.resolve("downloads");
        Files.createDirectories(downloads);
        if (!Files.isDirectory(downloads) || Files.exists(Path.of("/incari/game/incari"))) {
            problems.append(" createDirectories");
        }
        try (FileChannel log = FileChannel.open(
                downloads.resolve("log.json"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            log.write(ByteBuffer.wrap("{}\n".getBytes(StandardCharsets.UTF_8)));
        }

        // ServerList.save: a temporary file, then Util.safeReplaceFile's renames.
        Path temporary = Files.createTempFile(game, "servers", ".dat");
        Files.write(temporary, new byte[] {0x0A, (byte) 0xC8, (byte) 0xFF, 0});
        Path servers = game.resolve("servers.dat");
        if (Files.exists(servers)) {
            Files.move(servers, game.resolve("servers.dat_old"), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(temporary, servers, StandardCopyOption.REPLACE_EXISTING);
        try (InputStream in = Files.newInputStream(servers);
                DataInputStream data = new DataInputStream(in)) {
            if (data.readUnsignedByte() != 0x0A || data.readUnsignedShort() != 0xC8FF) {
                problems.append(" servers.dat");
            }
        }

        File region = game.resolve("r.0.0.mca").toFile();
        // TeaVM's RandomAccessFile doesn't create a missing file in "rw" mode, as the JDK's does;
        // the game opens its region files through FileChannel, which does (web-classlib).
        region.createNewFile();
        try (RandomAccessFile raf = new RandomAccessFile(region, "rw")) {
            raf.write("0123456789".getBytes(StandardCharsets.US_ASCII));
        }
        try (RandomAccessFile raf = new RandomAccessFile(region, "rw")) {
            raf.seek(4);
            raf.write('x');
        }
        if (!Files.readString(region.toPath()).equals("0123x56789")) {
            problems.append(" random access: ").append(Files.readString(region.toPath()));
        }

        Files.writeString(game.resolve("options.txt"), "fov:1.0\n");
        try (Stream<Path> walk = Files.walk(game)) {
            long count = walk.count();
            if (count < 6) {
                problems.append(" walk ").append(count);
            }
        }
        if (!new File("/incari/classpath").exists() && new File("/tmp").isDirectory()) {
            // Nothing mounted in a probe; /tmp is there.
        } else if (!new File("/tmp").isDirectory()) {
            problems.append(" /tmp");
        }
        Files.writeString(marker, Integer.toString(runs + 1));
        files.flush();
        System.out.println(problems.isEmpty() ? "probe passed" : "probe failed:" + problems);
    }
}
