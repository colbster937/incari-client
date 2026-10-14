package incari.tools.webbuild;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ServeTest {

    @Test
    void mountedDirectoriesServeOnlyWhatsInside() {
        Map<String, Path> files = new LinkedHashMap<>();
        Serve.addFile(files, "client.jar=/cache/client.jar");
        Serve.addDirectory(files, "assets/objects=/cache/assets/objects");

        assertThat(Serve.mounted(files, "/assets/objects/ab/abcdef"))
                .isEqualTo(Path.of("/cache/assets/objects/ab/abcdef"));
        assertThat(Serve.mounted(files, "/assets/objects/../../secret")).isNull();
        assertThat(Serve.mounted(files, "/assets/other")).isNull();
        // Named files aren't directories.
        assertThat(Serve.mounted(files, "/client.jar/x")).isNull();
    }
}
