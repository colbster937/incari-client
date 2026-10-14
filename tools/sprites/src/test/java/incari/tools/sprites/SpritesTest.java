package incari.tools.sprites;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojang.blaze3d.platform.NativeImage;
import incari.client.render.SpriteCache;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.client.ClientBootstrap;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.Resource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The vanilla sprites, decoded here and read back through the page's {@link SpriteCache}: the
 * same pixels and mip levels, for the vanilla pack's resources only.
 */
class SpritesTest {

    private static PackResources vanilla;
    private static MultiPackResourceManager resources;
    private static List<Sprites.Sprite> sprites;
    private static Map<String, Sprites.Sprite> byName;

    @TempDir
    static Path directory;

    @BeforeAll
    static void decode() throws IOException {
        BuildPlatform.install();
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ClientBootstrap.bootstrap();
        vanilla = Main.vanillaPack();
        resources = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(vanilla));
        sprites = Sprites.decode(resources);
        byName = sprites.stream().collect(Collectors.toMap(Sprites.Sprite::name, Function.identity()));
        Path file = directory.resolve("sprites.bin");
        try (OutputStream out = Files.newOutputStream(file)) {
            Sprites.write(sprites, out);
        }
        SpriteCache.install(file);
    }

    @AfterAll
    static void close() {
        sprites.forEach(Sprites.Sprite::close);
        resources.close();
        vanilla.close();
    }

    @Test
    void blockSpritesHaveEveryMipLevelAndOthersNone() {
        assertThat(sprites).hasSizeGreaterThan(3000);
        assertThat(byName.get("minecraft:block/stone").levels()).hasSize(5);
        assertThat(byName.get("minecraft:item/apple").levels()).hasSize(1);
        // Animated sprites are left to the game.
        assertThat(byName).doesNotContainKey("minecraft:block/water_still");
    }

    @Test
    void theCacheGivesBackTheSamePixels() throws IOException {
        for (String name :
                List.of("minecraft:block/stone", "minecraft:block/oak_leaves", "minecraft:block/short_grass")) {
            Identifier location = Identifier.parse(name);
            Resource resource = resources.getResourceOrThrow(location.withPath(path -> "textures/" + path + ".png"));
            NativeImage image = SpriteCache.image(location, resource);
            assertThat(image).as(name).isNotNull();
            List<NativeImage> expected = byName.get(name).levels();
            assertThat(bytes(image)).as(name).isEqualTo(bytes(expected.getFirst()));
            NativeImage[] levels = SpriteCache.mipLevels(image, 4);
            assertThat(levels).as(name).hasSize(5);
            for (int level = 0; level < levels.length; level++) {
                assertThat(bytes(levels[level])).as(name + " level " + level).isEqualTo(bytes(expected.get(level)));
            }
        }
    }

    @Test
    void fewerLevelsAreTheFirstOnes() throws IOException {
        Identifier location = Identifier.parse("minecraft:block/dirt");
        Resource resource = resources.getResourceOrThrow(Identifier.parse("minecraft:textures/block/dirt.png"));
        NativeImage image = SpriteCache.image(location, resource);
        assertThat(image).isNotNull();
        NativeImage[] levels = SpriteCache.mipLevels(image, 2);
        assertThat(levels).hasSize(3);
        assertThat(bytes(levels[2]))
                .isEqualTo(bytes(byName.get("minecraft:block/dirt").levels().get(2)));
    }

    @Test
    void onlyTheVanillaPacksResourcesComeFromTheCache() throws IOException {
        Resource vanillaResource = resources.getResourceOrThrow(Identifier.parse("minecraft:textures/block/sand.png"));
        Resource fromAPack = new Resource(
                new net.minecraft.server.packs.PathPackResources(
                        new net.minecraft.server.packs.PackLocationInfo(
                                "some_pack",
                                net.minecraft.network.chat.Component.literal("some pack"),
                                net.minecraft.server.packs.repository.PackSource.DEFAULT,
                                java.util.Optional.empty()),
                        directory),
                vanillaResource::open);
        assertThat(SpriteCache.image(Identifier.parse("minecraft:block/sand"), fromAPack))
                .isNull();
    }

    private static byte[] bytes(NativeImage image) {
        ByteBuffer pixels = image.getPixelBytes();
        byte[] out = new byte[pixels.remaining()];
        pixels.get(out);
        return out;
    }
}
