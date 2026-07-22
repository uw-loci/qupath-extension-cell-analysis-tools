package qupath.ext.qpcat.service;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.SetupQPCAT;
import qupath.lib.common.GeneralTools;

/**
 * Backend client for the in-app bug reporter.
 *
 * <p>Submits a user-written bug description -- optionally with system info, the
 * QP-CAT operation log, the QuPath log, and a window screenshot -- to a
 * Cloudflare Worker that holds a GitHub PAT server-side and files the report as
 * a GitHub Issue. No token is shipped in the JAR; the Worker is the only thing
 * that can talk to GitHub. The same Worker serves several QuPath extensions,
 * routed by {@link #REPO_KEY}.</p>
 *
 * <p>UI-free: gathers payload pieces and performs the blocking HTTP POST. The
 * caller runs {@link #submit} off the JavaFX Application Thread. ASCII-only in
 * internal strings (cp1252 runtime); user text is carried as UTF-8 in the JSON
 * body, which is fine over HTTP.</p>
 */
public class BugReportService {

    private static final Logger logger = LoggerFactory.getLogger(BugReportService.class);

    /** Deployed Cloudflare Worker endpoint (see the QPSC cloudflare-worker-bug-reporter README). */
    public static final String WORKER_URL = "https://qpsc-bug-reporter.imagescientistwebsite.workers.dev";

    /** Allow-list key understood by the Worker (see REPOS in src/index.js). */
    private static final String REPO_KEY = "qpcat";

    /** Human-facing label shown in the issue body. */
    private static final String EXTENSION_LABEL = "QP-CAT";

    public static final int MIN_DESCRIPTION_CHARS = 20;
    public static final int MAX_DESCRIPTION_CHARS = 10000;

    /** The worker truncates the issue title at 80 characters. */
    public static final int MIN_SUMMARY_CHARS = 8;
    public static final int MAX_SUMMARY_CHARS = 80;

    private static final int MAX_RUN_LOG_CHARS = 40000;
    private static final int MAX_QUPATH_LOG_CHARS = 12000;

    /** Worker rejects screenshots whose base64 exceeds this (~3 MB binary). */
    public static final int MAX_SCREENSHOT_B64_CHARS = 4 * 1024 * 1024;

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private BugReportService() {}

    /** Immutable request assembled by the dialog. */
    public record BugReport(
            String summary, String description, String sysinfo,
            Map<String, String> artifacts, String screenshotBase64) {}

    /** Outcome of a submission. {@code ok} false carries a user-facing {@code error}. */
    public record Result(boolean ok, String issueUrl, Integer issueNumber, String error) {}

    /** True once {@link #WORKER_URL} points at a real deployment. */
    public static boolean isConfigured() {
        return WORKER_URL != null && !WORKER_URL.contains("YOUR-SUBDOMAIN");
    }

    /** Extension version from the JAR manifest, or "dev" from an IDE. */
    public static String getExtensionVersion() {
        String v = GeneralTools.getPackageVersion(SetupQPCAT.class);
        return v != null ? v : "dev";
    }

    // ---- payload gathering -------------------------------------------------

    /** Multi-line system-info block (extension/QuPath/Java/OS versions). */
    public static String gatherSysInfo() {
        return "Extension: " + EXTENSION_LABEL + " " + getExtensionVersion() + "\n"
                + "QuPath: " + GeneralTools.getVersion() + "\n"
                + "Java: " + System.getProperty("java.version") + "\n"
                + "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")";
    }

    /**
     * Reads the requested logs, scrubs home-directory paths, and caps each to
     * keep the combined GitHub issue body under its 64 KB limit.
     *
     * @param includeSessionLog attach the QP-CAT operation log (per-project)
     * @param includeQuPathLog  attach QuPath's own log file, if one is on disk
     * @return ordered map of artifact-key -> text (only non-empty entries)
     */
    public static Map<String, String> gatherLogArtifacts(boolean includeSessionLog, boolean includeQuPathLog) {
        Map<String, String> artifacts = new LinkedHashMap<>();
        if (includeSessionLog) {
            String content = readCappedLog(findSessionLogFile(), MAX_RUN_LOG_CHARS);
            if (!content.isEmpty()) {
                artifacts.put("run_log", content);
            }
        }
        if (includeQuPathLog) {
            // Prefer QuPath's on-disk log (complete, includes startup) when file
            // logging is enabled; otherwise fall back to the in-memory capture of
            // the live log, which is present even when no log file is written.
            Path quPathLog = findQuPathLogFile();
            String content = quPathLog != null
                    ? readCappedLog(quPathLog, MAX_QUPATH_LOG_CHARS)
                    : capAndScrub(SessionLogBuffer.getText(), MAX_QUPATH_LOG_CHARS);
            if (!content.isEmpty()) {
                artifacts.put("qupath_log", content);
            }
        }
        return artifacts;
    }

    /** True if a QP-CAT operation log exists on disk (drives a checkbox state). */
    public static boolean isSessionLogAvailable() {
        return findSessionLogFile() != null;
    }

    /**
     * True if a QuPath log can be attached -- either an on-disk log file exists,
     * or the in-memory session buffer has captured the live log (the usual case,
     * since QuPath writes no file by default). Drives a checkbox state.
     */
    public static boolean isQuPathLogAvailable() {
        return findQuPathLogFile() != null || SessionLogBuffer.hasContent();
    }

    private static Path findSessionLogFile() {
        try {
            return qupath.ext.qpcat.service.OperationLogger.getInstance().getExistingLogFile();
        } catch (Exception e) {
            logger.debug("Could not locate QP-CAT operation log: {}", e.getMessage());
            return null;
        }
    }

    private static String readCappedLog(Path path, int maxChars) {
        if (path == null || !Files.isRegularFile(path)) {
            return "";
        }
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.debug("Could not read log artifact {}: {}", path, e.getMessage());
            return "";
        }
        return capAndScrub(text, maxChars);
    }

    /** Caps text to the most recent {@code maxChars} and scrubs home-dir paths. */
    private static String capAndScrub(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.length() > maxChars) {
            text = "... [truncated to last " + maxChars + " chars]\n" + text.substring(text.length() - maxChars);
        }
        return scrubPaths(text);
    }

    /**
     * Replaces the user's home directory with {@code ~} so reports don't leak a
     * username. Only applied to the copy bundled for submission.
     */
    static String scrubPaths(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty()) {
            return text;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) {
            return text.replaceAll("(?i)" + java.util.regex.Pattern.quote(home), "~");
        }
        return text.replace(home, "~");
    }

    /**
     * Scans logback's root logger for a {@link FileAppender} and returns its
     * file path -- QuPath's own application log when one is configured.
     */
    private static Path findQuPathLogFile() {
        try {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            ch.qos.logback.classic.Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            for (Iterator<Appender<ILoggingEvent>> it = root.iteratorForAppenders(); it.hasNext(); ) {
                Appender<ILoggingEvent> appender = it.next();
                if (appender instanceof FileAppender<ILoggingEvent> fileAppender) {
                    String file = fileAppender.getFile();
                    if (file != null && !file.isBlank()) {
                        Path p = Path.of(file);
                        if (Files.isRegularFile(p)) {
                            return p;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not locate QuPath log file: {}", e.getMessage());
        }
        return null;
    }

    // ---- submission --------------------------------------------------------

    /** Blocking POST of the report to the Worker. Run off the FX thread. Never throws. */
    public static Result submit(BugReport report) {
        if (!isConfigured()) {
            return new Result(false, null, null, "Bug reporting is not configured (Worker URL not set).");
        }
        String json;
        try {
            json = buildJson(report);
        } catch (Exception e) {
            return new Result(false, null, null, "Could not build request: " + e.getMessage());
        }
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(WORKER_URL))
                    .timeout(HTTP_TIMEOUT)
                    // Custom User-Agent REQUIRED: Cloudflare bot-blocks the default Java client UA.
                    .header("User-Agent", "qpsc-bug-reporter-client")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return parseResponse(response.statusCode(), response.body());
        } catch (java.net.http.HttpTimeoutException e) {
            return new Result(false, null, null, "Timed out contacting the bug-report server.");
        } catch (IOException e) {
            return new Result(false, null, null, "Network error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, null, null, "Submission was interrupted.");
        } catch (Exception e) {
            return new Result(false, null, null, "Unexpected error: " + e.getMessage());
        }
    }

    private static String buildJson(BugReport report) {
        JsonObject root = new JsonObject();
        root.addProperty("repo", REPO_KEY);
        String version = getExtensionVersion();
        root.addProperty("extension", EXTENSION_LABEL + " " + version);
        root.addProperty("app_version", version);
        root.addProperty("summary", report.summary());
        root.addProperty("description", report.description());

        String sysinfo = report.sysinfo();
        if (sysinfo != null && !sysinfo.isBlank()) {
            root.addProperty("sysinfo", sysinfo);
        }
        Map<String, String> artifacts = report.artifacts();
        if (artifacts != null && !artifacts.isEmpty()) {
            JsonObject artifactsObj = new JsonObject();
            for (Map.Entry<String, String> e : artifacts.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    artifactsObj.addProperty(e.getKey(), e.getValue());
                }
            }
            root.add("artifacts", artifactsObj);
        }
        String shot = report.screenshotBase64();
        if (shot != null && !shot.isEmpty()) {
            JsonObject screenshot = new JsonObject();
            screenshot.addProperty("content_base64", shot);
            screenshot.addProperty("mime_type", "image/png");
            root.add("screenshot", screenshot);
        }
        return root.toString();
    }

    private static Result parseResponse(int status, String body) {
        JsonObject obj = null;
        try {
            obj = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception ignored) {
            // fall through to status-based handling
        }
        if (obj != null && obj.has("ok") && obj.get("ok").getAsBoolean()) {
            String url = obj.has("issue_url") && !obj.get("issue_url").isJsonNull()
                    ? obj.get("issue_url").getAsString()
                    : "";
            Integer number = obj.has("issue_number") && !obj.get("issue_number").isJsonNull()
                    ? obj.get("issue_number").getAsInt()
                    : null;
            return new Result(true, url, number, null);
        }
        if (obj != null && obj.has("error") && !obj.get("error").isJsonNull()) {
            return new Result(false, null, null, obj.get("error").getAsString());
        }
        return new Result(false, null, null, "Server returned HTTP " + status + ".");
    }
}
