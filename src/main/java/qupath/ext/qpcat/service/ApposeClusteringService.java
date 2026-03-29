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
    private static final String SCRIPTS_BASE = RESOURCE_BASE + "scripts/";
    private static final String ENV_NAME = "qupath-qpcat";

    /**
     * Expected environment version. Must match ENVIRONMENT_VERSION in init_services.py
     * and the version in build.gradle.kts.
     */
    static final String EXPECTED_ENV_VERSION = "0.2.0";

    private static ApposeClusteringService instance;

    private Environment environment;
    private Service pythonService;
    private boolean initialized;
    private String initError;
    private Thread shutdownHook;
    private Consumer<String> debugListener;

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

            // TCCL must be set for all Appose operations
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(ApposeClusteringService.class.getClassLoader());

            try {
                boolean rebuilding = syncPixiToml(pixiToml);
                if (rebuilding) {
                    report(statusCallback,
                            "Dependencies changed -- rebuilding environment (this may take several minutes)...");
                    logger.info("Environment rebuild triggered by pixi.toml change");
                } else {
                    report(statusCallback, "Building pixi environment (this may take several minutes)...");
                }

                var builder = Appose.pixi()
                        .content(pixiToml)
                        .scheme("pixi.toml")
                        .name(ENV_NAME)
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
                    logger.info("[QPCAT Python] {}", msg);
                    Consumer<String> listener = debugListener;
                    if (listener != null) {
                        listener.accept(msg);
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

                String verifyScript =
                        "import sklearn\n" +
                        "import umap\n" +
                        "import leidenalg\n" +
                        "import scanpy\n" +
                        "import anndata\n" +
                        "task.outputs['sklearn_version'] = sklearn.__version__\n" +
                        "task.outputs['scanpy_version'] = scanpy.__version__\n" +
                        "task.outputs['umap_version'] = umap.__version__\n" +
                        "task.outputs['env_version'] = ENVIRONMENT_VERSION\n";

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
                logger.info("Verified: scikit-learn {}, scanpy {}, umap {}, env {}",
                        sklearnVersion, scanpyVersion, umapVersion, envVersion);

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
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

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

        int maxAttempts = 3;
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
                        try { Thread.sleep(200); } catch (InterruptedException ie) {
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
        int maxAttempts = 3;
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
                        try { Thread.sleep(200); } catch (InterruptedException ie) {
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
                        try { Thread.sleep(200); }
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

    /**
     * Syncs the on-disk pixi.toml with the JAR-bundled version.
     * If they differ, deletes .pixi and pixi.lock to force a rebuild.
     *
     * @return true if the environment was wiped for rebuild
     */
    private boolean syncPixiToml(String expectedContent) {
        try {
            Path envDir = getEnvironmentPath();
            Path pixiTomlFile = envDir.resolve("pixi.toml");
            if (!Files.exists(pixiTomlFile)) return false;

            String existing = Files.readString(pixiTomlFile, StandardCharsets.UTF_8);
            String normalizedExisting = existing.replace("\r\n", "\n").strip();
            String normalizedExpected = expectedContent.replace("\r\n", "\n").strip();
            if (normalizedExisting.equals(normalizedExpected)) return false;

            logger.info("pixi.toml changed - forcing environment rebuild");
            Files.writeString(pixiTomlFile, expectedContent, StandardCharsets.UTF_8);
            Files.deleteIfExists(envDir.resolve("pixi.lock"));
            Path pixiDir = envDir.resolve(".pixi");
            if (Files.isDirectory(pixiDir)) {
                deleteDirectoryRecursively(pixiDir);
            }
            return true;
        } catch (IOException e) {
            logger.warn("Failed to sync pixi.toml: {}", e.getMessage());
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
