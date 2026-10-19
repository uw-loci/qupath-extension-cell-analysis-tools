package qupath.ext.qpcat.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.mcp.McpJsonRpc.CallContext;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The three MCP tools: list saved results, build the explainer prompt for
 * one, and store an agent's reply as that result's explanations.
 * <p>
 * Every call fetches the project through the supplier (an FX hop) and does
 * all IO and the Appose round trip on the calling handler thread. Argument
 * mistakes are answered by {@link Tool#validate}, which the JSON-RPC layer
 * runs before its rate limiter and its one-in-flight gate.
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

    /** The project could not be read because QuPath's FX thread did not answer in time. */
    public static final class ProjectUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ProjectUnavailableException(long waitedSeconds) {
            super("QuPath's interface thread did not respond within " + waitedSeconds
                    + " s; it is busy or blocked (a long operation, or a dialog waiting for the\n"
                    + "person at QuPath). Nothing has changed. Try again once QuPath responds.");
        }
    }

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern COUNTED_SCOPE =
            Pattern.compile("^\\s*(\\d+)\\s+(?:project\\s+)?(image)s?(?:\\(s\\))?\\b");
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");

    private static final Set<String> EXPLAIN_KEYS = Set.of("result", "top_n", "cluster_ids");
    private static final Set<String> SET_KEYS =
            Set.of("result", "response", "agent", "overwrite", "prompt_hash", "top_n", "cluster_ids");

    private final Supplier<Project<?>> projectSupplier;
    private final LlmExplainerService explainer = new LlmExplainerService();

    /**
     * @param projectSupplier returns the open project or null; it is invoked once per
     *                        tool call and must itself be safe to call off the FX thread;
     *                        it may throw {@link ProjectUnavailableException}
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
                    + "newest first by each result's own timestamp: name, timestamp, summary, "
                    + "scope, size and whether explanations are already stored (hasExplanations). "
                    + "scope is one of \"1 image\", \"N images\" or \"unknown scope\" (a result saved "
                    + "without a scope, such as a YAML batch run); no image name or path is "
                    + "returned. Use a name from this list, exactly, in the other "
                    + "two tools. Takes no arguments and is not rate limited.";
        }
        @Override public JsonObject inputSchema() {
            return objectSchema(new JsonObject(), List.of());
        }
        @Override public boolean rateLimited() { return false; }
        @Override public ToolResult validate(JsonObject args) {
            return unknownKeys(args, Set.of(), LIST_RESULTS);
        }
        @Override public ToolResult call(JsonObject args, CallContext context) throws Exception {
            ToolResult invalid = validate(args);
            if (invalid != null) return invalid;
            Fetched f = fetchProject();
            if (f.error != null) return f.error;
            List<ResultEntry> entries = ClusteringResultManager.listResultEntries(f.project);
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
                o.addProperty("scope", coarseScope(e.scopeLabel));
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
                    + "per-cluster marker rankings and returns the prompt text plus a metadata "
                    + "block with its sha256 (prompt_hash). Answer the prompt yourself, then pass "
                    + "your full reply, the prompt_hash, and the same top_n and cluster_ids to "
                    + SET_EXPLANATIONS + ". Nothing is written by this tool.";
        }
        @Override public JsonObject inputSchema() {
            JsonObject props = new JsonObject();
            props.add("result", stringProp("Saved result name, exactly as listed by " + LIST_RESULTS + "."));
            props.add("top_n", topNProp());
            props.add("cluster_ids", clusterIdsProp());
            return objectSchema(props, List.of("result"));
        }
        @Override public ToolResult validate(JsonObject args) throws Exception {
            return prepare(args, false).error;
        }
        @Override public ToolResult call(JsonObject args, CallContext context) throws Exception {
            Prepared p = prepare(args, false);
            if (p.error != null) return p.error;

            SavedClusteringResult saved = ClusteringResultManager.loadSavedResult(p.project, p.resultName);
            String markers = saved.getMarkerRankingsJson();
            if (markers == null || markers.isBlank()) {
                return ToolResult.error("The result \"" + p.resultName + "\" has no per-cluster marker "
                        + "rankings, so no prompt can be built. Rankings are computed during "
                        + "clustering; a derived or imported result may lack them. Nothing has changed.");
            }
            ToolResult badIds = unknownClusterIds(p.clusterIds, markers, p.resultName);
            if (badIds != null) return badIds;
            ToolResult gate = apposeGate();
            if (gate != null) return gate;

            ExplainRequest req = baseRequest(p, saved, "(prompt only)", context);
            req.markerTableJson = markers;
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
                    + "result: " + p.resultName + "\n"
                    + "top_n: " + req.topN + "\n"
                    + "cluster_ids: " + (p.clusterIds.isEmpty() ? "all" : p.clusterIds.toString()) + "\n"
                    + "Next step: answer the prompt above in the exact JSON shape it asks for, then call "
                    + SET_EXPLANATIONS + " with: result (this name), response (your full reply), agent "
                    + "(a short label for the audit log), prompt_hash (the value above; required), and the same "
                    + "top_n and cluster_ids you passed here, if any. QP-CAT rebuilds the prompt from "
                    + "those and refuses the write if its hash differs from prompt_hash, so the record "
                    + "it stores is the prompt you answered.";
            return ToolResult.ok(res.prompt, meta);
        }
    }

    private final class SetExplanations implements Tool {
        @Override public String name() { return SET_EXPLANATIONS; }
        @Override public String title() { return "Store cluster explanations"; }
        @Override public String description() {
            return "Parses your reply to the prompt from " + EXPLAIN_PROMPT + " and stores the "
                    + "per-cluster phenotype suggestions on that saved result, with your agent "
                    + "label in the audit log. prompt_hash is required: pass it back from that "
                    + "prompt's metadata block, with any top_n / cluster_ids you used; the write is "
                    + "refused if the prompt rebuilt from them hashes differently. Refuses to replace existing "
                    + "explanations unless overwrite is the JSON boolean true. Writes nothing else.";
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
                    + "on the result (default false). Must be a JSON boolean.");
            props.add("overwrite", ow);
            props.add("prompt_hash", stringProp("Required. The prompt_hash from " + EXPLAIN_PROMPT
                    + "'s metadata block (sha256 hex). The write is refused when the prompt "
                    + "rebuilt for this result, top_n and cluster_ids hashes differently."));
            props.add("top_n", topNProp());
            props.add("cluster_ids", clusterIdsProp());
            return objectSchema(props, List.of("result", "response", "agent", "prompt_hash"));
        }
        @Override public ToolResult validate(JsonObject args) throws Exception {
            return prepare(args, true).error;
        }
        @Override public ToolResult call(JsonObject args, CallContext context) throws Exception {
            Prepared p = prepare(args, true);
            if (p.error != null) return p.error;

            SavedClusteringResult saved = ClusteringResultManager.loadSavedResult(p.project, p.resultName);
            ExplainRequest req = baseRequest(p, saved, p.agent, context);
            LlmExplanationsBundle existing = saved.hasLlmExplanations() ? saved.getLlmExplanations() : null;
            if (existing != null && !p.overwrite) {
                LlmExplainerService.writeAuditRefusal(req, "the result already holds "
                        + existing.getExplanations().size() + " suggestions written by "
                        + describeSource(existing) + " and overwrite was not true", null, -1);
                return ToolResult.error("The clustering result \"" + p.resultName + "\" already has "
                        + existing.getExplanations().size() + " suggestions, written by "
                        + describeSource(existing) + ". Nothing has changed.\n"
                        + "\n"
                        + "Check with the person at QuPath before replacing them. To replace, call "
                        + "this tool again with overwrite: true (the JSON boolean); the previous "
                        + "suggestions stay in the LLM audit log beside the result.");
            }
            String markers = saved.getMarkerRankingsJson();
            if (markers == null || markers.isBlank()) {
                return ToolResult.error("The result \"" + p.resultName + "\" has no per-cluster marker "
                        + "rankings, so the reply cannot be matched to clusters. Nothing has changed.");
            }
            ToolResult badIds = unknownClusterIds(p.clusterIds, markers, p.resultName);
            if (badIds != null) {
                LlmExplainerService.writeAuditRefusal(req, "cluster_ids named clusters the result does not have", null, -1);
                return badIds;
            }
            ToolResult gate = apposeGate();
            if (gate != null) return gate;

            req.markerTableJson = markers;
            req.pastedResponse = p.response;

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
            if (!p.promptHash.equalsIgnoreCase(res.promptHash)) {
                String how = "top_n=" + req.topN + " and cluster_ids="
                        + (p.clusterIds.isEmpty() ? "all" : p.clusterIds.toString());
                LlmExplainerService.writeAuditRefusal(req, "prompt_hash mismatch: the call sent "
                        + p.promptHash + " but the prompt rebuilt with " + how + " hashes to "
                        + res.promptHash, res, ms);
                return ToolResult.error("The prompt_hash you sent (" + p.promptHash + ") does not match "
                        + "the prompt QP-CAT rebuilds for \"" + p.resultName + "\" with " + how
                        + " (" + res.promptHash + "). Either you answered a prompt built with different "
                        + "top_n / cluster_ids, or the result changed since. Nothing has changed.\n"
                        + "\n"
                        + "Call " + EXPLAIN_PROMPT + " again with the top_n and cluster_ids you want, "
                        + "answer the prompt it returns, and pass its prompt_hash back here.");
            }

            List<ClusterExplanation> rows = res.explanations != null ? res.explanations : List.of();
            List<Integer> dropped = droppedClusterIds(p.response, rows);
            String droppedNote = dropped.isEmpty() ? "" : "Rows for cluster ids " + dropped
                    + " matched no cluster in this result and were dropped.";
            List<Integer> withoutPhenotype = new ArrayList<>();
            for (ClusterExplanation r : rows) {
                if (r.getPhenotype() == null) withoutPhenotype.add(r.getClusterId());
            }
            int answered = rows.size() - withoutPhenotype.size();
            if (answered == 0) {
                LlmExplainerService.writeAuditRefusal(req, "the reply was not the JSON array the prompt "
                        + "asks for: no row with a phenotype matched a cluster of this result"
                        + (dropped.isEmpty() ? "" : "; rows for cluster ids " + dropped + " matched nothing"),
                        res, ms);
                return ToolResult.error("The reply could not be parsed as the JSON array the prompt asks "
                        + "for (one object per cluster with cluster_id, phenotype, confidence, rationale, "
                        + "supporting_markers), or none of its rows named a cluster of this result. "
                        + "Nothing has changed. Send the JSON array itself."
                        + (dropped.isEmpty() ? "" : "\n" + droppedNote));
            }

            if (existing != null) {
                req.replaced = (existing.getProvider() != null ? existing.getProvider() : "UNKNOWN") + "/"
                        + (existing.getModel() != null && !existing.getModel().isBlank()
                                ? existing.getModel() : "(unnamed)")
                        + " written " + (existing.getTimestamp() != null ? existing.getTimestamp() : "(unknown time)")
                        + " (" + existing.getExplanations().size() + " suggestions)";
            }
            if (!dropped.isEmpty()) {
                req.droppedRows = "cluster ids " + dropped + " matched no cluster in this result";
            }

            LlmExplanationsBundle bundle = LlmExplainerService.toBundle(res, Provider.MCP, p.agent);
            ClusteringResultManager.persistLlmExplanations(p.project, p.resultName, bundle);
            LlmExplainerService.writeAuditLog(req, res, ms);
            int notified = ExplanationListeners.notify(p.resultName, bundle);

            StringBuilder sb = new StringBuilder();
            sb.append("Saved ").append(rows.size()).append(" suggestions (").append(answered)
                    .append(" with a phenotype, ").append(withoutPhenotype.size())
                    .append(" the model declined or did not answer) to the clustering result \"")
                    .append(p.resultName).append("\"");
            if (existing != null) {
                sb.append(",\nREPLACING the ").append(existing.getExplanations().size())
                        .append(" suggestions that were already stored there (written by ")
                        .append(describeSource(existing)).append("). The previous ones\n")
                        .append("are still in the LLM audit log beside the result.");
            } else {
                sb.append('.');
            }
            if (!withoutPhenotype.isEmpty()) {
                sb.append("\nClusters left without a phenotype: ").append(withoutPhenotype).append('.');
            }
            if (!dropped.isEmpty()) {
                sb.append('\n').append(droppedNote);
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

    // ------------------------------------------------------- argument checks

    /** Validated arguments of one call, or the refusal to send instead. */
    private static final class Prepared {
        Project<?> project;
        String resultName;
        Integer topN;
        List<Integer> clusterIds = List.of();
        String response;
        String agent;
        boolean overwrite;
        String promptHash;
        ToolResult error;

        static Prepared refuse(String text) {
            Prepared p = new Prepared();
            p.error = ToolResult.error(text);
            return p;
        }
    }

    /** Thrown by the typed-argument readers with the text to send back. */
    private static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;
        Invalid(String text) { super(text); }
    }

    /**
     * Checks argument names and types, then the project and the result name, in that
     * order; the cheapest refusal wins and none of them needs Python.
     */
    private Prepared prepare(JsonObject args, boolean forSet) throws IOException {
        String tool = forSet ? SET_EXPLANATIONS : EXPLAIN_PROMPT;
        ToolResult unknown = unknownKeys(args, forSet ? SET_KEYS : EXPLAIN_KEYS, tool);
        if (unknown != null) return refuseWith(unknown);
        Prepared p = new Prepared();
        try {
            p.resultName = requireString(args, "result");
            if (p.resultName == null) throw new Invalid("The \"result\" argument must be a non-empty string.");
            if (forSet) {
                p.response = requireString(args, "response");
                if (p.response == null) throw new Invalid("The \"response\" argument must be a "
                        + "non-empty string: your full reply to the prompt. Nothing has changed.");
                String agentRaw = requireString(args, "agent");
                if (agentRaw == null) throw new Invalid("The \"agent\" argument must be a non-empty "
                        + "string naming the agent, for the audit log. Nothing has changed.");
                p.agent = sanitizeAgentLabel(agentRaw);
                p.overwrite = readOverwrite(args);
                p.promptHash = readPromptHash(args);
            }
            p.topN = readTopN(args);
            p.clusterIds = readClusterIds(args);
        } catch (Invalid e) {
            return Prepared.refuse(e.getMessage());
        }
        Fetched f = fetchProject();
        if (f.error != null) return refuseWith(f.error);
        p.project = f.project;
        if (!isKnownResult(p.project, p.resultName, tool)) return refuseWith(unknownResult(p.resultName));
        return p;
    }

    private static Prepared refuseWith(ToolResult error) {
        Prepared p = new Prepared();
        p.error = error;
        return p;
    }

    /** The open project, or the text to send when it cannot be had. */
    private record Fetched(Project<?> project, ToolResult error) { }

    private Fetched fetchProject() {
        Project<?> project;
        try {
            project = projectSupplier.get();
        } catch (ProjectUnavailableException fxBlocked) {
            return new Fetched(null, ToolResult.error(fxBlocked.getMessage()));
        }
        if (project == null) return new Fetched(null, ToolResult.error(NO_PROJECT));
        return new Fetched(project, null);
    }

    /** Refuses argument names the schema does not list, naming the accepted ones. */
    static ToolResult unknownKeys(JsonObject args, Set<String> accepted, String tool) {
        List<String> bad = new ArrayList<>();
        for (String k : args.keySet()) {
            if (!accepted.contains(k)) bad.add(k);
        }
        if (bad.isEmpty()) return null;
        bad.sort(null);
        String ok = accepted.isEmpty() ? "none (this tool takes no arguments)" : new TreeSet<>(accepted).toString();
        return ToolResult.error(tool + " does not accept the argument(s) " + bad
                + ". Accepted argument names: " + ok + ". Nothing has changed.");
    }

    private static boolean readOverwrite(JsonObject args) throws Invalid {
        JsonElement e = args.get("overwrite");
        if (e == null || e.isJsonNull()) return false;
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            throw new Invalid("overwrite must be the JSON boolean true or false, not " + describeJson(e)
                    + " (the string \"true\" and the number 1 are refused). Nothing has changed.");
        }
        return e.getAsBoolean();
    }

    /** Required: the 64-character sha256 hex from the explain_prompt metadata block. */
    private static String readPromptHash(JsonObject args) throws Invalid {
        JsonElement e = args.get("prompt_hash");
        if (e == null || e.isJsonNull()) {
            throw new Invalid("The \"prompt_hash\" argument is required: pass the prompt_hash from the "
                    + "metadata block of " + EXPLAIN_PROMPT + " (64-character sha256 hex), so the stored "
                    + "record is the prompt you answered. Nothing has changed.");
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()
                || !SHA256_HEX.matcher(e.getAsString().trim()).matches()) {
            throw new Invalid("prompt_hash must be the 64-character sha256 hex string from the "
                    + "metadata block of " + EXPLAIN_PROMPT + ", not " + describeJson(e) + ". Nothing has changed.");
        }
        return e.getAsString().trim();
    }

    /** null when absent; otherwise an integer of at least 1. */
    private static Integer readTopN(JsonObject args) throws Invalid {
        JsonElement e = args.get("top_n");
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new Invalid("top_n must be a JSON integer, not " + describeJson(e) + ". Nothing has changed.");
        }
        double d = e.getAsDouble();
        if (d != Math.rint(d) || Math.abs(d) > Integer.MAX_VALUE) {
            throw new Invalid("top_n must be a whole number, not " + e.getAsString() + ". Nothing has changed.");
        }
        int n = (int) d;
        if (n < 1) throw new Invalid("top_n must be at least 1. Nothing has changed.");
        return n;
    }

    /** Empty when absent; otherwise the integers, in the order given. */
    private static List<Integer> readClusterIds(JsonObject args) throws Invalid {
        JsonElement e = args.get("cluster_ids");
        if (e == null || e.isJsonNull()) return List.of();
        if (!e.isJsonArray()) {
            throw new Invalid("cluster_ids must be a JSON array of integers, e.g. [0, 3], not "
                    + describeJson(e) + ". Nothing has changed.");
        }
        List<Integer> out = new ArrayList<>();
        for (JsonElement item : e.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()) {
                throw new Invalid("cluster_ids must contain integers only; found " + describeJson(item)
                        + ". Nothing has changed.");
            }
            double d = item.getAsDouble();
            if (d != Math.rint(d) || Math.abs(d) > Integer.MAX_VALUE) {
                throw new Invalid("cluster_ids must contain whole numbers only; found " + item.getAsString()
                        + ". Nothing has changed.");
            }
            out.add((int) d);
        }
        return out;
    }

    /** Refuses cluster ids the marker table does not have, listing the ones it does. */
    private static ToolResult unknownClusterIds(List<Integer> requested, String markersJson, String resultName) {
        if (requested.isEmpty()) return null;
        TreeSet<Integer> known = new TreeSet<>();
        try {
            for (String key : JsonParser.parseString(markersJson).getAsJsonObject().keySet()) {
                try { known.add(Integer.parseInt(key)); } catch (NumberFormatException ignored) { }
            }
        } catch (RuntimeException malformed) {
            return null;   // let the Python side report the table
        }
        List<Integer> missing = new ArrayList<>();
        for (Integer id : requested) {
            if (!known.contains(id) && !missing.contains(id)) missing.add(id);
        }
        if (missing.isEmpty()) return null;
        return ToolResult.error("cluster_ids " + missing + " are not clusters of the result \"" + resultName
                + "\"; its cluster ids are " + known + ". Nothing has changed.");
    }

    static String describeJson(JsonElement e) {
        if (e == null || e.isJsonNull()) return "null";
        if (e.isJsonArray()) return "an array";
        if (e.isJsonObject()) return "an object";
        var p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return "the boolean " + p.getAsBoolean();
        if (p.isNumber()) return "the number " + p.getAsString();
        return "the string \"" + McpHttpEndpoint.describe(p.getAsString()) + "\"";
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The scope of a saved result as a count only: "1 image", "N images", or "unknown scope"
     * when the result was saved without a label (YAML batch runs, older saves). Never the
     * image name the label carries for a single-image run.
     */
    static String coarseScope(String scopeLabel) {
        if (scopeLabel == null || scopeLabel.isBlank()) return "unknown scope";
        Matcher m = COUNTED_SCOPE.matcher(scopeLabel);
        if (m.find()) {
            String n = m.group(1);
            String kind = m.group(2);
            return n + " " + kind + ("1".equals(n) ? "" : "s");
        }
        return "1 image";
    }

    /**
     * Cluster ids in the agent's reply that match no row the parser produced, i.e. rows
     * the result does not have. Empty when the reply is not a JSON array at all.
     */
    static List<Integer> droppedClusterIds(String response, List<ClusterExplanation> rows) {
        Set<Integer> kept = new TreeSet<>();
        for (ClusterExplanation r : rows) kept.add(r.getClusterId());
        TreeSet<Integer> dropped = new TreeSet<>();
        for (int id : replyClusterIds(response)) {
            if (!kept.contains(id)) dropped.add(id);
        }
        return new ArrayList<>(dropped);
    }

    /** cluster_id values of the objects in the reply's JSON array; markdown fences tolerated. */
    static List<Integer> replyClusterIds(String response) {
        List<Integer> ids = new ArrayList<>();
        if (response == null) return ids;
        String text = response.trim();
        if (text.startsWith("```")) {
            int nl = text.indexOf('\n');
            text = nl >= 0 ? text.substring(nl + 1) : "";
            int end = text.lastIndexOf("```");
            if (end >= 0) text = text.substring(0, end);
        }
        JsonElement parsed = null;
        try {
            parsed = JsonParser.parseString(text);
        } catch (RuntimeException notJson) {
            int open = text.indexOf('[');
            int close = text.lastIndexOf(']');
            if (open >= 0 && close > open) {
                try {
                    parsed = JsonParser.parseString(text.substring(open, close + 1));
                } catch (RuntimeException stillNot) {
                    return ids;
                }
            }
        }
        if (parsed == null || !parsed.isJsonArray()) return ids;
        for (JsonElement item : parsed.getAsJsonArray()) {
            if (!item.isJsonObject() || !item.getAsJsonObject().has("cluster_id")) continue;
            JsonElement cid = item.getAsJsonObject().get("cluster_id");
            if (cid.isJsonPrimitive()) {
                try {
                    ids.add((int) Double.parseDouble(cid.getAsString().trim()));
                } catch (NumberFormatException ignored) {
                    // the Python parser drops it too
                }
            }
        }
        return ids;
    }

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

    private static ExplainRequest baseRequest(Prepared p, SavedClusteringResult saved, String agent,
                                              CallContext context) {
        ExplainRequest req = new ExplainRequest();
        req.provider = Provider.MCP;
        req.model = agent;
        req.resultName = p.resultName;
        req.timeoutSec = QpcatPreferences.getLlmTimeoutSec();
        if (p.topN != null) req.topN = p.topN;
        req.clusterIds = p.clusterIds;
        req.imageName = coarseScope(saved.getScopeLabel());
        req.client = context != null && context.client() != null ? context.client() : "";
        req.projectName = "";
        try {
            if (p.project.getPath() != null && p.project.getPath().getParent() != null
                    && p.project.getPath().getParent().getFileName() != null) {
                req.projectName = p.project.getPath().getParent().getFileName().toString();
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
     * @return the result, or null when the cap was hit (the stray call is soft-cancelled
     *         and its result discarded when it eventually finishes)
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
        String when = b.getTimestamp() != null ? b.getTimestamp() : "an unknown time";
        return LlmExplainerService.describeAuthor(b.getProvider(), b.getModel()) + " at " + when;
    }

    private static String requireString(JsonObject args, String key) {
        JsonElement e = args.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return null;
        String s = e.getAsString();
        return s.isBlank() ? null : s;
    }

    private static JsonObject topNProp() {
        JsonObject topN = new JsonObject();
        topN.addProperty("type", "integer");
        topN.addProperty("minimum", 1);
        topN.addProperty("description", "Markers per cluster to include (default 10).");
        return topN;
    }

    private static JsonObject clusterIdsProp() {
        JsonObject ids = new JsonObject();
        ids.addProperty("type", "array");
        JsonObject item = new JsonObject();
        item.addProperty("type", "integer");
        ids.add("items", item);
        ids.addProperty("description", "Cluster ids to explain; omit for all.");
        return ids;
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
