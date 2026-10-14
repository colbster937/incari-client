package incari.app.web;

import incari.render.webgl2.shaders.ShaderManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The game's shaders as the build translated them for WebGL2 ({@code tools/shaders}). They're
 * translated Mojang files, so like the client jar they're kept beside the page, never built into
 * it: in the asset pack ({@link AssetPack}), or as the manifest {@code :app-web:shaders} wrote, which
 * {@code :app-web:serve} offers at {@value #URL}.
 */
final class WebShaders {

    static final String URL = "shaders.json";

    private WebShaders() {}

    /**
     * The translated shaders: from the asset pack if one is mounted, else fetched from beside
     * the page, as an experiment's build without a pack has them.
     */
    static ShaderManifest load(boolean fromPack) throws IOException {
        double start = Fetch.now();
        byte[] bytes = fromPack ? AssetPack.read(URL) : null;
        if (bytes == null) {
            bytes = Fetch.bytes(URL, "is the development server serving the shaders? run ./gradlew :app-web:shaders");
        }
        ShaderManifest manifest = ShaderManifest.parse(new String(bytes, StandardCharsets.UTF_8));
        System.out.println("[Incari] Shaders: " + manifest.stages().size() + " translated stages loaded "
                + (fromPack ? "from the asset pack " : "") + "in " + Math.round(Fetch.now() - start) + " ms");
        return manifest;
    }
}
