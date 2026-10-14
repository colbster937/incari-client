package incari.tools.webbuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import incari.tools.build.CliException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WasmOptTest {

    @TempDir
    Path dir;

    @Test
    void extractsOneFileFromAGzippedTar() throws IOException {
        Path archive = dir.resolve("binaryen.tar.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(archive))) {
            entry(out, "binaryen/bin/", '5', new byte[0]);
            entry(out, "binaryen/bin/wasm-dis", '0', bytes(700, 'd'));
            entry(out, "binaryen/bin/wasm-opt", '0', bytes(1000, 'o'));
            out.write(new byte[1024]);
        }
        Path target = dir.resolve("wasm-opt");

        WasmOpt.extract(archive, "binaryen/bin/wasm-opt", target);

        assertThat(Files.readAllBytes(target)).isEqualTo(bytes(1000, 'o'));
    }

    @Test
    void anEntryTheArchiveLacksFails() throws IOException {
        Path archive = dir.resolve("empty.tar.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(archive))) {
            entry(out, "binaryen/bin/wasm-dis", '0', bytes(10, 'd'));
            out.write(new byte[1024]);
        }

        assertThatThrownBy(() -> WasmOpt.extract(archive, "binaryen/bin/wasm-opt", dir.resolve("wasm-opt")))
                .isInstanceOf(CliException.class);
    }

    private static byte[] bytes(int count, char value) {
        byte[] data = new byte[count];
        java.util.Arrays.fill(data, (byte) value);
        return data;
    }

    /** One ustar entry: a 512-byte header (name, octal size, type flag), the data padded to 512. */
    private static void entry(OutputStream out, String name, char type, byte[] data) throws IOException {
        byte[] header = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(nameBytes, 0, header, 0, nameBytes.length);
        byte[] size = String.format("%011o", data.length).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(size, 0, header, 124, size.length);
        header[156] = (byte) type;
        byte[] magic = "ustar".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, header, 257, magic.length);
        out.write(header);
        ByteArrayOutputStream padded = new ByteArrayOutputStream();
        padded.write(data);
        padded.write(new byte[(512 - data.length % 512) % 512]);
        out.write(padded.toByteArray());
    }
}
