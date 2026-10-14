package incari.tools.webbuild;

import incari.tools.build.CliException;
import incari.tools.build.Downloader;
import incari.tools.build.Log;
import incari.tools.build.Workspace;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Binaryen's {@code wasm-opt} over a release module (ADR 0034), with {@code --wasm-opt}.
 *
 * <p>The release of Binaryen is pinned (its tarball's SHA-1, taken from a copy checked against the
 * SHA-256 Binaryen publishes), fetched once into {@code work/cache/binaryen/}, and only for Linux
 * on x86-64 so far. The flags are the ones the floor stand-in was measured with (ADR 0043):
 *
 * <ul>
 *   <li>{@code --closed-world}: nothing outside the module calls into it but its exports, which lets
 *       Binaryen change the program's types: among other things it drops the vtable slots nothing
 *       calls, which every copy of the game (the page and each worker) holds. tools/teavm-patches'
 *       {@code WasmGCJsoCommonGenerator} exports every function handed to JavaScript, so that holds.
 *   <li>{@code -O3}.
 *   <li>No inlining ({@code -aimfs 0 -fimfs 0 -ocimfs 0 -pii 0}). With Binaryen's inlining the
 *       fly-through's slowest frames got slower (their tick 3.5 to 11 ms on the floor stand-in) and,
 *       uncapped, V8's optimizing compiler ran out of memory on the functions it grew. TeaVM has
 *       inlined already; without Binaryen's the module is 4.6 MB smaller.
 * </ul>
 *
 * <p>Under a minute on the development desktop for the Fast build; the Compatible
 * build's took a quarter of an hour and 10 GB with ADR 0034's flags.
 */
final class WasmOpt {

    static final String VERSION = "133";

    private static final String SHA1_LINUX_X86_64 = "7fa00016442b4579141a0c7c5b47eb5b9a7d4338";

    /** The features TeaVM's WebAssembly GC module uses; Binaryen reads nothing else in. */
    private static final List<String> FEATURES = List.of(
            "--enable-gc",
            "--enable-reference-types",
            "--enable-exception-handling",
            "--enable-bulk-memory",
            "--enable-nontrapping-float-to-int",
            "--enable-sign-ext",
            "--enable-mutable-globals",
            "--enable-multivalue");

    static final List<String> PASSES =
            List.of("--closed-world", "-O3", "-aimfs", "0", "-fimfs", "0", "-ocimfs", "0", "-pii", "0");

    private WasmOpt() {}

    /** Optimizes {@code module} in place. */
    static void optimize(Workspace ws, Path module) throws IOException, InterruptedException {
        Path tool = tool(ws);
        Path optimized = module.resolveSibling(module.getFileName() + ".opt");
        List<String> command = new ArrayList<>();
        command.add(tool.toString());
        command.add(module.toString());
        command.addAll(FEATURES);
        command.addAll(PASSES);
        command.add("-o");
        command.add(optimized.toString());
        Log.step("Optimizing " + module.getFileName() + " with Binaryen " + VERSION + " (" + String.join(" ", PASSES)
                + ")");
        long start = System.nanoTime();
        Process process = new ProcessBuilder(command).inheritIO().start();
        int status = process.waitFor();
        if (status != 0) {
            Files.deleteIfExists(optimized);
            throw new CliException("wasm-opt failed (exit " + status + ")");
        }
        long before = Files.size(module);
        Files.move(optimized, module, StandardCopyOption.REPLACE_EXISTING);
        Log.step(String.format(
                Locale.ROOT,
                "Optimized in %d s: %.1f MB to %.1f MB",
                (System.nanoTime() - start) / 1_000_000_000L,
                before / 1e6,
                Files.size(module) / 1e6));
    }

    /** {@code wasm-opt}, fetched and unpacked into the workspace's cache the first time. */
    private static Path tool(Workspace ws) throws IOException, InterruptedException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        if (!os.contains("linux") || !(arch.equals("amd64") || arch.equals("x86_64"))) {
            throw new CliException("--wasm-opt is only set up for Linux on x86-64 so far (this is " + os + " " + arch
                    + "): pin Binaryen's release for this platform in WasmOpt");
        }
        Path dir = ws.cacheDir().resolve("binaryen").resolve("version_" + VERSION);
        Path tool = dir.resolve("wasm-opt");
        if (Files.isExecutable(tool)) {
            return tool;
        }
        String file = "binaryen-version_" + VERSION + "-x86_64-linux.tar.gz";
        Path archive = dir.resolve(file);
        new Downloader()
                .fetch(
                        URI.create("https://github.com/WebAssembly/binaryen/releases/download/version_" + VERSION + "/"
                                + file),
                        SHA1_LINUX_X86_64,
                        archive);
        extract(archive, "binaryen-version_" + VERSION + "/bin/wasm-opt", tool);
        Files.setPosixFilePermissions(tool, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.delete(archive);
        return tool;
    }

    /** Copies one regular file out of a gzipped tar archive. */
    static void extract(Path archive, String entryName, Path target) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(archive))) {
            byte[] header = new byte[512];
            while (in.readNBytes(header, 0, 512) == 512) {
                if (header[0] == 0) {
                    break;
                }
                String name = field(header, 0, 100);
                String prefix = field(header, 345, 155);
                if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }
                long size = Long.parseLong(field(header, 124, 12).trim(), 8);
                long padded = (size + 511) / 512 * 512;
                if (name.equals(entryName) && (header[156] == '0' || header[156] == 0)) {
                    Path temp = target.resolveSibling(target.getFileName() + ".part");
                    Files.write(temp, in.readNBytes((int) size));
                    in.skipNBytes(padded - size);
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
                in.skipNBytes(padded);
            }
        }
        throw new CliException(archive + " has no " + entryName);
    }

    private static String field(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.US_ASCII);
    }
}
