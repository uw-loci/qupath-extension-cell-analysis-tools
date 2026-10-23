package qupath.ext.qpcat.service;

import org.apposed.appose.Appose;
import org.apposed.appose.Environment;
import org.apposed.appose.Service;
import org.apposed.appose.Service.Task;
import org.apposed.appose.Service.ResponseType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Singleton managing the Appose Environment and Python Service lifecycle
 * for QPCAT.
 * <p>
 * Provides an embedded Python runtime for clustering, dimensionality reduction,
 * and related operations via Appose's shared-memory IPC. No GPU required --
 * all operations are CPU-based.
 * <p>
 * The generic plumbing (manifest staging, worker shutdown, failure
 * classification, the thread-death retry) lives in the shared copies
 * {@link ApposeSupport} and {@link ApposeTaskRunner}; this class holds only
 * what is specific to the QP-CAT environment.
 */
public class ApposeClusteringService {

    private static final Logger logger = LoggerFactory.getLogger(ApposeClusteringService.class);

    private static final String RESOURCE_BASE = "qupath/ext/qpcat/";
    // Bundled lockfile pinning the FULL transitive dependency tree (all 4
    // platforms). We do NOT pass --frozen (see initialize() for why); instead
    // syncManifest() stages this lock next to the manifest it was generated
    // from, so plain `pixi install` finds the lock already up to date and
    // installs straight from it without re-resolving against current
    // conda-forge/PyPI -- which is what let setuptools drift to a
    // pkg_resources-less 81.x. That equivalence holds only while the two files
    // stay in step: regenerate via tools/regen-pixi-lock.sh whenever pixi.toml
    // changes, and commit both together.
    private static final String SCRIPTS_BASE = RESOURCE_BASE + "scripts/";
    /**
     * Default environment name. The ACTUAL name and manifest come from the
     * selected {@link qupath.ext.qpcat.model.ComputeVariant}, so the CPU and
     * GPU environments live side by side and switching does not overwrite
     * either. Kept for the default case and for older callers.
     */
    private static final String ENV_NAME = "qupath-qpcat";

    /**
     * After a verified build, offer to remove the environment this one replaced.
     *
     * <p>Runs off the initialization thread and never blocks it. Records the
     * current location as the new "last built" REGARDLESS of the answer -- the
     * question is asked once; declining means keep, not ask again next launch.
     */
    private void offerPreviousEnvCleanup() {
        final Path current = getEnvironmentPath();
        final String previous = qupath.ext.qpcat.preferences.QpcatPreferences.getEnvLastBuiltDir();
        qupath.ext.qpcat.preferences.QpcatPreferences.setEnvLastBuiltDir(current.toString());
        if (previous == null || previous.isBlank() || previous.equals(current.toString())) {
            return;
        }
        try {
            javafx.application.Platform.runLater(() -> {
                try {
                    ApposeEnvLocation.promptCleanup(Path.of(previous), current);
                } catch (Exception e) {
                    logger.warn("Previous-environment cleanup prompt failed: {}", e.getMessage());
                }
            });
        } catch (IllegalStateException e) {
            // No FX toolkit (headless / batch): nothing to prompt with. The old
            // directory simply stays, which is the safe outcome.
            logger.info("Previous environment at {} left in place (no UI available)", previous);
        }
    }

    /**
     * The configured environment directory, or null to let Appose use its own
     * default root. Kept next to {@link #getEnvironmentPath()} so the reported
     * path and the built path cannot drift apart.
     */
    private static Path configuredEnvDir() {
        String base = qupath.ext.qpcat.preferences.QpcatPreferences.getEnvBaseDir();
        if (base == null || base.isBlank()) {
            return null;
        }
        return ApposeEnvLocation.resolve(base, variant().envName());
    }

    /** The variant the user has selected (CPU unless they chose otherwise). */
    private static qupath.ext.qpcat.model.ComputeVariant variant() {
        return qupath.ext.qpcat.model.ComputeVariant.fromId(
                qupath.ext.qpcat.preferences.QpcatPreferences.getEnvVariant());
    }

    /**
     * Expected environment version. Must match ENVIRONMENT_VERSION in init_services.py
     * and the version in build.gradle.kts.
     */
    static final String EXPECTED_ENV_VERSION = "0.2.7";

    /**
     * Per-package minimum versions the code actually depends on, independent of
     * the coarse env-version string. run_clustering.py imports
     * {@code from sklearn.cluster import HDBSCAN}, which only exists in
     * scikit-learn >= 1.3; an older wheel (e.g. from a partially-rebuilt env)
     * passes the env-version check but fails at the first HDBSCAN run. Assert it
     * up front with an actionable message. Keep this list to versions we can
     * state with confidence -- a spurious minimum warning is worse than none.
     */
    static final String MIN_SKLEARN_VERSION = "1.3";

    private static ApposeClusteringService instance;

    /**
     * Serializes task execution on the shared Python worker.
     *
     * <p>Appose runs one Python thread per task inside ONE interpreter, and this
     * service is a singleton, so two QP-CAT workflows started from two dialogs
     * execute concurrently in the same process. That is not safe with the
     * scripts we ship: run_clustering, cellular_neighborhoods, spatial_stats and
     * regenerate_plots all drive matplotlib through pyplot's GLOBAL
     * current-figure state, and three of them call {@code plt.close("all")}. A
     * concurrent run therefore closes the other's figures, or writes the other's
     * figure under its own filename -- with no error raised, so the first sign
     * of it is a plot that does not match its result.
     *
     * <p>Serializing is the honest fix: there is one interpreter, so there is one
     * queue. It also covers any other shared-global hazard in these scripts
     * rather than only the pyplot one we happened to find. Fair, so a queued
     * workflow is not starved by a stream of short tasks; reentrant, so a task
     * that calls back in cannot deadlock itself.
     *
     * <p>Deliberately NOT taken by {@code withExtensionClassLoader}, which is a
     * generic TCCL helper and not a task runner.
     */
    private static final java.util.concurrent.locks.ReentrantLock TASK_LOCK =
            new java.util.concurrent.locks.ReentrantLock(true);

    /**
     * Takes {@link #TASK_LOCK}, saying so in the log if the caller has to wait.
     * Without that line a queued run looks like a hang -- the dialog sits on
     * "Running..." with nothing to explain why.
     */
    private static void acquireWorker(String scriptName) {
        if (TASK_LOCK.tryLock()) return;
        logger.info("Task '{}' is waiting for the Python worker -- another QP-CAT "
                + "analysis is running. Tasks run one at a time because they share "
                + "a single Python interpreter.", scriptName);
        TASK_LOCK.lock();
    }

    /**
     * Tasks that leave the reuse cache ({@code knn_cache.py}) in place: the
     * clustering run that fills it, and tasks too small for its memory to
     * matter. Every other task frees it first, so what a clustering run kept
     * never adds to a heavy task's peak.
     */
    private static final Set<String> KEEPS_REUSE_CACHE = Set.of(
            "run_clustering", "estimate_spatial_time", "regenerate_plots",
            "compute_thresholds", "run_phenotyping", "system_info", "run_llm_explainer");

    private static final String RELEASE_REUSE_SCRIPT =
            "import knn_cache\ntask.outputs['freed'] = knn_cache.release(reason)\n";

    /** Line in run_clustering.py below which nothing changes the clustered matrix. */
    static final String END_OF_PREPARATION = "# ==== END OF MATRIX PREPARATION ====";

    /** Tasks sent as run_clustering.py's preparation followed by their own script. */
    private static final Set<String> RUN_ON_PREPARED_MATRIX = Set.of("choose_k");

    /** Bytes knn_cache.py holds, as the last clustering run reported. */
    private volatile long reuseHeldBytes;

    private Environment environment;
    // Read outside the synchronized methods by the task path; a restart swaps
    // the reference, so readers must not see a stale one.
    private volatile Service pythonService;
    private volatile boolean initialized;
    private volatile String initError;
    private Thread shutdownHook;
    private Consumer<String> debugListener;

    /**
     * How {@link ApposeTaskRunner} reaches the worker and replaces a stale one.
     * A worker that died since initialization (OOM kill, user killed it) is
     * respawned here rather than failing every task for the rest of the
     * session; the environment is already built, so that costs seconds.
     */
    private final ApposeTaskRunner.WorkerSource workerSource = new ApposeTaskRunner.WorkerSource() {
        @Override
        public Service worker() throws IOException {
            ensureInitialized();
            Service service = pythonService;
            if (service != null && !service.isAlive() && environment != null) {
                logger.warn("QPCAT Python worker is not alive; recreating it before running the task");
                restartWorker(service);
                service = pythonService;
            }
            if (service == null) {
                throw new IOException("QPCAT task cannot start: the Python worker is being replaced. "
                        + "Retry the operation.");
            }
            return service;
        }

        @Override
        public void replaceWorker(Service stale) throws IOException {
            restartWorker(stale);
        }
    };

    // Capability flags reported by init_services.py at verification time.
    // Java reads these to gray out features whose Python deps are missing
    // rather than crashing the whole extension. Default false until init.
    private boolean harmonypyAvailable;
    private boolean banksyAvailable;

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
        return ApposeEnvLocation.isBuilt(getEnvironmentPath());
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

            String expected = ApposeSupport.loadResource(RESOURCE_BASE + variant().tomlResource());
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
        return ApposeEnvLocation.resolve(
                qupath.ext.qpcat.preferences.QpcatPreferences.getEnvBaseDir(),
                variant().envName());
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

            // Manifest AND lock come from the same variant, so they can never
            // be paired wrongly (lockResource() is derived from tomlResource()).
            var computeVariant = variant();
            String pixiToml = ApposeSupport.loadResource(RESOURCE_BASE + computeVariant.tomlResource());
            String pixiLock = ApposeSupport.loadResource(RESOURCE_BASE + computeVariant.lockResource());

            // TCCL must be set for all Appose operations
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(ApposeSupport.extensionClassLoader());

            try {
                long setupStart = System.nanoTime();
                boolean rebuilding = ApposeSupport.syncManifest(getEnvironmentPath(), pixiToml, pixiLock, logger);
                if (rebuilding) {
                    report(statusCallback,
                            "Dependencies changed -- rebuilding environment (this may take several minutes)...");
                    logger.info("Environment rebuild triggered by pixi manifest/lock change");
                } else {
                    report(statusCallback, "Building pixi environment (this may take several minutes)...");
                }

                // Reproducibility comes from the bundled lock staged by
                // syncManifest above: Appose's build() runs `pixi install`,
                // which installs from an up-to-date pixi.lock WITHOUT
                // re-resolving against current conda-forge/PyPI. We do NOT pass
                // .flags("--frozen") here -- Appose injects builder flags as
                // GLOBAL pixi args (`pixi --frozen install ...`), which pixi
                // rejects ("unexpected argument '--frozen'"). The staged
                // consistent lock already pins every version.
                var builder = Appose.pixi()
                        .content(pixiToml)
                        .scheme("pixi.toml")
                        .name(computeVariant.envName())
                        .logDebug()
                        .subscribeOutput(msg -> logger.info("[pixi] {}", msg))
                        .subscribeError(msg -> logger.warn("[pixi] {}", msg));

                // Forward build progress to the status callback if provided
                if (statusCallback != null) {
                    builder.subscribeProgress((msg, step, numSteps) ->
                            report(statusCallback, msg));
                }

                // Builder.base() overrides name(), so pass the FULL
                // <base>/<envName> directory -- the same path getEnvironmentPath()
                // reports and syncManifest() already staged the manifest into.
                // Without this the preference would move where we say the
                // environment is while Appose kept building somewhere else, and
                // the staged lock would never be read.
                Path configuredDir = configuredEnvDir();
                if (configuredDir != null) {
                    Files.createDirectories(configuredDir);
                    builder.base(configuredDir.toFile());
                    logger.info("Building the environment at the configured location: {}",
                            configuredDir);
                }

                long buildStart = System.nanoTime();
                environment = builder.build();

                logger.info("QPCAT Appose environment built ({} ms)", ApposeSupport.elapsedMs(buildStart));
                report(statusCallback, "Starting Python service...");

                long workerStart = System.nanoTime();
                spawnWorker();

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
                        "task.outputs['harmonypy_available'] = HARMONYPY_AVAILABLE\n" +
                        "task.outputs['banksy_available'] = BANKSY_AVAILABLE\n";

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
                Object banksyFlag = verifyTask.outputs.get("banksy_available");
                banksyAvailable = Boolean.TRUE.equals(banksyFlag);
                logger.info("Verified: scikit-learn {}, scanpy {}, umap {}, env {} "
                        + "(harmonypy={}, pybanksy={}); the Python worker started in {} ms, "
                        + "setup took {} ms",
                        sklearnVersion, scanpyVersion, umapVersion, envVersion,
                        harmonypyAvailable ? "available" : "MISSING",
                        banksyAvailable ? "available" : "MISSING",
                        ApposeSupport.elapsedMs(workerStart), ApposeSupport.elapsedMs(setupStart));

                // Version check: warn if environment version doesn't match expected
                if (!EXPECTED_ENV_VERSION.equals(envVersion)) {
                    logger.warn("Environment version mismatch: expected {}, got {}. "
                            + "Some features may not work correctly. "
                            + "Use Setup & help > Rebuild analysis environment to update.",
                            EXPECTED_ENV_VERSION, envVersion);
                    report(statusCallback,
                            "Warning: environment version mismatch (expected "
                            + EXPECTED_ENV_VERSION + ", got " + envVersion
                            + "). Rebuild recommended.");
                }

                // Per-package minimum-version assertion (independent of the coarse
                // env-version string). scikit-learn < 1.3 lacks sklearn.cluster.HDBSCAN,
                // which run_clustering.py imports -- surface it here, not at first run.
                if (!meetsMinVersion(sklearnVersion, MIN_SKLEARN_VERSION)) {
                    logger.warn("scikit-learn {} is older than the required {}; HDBSCAN "
                            + "clustering will fail. Use Setup & help > Rebuild analysis "
                            + "environment.", sklearnVersion, MIN_SKLEARN_VERSION);
                    report(statusCallback,
                            "Warning: scikit-learn " + sklearnVersion + " < "
                            + MIN_SKLEARN_VERSION + " (HDBSCAN unavailable). Rebuild recommended.");
                }

                initialized = true;
                initError = null;
                registerShutdownHook();
                report(statusCallback, "Setup complete! (scikit-learn " + sklearnVersion
                        + ", scanpy " + scanpyVersion + ", env v" + envVersion + ")");
                logger.info("QPCAT Appose service initialized (env v{})", envVersion);

                // The environment is now built AND verified, which is the only
                // point at which it is safe to offer to remove the one it
                // replaced. Off the init thread: a prompt here would block
                // startup behind a question nobody may be sitting in front of.
                offerPreviousEnvCleanup();
            } finally {
                Thread.currentThread().setContextClassLoader(original);
            }

        } catch (Exception e) {
            // Match failure signatures against the FULL cause chain -- pixi's
            // BuildException only says "pixi build failed" at the top; the
            // actionable "failed to link ... os error 32" / "No module named"
            // text lives in a nested cause.
            String fullMsg = ApposeSupport.collectCauseMessages(e);
            initError = e.getMessage();
            initialized = false;
            logger.error("Failed to initialize QPCAT Appose: {}", e.getMessage(), e);

            // Tear down any worker that started before the failure. This both prevents
            // an orphaned Python process and lets the env-wipe below run safely (a live
            // worker holding files under .pixi/ would make the wipe partial / risk the
            // Windows file-lock). Must happen BEFORE looksLikeStaleEnv/wipeEnvForRebuild.
            teardownPythonServiceQuietly();

            // Detect the well-known "stale env" case: the on-disk pixi.toml
            // appears current but the resolved env is missing a required
            // package (most commonly setuptools / pkg_resources, surfaced
            // transitively via the xarray_schema -> spatialdata -> squidpy
            // import chain). Auto-wipe .pixi/ + pixi.lock so the next launch
            // forces a clean Pixi sync.
            if (looksLikeStaleEnv(fullMsg)) {
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
            } else if (ApposeSupport.looksLikeWindowsFileLock(fullMsg)) {
                // v0.3.4: Windows file-lock during pixi link step. Do NOT auto-wipe
                // -- another process is actively holding a file in .pixi/envs/, so
                // a wipe risks corruption. Show the user the recovery steps.
                String advice = ApposeSupport.windowsFileLockAdvice(getEnvironmentPath());
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
     * Creates the Python worker, queues its init script and registers the
     * {@code spatial_stats} and {@code knn_cache} modules. Used by {@link #initialize} and by
     * {@link #restartWorker(Service)}, so a replaced worker has the same
     * globals as the original.
     *
     * <p>Caller must hold the monitor and have the extension classloader set
     * as TCCL. {@code import numpy} must stay the literal first line of the
     * init string to avoid a Windows deadlock (numpy/numpy#24290).
     */
    private void spawnWorker() throws IOException {
        Service fresh = ApposeGpu.pinned(environment).python();

        fresh.debug(msg -> {
            // Filter out Appose protocol messages (EXECUTE requests contain
            // the full script + base64 inputs and can be 25+ MB). Only log
            // Python stderr output (logging, warnings, errors).
            if (ApposeSupport.isProtocolEnvelope(msg)) {
                return;
            }
            // Truncate very long messages (safety net)
            String logMsg = msg.length() > 2000
                    ? msg.substring(0, 2000) + "... [truncated]" : msg;
            logger.info("[QPCAT Python] {}", logMsg);
            Consumer<String> listener = debugListener;
            if (listener != null) {
                listener.accept(logMsg);
            } else {
                // Belt and braces. The console was blank for a whole
                // session whenever this field happened to be unwired,
                // with the same text going to the QuPath log -- so a
                // missing listener must not be a silent data loss.
                logger.debug("Python console listener not wired; line only in the log");
            }
        });

        // Import numpy first to avoid Windows threading deadlock
        // Load model_utils into global scope so task scripts can use
        // detect_device() and the shared helpers without import
        String initScript = "import numpy\n"
                + loadScript("init_services.py") + "\n"
                + loadScript("model_utils.py");
        fresh.init(initScript);
        pythonService = fresh;

        // Register the importable modules in the persistent worker. Task
        // scripts run via exec(compile(..., "<string>")) so they have NO
        // __file__ and the bundled sibling scripts are not on sys.path, so a
        // plain `import spatial_stats` would fail. The first registration is
        // also the task that starts the subprocess, so isAlive() is meaningful
        // on return.
        registerModule(fresh, "spatial_stats",
                "spatial smoothing and spatial statistics may be unavailable");
        // A module outlives a task, which is what lets one clustering run keep
        // its neighbour graph and embedding for the next.
        registerModule(fresh, "knn_cache", "nothing is reused between clustering runs");
        reuseHeldBytes = 0;
        ApposeGpu.checkWorker(fresh, "QPCAT", logger);
    }

    /**
     * Registers a bundled script as an importable module in the worker.
     * Non-fatal: the scripts that import it say so when it is missing. The
     * source travels as an Appose INPUT, not a Java string literal, to avoid
     * escaping issues.
     *
     * @param worker the fresh worker
     * @param name script name without {@code .py}
     * @param ifMissing what the user loses, for the warning
     */
    private void registerModule(Service worker, String name, String ifMissing) {
        try {
            String regScript =
                    "import sys as _sys, types as _types\n" +
                    "_mod = _types.ModuleType(module_name)\n" +
                    "exec(compile(module_source, module_name + '.py', 'exec'), _mod.__dict__)\n" +
                    "_sys.modules[module_name] = _mod\n" +
                    "task.outputs['registered'] = module_name\n";
            Task regTask = worker.task(regScript, Map.of(
                    "module_name", name,
                    "module_source", loadScript(name + ".py")));
            regTask.listen(event -> {
                if (event.responseType == ResponseType.FAILURE
                        || event.responseType == ResponseType.CRASH) {
                    logger.error("{} module registration failed: {}", name, regTask.error);
                }
            });
            regTask.waitFor();
            logger.info("Registered {} as an importable module", name);
        } catch (Exception regEx) {
            logger.warn("Could not register the {} module; {}: {}",
                    name, ifMissing, regEx.getMessage());
        }
    }

    /**
     * Recreates ONLY the Python worker subprocess, killing the stale one first.
     * The pixi environment is untouched; this re-runs the init script and the
     * module registration, which is seconds rather than the minutes a rebuild
     * costs.
     *
     * <p>A no-op when {@code staleWorker} is not the current worker, so two
     * tasks failing on the same stale worker do not tear down each other's
     * replacement. Pass {@code null} to restart unconditionally.
     */
    public synchronized void restartWorker(Service staleWorker) throws IOException {
        if (environment == null) {
            throw new IOException("Cannot restart worker: Appose environment not built");
        }
        if (staleWorker != null && pythonService != staleWorker) {
            logger.info("QPCAT Python worker was already replaced by another thread; skipping redundant restart");
            return;
        }
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(ApposeSupport.extensionClassLoader());
        try {
            long start = System.nanoTime();
            Service stale = pythonService;
            pythonService = null;
            ApposeSupport.stopWorker(stale, 3000, logger);
            spawnWorker();
            logger.info("QPCAT Appose worker restarted in {} ms (fresh Python subprocess)",
                    ApposeSupport.elapsedMs(start));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to restart QPCAT Appose worker: " + e.getMessage(), e);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    /**
     * Runs a named task script with the given inputs.
     *
     * <p>Tasks run one at a time (see {@code TASK_LOCK}). A stale worker is
     * replaced and the task retried, only when no Python ran; the attempt
     * count and delay are the user's preferences. See {@link ApposeTaskRunner}.
     */
    public Task runTask(String scriptName, Map<String, Object> inputs) throws IOException {
        return runTaskWithListener(scriptName, inputs, null, null);
    }

    /**
     * Runs a task with a custom event listener for progress updates.
     */
    public Task runTaskWithListener(String scriptName, Map<String, Object> inputs,
                                    Consumer<org.apposed.appose.TaskEvent> eventListener)
            throws IOException {
        return runTaskWithListener(scriptName, inputs, eventListener, null);
    }

    /**
     * As above, but invokes {@code onTaskCreated} with the live {@link Task}
     * immediately after it is created (before the blocking wait), so the caller
     * can hold a reference to cancel it mid-run. Fires once per retry attempt.
     */
    public Task runTaskWithListener(String scriptName, Map<String, Object> inputs,
                                    Consumer<org.apposed.appose.TaskEvent> eventListener,
                                    Consumer<Task> onTaskCreated)
            throws IOException {
        ensureInitialized();

        String script;
        try {
            script = loadScript(scriptName + ".py");
            if (RUN_ON_PREPARED_MATRIX.contains(scriptName)) {
                script = preparationOf(loadScript("run_clustering.py")) + "\n" + script;
            }
        } catch (IOException e) {
            throw new IOException("Failed to load task script: " + scriptName, e);
        }

        ApposeTaskRunner.Options options = new ApposeTaskRunner.Options()
                .maxAttempts(qupath.ext.qpcat.preferences.QpcatPreferences.getTaskMaxRetries())
                .retryDelayMs(qupath.ext.qpcat.preferences.QpcatPreferences.getTaskRetrySleepMs())
                .listener(eventListener)
                .onCreated(onTaskCreated);
        acquireWorker(scriptName);
        try {
            if (!KEEPS_REUSE_CACHE.contains(scriptName)) {
                releaseReuseCacheLocked("before " + scriptName, false);
            }
            if (!"run_clustering".equals(scriptName)) {
                return ApposeTaskRunner.run("QPCAT", workerSource, scriptName, script, inputs, options);
            }
            Task task;
            try {
                task = ApposeTaskRunner.run("QPCAT", workerSource, scriptName, script, inputs, options);
            } catch (IOException | RuntimeException e) {
                // A failed or cancelled run may have kept something without
                // reporting it, so its size is unknown: free it.
                releaseReuseCacheLocked("the clustering run did not finish", true);
                throw e;
            }
            Object held = task.outputs.get("reuse_held_bytes");
            reuseHeldBytes = held instanceof Number n ? n.longValue() : 0;
            return task;
        } finally {
            TASK_LOCK.unlock();
        }
    }

    /**
     * Bytes the worker keeps between clustering runs for reuse (the last
     * neighbour graph and embedding), as the last run reported. The scaling
     * check counts it, because it stays allocated while the next task runs.
     */
    public long getReuseHeldBytes() {
        return reuseHeldBytes;
    }

    /**
     * Frees what the worker keeps between clustering runs, on a background
     * thread; waits for a running task first. A no-op when nothing is kept.
     *
     * @param reason why, for the log
     */
    public void releaseReuseCache(String reason) {
        if (reuseHeldBytes == 0 || !isAvailable()) return;
        Thread t = new Thread(() -> {
            acquireWorker("release reuse cache");
            try {
                releaseReuseCacheLocked(reason, false);
            } finally {
                TASK_LOCK.unlock();
            }
        }, "QPCAT-ReuseRelease");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Caller holds {@link #TASK_LOCK}.
     *
     * @param force free even when the last report said nothing is kept
     */
    private void releaseReuseCacheLocked(String reason, boolean force) {
        if (reuseHeldBytes == 0 && !force) return;
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(ApposeSupport.extensionClassLoader());
        try {
            ApposeTaskRunner.run("QPCAT", workerSource, "knn_cache release",
                    RELEASE_REUSE_SCRIPT, Map.of("reason", reason),
                    new ApposeTaskRunner.Options().maxAttempts(1));
            reuseHeldBytes = 0;
        } catch (Exception e) {
            logger.warn("Could not free the memory kept for reuse ({}): {}",
                    reason, e.getMessage());
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    /**
     * Best-effort teardown of a partially-started Python worker. Used on the init
     * failure path: if {@code environment.python()} succeeded but a later step threw,
     * the worker process is left alive and orphaned, and a subsequent env wipe would
     * race a live process still holding files under {@code .pixi/} (the exact Windows
     * file-lock we otherwise guard against).
     */
    private void teardownPythonServiceQuietly() {
        Service service = pythonService;
        pythonService = null;
        ApposeSupport.stopWorker(service, 500, logger);
    }

    public synchronized void shutdown() {
        Service service = pythonService;
        if (service != null) {
            logger.info("Shutting down QPCAT Python service...");
            ApposeSupport.stopWorker(service, 5000, logger);
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
            ApposeSupport.deleteDirectoryRecursively(envPath);
        }
    }

    public boolean isAvailable() {
        return initialized && initError == null && pythonService != null;
    }

    /** True while another task holds the Python worker (see {@code TASK_LOCK}). */
    public boolean isBusy() {
        return TASK_LOCK.isLocked();
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
     * Whether pybanksy imported cleanly in the worker, so BANKSY clustering can
     * actually run.
     *
     * <p>Probed at init like harmonypy. Before this existed the verification
     * script did not mention banksy at all, so an environment with a broken
     * pybanksy verified as healthy and the user only discovered it after
     * configuring and launching a BANKSY run.
     */
    public boolean isBanksyAvailable() {
        return initialized && banksyAvailable;
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
        return ApposeSupport.withExtensionClassLoader(callable);
    }

    // ==================== Internal Helpers ====================

    private static void report(Consumer<String> callback, String message) {
        if (callback != null) callback.accept(message);
    }

    /**
     * True if {@code actual} is >= {@code minimum} by dotted numeric comparison
     * (e.g. "1.3.2" >= "1.3"). Non-numeric / pre-release suffixes on a component
     * are ignored (only the leading integer of each component is compared). On
     * any parse failure this returns {@code true} so an unrecognized version
     * string never blocks or spuriously warns.
     */
    static boolean meetsMinVersion(String actual, String minimum) {
        if (actual == null || minimum == null) return true;
        try {
            String[] a = actual.split("\\.");
            String[] m = minimum.split("\\.");
            int n = Math.max(a.length, m.length);
            for (int i = 0; i < n; i++) {
                int av = i < a.length ? leadingInt(a[i]) : 0;
                int mv = i < m.length ? leadingInt(m[i]) : 0;
                if (av != mv) return av > mv;
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    /** Leading integer of a version component ("2rc1" -> 2, "" -> 0). */
    private static int leadingInt(String s) {
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        return i == 0 ? 0 : Integer.parseInt(s.substring(0, i));
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
                ApposeSupport.deleteDirectoryRecursively(pixiDir);
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

    private void ensureInitialized() throws IOException {
        if (!isAvailable()) {
            throw new IOException("QPCAT service is not available"
                    + (initError != null ? ": " + initError : ""));
        }
    }

    /**
     * The part of run_clustering.py that builds {@code cluster_matrix}.
     *
     * @param runScript the full run_clustering.py source
     * @return the source above {@link #END_OF_PREPARATION}
     * @throws IOException when the marker is missing or appears more than once
     */
    static String preparationOf(String runScript) throws IOException {
        int at = runScript.indexOf(END_OF_PREPARATION);
        if (at < 0 || runScript.indexOf(END_OF_PREPARATION, at + 1) >= 0) {
            throw new IOException("run_clustering.py must contain exactly one line '"
                    + END_OF_PREPARATION + "'");
        }
        return runScript.substring(0, at);
    }

    String loadScript(String scriptFileName) throws IOException {
        return ApposeSupport.loadResource(SCRIPTS_BASE + scriptFileName);
    }

    private void registerShutdownHook() {
        if (shutdownHook != null) return;
        shutdownHook = ApposeSupport.registerShutdownHook("QPCAT-ShutdownHook", () -> pythonService, logger);
    }

    private void removeShutdownHook() {
        ApposeSupport.removeShutdownHook(shutdownHook);
        shutdownHook = null;
    }
}
