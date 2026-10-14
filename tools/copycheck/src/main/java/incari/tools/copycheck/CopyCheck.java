package incari.tools.copycheck;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Finds runs of Incari code that are token-for-token identical to code in the vendor tree.
 *
 * <p>Agents and people work next to the decompiled game, and code pasted from it into an
 * Incari module is both a quality problem and a legal one: Incari modules are published, Mojang
 * code must never be. Formatting, comments and imports are ignored, so reformatting a pasted
 * block doesn't hide it; renaming every identifier would, which is what a review is for.
 */
final class CopyCheck {

    /** Enough tokens for about five to eight lines of code: past what signatures and idioms share. */
    static final int DEFAULT_WINDOW = 60;

    /** One copied run: {@code tokens} tokens long, on lines {@code firstLine..lastLine} of {@code file}. */
    record Finding(String file, int firstLine, int lastLine, int tokens) {
        @Override
        public String toString() {
            return file + ":" + firstLine + "-" + lastLine + ": " + tokens + " tokens identical to vendor code";
        }
    }

    private final WindowIndex index;

    private CopyCheck(WindowIndex index) {
        this.index = index;
    }

    /** Indexes every {@code .java} file under {@code vendorRoot}. */
    static CopyCheck indexVendorTree(Path vendorRoot, int window) throws IOException {
        WindowIndex index = new WindowIndex(window);
        try (Stream<Path> files = Files.walk(vendorRoot)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                index.addFile(JavaTokens.tokenize(Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        index.seal();
        return new CopyCheck(index);
    }

    static CopyCheck of(List<String> vendorSources, int window) {
        WindowIndex index = new WindowIndex(window);
        for (String source : vendorSources) {
            index.addFile(JavaTokens.tokenize(source));
        }
        index.seal();
        return new CopyCheck(index);
    }

    /** Every copied run in one Incari file, merged where windows overlap. */
    List<Finding> check(String name, String source) {
        List<JavaTokens.Token> tokens = JavaTokens.tokenize(source);
        long[] hashes = WindowIndex.windowHashes(tokens, index.window());
        List<Finding> findings = new ArrayList<>();
        int runStart = -1;
        int runEnd = -1;
        for (int i = 0; i < hashes.length; i++) {
            if (!index.contains(hashes[i])) {
                continue;
            }
            int windowEnd = i + index.window() - 1;
            if (runStart >= 0 && i <= runEnd + 1) {
                runEnd = windowEnd;
            } else {
                if (runStart >= 0) {
                    findings.add(finding(name, tokens, runStart, runEnd));
                }
                runStart = i;
                runEnd = windowEnd;
            }
        }
        if (runStart >= 0) {
            findings.add(finding(name, tokens, runStart, runEnd));
        }
        return findings;
    }

    private static Finding finding(String name, List<JavaTokens.Token> tokens, int start, int end) {
        return new Finding(name, tokens.get(start).line(), tokens.get(end).line(), end - start + 1);
    }
}
