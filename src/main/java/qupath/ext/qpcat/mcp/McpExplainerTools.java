package qupath.ext.qpcat.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.mcp.McpJsonRpc.Tool;
import qupath.ext.qpcat.mcp.McpJsonRpc.ToolResult;
import qupath.ext.qpcat.model.ClusterExplanation;
import qupath.ext.qpcat.model.SavedClusteringResult;
import qupath.ext.qpcat.model.SavedClusteringResult.LlmExplanationsBundle;
import qupath.ext.qpcat.preferences.QpcatPreferences;
import qupath.ext.qpcat.service.ApposeClusteringService;
import qupath.ext.qpcat.service.ClusteringResultManager;
import qupath.ext.qpcat.service.ClusteringResultManager.ResultEntry;
import qupath.ext.qpcat.service.LlmExplainerService;
import qupath.ext.qpcat.service.LlmExplainerService.ExplainRequest;
import qupath.ext.qpcat.service.LlmExplainerService.ExplainResult;
import qupath.ext.qpcat.service.LlmExplainerService.Provider;
import qupath.lib.projects.Project;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The three MCP tools: list saved results, build the explainer prompt for
 * one, and store an agent's reply as that result's explanations.
 * <p>
 * Every call fetches the project through the supplier (an FX hop) and does
 * all IO and the Appose round trip on the calling handler thread.
 */
public final class McpExplainerTools {

    private static final Logger logger = LoggerFactory.getLogger(McpExplainerTools.class);

    public static final String LIST_RESULTS = "qpcat_list_results";
    public static final String EXPLAIN_PROMPT = "qpcat_explain_prompt";
    public static final String SET_EXPLANATIONS = "qpcat_set_explanations";

    /** Must match the tab title in {@code ClusteringDialog}. */
    public static final String EXPLAINER_TAB_TITLE = "Cluster Explainer (LLM) [Experimental]";
    static final String VIEW_PAST_RESULTS_PATH =
            "Extensions > QP-CAT > Results & populations > View Past Results...";
    static final int MAX_AGENT_LABEL = 60;
    static final long EXPLAIN_CAP_SECONDS = 300;

    static final String APPOSE_NOT_RUNNING =
            "QP-CAT's Python environment is not running in this QuPath session, so the\n"
            + "prompt cannot be built. Nothing has changed.\n"
            + "\n"
            + "Ask the person at QuPath to do one of these, then call this tool again:\n"
            + "  - Extensions > QP-CAT > Setup & help > Set up analysis environment\n"
            + "    (first run) -- needed once per machine, and it can take several minutes.\n"
            + "  - Or simply run any QP-CAT clustering, which starts it.\n"
            + "\n"
            + "This tool will not start the environment itself: building it can take minutes\n"
            + "and that is not something a network request should be able to trigger.";

    static final String APPOSE_BUSY =
            "QP-CAT is busy running another analysis and can only run one Python task at a\n"
            + "time, so the prompt cannot be built right now. Nothing has changed.\n"
            + "\n"
            + "Try again when it finishes. A clustering run on a large image can take many\n"
            + "minutes. The person at QuPath can see progress in the QP-CAT progress window.";

    static final String NO_PROJECT =
            "No project is open in QuPath, so there are no saved clustering results to serve.\n"
            + "Nothing has changed. Ask the person at QuPath to open a project and turn the\n"
            + "MCP server on again.";

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    private final Supplier<Project<?>> projectSupplier;
    private final LlmExplainerService explainer = new LlmExplainerService();

    /**
     * @param projectSupplier returns the open project or null; it is invoked once per
     *                        tool call and must itself be safe to call off the FX thread
     */
    public McpExplainerTools(Supplier<Project<?>> projectSupplier) {
        this.projectSupplier = projectSupplier;
    }

    /** @return the three tools, in listing order */
    public List<Tool> tools() {
        return List.of(new ListResults(), new ExplainPrompt(), new SetExplanations());
    }

    // ------------------------------------------------------------------ tools

    private final class ListResults implements Tool {
        @Override public String name() { return LIST_RESULTS; }
        @Override public String title() { return "List saved clustering results"; }
        @Override public String description() {
            return "Lists the saved QP-CAT clustering results of the project open in QuPath, "
                    + "newest first: name, timestamp, summary, scope, size and whether "
                    + "explanations are already stored (hasExplanations). Use a name from this "
                    + "list, exactly, in the other two tools.";
        }
        @Override public JsonObject inputSchema() {
            return objectSchema(new JsonObject(), List.of());
        }
        @Override public ToolResult call(JsonObject args) throws Exception {
            Project<?> project = projectSupplier.get();
            if (project == null) return ToolResult.error(NO_PROJECT);
            List<ResultEntry> entries = ClusteringResultManager.listResultEntries(project);
            if (entries.isEmpty()) {
                return ToolResult.ok("The open project has no saved clustering results yet. "
                        + "The person at QuPath needs to run a QP-CAT clustering first; every "
                        + "run is saved automatically.");
            }
            JsonArray arr = new JsonArray();
            for (ResultEntry e : entries) {
                JsonObject o = new JsonObject();
                o.addProperty("name", e.name);
                o.addProperty("timestamp", e.timestamp);
                o.addProperty("summary", e.summary);
                o.addProperty("scope", e.scopeLabel);
                o.addProperty("autoSaved", e.autoSaved);
                o.addProperty("sizeBytes", e.sizeBytes);
                if (e.derivedFrom != null) o.addProperty("derivedFrom", e.derivedFrom);
                if (e.derivedOp != null) o.addProperty("derivedOp", e.derivedOp);
                o.addProperty("hasExplanations", e.hasExplanations);
                arr.add(o);
            }
            return ToolResult.ok(PRETTY.toJson(arr));
        }
    }

    private final class ExplainPrompt implements Tool {
        @Override public String name() { return EXPLAIN_PROMPT; }
        @Override public String title() { return "Build the cluster-explainer prompt"; }
        @Override public String description() {
            return "Builds QP-CAT's cluster phenotype prompt for one saved result from its "
                    + "per-cluster marker rankings and returns the prompt text and its sha256. "
                    + "Answer the prompt yourself, then pass your full reply to "
                    + SET_EXPLANATIONS + ". Nothing is written by this tool.";
        }
        @Override public JsonObject inputSchema() {
            JsonObject props = new JsonObject();
            props.add("result", stringProp("Saved result name, exactly as listed by " + LIST_RESULTS + "."));
            JsonObject topN = new JsonObject();
            topN.addProperty("type", "integer");
            topN.addProperty("minimum", 1);
            topN.addProperty("description", "Markers per cluster to include (default 10).");
            props.add("top_n", topN);
            JsonObject ids = new JsonObject();
            ids.addProperty("type", "array");
            JsonObject item = new JsonObject();
            item.addProperty("type", "integer");
            ids.add("items", item);
            ids.addProperty("description", "Cluster ids to explain; omit for all.");
            props.add("cluster_ids", ids);
            return objectSchema(props, List.of("result"));
        }
        @Override public ToolResult call(JsonObject args) throws Exception {
            Project<?> project = projectSupplier.get();
            if (project == null) return ToolResult.error(NO_PROJECT);
            String resultName = requireString(args, "result");
            if (resultName == null) return ToolResult.error("The \"result\" argument must be a non-empty string.");
            if (!isKnownResult(project, resultName, EXPLAIN_PROMPT)) return unknownResult(resultName);

            Integer topN = optionalInt(args, "top_n");
            if (topN != null && topN < 1) return ToolResult.error("top_n must be at least 1.");
            List<Integer> clusterIds = optionalIntList(args, "cluster_ids");
            if (clusterIds == null) return ToolResult.error("cluster_ids must be an array of integers.");

            SavedClusteringResult saved = ClusteringResultManager.loadSavedResult(project, resultName);
            String markers = saved.getMarkerRankingsJson();
            if (markers == null || markers.isBlank()) {
                return ToolResult.error("The result \"" + resultName + "\" has no per-cluster marker "
                        + "rankings, so no prompt can be built. Rankings are computed during "
                        + "clustering; a derived or imported result may lack them. Nothing has changed.");
            }
            ToolResult gate = apposeGate();
            if (gate != null) return gate;

            ExplainRequest req = baseRequest(project, resultName, "(prompt only)");
            req.markerTableJson = markers;
            if (topN != null) req.topN = topN;
            req.clusterIds = clusterIds;
            req.pastedResponse = "";

            ExplainResult res = runExplainCapped(req);
            if (res == null) return timedOut("built");
            if (!res.isSuccess()) {
                return ToolResult.error("Could not build the prompt: " + res.errorType
                        + (res.errorDetail != null ? " -- " + res.errorDetail : "")
                        + ". Nothing has changed.");
            }
            if (res.prompt == null || res.prompt.isBlank()) {
                return ToolResult.error("The prompt came back empty. Nothing has changed.");
            }
            String meta = "prompt_hash: " + res.promptHash + "\n"
                    + "prompt_template: " + res.promptTemplate + "\n"
                    + "result: " + resultName + "\n"
                    + "clusters: " + (clusterIds.isEmpty() ? "all" : clusterIds.toString()) + "\n"
                    + "Next step: answer the prompt above in the exact JSON shape it asks for, then call "
                    + SET_EXPLANATIONS + " with this result name, your full reply as \"response\", "
                    + "and a short \"agent\" label for the audit log.";
            return ToolResult.ok(res.prompt, meta);
        }
    }

    private final class SetExplanations implements Tool {
        @Override public String name() { return SET_EXPLANATIONS; }
        @Override public String title() { return "Store cluster explanations"; }
        @Override public String description() {
            return "Parses your reply to the prompt from " + EXPLAIN_PROMPT + " and stores the "
                    + "per-cluster phenotype suggestions on that saved result, with your agent "
                    + "label in the audit log. Refuses to replace existing explanations unless "
                    + "overwrite is true. Writes nothing else.";
        }
        @Override public JsonObject inputSchema() {
            JsonObject props = new JsonObject();
            props.add("result", stringProp("Saved result name, exactly as listed by " + LIST_RESULTS + "."));
            props.add("response", stringProp("Your full reply to the prompt (the JSON array; "
                    + "markdown fences are tolerated)."));
            props.add("agent", stringProp("Short label naming the agent and model, e.g. "
                    + "\"Claude Code (claude-opus-5-5)\". Recorded in the audit log."));
            JsonObject ow = new JsonObject();
            ow.addProperty("type", "boolean");
            ow.addProperty("description", "Set true to replace explanations that already exist "
                    + "on the result (default false).");
            props.add("overwrite", ow);
            return objectSchema(props, List.of("result", "response", "agent"));
        }
        @Override public ToolResult call(JsonObject args) throws Exception {
            Project<?> project = projectSupplier.get();
            if (project == null) return ToolResult.error(NO_PROJECT);
            String resultName = requireString(args, "result");
            if (resultName == null) return ToolResult.error("The \"result\" argument must be a non-empty string.");
            if (!isKnownResult(project, resultName, SET_EXPLANATIONS)) return unknownResult(resultName);
            String response = requireString(args, "response");
            if (response == null) return ToolResult.error("The \"response\" argument must be a "
                    + "non-empty string: your full reply to the prompt. Nothing has changed.");
            String agentRaw = requireString(args, "agent");
            if (agentRaw == null) return ToolResult.error("The \"agent\" argument must be a non-empty "
                    + "string naming the agent, for the audit log. Nothing has changed.");
            String agent = sanitizeAgentLabel(agentRaw);
            boolean overwrite = args.has("overwrite") && args.get("overwrite").isJsonPrimitive()
                    && args.get("overwrite").getAsJsonPrimitive().isBoolean()
                    && args.get("overwrite").getAsBoolean();

            SavedClusteringResult saved = ClusteringResultManager.loadSavedResult(project, resultName);
            LlmExplanationsBundle existing = saved.hasLlmExplanations() ? saved.getLlmExplanations() : null;
            if (existing != null && !overwrite) {
                return ToolResult.error("The clustering result \"" + resultName + "\" already has "
                        + existing.getExplanations().size() + " suggestions, written by "
                        + describeSource(existing) + ". Nothing has changed.\n"
                        + "\n"
                        + "Check with the person at QuPath before replacing them. To replace, call "
                        + "this tool again with overwrite: true; the previous suggestions stay in the "
                        + "LLM audit log beside the result.");
            }
            String markers = saved.getMarkerRankingsJson();
            if (markers == null || markers.isBlank()) {
                return ToolResult.error("The result \"" + resultName + "\" has no per-cluster marker "
                        + "rankings, so the reply cannot be matched to clusters. Nothing has changed.");
            }
            ToolResult gate = apposeGate();
            if (gate != null) return gate;

            ExplainRequest req = baseRequest(project, resultName, agent);
            req.markerTableJson = markers;
            req.pastedResponse = response;

            long t0 = System.nanoTime();
            ExplainResult res = runExplainCapped(req);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            if (res == null) return timedOut("parsed");
            if (!res.isSuccess()) {
                LlmExplainerService.writeAuditLog(req, res, ms);
                return ToolResult.error("The reply could not be used: " + res.errorType
                        + (res.errorDetail != null ? " -- " + res.errorDetail : "")
                        + ". Nothing has changed.");
            }
            List<ClusterExplanation> rows = res.explanations;
            if (rows == null || rows.isEmpty() || rows.stream().allMatch(r -> r.getPhenotype() == null)) {
                return ToolResult.error("The reply could not be parsed as the JSON array the prompt asks "
                        + "for (one object per cluster with cluster_id, phenotype, confidence, rationale, "
                        + "supporting_markers). Nothing has changed. Send the JSON array itself.");
            }

            LlmExplanationsBundle bundle = LlmExplainerService.toBundle(res, Provider.MCP, agent);
            ClusteringResultManager.persistLlmExplanations(project, resultName, bundle);
            LlmExplainerService.writeAuditLog(req, res, ms);
            int notified = ExplanationListeners.notify(resultName, bundle);

            StringBuilder sb = new StringBuilder();
            sb.append("Saved ").append(rows.size()).append(" suggestions to the clustering result \"")
                    .append(resultName).append("\"");
            if (existing != null) {
                sb.append(",\nREPLACING the ").append(existing.getExplanations().size())
                        .append(" suggestions that were already stored there (written by ")
                        .append(describeSource(existing)).append("). The previous ones\n")
                        .append("are still in the LLM audit log beside the result.");
            } else {
                sb.append('.');
            }
            sb.append("\n\n");
            if (notified > 0) {
                sb.append("A results window showing this result is open in QuPath; its \"")
                        .append(EXPLAINER_TAB_TITLE).append("\" tab has been updated.");
            } else {
                sb.append("No results window is open in QuPath, so there is nothing on screen to update.\n")
                        .append("The person can see them any time: ").append(VIEW_PAST_RESULTS_PATH)
                        .append(", then open that result and go to the\n\"")
                        .append(EXPLAINER_TAB_TITLE).append("\" tab.");
            }
            return ToolResult.ok(sb.toString());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static boolean isKnownResult(Project<?> project, String name, String tool) throws IOException {
        try {
            ClusteringResultManager.requireKnownResult(project, name);
            return true;
        } catch (IllegalArgumentException unknown) {
            logger.debug("MCP tool rejected: unknown result name (tool={})", tool);
            return false;
        }
    }

    private static ToolResult unknownResult(String name) {
        return ToolResult.error("No saved clustering result named \"" + name + "\" in the open project.\n"
                + "\n"
                + "Call " + LIST_RESULTS + " to see the names that exist, and use one of those\n"
                + "exactly -- names are matched exactly, not by prefix.");
    }

    /** Returns the gating error, or null when the Python worker can take a task now. */
    private static ToolResult apposeGate() {
        ApposeClusteringService service = ApposeClusteringService.getInstance();
        if (!service.isAvailable()) return ToolResult.error(APPOSE_NOT_RUNNING);
        if (service.isBusy()) return ToolResult.error(APPOSE_BUSY);
        return null;
    }

    private static ToolResult timedOut(String verb) {
        return ToolResult.error("The Python round trip did not finish within " + (EXPLAIN_CAP_SECONDS / 60)
                + " minutes, so nothing was " + verb + ". Nothing has been written. "
                + "Try again; if it repeats, the person at QuPath should check the QP-CAT log.");
    }

    private static ExplainRequest baseRequest(Project<?> project, String resultName, String agent) {
        ExplainRequest req = new ExplainRequest();
        req.provider = Provider.MCP;
        req.model = agent;
        req.resultName = resultName;
        req.timeoutSec = QpcatPreferences.getLlmTimeoutSec();
        req.projectName = "";
        try {
            if (project.getPath() != null && project.getPath().getParent() != null
                    && project.getPath().getParent().getFileName() != null) {
                req.projectName = project.getPath().getParent().getFileName().toString();
            }
        } catch (RuntimeException ignored) {
            // provenance only
        }
        String user = System.getProperty("user.name");
        req.operatorUser = user != null ? user : "";
        return req;
    }

    /**
     * Runs the Appose round trip on a daemon thread with a wall-clock cap.
     *
     * @return the result, or null when the cap was hit (the stray call is soft-cancelled)
     */
    private ExplainResult runExplainCapped(ExplainRequest req) throws IOException {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        FutureTask<ExplainResult> task = new FutureTask<>(() -> explainer.runExplain(req, cancelled));
        Thread t = new Thread(task, "QPCAT-MCP-explain");
        t.setDaemon(true);
        t.start();
        try {
            return task.get(EXPLAIN_CAP_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            cancelled.set(true);
            logger.warn("MCP explain round trip exceeded {} s; result discarded", EXPLAIN_CAP_SECONDS);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelled.set(true);
            throw new IOException("Interrupted while waiting for the Python worker.");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException(cause != null ? cause.toString() : e.toString(), cause);
        }
    }

    /** Agent label as stored and shown: control characters stripped, at most 60 chars. */
    static String sanitizeAgentLabel(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= 0x20 && c != 0x7f) sb.append(c);
        }
        String s = sb.toString().trim();
        if (s.length() > MAX_AGENT_LABEL) s = s.substring(0, MAX_AGENT_LABEL).trim();
        return s.isEmpty() ? "(unnamed agent)" : s;
    }

    static String describeSource(LlmExplanationsBundle b) {
        String who = b.getModel() != null && !b.getModel().isBlank() ? b.getModel() : "an unnamed source";
        String how = b.getProvider() != null ? b.getProvider() : "UNKNOWN";
        String when = b.getTimestamp() != null ? b.getTimestamp() : "an unknown time";
        return who + " via " + how + " at " + when;
    }

    private static String requireString(JsonObject args, String key) {
        JsonElement e = args.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return null;
        String s = e.getAsString();
        return s.isBlank() ? null : s;
    }

    private static Integer optionalInt(JsonObject args, String key) {
        JsonElement e = args.get(key);
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsInt();
        return -1;
    }

    /** Empty list when absent; null when present but not an integer array. */
    private static List<Integer> optionalIntList(JsonObject args, String key) {
        JsonElement e = args.get(key);
        if (e == null || e.isJsonNull()) return List.of();
        if (!e.isJsonArray()) return null;
        List<Integer> out = new ArrayList<>();
        for (JsonElement item : e.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()) return null;
            out.add(item.getAsInt());
        }
        return out;
    }

    private static JsonObject stringProp(String description) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        o.addProperty("description", description);
        return o;
    }

    private static JsonObject objectSchema(JsonObject properties, List<String> required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        JsonArray req = new JsonArray();
        for (String r : required) req.add(r);
        schema.add("required", req);
        schema.addProperty("additionalProperties", false);
        return schema;
    }
}
