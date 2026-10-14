package incari.online;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The vectors shared with the backend ({@code vectors.json}, made with Python's hashlib): server
 * hashes, proof of work and recovery codes must come out the same on both sides.
 */
class VectorsTest {

    private static JsonObject vectors() throws IOException {
        try (InputStream in = Objects.requireNonNull(VectorsTest.class.getResourceAsStream("vectors.json"))) {
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    @Test
    void serverHashes() throws IOException {
        JsonObject vectors = vectors();
        assertThat(vectors.get("serverHashPrefix").getAsString()).isEqualTo(ServerHash.PREFIX);
        for (JsonElement each : vectors.getAsJsonArray("serverHashes")) {
            JsonObject vector = each.getAsJsonObject();
            String address = vector.get("address").getAsString();
            assertThat(ServerHash.normalize(address))
                    .as(address)
                    .isEqualTo(vector.get("normalized").getAsString());
            assertThat(ServerHash.of(address))
                    .as(address)
                    .isEqualTo(vector.get("hash").getAsString());
        }
    }

    @Test
    void proofOfWork() throws IOException {
        for (JsonElement each : vectors().getAsJsonArray("proofOfWork")) {
            JsonObject vector = each.getAsJsonObject();
            ProofOfWork work = new ProofOfWork(
                    ProofOfWork.base64Url(vector.get("challenge").getAsString()),
                    vector.get("bits").getAsInt());
            while (!work.step(1000)) {
                // On to the answer.
            }
            // The first nonce that passes, as the vectors' maker searched from 0 too.
            assertThat(work.nonce()).isEqualTo(vector.get("nonce").getAsLong());
        }
    }

    @Test
    void recoveryCodes() throws IOException {
        JsonObject codes = vectors().getAsJsonObject("recoveryCodes");
        for (JsonElement each : codes.getAsJsonArray("valid")) {
            String typed = each.getAsJsonArray().get(0).getAsString();
            assertThat(RecoveryCodes.normalize(typed))
                    .as(typed)
                    .isEqualTo(each.getAsJsonArray().get(1).getAsString());
        }
        for (JsonElement each : codes.getAsJsonArray("invalid")) {
            assertThat(RecoveryCodes.normalize(each.getAsString()))
                    .as(each.getAsString())
                    .isNull();
        }
    }

    @Test
    void sha256AgreesWithTheJdk() throws NoSuchAlgorithmException {
        MessageDigest jdk = MessageDigest.getInstance("SHA-256");
        for (int length = 0; length < 200; length++) {
            byte[] message = new byte[length];
            for (int i = 0; i < length; i++) {
                message[i] = (byte) (i * 31 + length);
            }
            assertThat(Sha256.hash(message)).as("length %d", length).isEqualTo(jdk.digest(message));
        }
    }

    @Test
    void base64UrlReadsWithAndWithoutPadding() {
        assertThat(ProofOfWork.base64Url("AAECAwQFBgcICQoLDA0ODw"))
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
        assertThat(ProofOfWork.base64Url("AAECAwQFBgcICQoLDA0ODw==")).hasSize(16);
        assertThat(ProofOfWork.base64Url("-_8")).containsExactly(0xFB, 0xFF);
    }
}
