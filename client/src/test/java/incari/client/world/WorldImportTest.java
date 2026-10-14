package incari.client.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldImportTest {

    @TempDir
    Path temp;

    private Path world(String name) throws IOException {
        Path world = temp.resolve("source").resolve(name);
        Files.createDirectories(world.resolve("region"));
        Files.writeString(world.resolve("level.dat"), "level " + name);
        Files.writeString(world.resolve("session.lock"), "lock");
        Files.write(world.resolve("region").resolve("r.0.0.mca"), new byte[] {1, 2, 3, 4});
        return world;
    }

    @Test
    void aDownloadedWorldComesBackUnderItsOwnName() throws IOException {
        Path world = world("Castle");
        Path zip = temp.resolve("Castle.zip");
        try (OutputStream out = Files.newOutputStream(zip)) {
            FolderDownloads.zip(world, "Castle", out);
        }
        Path saves = temp.resolve("saves");

        assertThat(WorldImport.importOne(zip, saves)).isEqualTo("Castle");
        assertThat(Files.readString(saves.resolve("Castle/level.dat"))).isEqualTo("level Castle");
        assertThat(Files.readAllBytes(saves.resolve("Castle/region/r.0.0.mca"))).containsExactly(1, 2, 3, 4);
        assertThat(saves.resolve("Castle/session.lock")).doesNotExist();
    }

    @Test
    void anImportNeverReplacesAWorld() throws IOException {
        Path world = world("Castle");
        Path zip = temp.resolve("Castle.zip");
        try (OutputStream out = Files.newOutputStream(zip)) {
            FolderDownloads.zip(world, "Castle", out);
        }
        Path saves = temp.resolve("saves");

        String first = WorldImport.importOne(zip, saves);
        String second = WorldImport.importOne(zip, saves);

        assertThat(first).isEqualTo("Castle");
        assertThat(second).isNotEqualTo("Castle").startsWith("Castle");
        assertThat(saves.resolve(second).resolve("level.dat")).exists();
    }

    @Test
    void aZipWithTheWorldAtItsRootIsNamedAfterTheZip() throws IOException {
        Path zip = temp.resolve("Island.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("level.dat"));
            out.write("island".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        assertThat(WorldImport.importOne(zip, temp.resolve("saves"))).isEqualTo("Island");
    }

    @Test
    void aFolderIsCopiedInWithoutItsLock() throws IOException {
        Path saves = temp.resolve("saves");

        assertThat(WorldImport.importOne(world("Farm"), saves)).isEqualTo("Farm");
        assertThat(saves.resolve("Farm/region/r.0.0.mca")).exists();
        assertThat(saves.resolve("Farm/session.lock")).doesNotExist();
    }

    @Test
    void somethingThatIsNoWorldIsTurnedDown() throws IOException {
        Path zip = temp.resolve("pack.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("pack.mcmeta"));
            out.closeEntry();
        }

        assertThat(WorldImport.importOne(zip, temp.resolve("saves"))).isNull();
        assertThat(WorldImport.importOne(temp.resolve("notes.txt"), temp.resolve("saves")))
                .isNull();
    }

    @Test
    void anEntryOutsideTheWorldIsRefused() throws IOException {
        Path zip = temp.resolve("Evil.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("level.dat"));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("../../outside.txt"));
            out.closeEntry();
        }

        assertThatThrownBy(() -> WorldImport.importOne(zip, temp.resolve("saves")))
                .isInstanceOf(IOException.class);
        assertThat(temp.resolve("outside.txt")).doesNotExist();
    }
}
