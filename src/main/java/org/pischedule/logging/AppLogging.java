package org.pischedule.logging;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.ErrorManager;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;

/** Configures application logging without changing the JVM's root logger. */
public final class AppLogging {
    private static final Logger LOGGER = Logger.getLogger("org.pischedule");
    private static final Formatter FORMATTER = new PlainTextFormatter();
    private static Handler ownedHandler;
    private static Path destination;

    private AppLogging() {}

    /**
     * Logs to UTF-8 stdout when {@code file} is null, otherwise appends to that file, creating its
     * parent directories. File setup failures are propagated; the previous destination stays active.
     * The exact file is exclusively locked while in use; lock contention fails rather than selecting
     * another filename. Files are not rotated.
     */
    public static synchronized void configure(Path file) throws IOException {
        Path requested = file == null ? null : file.toAbsolutePath().normalize();
        if (requested != null && requested.equals(destination) && ownedHandler != null) {
            LOGGER.setLevel(Level.INFO);
            LOGGER.setUseParentHandlers(false);
            return;
        }

        Handler replacement;
        if (requested == null) {
            replacement = new StdoutHandler();
        } else {
            try {
                replacement = openFileHandler(requested);
            } catch (IOException | SecurityException e) {
                throw new IOException("Cannot open log file " + requested, e);
            }
        }
        try {
            replacement.setEncoding(StandardCharsets.UTF_8.name());
            replacement.setFormatter(FORMATTER);
            replacement.setLevel(Level.INFO);
        } catch (IOException | RuntimeException | Error e) {
            try {
                replacement.close();
            } catch (RuntimeException | Error closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }

        Handler previous = ownedHandler;
        LOGGER.setLevel(Level.INFO);
        LOGGER.setUseParentHandlers(false);
        if (previous != null) {
            LOGGER.removeHandler(previous);
        }
        LOGGER.addHandler(replacement);
        ownedHandler = replacement;
        destination = requested;
        if (previous != null) {
            previous.close();
        }
    }

    private static Handler openFileHandler(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        FileChannel channel =
                FileChannel.open(
                        file,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                throw new IOException("Log file is already locked: " + file, e);
            }
            if (lock == null) {
                throw new IOException("Log file is already locked: " + file);
            }
            return new FileLogHandler(channel, lock);
        } catch (IOException | RuntimeException | Error e) {
            try {
                // Closing the channel also releases any lock acquired before handler setup failed.
                channel.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    private static final class FileLogHandler extends StreamHandler {
        private final FileChannel channel;
        private final FileLock lock;

        private FileLogHandler(FileChannel channel, FileLock lock) {
            super(Channels.newOutputStream(channel), FORMATTER);
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public synchronized void publish(LogRecord record) {
            super.publish(record);
            flush();
        }

        @Override
        public synchronized void close() {
            try {
                super.close();
            } finally {
                // Normally the stream closes the channel and releases its lock. Still clean up if
                // StreamHandler encountered an error while flushing or closing its writer.
                try {
                    if (lock.isValid()) {
                        lock.release();
                    }
                } catch (IOException e) {
                    reportError("Cannot release log file lock", e, ErrorManager.CLOSE_FAILURE);
                } finally {
                    try {
                        channel.close();
                    } catch (IOException e) {
                        reportError("Cannot close log file", e, ErrorManager.CLOSE_FAILURE);
                    }
                }
            }
        }
    }

    private static final class StdoutHandler extends StreamHandler {
        private StdoutHandler() {
            super(System.out, FORMATTER);
        }

        @Override
        public synchronized void publish(LogRecord record) {
            super.publish(record);
            flush();
        }

        @Override
        public synchronized void close() {
            // StreamHandler.close() would close the process-wide System.out stream.
            flush();
        }
    }

    private static final class PlainTextFormatter extends Formatter {
        private static final DateTimeFormatter DATE =
                DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS XXX")
                        .withZone(ZoneId.systemDefault());

        @Override
        public String format(LogRecord record) {
            StringBuilder text =
                    new StringBuilder()
                            .append(DATE.format(record.getInstant()))
                            .append(" [")
                            .append(record.getLevel().getName())
                            .append("] ")
                            .append(record.getLoggerName())
                            .append(": ")
                            .append(formatMessage(record))
                            .append(System.lineSeparator());
            if (record.getThrown() != null) {
                StringWriter stackTrace = new StringWriter();
                record.getThrown().printStackTrace(new PrintWriter(stackTrace));
                text.append(stackTrace);
            }
            return text.toString();
        }
    }
}
