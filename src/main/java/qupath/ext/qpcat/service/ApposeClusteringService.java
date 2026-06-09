package qupath.ext.qpcat.service;

import org.apposed.appose.Appose;
import org.apposed.appose.Environment;
import org.apposed.appose.Service;
import org.apposed.appose.Service.Task;
import org.apposed.appose.Service.ResponseType;
import org.apposed.appose.TaskException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Singleton managing the Appose Environment and Python Service lifecycle
 * for QPCAT.
 * <p>
 * Provides an embedded Python runtime for clustering, dimensionality reduction,
 * and related operations via Appose's shared-memory IPC. No GPU required --
 * all operations are CPU-based.
 */
public class ApposeClusteringService {

    private static final Logger logger = LoggerFactory.getLogger(ApposeClusteringService.class);

    private static final String RESOURCE_BASE = "qupath/ext/qpcat/";
    private static final String PIXI_TOML_RESOURCE = RESOURCE_BASE + "pixi.toml";
    // Bundled lockfile pinning the FULL transitive dependency tree (all 4
    // platforms). Installed with --frozen so users get the exact, tested
    // versions on every update -- no re-resolution against current
    // conda-forge/PyPI, which is what let setuptools drift to a pkg_resources-
    // less 81.x. Regenerate via tools/regen-pixi-lock.sh when pixi.toml changes.
    private static final String PIXI_LOCK_RESOURCE = RESOURCE_BASE + "pixi.lock";
    private static final String SCRIPTS_BASE = RESOURCE_BASE + "scripts/";
    private static final String ENV_NAME = "qupath-qpcat";

    /**
     * Expected environment version. Must match ENVIRONMENT_VERSION in init_services.py
     * and the version in build.gradle.kts.
     */
    static final String EXPECTED_ENV_VERSION = "0.2.7";

    private static ApposeClusteringService instance;

    private Environment environment;
    private Service pythonService;
    private boolean initialized;
    private String initError;
    private Thread shutdownHook;
    private Consumer<String> debugListener;

    // Capability flags reported by init_services.py at verification time.
    // Java reads these to gray out features whose Python deps are missing
    // rather than crashing the whole extension. Default false until init.
    private boolean harmonypyAvailable;

    private ApposeClusteringService() {}

    public static synchronized ApposeClusteringService getInstance() {
        if (instance == null) {
            instance = new ApposeClusteringService();
        }
        return instance;
    }

    /**
     * Checks if the Appose pixi environment appears to be built on disk.
     */
    public static boolean isEnvironmentBuilt() {
        ApposeClusteringService svc = instance;
        if (svc != null && svc.environment != null) {
            Path envDir = Path.of(svc.environment.base());
            return Files.isDirectory(envDir.resolve(".pixi"));
        }
        Path envDir = getEnvironmentPath();
        return Files.isDirectory(envDir.resolve(".pixi"));
    }

    /**
     * Checks if the on-disk pixi.toml differs from the JAR-bundled version,
     * indicating the environment needs a rebuild (e.g., new dependencies were added).
     *
     * @return true if the environment exists but its pixi.toml is outdated
     */
    public static boolean isEnvironmentStale() {
        try {
            Path envDir = getEnvironmentPath();
            Path pixiTomlFile = envDir.resolve("pixi.toml");
            if (!Files.exists(pixiTomlFile)) return false;
            if (!Files.isDirectory(envDir.resolve(".pixi"))) return false;

            String expected = loadResource(PIXI_TOML_RESOURCE);
            String existing = Files.readString(pixiTomlFile, StandardCharsets.UTF_8);
            return !existing.replace("\r\n", "\n").strip()
                    .equals(expected.replace("\r\n", "\n").strip());
        } catch (Exception e) {
            return false;
        }
    }

    public static Path getEnvironmentPath() {
        ApposeClusteringService svc = instance;
        if (svc != null && svc.environment != null) {
            return Path.of(svc.environment.base());
        }
        return Path.of(System.getProperty("user.home"),
                ".local", "share", "appose", ENV_NAME);
    }

    /**
     * Builds the pixi environment and starts the Python service.
     */
    public synchronized void initialize() throws IOException {
        initialize(null);
    }

    public synchronized void initialize(Consumer<String> statusCallback) throws IOException {
        if (initialized) {
            report(statusCallback, "Already initialized");
            return;
        }

        try {
            report(statusCallback, "Loading environment configuration...");
            logger.info("Initializing QPCAT Appose environment...");

            String pixiToml = loadResource(PIXI_TOML_RESOURCE);
            String pixiLock = loadResource(PIXI_LOCK_RESOURCE);

            // TCCL must be set for all Appose operations
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(ApposeClusteringService.class.getClassLoader());

            try {
                boolean rebuilding = syncManifest(pixiToml, pixiLock);
                if (rebuilding) {
                    report(statusCallback,
                            "Dependencies changed -- rebuilding environment (this may take several minutes)...");
                    logger.info("Environment rebuild triggered by pixi manifest/lock change");
                } else {
                    report(statusCallback, "Building pixi environment (this may take several minutes)...");
                }

                // Install strictly from the bundled lockfile: pixi installs the
                // exact pinned versions and never re-resolves against current
                // conda-forge/PyPI. Appose writes the manifest + passes the
                // flags to `pixi install`.
                var builder = Appose.pixi()
                        .content(pixiToml)
                        .scheme("pixi.toml")
                        .name(ENV_NAME)
                        .flags(java.util.List.of("--frozen"))
                        .logDebug()
                        .subscribeOutput(msg -> logger.info("[pixi] {}", msg))
                        .subscribeError(msg -> logger.warn("[pixi] {}", msg));

                // Forward build progress to the status callback if provided
                if (statusCallback != null) {
                    builder.subscribeProgress((msg, step, numSteps) ->
                            report(statusCallback, msg));
                }

                environment = builder.build();

                logger.info("QPCAT Appose environment built");
                report(statusCallback, "Starting Python service...");

                pythonService = environment.python();

                pythonService.debug(msg -> {
                    // Filter out Appose protocol messages (EXECUTE requests contain
                    // the full script + base64 inputs and can be 25+ MB). Only log
                    // Python stderr output (logging, warnings, errors).
                    if (msg.contains("\"requestType\"") || msg.contains("\"responseType\"")) {
                        // Protocol message -- skip logging (too large, not useful)
                        return;
                    }
                    // Truncate very long messages (safety net)
                    String logMsg = msg.length() > 2000
                            ? msg.substring(0, 2000) + "... [truncated]" : msg;
                    logger.info("[QPCAT Python] {}", logMsg);
                    Consumer<String> listener = debugListener;
                    if (listener != null) {
                        listener.accept(logMsg);
                    }
                });

                // Import numpy first to avoid Windows threading deadlock
                // Load model_utils into global scope so task scripts can use
                // detect_device() and FOUNDATION_MODELS without import
                String initScript = "import numpy\n"
                        + loadScript("init_services.py") + "\n"
                        + loadScript("model_utils.py");
                pythonService.init(initScript);

                // Verify packages are importable
                report(statusCallback, "Verifying installed packages...");
                logger.info("Running environment verification...");

                // squidpy is imported here so the stale-env case (xarray_schema
                // -> pkg_resources missing because setuptools didn't land in the
                // env) is caught at init time, not at first clustering run. The
                // catch block below detects that signature and auto-wipes the
                // env so the next launch rebuilds cleanly.
                String verifyScript =
                        "import sklearn\n" +
                        "import umap\n" +
                        "import leidenalg\n" +
                        "import scanpy\n" +
                        "import anndata\n" +
                        "import squidpy\n" +
                        "task.outputs['sklearn_version'] = sklearn.__version__\n" +
                        "task.outputs['scanpy_version'] = scanpy.__version__\n" +
                        "task.outputs['umap_version'] = umap.__version__\n" +
                        "task.outputs['env_version'] = ENVIRONMENT_VERSION\n" +
                        "task.outputs['harmonypy_available'] = HARMONYPY_AVAILABLE\n";

                Task verifyTask = pythonService.task(verifyScript);
                verifyTask.listen(event -> {
                    if (event.responseType == ResponseType.FAILURE
                            || event.responseType == ResponseType.CRASH) {
                        logger.error("Verification failed: {}", verifyTask.error);
                    }
                });
                verifyTask.waitFor();

                String sklearnVersion = String.valueOf(verifyTask.outputs.get("sklearn_version"));
                String scanpyVersion = String.valueOf(verifyTask.outputs.get("scanpy_version"));
                String umapVersion = String.valueOf(verifyTask.outputs.get("umap_version"));
                String envVersion = String.valueOf(verifyTask.outputs.get("env_version"));
                Object harmonypyFlag = verifyTask.outputs.get("harmonypy_available");
                harmonypyAvailable = Boolean.TRUE.equals(harmonypyFlag);
                logger.info("Verified: scikit-learn {}, scanpy {}, umap {}, env {} (harmonypy={})",
                        sklearnVersion, scanpyVersion, umapVersion, envVersion,
                        harmonypyAvailable ? "available" : "MISSING");

                // Version check: warn if environment version doesn't match expected
                if (!EXPECTED_ENV_VERSION.equals(envVersion)) {
                    logger.warn("Environment version mismatch: expected {}, got {}. "
                            + "Some features may not work correctly. "
                            + "Use Utilities > Rebuild Clustering Environment to update.",
                            EXPECTED_ENV_VERSION, envVersion);
                    report(statusCallback,
                            "Warning: environment version mismatch (expected "
                            + EXPECTED_ENV_VERSION + ", got " + envVersion
                            + "). Rebuild recommended.");
                }

                initialized = true;
                initError = null;
                registerShutdownHook();
                report(statusCallback, "Setup complete! (scikit-learn " + sklearnVersion
                        + ", scanpy " + scanpyVersion + ", env v" + envVersion + ")");
                logger.info("QPCAT Appose service initialized (env v{})", envVersion);
            } finally {
                Thread.currentThread().setContextClassLoader(original);
            }

        } catch (Exception e) {
            initError = e.getMessage();
            initialized = false;
            logger.error("Failed to initialize QPCAT Appose: {}", e.getMessage(), e);

            // Detect the well-known "stale env" case: the on-disk pixi.toml
            // appears current but the resolved env is missing a required
            // package (most commonly setuptools / pkg_resources, surfaced
            // transitively via the xarray_schema -> spatialdata -> squidpy
            // import chain). Auto-wipe .pixi/ + pixi.lock so the next launch
            // forces a clean Pixi sync.
            if (looksLikeStaleEnv(e.getMessage())) {
                if (wipeEnvForRebuild()) {
                    String advice = "QP-CAT detected a stale Pixi environment and cleaned "
                            + "it up automatically. Restart QuPath to rebuild "
                            + "the environment from scratch (~30 sec to 2 min, "
                            + "depending on cached downloads).";
                    logger.warn(advice);
                    report(statusCallback, advice);
                    try {
                        qupath.fx.dialogs.Dialogs.showWarningNotification(
                                "QP-CAT", advice);
                    } catch (Exception fxEx) {
                        // FX may not be initialized in some headless contexts
                        // -- the log + status callback already told the user.
                    }
                }
            } else if (looksLikeWindowsFileLock(e.getMessage())) {
                // v0.3.4: Windows file-lock during pixi link step. Do NOT auto-wipe
                // -- another process is actively holding a file in .pixi/envs/, so
                // a wipe risks corruption. Show the user the recovery steps.
                String advice = WINDOWS_FILE_LOCK_ADVICE;
                logger.warn("Pixi env install hit a Windows file lock; manual recovery required\n{}", advice);
                report(statusCallback, "Pixi env install failed: Windows file lock. See recovery steps.");
                try {
                    qupath.fx.dialogs.Dialogs.showWarningNotification(
                            "QP-CAT", "Pixi env install failed: a file was locked by another"
                                    + " process. Close QuPath, delete the .pixi folder, and"
                                    + " relaunch. See the log for full recovery steps.");
                } catch (Exception fxEx) {
                    // FX not running; log advice already emitted.
                }
            }

            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    /**
     * v0.3.4: Recovery instructions for the Windows file-lock failure mode
     * during Pixi env install (`failed to link` + os error 32). Emitted to
     * the log; surfaced as a short notification with a pointer to the log
     * for the full steps.
     */
    private static final String WINDOWS_FILE_LOCK_ADVICE =
            "QP-CAT cannot finish building its Python environment because another"
            + " process is holding a file open inside the env directory.\n\n"
            + "RECOVERY STEPS (Windows):\n"
            + "  1. Close QuPath completely (File -> Quit).\n"
            + "  2. Open Task Manager -- end any leftover java.exe or python.exe"
            + " running under your user.\n"
            + "  3. In PowerShell:\n"
            + "       Remove-Item -Recurse -Force \"$env:USERPROFILE\\.local\\share\\appose\\qupath-qpcat\\.pixi\"\n"
            + "       Remove-Item -Force \"$env:USERPROFILE\\.local\\share\\appose\\qupath-qpcat\\pixi.lock\"\n"
            + "  4. (If step 3 fails: reboot Windows -- guaranteed to release every file handle.)\n"
            + "  5. (Optional) Add an antivirus exclusion for the appose folder to prevent"
            + " repeat occurrences:\n"
            + "       %USERPROFILE%\\.local\\share\\appose\\\n"
            + "  6. Relaunch QuPath. Pixi will rebuild from scratch and the link step will succeed.";

    /**
     * Runs a named task script with the given inputs.
     */
    public Task runTask(String scriptName, Map<String, Object> inputs) throws IOException {
        ensureInitialized();

        String script;
        try {
            script = loadScript(scriptName + ".py");
        } catch (IOException e) {
            throw new IOException("Failed to load task script: " + scriptName, e);
        }

        int maxAttempts = qupath.ext.qpcat.preferences.QpcatPreferences.getTaskMaxRetries();
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(ApposeClusteringService.class.getClassLoader());
        try {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    Task task = pythonService.task(script, inputs);
                    task.listen(event -> {
                        if (event.responseType == ResponseType.CRASH) {
                            logger.error("Task '{}' CRASH: {}", scriptName, task.error);
                        } else if (event.responseType == ResponseType.FAILURE) {
                            logger.error("Task '{}' FAILURE: {}", scriptName, task.error);
                        }
                    });
                    task.waitFor();
                    return task;
                } catch (TaskException e) {
                    if (e.getMessage() != null
                            && e.getMessage().contains("thread death")
                            && attempt < maxAttempts) {
                        logger.warn("Task '{}' thread death (attempt {}/{}), retrying...",
                                scriptName, attempt, maxAttempts);
                        try { Thread.sleep(qupath.ext.qpcat.preferences.QpcatPreferences.getTaskRetrySleepMs()); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Task '" + scriptName + "' interrupted", ie);
                        }
                        continue;
                    }
                    throw new IOException("Task '" + scriptName + "' failed: " + e.getMessage(), e);
                }
            }
            throw new IOException("Task '" + scriptName + "' failed after " + maxAttempts + " attempts");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Task '" + scriptName + "' interrupted", e);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    /**
     * Runs a task with a custom event listener for progress updates.
     */
    public Task runTaskWithListener(String scriptName, Map<String, Object> inputs,
                                    Consumer<org.apposed.appose.TaskEvent> eventListener)
            throws IOException {
        ensureInitialized();

        String script;
        try {
            script = loadScript(scriptName + ".py");
        } catch (IOException e) {
            throw new IOException("Failed to load task script: " + scriptName, e);
        }

        // Retry on "thread death" -- Appose spawns a Python thread per task.
        // Stale thread deaths from previous tasks can get misattributed to the
        // current task, causing a spurious failure. Retrying after a brief pause
        // lets the stale cleanup messages drain before resubmitting.
        // Same pattern as the DL pixel classifier extension.
        int maxAttempts = qupath.ext.qpcat.preferences.QpcatPreferences.getTaskMaxRetries();
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(ApposeClusteringService.class.getClassLoader());
        try {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    Task task = pythonService.task(script, inputs);
                    task.listen(eventListener::accept);
                    task.waitFor();
                    return task;
                } catch (TaskException e) {
                    if (e.getMessage() != null
                            && e.getMessage().contains("thread death")
                            && attempt < maxAttempts) {
                        logger.warn("Task '{}' failed with thread death (attempt {}/{}), retrying...",
                                scriptName, attempt, maxAttempts);
                        try { Thread.sleep(qupath.ext.qpcat.preferences.QpcatPreferences.getTaskRetrySleepMs()); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Task '" + scriptName + "' interrupted", ie);
                        }
                        continue;
                    }
                    throw new IOException("Task '" + scriptName + "' failed: " + e.getMessage(), e);
                }
            }
            throw new IOException("Task '" + scriptName + "' failed after " + maxAttempts + " attempts");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Task '" + scriptName + "' interrupted", e);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    public synchronized void shutdown() {
        if (pythonService != null) {
            try {
                logger.info("Shutting down QPCAT Python service...");
                pythonService.close();
                if (pythonService.isAlive()) {
                    long deadline = System.currentTimeMillis() + 5000;
                    while (pythonService.isAlive() && System.currentTimeMillis() < deadline) {
                        try { Thread.sleep(qupath.ext.qpcat.preferences.QpcatPreferences.getTaskRetrySleepMs()); }
                        catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                if (pythonService.isAlive()) {
                    logger.warn("Python service did not exit gracefully, force-killing");
                    pythonService.kill();
                }
            } catch (Exception e) {
                try { pythonService.kill(); }
                catch (Exception ignored) {}
                logger.warn("Error during shutdown: {}", e.getMessage());
            }
            pythonService = null;
        }
        initialized = false;
        removeShutdownHook();
        logger.info("QPCAT Appose service shut down");
    }

    public synchronized void deleteEnvironment() throws IOException {
        if (pythonService != null) {
            throw new IOException("Cannot delete environment while Python service is running. "
                    + "Call shutdown() first.");
        }
        if (environment != null) {
            try {
                logger.info("Deleting environment via API: {}", environment.base());
                environment.delete();
                environment = null;
                return;
            } catch (Exception e) {
                logger.warn("environment.delete() failed, falling back: {}", e.getMessage());
                environment = null;
            }
        }
        Path envPath = getEnvironmentPath();
        if (Files.exists(envPath)) {
            logger.info("Deleting environment directory: {}", envPath);
            deleteDirectoryRecursively(envPath);
        }
    }

    public boolean isAvailable() {
        return initialized && initError == null && pythonService != null;
    }

    public String getInitError() { return initError; }

    /**
     * Whether the Python environment has the harmonypy package available.
     * Drives gray-out of the "Batch correction (Harmony)" UI in
     * {@code ClusteringDialog}. Returns false until {@link #initialize}
     * has completed successfully.
     */
    public boolean isHarmonypyAvailable() {
        return initialized && harmonypyAvailable;
    }

    /**
     * Sets a listener that receives Python debug/stderr output.
     * Useful for forwarding to the Python Console window.
     */
    public void setDebugListener(Consumer<String> listener) {
        this.debugListener = listener;
    }

    /**
     * Executes a callable with the extension classloader as TCCL.
     */
    public static <T> T withExtensionClassLoader(java.util.concurrent.Callable<T> callable) throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(ApposeClusteringService.class.getClassLoader());
        try {
            return callable.call();
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    // ==================== Internal Helpers ====================

    private static void report(Consumer<String> callback, String message) {
        if (callback != null) callback.accept(message);
    }

    /** True when the init exception message carries a well-known signature
     *  that means the Pixi env is structurally OK on disk but missing a
     *  package the verify import needed -- usually setuptools / pkg_resources
     *  surfaced via xarray_schema (transitive dep of squidpy). */
    private static boolean looksLikeStaleEnv(String message) {
        if (message == null) return false;
        String m = message;
        return m.contains("No module named 'pkg_resources'")
                || m.contains("No module named 'setuptools'")
                || m.contains("No module named 'xarray_schema'")
                || m.contains("No module named 'spatialdata'")
                || m.contains("No module named 'squidpy'");
    }

    /** v0.3.4: True when the Pixi build failed because Windows held an
     *  exclusive lock on a file the link step needed to replace. The
     *  canonical signature is the conda "failed to link" line plus the
     *  Windows "os error 32" / "being used by another process" wording.
     *  Distinct from looksLikeStaleEnv -- this is a transient OS-level
     *  failure, not a structural env corruption. Do NOT auto-wipe (the
     *  blocking process may still be writing to the env). */
    private static boolean looksLikeWindowsFileLock(String message) {
        if (message == null) return false;
        String m = message;
        boolean linkFailure = m.contains("failed to link");
        boolean processBlock = m.contains("os error 32")
                || m.contains("being used by another process");
        return linkFailure && processBlock;
    }

    /** Best-effort delete of .pixi/ + pixi.lock so the next QuPath launch
     *  triggers a clean Pixi sync from the JAR-bundled pixi.toml. Returns
     *  true on success (anything was deleted), false on any IO failure. */
    private boolean wipeEnvForRebuild() {
        try {
            Path envDir = getEnvironmentPath();
            if (!Files.isDirectory(envDir)) return false;
            boolean wiped = false;
            Path pixiLock = envDir.resolve("pixi.lock");
            if (Files.exists(pixiLock)) {
                Files.deleteIfExists(pixiLock);
                wiped = true;
            }
            Path pixiDir = envDir.resolve(".pixi");
            if (Files.isDirectory(pixiDir)) {
                deleteDirectoryRecursively(pixiDir);
                wiped = true;
            }
            if (wiped) {
                logger.info("Wiped Pixi env at {} -- will rebuild on next launch", envDir);
            }
            return wiped;
        } catch (IOException ioe) {
            logger.warn("Failed to wipe stale Pixi env: {}", ioe.getMessage());
            return false;
        }
    }

    /**
     * Sync the on-disk pixi.toml AND pixi.lock with the JAR-bundled versions.
     * The lock is the source of truth for the installed versions (we build with
     * --frozen), so it must be staged into the env dir before the build:
     *
     * <ul>
     *   <li>First run (no manifest on disk): create the env dir and stage the
     *       lock so the very first --frozen install has it. Appose writes the
     *       manifest itself.</li>
     *   <li>Either file changed vs the bundle: rewrite both and delete .pixi/
     *       so pixi reinstalls cleanly from the new lock.</li>
     *   <li>Unchanged: ensure the lock is present (re-stage if a prior wipe
     *       removed it), then no-op.</li>
     * </ul>
     *
     * @return true if the environment was wiped for a rebuild
     */
    private boolean syncManifest(String expectedToml, String expectedLock) {
        try {
            Path envDir = getEnvironmentPath();
            Path tomlFile = envDir.resolve("pixi.toml");
            Path lockFile = envDir.resolve("pixi.lock");

            if (!Files.exists(tomlFile)) {
                // First build: stage the lock; Appose writes the manifest.
                Files.createDirectories(envDir);
                Files.writeString(lockFile, expectedLock, StandardCharsets.UTF_8);
                return false;
            }

            String onToml = Files.readString(tomlFile, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").strip();
            String exToml = expectedToml.replace("\r\n", "\n").strip();
            String onLock = Files.exists(lockFile)
                    ? Files.readString(lockFile, StandardCharsets.UTF_8).replace("\r\n", "\n").strip()
                    : "";
            String exLock = expectedLock.replace("\r\n", "\n").strip();

            if (onToml.equals(exToml) && onLock.equals(exLock)) {
                // Unchanged -- but make sure the lock is on disk for --frozen.
                if (!Files.exists(lockFile)) {
                    Files.writeString(lockFile, expectedLock, StandardCharsets.UTF_8);
                }
                return false;
            }

            logger.info("pixi manifest/lock changed - forcing environment rebuild");
            Files.writeString(tomlFile, expectedToml, StandardCharsets.UTF_8);
            Files.writeString(lockFile, expectedLock, StandardCharsets.UTF_8);
            Path pixiDir = envDir.resolve(".pixi");
            if (Files.isDirectory(pixiDir)) {
                deleteDirectoryRecursively(pixiDir);
            }
            return true;
        } catch (IOException e) {
            logger.warn("Failed to sync pixi manifest/lock: {}", e.getMessage());
            return false;
        }
    }

    private void ensureInitialized() throws IOException {
        if (!isAvailable()) {
            throw new IOException("QPCAT service is not available"
                    + (initError != null ? ": " + initError : ""));
        }
    }

    String loadScript(String scriptFileName) throws IOException {
        return loadResource(SCRIPTS_BASE + scriptFileName);
    }

    private static String loadResource(String resourcePath) throws IOException {
        try (InputStream is = ApposeClusteringService.class.getClassLoader()
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

    private void registerShutdownHook() {
        if (shutdownHook != null) return;
        shutdownHook = new Thread(() -> {
            Service svc = pythonService;
            if (svc != null) {
                try {
                    svc.close();
                    if (svc.isAlive()) Thread.sleep(2000);
                    if (svc.isAlive()) svc.kill();
                } catch (Exception e) {
                    try { svc.kill(); } catch (Exception ignored) {}
                }
            }
        }, "QPCAT-ShutdownHook");
        shutdownHook.setDaemon(false);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    private void removeShutdownHook() {
        if (shutdownHook != null) {
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch (IllegalStateException e) { /* JVM shutting down */ }
            shutdownHook = null;
        }
    }

    private static void deleteDirectoryRecursively(Path directory) throws IOException {
        java.nio.file.FileVisitor<Path> visitor = new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir,
                    IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        };
        Files.walkFileTree(directory, visitor);
    }
}
