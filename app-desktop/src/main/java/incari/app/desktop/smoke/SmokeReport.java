package incari.app.desktop.smoke;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The smoke test's results: one line per check, written to {@code <gameDir>/incari-smoke/report.txt}. */
final class SmokeReport {

    private static final Logger LOGGER = LoggerFactory.getLogger("Smoke test");

    private final Path file;
    private final List<String> lines = new ArrayList<>();
    private int failures;

    SmokeReport(Path directory) {
        this.file = directory.resolve("report.txt");
    }

    void pass(String check, String detail) {
        record("PASS", check, detail);
    }

    void fail(String check, String detail) {
        failures++;
        record("FAIL", check, detail);
    }

    void check(String check, boolean passed, String detail) {
        if (passed) {
            pass(check, detail);
        } else {
            fail(check, detail);
        }
    }

    boolean passed() {
        return failures == 0 && !lines.isEmpty();
    }

    private synchronized void record(String result, String check, String detail) {
        String line = result + "  " + check + (detail.isEmpty() ? "" : "  (" + detail + ")");
        lines.add(line);
        LOGGER.info(line);
        write();
    }

    synchronized void finish() {
        lines.add(passed() ? "RESULT  all checks passed" : "RESULT  " + failures + " check(s) failed");
        LOGGER.info(lines.getLast());
        write();
    }

    private void write() {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Couldn't write the smoke test report", e);
        }
    }
}
