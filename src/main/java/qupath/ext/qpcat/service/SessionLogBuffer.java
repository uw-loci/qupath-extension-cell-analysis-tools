package qupath.ext.qpcat.service;

import java.util.ArrayDeque;
import java.util.Deque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ui.logviewer.api.LogMessage;
import qupath.ui.logviewer.api.listener.LoggerListener;
import qupath.ui.logviewer.api.manager.LoggerManager;

/**
 * Captures QuPath's live log in memory so the bug reporter can attach it even
 * when file logging is disabled.
 *
 * <p>QuPath only writes a log file when "Create log files" is enabled in
 * Preferences (off by default), so a report could not otherwise include the log
 * the user can see in View -> Show log. This registers a {@link LoggerListener}
 * on QuPath's running logging framework at extension startup -- the same
 * framework that feeds the on-screen log viewer -- and keeps a bounded tail of
 * recent messages that {@link BugReportService} can attach.</p>
 *
 * <p>Thread-safe: {@link #addLogMessage} is invoked from arbitrary logging
 * threads. Registration is idempotent (a static appender de-dupes by listener),
 * and if no logging framework is found the buffer simply stays empty.</p>
 */
public final class SessionLogBuffer implements LoggerListener {

    private static final Logger logger = LoggerFactory.getLogger(SessionLogBuffer.class);

    /** Keep at most this many recent characters; the reporter caps again on submit. */
    private static final int MAX_CHARS = 200_000;

    private static final SessionLogBuffer INSTANCE = new SessionLogBuffer();

    private final Deque<String> lines = new ArrayDeque<>();
    private int totalChars = 0;
    private volatile boolean registered = false;

    private SessionLogBuffer() {}

    /**
     * Registers the buffer with QuPath's logging framework. Idempotent and safe
     * to call from extension setup. Does nothing if no framework is available.
     */
    public static synchronized void init() {
        if (INSTANCE.registered) {
            return;
        }
        try {
            var manager = LoggerManager.getCurrentLoggerManager();
            if (manager.isPresent()) {
                manager.get().addListener(INSTANCE);
                INSTANCE.registered = true;
                logger.debug("Session log buffer registered with the logging framework.");
            } else {
                logger.debug("No logging framework found; session log buffer inactive.");
            }
        } catch (Exception e) {
            logger.debug("Could not register session log buffer: {}", e.getMessage());
        }
    }

    /** True once at least one message has been captured (drives the checkbox state). */
    public static boolean hasContent() {
        synchronized (INSTANCE.lines) {
            return !INSTANCE.lines.isEmpty();
        }
    }

    /** Snapshot of the buffered log text, oldest line first. */
    public static String getText() {
        synchronized (INSTANCE.lines) {
            StringBuilder sb = new StringBuilder(INSTANCE.totalChars);
            for (String line : INSTANCE.lines) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    @Override
    public void addLogMessage(LogMessage message) {
        if (message == null) {
            return;
        }
        String line;
        try {
            line = message.toReadableString() + "\n";
        } catch (Exception e) {
            return;
        }
        synchronized (lines) {
            lines.addLast(line);
            totalChars += line.length();
            while (totalChars > MAX_CHARS && lines.size() > 1) {
                totalChars -= lines.removeFirst().length();
            }
        }
    }
}
