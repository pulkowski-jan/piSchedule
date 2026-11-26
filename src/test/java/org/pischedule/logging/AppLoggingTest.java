package org.pischedule.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.pischedule.Main;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.logging.Filter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** These tests temporarily replace System.out and application logger configuration. */
@Isolated
class AppLoggingTest {
    private static final Logger PACKAGE_LOGGER = Logger.getLogger("org.pischedule");
    private static final Logger LOGGER = Logger.getLogger("org.pischedule.logging.test");

    @TempDir Path temporaryDirectory;

    private PrintStream originalOut;
    private TrackingOutputStream output;
    private PrintStream capturedOut;
    private Handler[] originalHandlers;
    private Level originalLevel;
    private Filter originalFilter;
    private boolean originalUseParentHandlers;

    @BeforeEach
    void captureOutput() {
        originalOut = System.out;
        originalHandlers = PACKAGE_LOGGER.getHandlers();
        originalLevel = PACKAGE_LOGGER.getLevel();
        originalFilter = PACKAGE_LOGGER.getFilter();
        originalUseParentHandlers = PACKAGE_LOGGER.getUseParentHandlers();
        for (Handler handler : originalHandlers) {
            PACKAGE_LOGGER.removeHandler(handler);
        }
        PACKAGE_LOGGER.setFilter(null);
        output = new TrackingOutputStream();
        // Deliberately disable PrintStream's own auto-flush to exercise the logging handler.
        capturedOut = new PrintStream(output, false, StandardCharsets.UTF_8);
        System.setOut(capturedOut);
    }

    @AfterEach
    void restoreOutputAndLogger() throws IOException {
        System.setOut(originalOut);
        // Release any open file handler and its lock before @TempDir cleanup.
        AppLogging.configure(null);
        for (Handler handler : PACKAGE_LOGGER.getHandlers()) {
            PACKAGE_LOGGER.removeHandler(handler);
            handler.close();
        }
        for (Handler handler : originalHandlers) {
            PACKAGE_LOGGER.addHandler(handler);
        }
        PACKAGE_LOGGER.setLevel(originalLevel);
        PACKAGE_LOGGER.setFilter(originalFilter);
        PACKAGE_LOGGER.setUseParentHandlers(originalUseParentHandlers);
        capturedOut.close();
    }

    @Test
    void stdoutFlushesEveryRecordAndIncludesDateLevelAndUnicode() throws IOException {
        AppLogging.configure(null);
        int flushesBefore = output.flushes;

        LOGGER.info("Łagiewniki → Kraków ☀");

        assertTrue(output.flushes > flushesBefore);
        assertTrue(output.text().contains("Łagiewniki → Kraków ☀"));
        assertTrue(
                output.text()
                        .matches(
                                "(?s)\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} .*"
                                        + "\\[INFO\\] org\\.pischedule\\.logging\\.test: .*"));
        assertEquals(Level.INFO, PACKAGE_LOGGER.getLevel());
        assertFalse(PACKAGE_LOGGER.getUseParentHandlers());
    }

    @Test
    void createsDirectoriesAndAppendsUtf8ToExactFilenameAcrossReconfiguration() throws IOException {
        Path file = temporaryDirectory.resolve("logs/100%/application%g.log");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "previous run\n", StandardCharsets.UTF_8);
        AppLogging.configure(file);

        LOGGER.info("Łagiewniki → Kraków");
        AppLogging.configure(null);
        AppLogging.configure(file);
        LOGGER.info("next run");

        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("previous run\n"));
        assertTrue(text.contains("Łagiewniki → Kraków"));
        assertTrue(text.contains("next run"));
        assertEquals("", output.text());
    }

    @Test
    void createsMissingParentDirectories() throws IOException {
        Path file = temporaryDirectory.resolve("new/nested/application.log");
        AppLogging.configure(file);
        LOGGER.info("created");
        assertTrue(Files.readString(file).contains("created"));
    }

    @Test
    void reconfigurationDoesNotDuplicateHandlersOrRecords() throws IOException {
        AppLogging.configure(null);
        AppLogging.configure(null);
        LOGGER.info("stdout marker");
        assertEquals(1, PACKAGE_LOGGER.getHandlers().length);
        assertEquals(1, occurrences(output.text(), "stdout marker"));

        Path file = temporaryDirectory.resolve("application.log");
        AppLogging.configure(file);
        AppLogging.configure(file);
        LOGGER.info("file marker");
        assertEquals(1, PACKAGE_LOGGER.getHandlers().length);
        assertEquals(1, occurrences(Files.readString(file), "file marker"));
        assertFalse(output.text().contains("file marker"));
        assertFalse(Files.exists(temporaryDirectory.resolve("application.log.1")));
    }

    @Test
    void closingOrReplacingStdoutHandlerNeverClosesSystemOut() throws IOException {
        AppLogging.configure(null);
        PACKAGE_LOGGER.getHandlers()[0].close();
        assertFalse(output.closed);

        AppLogging.configure(null);
        AppLogging.configure(temporaryDirectory.resolve("application.log"));
        assertFalse(output.closed);
        System.out.print("still open");
        System.out.flush();
        assertTrue(output.text().contains("still open"));
    }

    @Test
    void fileSetupFailureIsExplicitAndLeavesBootstrapDestinationUsable() throws IOException {
        AppLogging.configure(null);
        Path blocker = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(blocker, "blocker");
        Path file = blocker.resolve("application.log");

        IOException failure = assertThrows(IOException.class, () -> AppLogging.configure(file));

        assertTrue(failure.getMessage().contains(file.toString()));
        LOGGER.log(Level.SEVERE, "startup failed", failure);
        assertTrue(output.text().contains("[SEVERE]"));
        assertTrue(output.text().contains("java.io.IOException: Cannot open log file"));
        assertTrue(output.text().contains("\tat "));
        assertEquals(1, PACKAGE_LOGGER.getHandlers().length);
    }

    @Test
    void lockedExactFileFailsWithoutAlternateFilenameAndKeepsBootstrapUsable() throws IOException {
        AppLogging.configure(null);
        Path file = temporaryDirectory.resolve("application.log");
        try (FileChannel channel =
                        FileChannel.open(
                                file,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE,
                                StandardOpenOption.APPEND);
                FileLock lock = channel.tryLock()) {
            assertTrue(lock != null && lock.isValid());
            Handler bootstrapHandler = PACKAGE_LOGGER.getHandlers()[0];

            IOException failure = assertThrows(IOException.class, () -> AppLogging.configure(file));

            assertTrue(failure.getMessage().contains(file.toString()));
            assertTrue(failure.getCause().getMessage().contains("already locked"));
            assertEquals(1, PACKAGE_LOGGER.getHandlers().length);
            assertEquals(bootstrapHandler, PACKAGE_LOGGER.getHandlers()[0]);
            LOGGER.log(Level.SEVERE, "locked log startup failed", failure);
            assertTrue(output.text().contains("locked log startup failed"));
            assertFalse(output.closed);
            assertEquals("", Files.readString(file));
            try (var entries = Files.list(temporaryDirectory)) {
                assertEquals(List.of(file), entries.toList());
            }
        }

        // Failed setup must not retain its temporary channel or lock after contention clears.
        AppLogging.configure(file);
        LOGGER.info("lock released");
        assertTrue(Files.readString(file).contains("lock released"));
    }

    @Test
    void replacingFileHandlerFlushesAndReleasesItsExclusiveLock() throws IOException {
        Path file = temporaryDirectory.resolve("application.log");
        AppLogging.configure(file);
        LOGGER.info("immediately visible");
        assertTrue(Files.readString(file).contains("immediately visible"));

        AppLogging.configure(null);

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE);
                FileLock lock = channel.tryLock()) {
            assertTrue(lock != null && lock.isValid());
        }
        assertFalse(output.closed);
    }

    @Test
    void exceptionsIncludeTheirStackTracesAndCauses() throws IOException {
        AppLogging.configure(null);
        LOGGER.log(
                Level.WARNING,
                "Update failed",
                new IOException("outer", new IllegalStateException("root cause")));

        assertTrue(output.text().contains("[WARNING]"));
        assertTrue(output.text().contains("java.io.IOException: outer"));
        assertTrue(
                output.text().contains("Caused by: java.lang.IllegalStateException: root cause"));
        assertTrue(output.text().contains("\tat "));
    }

    @Test
    void bootstrapHelpUsesStdoutWithoutLoadingConfiguration() {
        Main.main(new String[] {"--config", "missing-file.toml", "--help"});

        assertTrue(output.text().contains("[INFO]"));
        assertTrue(output.text().contains("Usage: java -jar piSchedule.jar"));
        assertFalse(output.closed);
    }

    @Test
    void configurationDoesNotChangeRootLogger() throws IOException {
        Logger root = Logger.getLogger("");
        Level level = root.getLevel();
        Handler[] handlers = root.getHandlers();
        boolean useParents = root.getUseParentHandlers();

        AppLogging.configure(null);

        assertEquals(level, root.getLevel());
        assertEquals(List.of(handlers), List.of(root.getHandlers()));
        assertEquals(useParents, root.getUseParentHandlers());
    }

    private static int occurrences(String text, String marker) {
        return text.split(Pattern.quote(marker), -1).length - 1;
    }

    private static final class TrackingOutputStream extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int flushes;
        private boolean closed;

        @Override
        public void write(int value) throws IOException {
            if (closed) {
                throw new IOException("Stream is closed");
            }
            bytes.write(value);
        }

        @Override
        public void flush() {
            flushes++;
        }

        @Override
        public void close() {
            closed = true;
        }

        private String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }
}
