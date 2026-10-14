package incari.tools.build;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Downloads files and checks their SHA-1. A file already on disk with the right hash is
 * reused, so re-running {@code incari fetch} costs only the hash checks.
 *
 * <p>Writes go to a temp file first and are moved into place only after the hash matches,
 * so an interrupted download never leaves a truncated jar that later steps trust.
 */
public final class Downloader {

    private static final int ATTEMPTS = 3;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /** Downloads {@code url} to {@code target} unless it's already there with {@code sha1}. */
    public void fetch(URI url, String sha1, Path target) throws IOException, InterruptedException {
        if (Files.isRegularFile(target) && sha1(target).equals(sha1)) {
            return;
        }
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        IOException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                download(url, temp);
                String actual = sha1(temp);
                if (!actual.equals(sha1)) {
                    throw new IOException("SHA-1 mismatch for " + url + ": expected " + sha1 + ", got " + actual);
                }
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException e) {
                last = e;
            } finally {
                Files.deleteIfExists(temp);
            }
        }
        throw new CliException("Download failed after " + ATTEMPTS + " attempts: " + url, last);
    }

    /** Downloads without a known hash (only the version manifest, which changes over time). */
    public String fetchString(URI url) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request(url), HttpResponse.BodyHandlers.ofString());
        checkStatus(url, response.statusCode());
        return response.body();
    }

    private void download(URI url, Path temp) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = http.send(request(url), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            checkStatus(url, response.statusCode());
            Files.copy(body, temp, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static HttpRequest request(URI url) {
        return HttpRequest.newBuilder(url).timeout(Duration.ofMinutes(5)).GET().build();
    }

    private static void checkStatus(URI url, int status) throws IOException {
        if (status != 200) {
            throw new IOException("HTTP " + status + " from " + url);
        }
    }

    public static String sha1(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JDK ships SHA-1", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
