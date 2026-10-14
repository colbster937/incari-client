package incari.client.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * What {@link PacketHandoff} relies on, read from the game's sources (the patched tree in
 * {@code work/client}): every client handler that hands its packet to the game's thread does so as
 * its first statement, to the game's packet processor, except the two {@link PacketHandoff} never
 * notes. A new version that adds a handler doing something first fails here.
 */
class PacketHandoffTest {

    private static final String HANDOFF = "PacketUtils.ensureRunningOnSameThread(";

    /** The handlers that do something before handing off, by their packet type. */
    private static final Set<String> NOT_NOTED = Set.of("ClientboundCustomPayloadPacket", "ClientboundTransferPacket");

    private static final Pattern HANDLER = Pattern.compile(
            "\n    (?:@Override\n    )?public void (handle\\w+)\\(final (\\w+) packet\\) \\{\n(.*?)\n    }\n",
            Pattern.DOTALL);

    @Test
    void everyHandlerThatHandsOffDoesSoFirst() throws IOException {
        Path listeners = Path.of(System.getProperty("incari.repoRoot", "../.."))
                .resolve("work/client/src/main/java/net/minecraft/client/multiplayer");
        List<String> handingOff = new ArrayList<>();
        List<String> wrong = new ArrayList<>();
        try (Stream<Path> files = Files.list(listeners)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher handler = HANDLER.matcher(Files.readString(file));
                while (handler.find()) {
                    String body = handler.group(3);
                    if (!body.contains(HANDOFF)) {
                        continue;
                    }
                    String where = file.getFileName() + " " + handler.group(1);
                    handingOff.add(where);
                    if (NOT_NOTED.contains(handler.group(2))) {
                        continue;
                    }
                    String first = body.strip().lines().findFirst().orElse("").strip();
                    if (!first.equals(HANDOFF + "packet, this, this.minecraft.packetProcessor());")
                            || body.indexOf(HANDOFF) != body.lastIndexOf(HANDOFF)) {
                        wrong.add(where + ": " + first);
                    }
                }
            }
        }
        // The pattern still finds the game's handlers (138 hand off in 26.3).
        assertThat(handingOff).hasSizeGreaterThan(100);
        assertThat(wrong).isEmpty();
    }
}
