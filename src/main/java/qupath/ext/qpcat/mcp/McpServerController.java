package qupath.ext.qpcat.mcp;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.value.ObservableValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.service.OperationLogger;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;

import java.io.IOException;
import java.net.BindException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Lifecycle of the QP-CAT MCP server: off at every launch, started and
 * stopped by the user, stopped by any project change or 60 idle minutes.
 * Holds the only copy of the per-session token. No preference records
 * whether it runs. An administrator disables it for an installation with
 * the JVM property {@code -Dqpcat.mcp.disabled=true}, read once at load.
 */
public final class McpServerController {

    private static final Logger logger = LoggerFactory.getLogger(McpServerController.class);

    public static final int FIRST_PORT = 51516;
    public static final int LAST_PORT = 51525;
    static final long IDLE_TIMEOUT_MINUTES = 60;
    static final int MAX_TOOL_CALLS_PER_MINUTE = 120;
    static final long FX_HOP_TIMEOUT_SECONDS = 15;
    public static final String DISABLED_PROPERTY = "qpcat.mcp.disabled";
    private static final boolean DISABLED_BY_POLICY = readDisabledByPolicy(System.getProperties());

    static final String DISABLED_TEXT =
            "The MCP server is disabled on this installation (" + DISABLED_PROPERTY + "=true).";

    static final String NO_PROJECT_TEXT =
            "No project is open, so there are no saved clustering results to serve.\n"
            + "\n"
            + "Open a project (File > Open Project), then turn the MCP server on again.\n"
            + "\n"
            + "The server was NOT started.";

    static final String PORTS_EXHAUSTED_TEXT =
            "Could not start the MCP server: ports " + FIRST_PORT + " to " + LAST_PORT
            + " are all in use on this\ncomputer.\n"
            + "\n"
            + "Something else is listening there -- most likely another copy of QuPath with\n"
            + "this server already on, or the qupath-mcp extension. Check for a second QuPath\n"
            + "window, stop its MCP server, and try again.\n"
            + "\n"
            + "The server was NOT started.";

    static final String INSTRUCTIONS =
            "QP-CAT (QuPath Cell Analysis Tools) serves the saved clustering results of the "
            + "project currently open in QuPath. Workflow: call qpcat_list_results, pick a result "
            + "name, call qpcat_explain_prompt with it, answer the returned prompt yourself in the "
            + "JSON shape it asks for, then call qpcat_set_explanations with the result name, your "
            + "full reply, a short agent label, and the prompt_hash (plus any top_n or cluster_ids "
            + "you passed) from the prompt's metadata block, so the stored record is the prompt you "
            + "answered. The tools read saved results and write only their explanations field. They "
            + "cannot run analyses, read images or cells, execute code, or touch files outside the "
            + "project's results folder.";

    private static final McpServerController INSTANCE = new McpServerController();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ReadOnlyBooleanWrapper running = new ReadOnlyBooleanWrapper(this, "running", false);
    private final ReadOnlyIntegerWrapper toolCallCount = new ReadOnlyIntegerWrapper(this, "toolCallCount", 0);
    private final ReadOnlyStringWrapper lastEvent = new ReadOnlyStringWrapper(this, "lastEvent", "off");

    private volatile boolean runningFlag;
    private volatile String token;
    private volatile Instant startedAt;
    private volatile int port;
    private volatile long lastActivityNanos;
    /** The project being served; start/stop audit lines go into ITS log, even after it closes. */
    private volatile Project<?> servedProject;
    private McpHttpEndpoint endpoint;
    private McpJsonRpc rpc;
    private ScheduledExecutorService idleTimer;
    private volatile Supplier<Project<?>> projectSupplier = () -> {
        QuPathGUI gui = QuPathGUI.getInstance();
        return gui != null ? gui.getProject() : null;
    };

    private McpServerController() { }

    public static McpServerController getInstance() {
        return INSTANCE;
    }

    public ReadOnlyBooleanProperty runningProperty() { return running.getReadOnlyProperty(); }
    public boolean isRunning() { return runningFlag; }
    public ReadOnlyIntegerProperty toolCallCountProperty() { return toolCallCount.getReadOnlyProperty(); }
    public ReadOnlyStringProperty lastEventProperty() { return lastEvent.getReadOnlyProperty(); }

    /** @return the bound port, or 0 when stopped */
    public int getPort() { return runningFlag ? port : 0; }

    /** @return {@code http://127.0.0.1:<port>/mcp}, or null when stopped */
    public String getUrl() { return runningFlag ? "http://127.0.0.1:" + port + McpHttpEndpoint.PATH : null; }

    /** @return the bearer token for this run, or null when stopped; never logged */
    public String getToken() { return token; }

    /** @return when the server started, or null when stopped */
    public Instant getStartedAt() { return startedAt; }

    /**
     * @return true when the launcher set {@code -Dqpcat.mcp.disabled=true}; read once
     *         at class load, so a script cannot flip it at runtime
     */
    public static boolean isDisabledByPolicy() { return DISABLED_BY_POLICY; }

    /** Pure form of {@link #isDisabledByPolicy()} for tests; true only for the word "true". */
    static boolean readDisabledByPolicy(java.util.Properties props) {
        String v = props.getProperty(DISABLED_PROPERTY);
        return v != null && v.trim().equalsIgnoreCase("true");
    }

    /**
     * Binds the endpoint on loopback and starts serving. Picks {@link #FIRST_PORT}
     * or the next free port up to {@link #LAST_PORT}, and generates a fresh token.
     *
     * @throws IOException with a user-readable message when the installation disables the
     *                     server, no project is open, QuPath's interface thread does not
     *                     answer, or every port is taken; nothing is left running in that case
     */
    public synchronized void start() throws IOException {
        if (runningFlag) return;
        if (DISABLED_BY_POLICY) {
            logger.info("MCP server start refused: {}=true", DISABLED_PROPERTY);
            throw new IOException(DISABLED_TEXT);
        }
        Project<?> project;
        try {
            project = currentProject();
        } catch (McpExplainerTools.ProjectUnavailableException fxBlocked) {
            throw new IOException(fxBlocked.getMessage(), fxBlocked);
        }
        if (project == null) {
            throw new IOException(NO_PROJECT_TEXT);
        }
        String freshToken = newToken();
        String version = GeneralTools.getPackageVersion(McpServerController.class);
        if (version == null || version.isBlank()) version = "dev";
        McpExplainerTools tools = new McpExplainerTools(this::currentProject);
        McpJsonRpc jsonRpc = new McpJsonRpc(version, INSTRUCTIONS, tools.tools(),
                MAX_TOOL_CALLS_PER_MINUTE, this::onToolCall);

        McpHttpEndpoint bound = null;
        int boundPort = -1;
        for (int candidate = FIRST_PORT; candidate <= LAST_PORT && bound == null; candidate++) {
            try {
                bound = new McpHttpEndpoint(candidate, freshToken, jsonRpc, this::touch);
                boundPort = candidate;
            } catch (BindException inUse) {
                logger.info("MCP port {} is in use; trying the next one", candidate);
            } catch (IOException e) {
                logger.warn("MCP bind on port {} failed: {}", candidate, e.toString());
            }
        }
        if (bound == null) {
            logger.warn("Failed to start MCP endpoint: no free port in {}-{}", FIRST_PORT, LAST_PORT);
            throw new IOException(PORTS_EXHAUSTED_TEXT);
        }

        this.endpoint = bound;
        this.rpc = jsonRpc;
        this.servedProject = project;
        this.token = freshToken;
        this.port = boundPort;
        this.startedAt = Instant.now();
        this.lastActivityNanos = System.nanoTime();
        this.runningFlag = true;
        startIdleTimer();
        String url = getUrl();
        logger.info("MCP endpoint listening on {}", url);
        if (boundPort != FIRST_PORT) {
            logger.info("MCP port {} was in use; bound {} instead", FIRST_PORT, boundPort);
        }
        final int finalPort = boundPort;
        auditEvent(project, "started on 127.0.0.1 port " + finalPort + " (loopback only; the token is not recorded)");
        fx(() -> {
            toolCallCount.set(0);
            lastEvent.set("started on port " + finalPort);
            running.set(true);
        });
    }

    /**
     * Stops the server, forgets every session and clears the token. Idempotent.
     *
     * @param reason short ASCII reason for the log and the last-event text
     */
    public synchronized void stop(String reason) {
        if (!runningFlag && endpoint == null) return;
        String why = reason != null ? reason : "requested";
        if (endpoint != null) {
            endpoint.close();
            endpoint = null;
        }
        if (rpc != null) {
            rpc.terminateSessions();
            rpc = null;
        }
        if (idleTimer != null) {
            idleTimer.shutdownNow();
            idleTimer = null;
        }
        token = null;
        startedAt = null;
        runningFlag = false;
        logger.info("MCP endpoint stopped (reason: {})", why);
        auditEvent(servedProject, "stopped (reason: " + why + ")");
        servedProject = null;
        fx(() -> {
            running.set(false);
            lastEvent.set("stopped: " + why);
        });
    }

    /**
     * Stops the server whenever the open project changes. Call once at install.
     *
     * @param qupath the GUI whose project property to follow
     */
    public void bindToProject(QuPathGUI qupath) {
        bindToProjectProperty(qupath.projectProperty());
    }

    /** As {@link #bindToProject(QuPathGUI)}, for any project-valued observable. */
    void bindToProjectProperty(ObservableValue<? extends Project<?>> property) {
        this.projectSupplier = property::getValue;
        property.addListener((obs, oldProject, newProject) -> {
            if (runningFlag) {
                stop("project changed");
            }
        });
    }

    /**
     * Fetches the open project on the FX thread; null when none is open.
     *
     * @throws McpExplainerTools.ProjectUnavailableException when the FX thread did not
     *         answer within {@link #FX_HOP_TIMEOUT_SECONDS}; this is not "no project"
     */
    Project<?> currentProject() {
        try {
            return onFx(() -> projectSupplier.get());
        } catch (java.util.concurrent.TimeoutException e) {
            logger.warn("The FX thread did not answer within {} s; cannot read the open project",
                    FX_HOP_TIMEOUT_SECONDS);
            throw new McpExplainerTools.ProjectUnavailableException(FX_HOP_TIMEOUT_SECONDS);
        } catch (Exception e) {
            logger.warn("Could not read the open project: {}", e.toString());
            return null;
        }
    }

    /** One line in that project's QP-CAT operation log; a no-op when the project is null. */
    private static void auditEvent(Project<?> project, String description) {
        try {
            OperationLogger.getInstance().logEvent(project, "MCP SERVER", description);
        } catch (RuntimeException e) {
            logger.debug("MCP audit line not written: {}", e.toString());
        }
    }

    private void onToolCall(String toolName, String outcome) {
        touch();
        fx(() -> {
            toolCallCount.set(toolCallCount.get() + 1);
            lastEvent.set("tool call: " + toolName + " -> " + outcome);
        });
    }

    private void touch() {
        lastActivityNanos = System.nanoTime();
    }

    private void startIdleTimer() {
        idleTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "QPCAT-MCP-idle");
            t.setDaemon(true);
            return t;
        });
        idleTimer.scheduleAtFixedRate(() -> {
            long idleMinutes = (System.nanoTime() - lastActivityNanos) / 60_000_000_000L;
            if (idleMinutes >= IDLE_TIMEOUT_MINUTES) {
                stop("idle for " + IDLE_TIMEOUT_MINUTES + " minutes");
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Runs on the FX thread; inline when already there or when no toolkit is running. */
    static void fx(Runnable r) {
        ExplanationListeners.runOnFx(r);
    }

    /** Evaluates on the FX thread and waits; inline when no toolkit is running. */
    static <T> T onFx(Callable<T> work) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return work.call();
        }
        FutureTask<T> task = new FutureTask<>(work);
        try {
            Platform.runLater(task);
        } catch (IllegalStateException noToolkit) {
            return work.call();
        }
        return task.get(FX_HOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
