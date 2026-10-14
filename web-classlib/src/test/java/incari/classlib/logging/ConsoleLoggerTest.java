package incari.classlib.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class ConsoleLoggerTest {

    /** What the game's Bootstrap.wrapStreams does: standard output becomes a logger. */
    private static final class LoggingStream extends PrintStream {
        private final Logger logger;

        LoggingStream(Logger logger) {
            super(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
            this.logger = logger;
        }

        @Override
        public void println(String line) {
            logger.info("[STDOUT]: {}", line);
        }
    }

    @Test
    void loggingSurvivesStandardOutputBecomingALogger() {
        Logger logger = ConsoleLogging.INSTANCE.getLogger("test");
        PrintStream original = System.out;
        System.setOut(new LoggingStream(logger));
        try {
            assertThatCode(() -> logger.info("hello")).doesNotThrowAnyException();
            assertThatCode(() -> System.out.println("from the game")).doesNotThrowAnyException();
        } finally {
            System.setOut(original);
        }
    }

    /** As the game's ReportedException: the cause is answered by getCause(), not stored. */
    private static final class Reported extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Throwable answer;

        Reported(Throwable answer) {
            super("mouseClicked event handler");
            this.answer = answer;
        }

        @Override
        public synchronized Throwable getCause() {
            return answer;
        }
    }

    @Test
    void causesAnsweredByGetCauseArePrinted() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        ConsoleLogger.printWithCauses(
                new Reported(new IllegalStateException("the real problem", new ArithmeticException("deeper"))), out);

        assertThat(bytes.toString(StandardCharsets.UTF_8))
                .contains("mouseClicked event handler")
                .contains("Caused by: java.lang.IllegalStateException: the real problem")
                .contains("Caused by: java.lang.ArithmeticException: deeper");
    }
}
