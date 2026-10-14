package incari.tools.sprites;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.client.ClientBootstrap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResourcesBuilder;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;

/**
 * {@code incari-sprites --out <file>}: decodes the vanilla sprites on this JVM, with the game's
 * resources on the class path (the client jar's, as {@code :client} extracts them), and writes them
 * in the format {@code SpriteCache} reads.
 */
public final class Main {

    /** Fewer sprites than this means something went wrong (26.3's atlases hold about 4,000). */
    private static final int MINIMUM = 1000;

    private Main() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !args[0].equals("--out")) {
            System.err.println("usage: incari-sprites --out <file>");
            System.exit(2);
        }
        Path out = Path.of(args[1]);
        BuildPlatform.install();
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // The client's own registries: the atlases' sprite source types among them.
        ClientBootstrap.bootstrap();
        long start = System.nanoTime();
        List<Sprites.Sprite> sprites;
        try (PackResources vanilla = vanillaPack();
                MultiPackResourceManager resources =
                        new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(vanilla))) {
            sprites = Sprites.decode(resources);
        }
        if (sprites.size() < MINIMUM) {
            // A game version that moved its sprites, or resources missing from the class path:
            // fail rather than ship a file that saves nothing.
            System.err.println("Only " + sprites.size() + " sprites decoded (expected thousands): see the log above");
            System.exit(1);
        }
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(out), 1 << 20)) {
            Sprites.write(sprites, file);
        }
        sprites.forEach(Sprites.Sprite::close);
        System.out.printf(
                "==> %d sprites decoded with their mip levels into %s (%.1f MB) in %d ms%n",
                sprites.size(), out, Files.size(out) / 1e6, (System.nanoTime() - start) / 1_000_000);
    }

    /** The vanilla pack as the game makes it: its resources from the class path, by the vanilla pack's id. */
    static PackResources vanillaPack() {
        return new VanillaPackResourcesBuilder()
                .exposeNamespace("minecraft")
                .pushJarResources()
                .build(new PackLocationInfo(
                        "vanilla",
                        Component.literal("vanilla"),
                        PackSource.BUILT_IN,
                        Optional.of(KnownPack.vanilla("vanilla"))))
                .fullResources();
    }
}
