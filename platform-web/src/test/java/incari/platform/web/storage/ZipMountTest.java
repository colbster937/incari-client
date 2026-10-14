package incari.platform.web.storage;

import static incari.platform.web.storage.TreeFileSystemTest.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.teavm.runtime.fs.VirtualFileAccessor;

/** The client jar mounted in place: its central directory read once, entries inflated on open. */
class ZipMountTest {

    private final TreeFileSystem files =
            new TreeFileSystem(new Journal("/incari", List.of("/incari/classpath")), () -> 5);

    @Test
    void includedEntriesMountReadOnlyAndReadBack() throws IOException {
        byte[] noise = new byte[100_000];
        new Random(1).nextBytes(noise);
        byte[] zip = zip(
                deflated("version.json", bytes("{\"id\":\"26.3\"}")),
                stored("assets/minecraft/lang/en_us.json", bytes("{\"menu.singleplayer\":\"Singleplayer\"}")),
                deflated("assets/minecraft/textures/noise.bin", noise),
                deflated("net/minecraft/client/Minecraft.class", bytes("class")),
                deflated("META-INF/MANIFEST.MF", bytes("Manifest-Version: 1.0")),
                directory("data/"),
                deflated("pack.png", new byte[0]));
        int mounted = files.mount(
                "/incari/classpath",
                ZipArchive.open(source(zip)),
                name -> !name.endsWith(".class") && !name.startsWith("META-INF/"));
        assertThat(mounted).isEqualTo(4);
        assertThat(read("/incari/classpath/version.json")).isEqualTo("{\"id\":\"26.3\"}");
        assertThat(read("/incari/classpath/assets/minecraft/lang/en_us.json")).contains("Singleplayer");
        assertThat(readBytes("/incari/classpath/assets/minecraft/textures/noise.bin"))
                .isEqualTo(noise);
        assertThat(files.getFile("/incari/classpath/assets/minecraft/textures/noise.bin")
                        .length())
                .isEqualTo(noise.length);
        assertThat(read("/incari/classpath/pack.png")).isEmpty();
        assertThat(files.getFile("/incari/classpath/net").exists()).isFalse();
        assertThat(files.getFile("/incari/classpath").listFiles())
                .containsExactly("assets", "pack.png", "version.json");

        // Read-only: no writers, no new files, no deletes, no renames out.
        assertThat(files.getFile("/incari/classpath/version.json").createAccessor(true, true, false))
                .isNull();
        assertThat(files.getFile("/incari/classpath/version.json").canWrite()).isFalse();
        assertThatThrownBy(() -> files.getFile("/incari/classpath/assets").createFile("x"))
                .isInstanceOf(IOException.class);
        assertThat(files.getFile("/incari/classpath/assets").createDirectory("x"))
                .isFalse();
        assertThat(files.getFile("/incari/classpath/version.json").delete()).isFalse();
        files.getFile("/incari").createDirectory("game");
        assertThat(files.getFile("/incari/game").adopt(files.getFile("/incari/classpath/version.json"), "v"))
                .isFalse();
        assertThat(files.hasChanges()).isTrue();
        assertThat(files.collectChanges()).extracting(StoreOp::path).containsExactly("/incari/game");
    }

    @Test
    void theEndRecordIsFoundBehindAComment() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.setComment("x".repeat(1000));
            zip.putNextEntry(new ZipEntry("a.txt"));
            zip.write(bytes("a"));
        }
        ZipArchive archive = ZipArchive.open(source(out.toByteArray()));
        assertThat(archive.entries()).extracting(ZipArchive.Entry::name).containsExactly("a.txt");
        assertThat(archive.read(archive.entries().getFirst())).isEqualTo(bytes("a"));
    }

    @Test
    void somethingElseIsntAZip() {
        assertThatThrownBy(() -> ZipArchive.open(source(bytes("not a zip at all, just text"))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Not a zip");
        byte[] zip = zip(deflated("a.txt", bytes("aaaa")));
        assertThatThrownBy(() -> ZipArchive.open(source(Arrays.copyOf(zip, zip.length - 30))))
                .isInstanceOf(IOException.class);
    }

    @Test
    void compactedEntriesReadAsTheZipsDo() throws IOException {
        byte[] noise = new byte[100_000];
        new Random(2).nextBytes(noise);
        byte[] zip = zip(
                deflated("version.json", bytes("{\"id\":\"26.3\"}")),
                deflated("assets/minecraft/textures/noise.bin", noise),
                stored("data/minecraft/tags/block/logs.json", bytes("{\"values\":[]}")),
                deflated("data/minecraft/noise.bin", noise),
                deflated("net/minecraft/server/Main.class", bytes("class")));
        ZipArchive compacted = ZipArchive.open(source(zip)).compact(name -> !name.startsWith("assets/"));
        // Only what was kept, still compressed: far smaller than the zip with its 100 kB of noise.
        assertThat(compacted.entries())
                .extracting(ZipArchive.Entry::name)
                .containsExactly(
                        "version.json",
                        "data/minecraft/tags/block/logs.json",
                        "data/minecraft/noise.bin",
                        "net/minecraft/server/Main.class");
        int mounted = files.mount("/incari/classpath", compacted, name -> !name.endsWith(".class"));
        assertThat(mounted).isEqualTo(3);
        assertThat(read("/incari/classpath/version.json")).isEqualTo("{\"id\":\"26.3\"}");
        assertThat(read("/incari/classpath/data/minecraft/tags/block/logs.json"))
                .isEqualTo("{\"values\":[]}");
        assertThat(readBytes("/incari/classpath/data/minecraft/noise.bin")).isEqualTo(noise);
        assertThat(files.getFile("/incari/classpath/assets").exists()).isFalse();
    }

    private String read(String path) throws IOException {
        return new String(readBytes(path), StandardCharsets.UTF_8);
    }

    private byte[] readBytes(String path) throws IOException {
        VirtualFileAccessor accessor = files.getFile(path).createAccessor(true, false, false);
        byte[] bytes = new byte[accessor.size()];
        int done = 0;
        while (done < bytes.length) {
            done += accessor.read(bytes, done, bytes.length - done);
        }
        return bytes;
    }

    private static ZipArchive.Source source(byte[] zip) {
        return new ZipArchive.Source() {
            @Override
            public int size() {
                return zip.length;
            }

            @Override
            public byte[] read(int position, int length) {
                return Arrays.copyOfRange(zip, position, position + length);
            }
        };
    }

    @SuppressWarnings("ArrayRecordComponent") // Test data, never compared.
    private record Item(ZipEntry entry, byte[] data) {}

    private static Item deflated(String name, byte[] data) {
        return new Item(new ZipEntry(name), data);
    }

    private static Item stored(String name, byte[] data) {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        entry.setCrc(crc.getValue());
        return new Item(entry, data);
    }

    private static Item directory(String name) {
        return new Item(new ZipEntry(name), new byte[0]);
    }

    private static byte[] zip(Item... items) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Item item : items) {
                zip.putNextEntry(item.entry());
                zip.write(item.data());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }
}
