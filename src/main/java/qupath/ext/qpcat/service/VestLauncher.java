package qupath.ext.qpcat.service;

import org.apposed.appose.Appose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Launches the standalone <a href="https://github.com/scads/vest">VEST</a> 3D viewer on a
 * QP-CAT export bundle (embedding.csv + images/) and opens it in the browser.
 *
 * <p>VEST lives in its OWN on-demand Appose/pixi environment ({@code qpcat-vest}: Flask +
 * pandas + vision-embedding-space-travelling), kept deliberately separate from the
 * clustering env so VEST's modern pandas/numpy never conflict with the scanpy/squidpy
 * stack. The env (~150-170 MB) builds lazily the first time the user launches VEST.</p>
 *
 * <p>VEST is a small Flask server that opens the user's browser itself and blocks until
 * killed, so QP-CAT runs it as a subprocess (not through Appose's request/response task
 * IPC) and manages its lifecycle: one server at a time, an explicit {@link #stop()}, and a
 * JVM shutdown hook so it never outlives QuPath.</p>
 */
public final class VestLauncher {

    private static final Logger logger = LoggerFactory.getLogger(VestLauncher.class);
    private static final String ENV_NAME = "qpcat-vest";
    private static final String VEST_TOML_RESOURCE = "qupath/ext/qpcat/vest.toml";

    // Guarded by the class monitor: at most one VEST server at a time.
    private static Process serverProcess;
    private static String serverUrl;

    static {
        Runtime.getRuntime().addShutdownHook(
                new Thread(VestLauncher::stop, "qpcat-vest-shutdown"));
    }

    private VestLauncher() {}

    /** Directory the VEST pixi env lives in (matches the main service's convention). */
    static Path getEnvDir() {
        return Path.of(System.getProperty("user.home"),
                ".local", "share", "appose", ENV_NAME);
    }

    /** True once the pixi env has been built. */
    public static boolean isEnvBuilt() {
        return Files.isDirectory(getEnvDir().resolve(".pixi"));
    }

    /** True while a VEST server is running. */
    public static synchronized boolean isRunning() {
        return serverProcess != null && serverProcess.isAlive();
    }

    /** URL of the running VEST server, or null. */
    public static synchronized String getUrl() {
        return isRunning() ? serverUrl : null;
    }

    /**
     * Build the VEST env if needed, then launch the viewer on {@code csv} + {@code imageDir}.
     * Blocks until the server is up (VEST opens the browser itself). Must be called OFF the
     * FX thread -- the first call may download the env. Returns the server URL.
     */
    public static synchronized String launch(Path csv, Path imageDir, Consumer<String> status)
            throws IOException {
        if (csv == null || !Files.exists(csv)) {
            throw new IOException("VEST needs an exported embedding.csv; none found.");
        }
        ensureEnv(status);
        Path vestExe = resolveVestExecutable(getEnvDir());
        if (vestExe == null) {
            throw new IOException("Could not find the 'vest' program in the built environment "
                    + "(" + getEnvDir() + ").");
        }

        stop();  // one server at a time
        int port = freePort();
        report(status, "Launching VEST viewer...");

        ProcessBuilder pb = new ProcessBuilder(
                vestExe.toString(),
                csv.toString(),
                "--image-path", (imageDir != null ? imageDir : csv.getParent()).toString(),
                "--port", Integer.toString(port),
                "--no-debug");
        pb.directory(csv.getParent().toFile());
        pb.redirectErrorStream(true);

        Process p = pb.start();
        serverProcess = p;
        serverUrl = "http://127.0.0.1:" + port;
        drainAsync(p);

        // If VEST dies immediately (e.g. bad CSV, port clash) surface it instead of
        // leaving a dead "server". Still alive after a moment => it is serving, and its
        // own 1s browser-open timer will have fired.
        try {
            if (p.waitFor(2, TimeUnit.SECONDS)) {
                serverProcess = null;
                String url = serverUrl;
                serverUrl = null;
                throw new IOException("VEST exited immediately (code " + p.exitValue()
                        + "). Check the log; URL was " + url + ".");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        logger.info("VEST viewer running at {} (pid {})", serverUrl, p.pid());
        OperationLogger.getInstance().logEvent("VEST VIEWER", "Launched at " + serverUrl);
        return serverUrl;
    }

    /** Stop the running VEST server, if any. */
    public static synchronized void stop() {
        Process p = serverProcess;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
            logger.info("VEST viewer stopped");
        }
        serverProcess = null;
        serverUrl = null;
    }

    // ---- env build ----

    private static void ensureEnv(Consumer<String> status) throws IOException {
        if (isEnvBuilt()) {
            return;
        }
        report(status, "Building the VEST environment (one-time, ~165 MB download)...");
        logger.info("Building VEST Appose env '{}' at {}", ENV_NAME, getEnvDir());
        String toml = loadResource(VEST_TOML_RESOURCE);
        try {
            // TCCL-wrapped: the pixi Scheme/BuilderFactory is resolved via ServiceLoader.
            ApposeClusteringService.withExtensionClassLoader(() -> {
                Appose.pixi()
                        .content(toml)
                        .scheme("pixi.toml")
                        .name(ENV_NAME)
                        .logDebug()
                        .subscribeOutput(m -> logger.info("[vest-pixi] {}", m))
                        .subscribeError(m -> logger.warn("[vest-pixi] {}", m))
                        .build();
                return null;
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to build the VEST environment: " + e.getMessage(), e);
        }
        if (!isEnvBuilt()) {
            throw new IOException("VEST environment build did not complete.");
        }
        report(status, "VEST environment ready.");
    }

    /** Locate the {@code vest} console script inside the built pixi env (cross-platform). */
    private static Path resolveVestExecutable(Path envDir) {
        Path base = envDir.resolve(".pixi").resolve("envs").resolve("default");
        List<Path> candidates = List.of(
                base.resolve("bin").resolve("vest"),          // linux/macOS
                base.resolve("Scripts").resolve("vest.exe"),  // windows
                base.resolve("bin").resolve("vest.exe"),
                base.resolve("Scripts").resolve("vest"));
        for (Path c : candidates) {
            if (Files.isRegularFile(c)) return c;
        }
        return null;
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void drainAsync(Process p) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    logger.info("[vest] {}", line);
                }
            } catch (IOException ignored) {
                // stream closed on process exit
            }
        }, "qpcat-vest-out");
        t.setDaemon(true);
        t.start();
    }

    private static String loadResource(String resourcePath) throws IOException {
        try (InputStream is = VestLauncher.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        }
    }

    private static void report(Consumer<String> status, String msg) {
        if (status != null) {
            try { status.accept(msg); } catch (Exception ignore) { /* UI sink */ }
        }
    }
}
