package qupath.ext.qpcat.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apposed.appose.Service.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.ClusterExplanation;
import qupath.ext.qpcat.model.SavedClusteringResult.LlmExplanationsBundle;

import java.io.IOException;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Java-side orchestrator for the LLM Cluster Explainer feature.
 * <p>
 * Builds the Appose task inputs, dispatches through
 * {@link ApposeClusteringService#withExtensionClassLoader(java.util.concurrent.Callable)},
 * reads the explanations + prompt + response back out, and writes a
 * multi-line audit-log entry via {@link OperationLogger#logLargeOperation}.
 * <p>
 * Cancellation semantics are SOFT (per design decision UI Q3): callers set
 * an {@link AtomicBoolean} flag that the Java reader checks AFTER the
 * Appose call returns. The in-flight HTTP request is allowed to drain in
 * the background; we just refuse to write its result into the UI. The
 * Python {@code timeout_sec} input bounds the worst-case latency.
 * <p>
 * Threading: callers must invoke {@link #runExplain} from a non-FX thread.
 * The class is otherwise stateless.
 */
public class LlmExplainerService {

    private static final Logger logger = LoggerFactory.getLogger(LlmExplainerService.class);

    /** Provider enum surfaced in the UI and the audit log. */
    public enum Provider {
        NONE, ANTHROPIC, OLLAMA
    }

    /** Marshalled result of a single explain call. */
    public static class ExplainResult {
        public final List<ClusterExplanation> explanations;
        public final String promptTemplate;
        public final String promptHash;
        public final String prompt;
        public final String responseRaw;
        public final int tokenCount;
        public final String errorType;       // null on success
        public final String errorDetail;     // null on success

        ExplainResult(List<ClusterExplanation> explanations,
                      String promptTemplate, String promptHash,
                      String prompt, String responseRaw, int tokenCount,
                      String errorType, String errorDetail) {
            this.explanations = explanations;
            this.promptTemplate = promptTemplate;
            this.promptHash = promptHash;
            this.prompt = prompt;
            this.responseRaw = responseRaw;
            this.tokenCount = tokenCount;
            this.errorType = errorType;
            this.errorDetail = errorDetail;
        }

        public boolean isSuccess() { return errorType == null; }
    }

    /** Parameters of a single LLM call. */
    public static class ExplainRequest {
        public Provider provider = Provider.NONE;
        public String model = "";
        public String apiKey = "";          // never logged, never persisted
        public String endpoint = "";
        public String markerTableJson = "";
        public int topN = 10;
        public List<Integer> clusterIds = Collections.emptyList(); // empty = all
        public int timeoutSec = 60;
    }

    /**
     * Read the QPCAT_ANTHROPIC_KEY environment variable. Returns an empty
     * string when the var is unset. The key NEVER reaches PathPrefs.
     */
    public static String readEnvAnthropicKey() {
        String v = System.getenv("QPCAT_ANTHROPIC_KEY");
        return v != null ? v.trim() : "";
    }

    /**
     * Run an explain call synchronously on the calling thread.
     * <p>
     * Wraps the Appose dispatch in
     * {@link ApposeClusteringService#withExtensionClassLoader(java.util.concurrent.Callable)}
     * (TCCL workaround) and never blocks on the FX thread by design --
     * callers spawn a daemon thread per
     * {@code ZeroShotPhenotypingDialog.runPhenotyping} pattern.
     *
     * @param req       request parameters
     * @param cancelled soft-cancel flag; checked after the Appose call
     *                  returns. When set, the call's result is discarded
     *                  and a synthetic cancelled ExplainResult is returned.
     */
    public ExplainResult runExplain(ExplainRequest req, AtomicBoolean cancelled)
            throws IOException {
        if (req == null) {
            throw new IllegalArgumentException("ExplainRequest must not be null.");
        }
        if (req.provider == null || req.provider == Provider.NONE) {
            throw new IllegalArgumentException("A provider must be selected.");
        }

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("provider", req.provider.name());
        inputs.put("model", req.model != null ? req.model : "");
        inputs.put("api_key", req.apiKey != null ? req.apiKey : "");
        inputs.put("endpoint", req.endpoint != null ? req.endpoint : "");
        inputs.put("marker_table_json",
                req.markerTableJson != null ? req.markerTableJson : "");
        inputs.put("top_n", Math.max(1, req.topN));
        // cluster_ids: JSON list or "all"
        if (req.clusterIds == null || req.clusterIds.isEmpty()) {
            inputs.put("cluster_ids", "all");
        } else {
            inputs.put("cluster_ids", new Gson().toJson(req.clusterIds));
        }
        inputs.put("timeout_sec", Math.max(5, req.timeoutSec));

        Task task;
        try {
            task = ApposeClusteringService.withExtensionClassLoader(() ->
                    ApposeClusteringService.getInstance()
                            .runTask("run_llm_explainer", inputs));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("LLM explainer dispatch failed: " + e.getMessage(), e);
        }

        if (cancelled != null && cancelled.get()) {
            logger.info("LLM explainer call returned but was cancelled mid-flight; "
                    + "discarding result.");
            return cancelledResult();
        }

        return marshal(task);
    }

    /** Convert a finished Appose task into an ExplainResult. */
    private ExplainResult marshal(Task task) {
        Map<String, Object> out = task.outputs;
        String errorType = stringOrNull(out.get("error_type"));
        String errorDetail = stringOrNull(out.get("error_detail"));
        String prompt = stringOr(out.get("prompt"), "");
        String promptHash = stringOr(out.get("prompt_hash"), "");
        String promptTemplate = stringOr(out.get("prompt_template"),
                "cluster_phenotype_v1");
        String responseRaw = stringOr(out.get("response_raw"), "");
        int tokenCount = -1;
        Object tcObj = out.get("token_count");
        if (tcObj instanceof Number) {
            tokenCount = ((Number) tcObj).intValue();
        }

        List<ClusterExplanation> explanations;
        String explanationsJson = stringOr(out.get("explanations_json"), "");
        if (explanationsJson.isEmpty()) {
            explanations = new ArrayList<>();
        } else {
            explanations = parseExplanations(explanationsJson);
        }

        return new ExplainResult(explanations, promptTemplate, promptHash,
                prompt, responseRaw, tokenCount, errorType, errorDetail);
    }

    private static ExplainResult cancelledResult() {
        return new ExplainResult(new ArrayList<>(), "cluster_phenotype_v1",
                "", "", "", -1, "REQUEST_CANCELLED",
                "Cancelled by user; result discarded.");
    }

    /**
     * Public for unit testing: parse the python-side explanations JSON into
     * model objects. Tolerant of partial / malformed entries.
     */
    public static List<ClusterExplanation> parseExplanations(String json) {
        if (json == null || json.isEmpty()) return new ArrayList<>();
        Type type = new TypeToken<List<Map<String, Object>>>(){}.getType();
        Gson gson = new Gson();
        List<Map<String, Object>> raw;
        try {
            raw = gson.fromJson(json, type);
        } catch (Exception e) {
            logger.warn("Failed to parse explanations_json: {}", e.getMessage());
            return new ArrayList<>();
        }
        List<ClusterExplanation> out = new ArrayList<>();
        if (raw == null) return out;
        for (Map<String, Object> row : raw) {
            ClusterExplanation ex = new ClusterExplanation();
            Object cid = row.get("cluster_id");
            if (cid instanceof Number) {
                ex.setClusterId(((Number) cid).intValue());
            } else if (cid != null) {
                try { ex.setClusterId(Integer.parseInt(cid.toString())); }
                catch (NumberFormatException ignored) {}
            }
            Object phenotype = row.get("phenotype");
            ex.setPhenotype(phenotype != null ? phenotype.toString() : null);
            Object confidence = row.get("confidence");
            ex.setConfidence(ClusterExplanation.Confidence.fromRaw(
                    confidence != null ? confidence.toString() : null));
            Object rationale = row.get("rationale");
            ex.setRationale(rationale != null ? rationale.toString() : "");
            Object markers = row.get("supporting_markers");
            List<String> markerList = new ArrayList<>();
            if (markers instanceof List<?>) {
                for (Object m : (List<?>) markers) {
                    if (m != null) markerList.add(m.toString());
                }
            }
            ex.setSupportingMarkers(markerList);
            out.add(ex);
        }
        return out;
    }

    /**
     * Build the {@link LlmExplanationsBundle} that gets attached to
     * {@code SavedClusteringResult} for persistence.
     */
    public static LlmExplanationsBundle toBundle(ExplainResult result,
                                                   Provider provider,
                                                   String model) {
        LlmExplanationsBundle bundle = new LlmExplanationsBundle();
        bundle.setProvider(provider != null ? provider.name() : "NONE");
        bundle.setModel(model);
        bundle.setPromptTemplate(result.promptTemplate);
        bundle.setPromptHash(result.promptHash);
        bundle.setTimestamp(LocalDateTime.now().toString());
        bundle.setExplanations(result.explanations);
        return bundle;
    }

    /**
     * Write a multi-line audit-log entry for a successful or failed call.
     * The bearer-token redaction lives in the Python error classifier, so
     * by the time we get an errorDetail string here it is already scrubbed;
     * we still avoid logging the API key itself by construction (we never
     * pass it to OperationLogger).
     */
    public static void writeAuditLog(ExplainRequest req, ExplainResult result,
                                      long durationMs) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Provider", req.provider != null ? req.provider.name() : "NONE");
        params.put("Model", req.model != null ? req.model : "");
        if (req.provider == Provider.OLLAMA) {
            params.put("Endpoint", req.endpoint != null ? req.endpoint : "");
        }
        params.put("Prompt template", result.promptTemplate);
        if (result.promptHash != null && !result.promptHash.isEmpty()) {
            params.put("Prompt hash", result.promptHash);
        }
        params.put("Cluster ids",
                req.clusterIds == null || req.clusterIds.isEmpty()
                        ? "all"
                        : req.clusterIds.toString());
        params.put("Top markers", String.valueOf(req.topN));
        params.put("Token count",
                result.tokenCount >= 0 ? String.valueOf(result.tokenCount) : "unknown");

        Map<String, String> blocks = new LinkedHashMap<>();
        blocks.put("Prompt",
                LlmAuditScrubber.scrub(result.prompt != null ? result.prompt : ""));
        blocks.put("Response",
                LlmAuditScrubber.scrub(result.responseRaw != null ? result.responseRaw : ""));

        String summary;
        if (result.isSuccess()) {
            summary = result.explanations.size() + " clusters explained";
        } else {
            params.put("Error type", result.errorType);
            params.put("Error detail",
                    LlmAuditScrubber.scrub(
                            result.errorDetail != null ? result.errorDetail : ""));
            summary = "FAILED: " + result.errorType;
        }
        OperationLogger.getInstance().logLargeOperation(
                "LLM EXPLAIN", params, blocks, summary, durationMs);
    }

    private static String stringOr(Object o, String fallback) {
        return o != null ? o.toString() : fallback;
    }

    private static String stringOrNull(Object o) {
        return o != null ? o.toString() : null;
    }
}
