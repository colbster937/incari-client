package incari.tools.build;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

/**
 * Runs Vineflower over the client jar into {@code work/decompiled/<version>}.
 *
 * <p>The output is only as stable as its inputs: the same jar, the same Vineflower version
 * and the same options must give byte-identical sources on every machine, or patches stop
 * applying. All three go into a stamp file, and a matching stamp skips the (multi-minute)
 * decompile.
 */
public final class Decompiler {

    private static final String MAIN_CLASS = "org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler";
    private static final String STAMP_FILE = ".incari-decompile-stamp";

    /**
     * Changing any of these changes every decompiled file, so every patch must be
     * regenerated. Treat an edit here like a Minecraft version bump.
     */
    private static final List<String> OPTIONS = List.of(
            "--folder",
            // Only classes: assets and data stay in the jar and never enter the git tree.
            "--skip-extra-files",
            // Mojang's own style, so the tree reads like the game's sources.
            "--indent-string=    ",
            "--log-level=warn");

    private Decompiler() {}

    public static void decompile(Workspace ws, GameFiles game) throws IOException, InterruptedException {
        Path output = ws.decompiledDir(game.version().id());
        Path vineflowerJar = vineflowerJar();
        String stamp = stamp(game, vineflowerJar);

        Path stampFile = output.resolve(STAMP_FILE);
        if (Files.isRegularFile(stampFile) && Files.readString(stampFile).equals(stamp)) {
            Log.step("Decompiled sources for " + game.version().id() + " are up to date");
            return;
        }

        Log.step("Decompiling " + game.version().id() + " with Vineflower (takes a few minutes)");
        Path temp = output.resolveSibling(output.getFileName() + ".tmp");
        FileTrees.deleteRecursively(temp);
        Files.createDirectories(temp);

        List<String> command = new ArrayList<>();
        command.add(ProcessRunner.javaExecutable());
        command.add(System.getenv().getOrDefault("INCARI_DECOMPILE_XMX", "-Xmx8g"));
        command.add("-cp");
        command.add(vineflowerJar.toString());
        command.add(MAIN_CLASS);
        command.addAll(OPTIONS);
        for (GameFiles.LibraryJar library : game.libraries()) {
            // Libraries let Vineflower resolve generics and overloads it would otherwise
            // guess at, which means fewer casts and fewer decompile errors to patch around.
            command.add("-e=" + library.jar());
        }
        command.add(game.clientJar().toString());
        command.add(temp.toString());
        ProcessRunner.run(command, ws.repoRoot(), true);

        Files.writeString(temp.resolve(STAMP_FILE), stamp);
        FileTrees.deleteRecursively(output);
        Files.move(temp, output);
    }

    private static String stamp(GameFiles game, Path vineflowerJar) throws IOException {
        String vineflowerVersion;
        try (JarFile jar = new JarFile(vineflowerJar.toFile())) {
            vineflowerVersion = jar.getManifest().getMainAttributes().getValue("Implementation-Version");
        }
        return "client=" + Downloader.sha1(game.clientJar()) + "\n"
                + "vineflower=" + vineflowerVersion + "\n"
                + "options=" + String.join(" ", OPTIONS) + "\n"
                + "libraries="
                + game.libraries().stream()
                        .map(l -> l.jar().getFileName().toString())
                        .sorted()
                        .toList() + "\n";
    }

    private static Path vineflowerJar() {
        try {
            Class<?> main = Class.forName(MAIN_CLASS, false, Decompiler.class.getClassLoader());
            return Path.of(
                    main.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (ClassNotFoundException | URISyntaxException e) {
            throw new IllegalStateException("Vineflower must be on the CLI's runtime classpath", e);
        }
    }
}
