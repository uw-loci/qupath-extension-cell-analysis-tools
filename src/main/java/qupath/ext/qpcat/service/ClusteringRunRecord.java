package qupath.ext.qpcat.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.lib.common.GeneralTools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Writes the two reproducibility artifacts that sit next to an auto-saved
 * clustering result, so a run can be reproduced without guesswork:
 *
 * <ul>
 *   <li>{@code <name>_config.json} -- the exact {@link ClusteringConfig} as JSON
 *       (same format the dialog's Save Config writes). Reload it in the Run
 *       Clustering dialog via <b>Load Config from file...</b> and click Run.</li>
 *   <li>{@code <name>_RUN_INFO.txt} -- a human-readable record of every parameter
 *       plus the three ways to reproduce the run.</li>
 * </ul>
 *
 * <p>These complement -- they do not replace -- the auto-saved {@code <name>.json}
 * result (reopened via "View Past Results") and the headless YAML batch. Best
 * effort: a failure to write either file never fails the run.</p>
 */
public final class ClusteringRunRecord {

    private static final Logger logger = LoggerFactory.getLogger(ClusteringRunRecord.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ClusteringRunRecord() {}

    /**
     * Write {@code <savedName>_config.json} and {@code <savedName>_RUN_INFO.txt}
     * into {@code resultsDir}.
     *
     * @param resultsDir the project's clustering-results directory
     * @param savedName  the sanitized result base name (no extension)
     * @param config     the configuration that produced the run
     * @param result     the computed result (for cluster / cell counts)
     * @param scopeLabel human-readable scope ("Current image: X", "Entire project", etc.)
     */
    public static void write(Path resultsDir, String savedName, ClusteringConfig config,
                             ClusteringResult result, String scopeLabel) {
        if (resultsDir == null || savedName == null || config == null) {
            return;
        }
        try {
            Files.writeString(resultsDir.resolve(savedName + "_config.json"),
                    GSON.toJson(config));
        } catch (Exception e) {
            logger.warn("Could not write reproduce config for '{}': {}", savedName, e.getMessage());
        }
        try {
            Files.writeString(resultsDir.resolve(savedName + "_RUN_INFO.txt"),
                    buildRunInfo(savedName, config, result, scopeLabel));
        } catch (IOException e) {
            logger.warn("Could not write run-info for '{}': {}", savedName, e.getMessage());
        }
    }

    private static String buildRunInfo(String savedName, ClusteringConfig config,
                                       ClusteringResult result, String scopeLabel) {
        String ext = GeneralTools.getPackageVersion(ClusteringRunRecord.class);
        StringBuilder sb = new StringBuilder();
        sb.append("QP-CAT clustering run\n");
        sb.append("=====================\n\n");
        sb.append("Result name : ").append(savedName).append('\n');
        sb.append("Scope       : ").append(scopeLabel != null ? scopeLabel : "(unknown)").append('\n');
        if (result != null) {
            sb.append("Outcome     : ").append(result.getNClusters())
                    .append(" clusters over ").append(result.getNCells()).append(" cells\n");
        }
        sb.append("QP-CAT      : ").append(ext != null ? ext : "(unknown)").append('\n');
        sb.append("QuPath      : ").append(GeneralTools.getVersion()).append("\n\n");

        sb.append("Parameters\n");
        sb.append("----------\n");
        sb.append("Algorithm        : ").append(config.getAlgorithm().getDisplayName())
                .append("  (id: ").append(config.getAlgorithm().getId()).append(")\n");
        sb.append("Algorithm params : ").append(mapToString(config.getAlgorithmParams())).append('\n');
        sb.append("Normalization    : ").append(config.getNormalization().getId()).append('\n');
        sb.append("Embedding        : ").append(config.getEmbeddingMethod().getId());
        if (config.getEmbeddingParams() != null && !config.getEmbeddingParams().isEmpty()) {
            sb.append("  ").append(mapToString(config.getEmbeddingParams()));
        }
        sb.append('\n');
        sb.append("Batch correction : ").append(config.isEnableBatchCorrection()).append('\n');
        sb.append("Spatial smoothing: ").append(config.isEnableSpatialSmoothing());
        if (config.isEnableSpatialSmoothing()) {
            sb.append("  (").append(config.getSpatialSmoothingIterations()).append(" iter, graph: ")
                    .append(config.getSpatialGraphType()).append(")");
        }
        sb.append('\n');
        if (config.isEnableSpatialAnalysis() || config.isAnySpatialStatEnabled()) {
            sb.append("Spatial stats    : graph=").append(config.getSpatialGraphType())
                    .append(" k=").append(config.getSpatialGraphK())
                    .append(" radius=").append(config.getSpatialGraphRadius())
                    .append(" perms=").append(config.getSpatialPermutations())
                    .append(" [nhood/moran=").append(config.isEnableSpatialAnalysis())
                    .append(", ripley=").append(config.isEnableRipley())
                    .append(", geary=").append(config.isEnableGeary())
                    .append(", co-occ pairwise=").append(config.isEnableCoOccurrencePairwise())
                    .append(", co-occ one-vs-rest=").append(config.isEnableCoOccurrenceOneVsRest())
                    .append("]\n");
        }
        sb.append("Measurements (").append(measurementCount(config)).append("):\n");
        if (config.getSelectedMeasurements() != null) {
            for (String m : config.getSelectedMeasurements()) {
                sb.append("  - ").append(m).append('\n');
            }
        }
        sb.append('\n');

        sb.append("How to reproduce this run\n");
        sb.append("-------------------------\n");
        sb.append("1. Re-open the result (no recompute):\n");
        sb.append("   Extensions > QP-CAT > View Past Results... -> '").append(savedName).append("'\n\n");
        sb.append("2. Re-run in the GUI with the same settings:\n");
        sb.append("   Extensions > QP-CAT > Find cell populations (clustering)... ->\n");
        sb.append("   Load Config from file... -> pick '").append(savedName).append("_config.json'\n");
        sb.append("   (in this folder) -> set the Scope -> Run Clustering.\n\n");
        sb.append("3. Re-run headless / in a script (no dialog):\n");
        sb.append("   Use the YAML headless batch -- 'qpcat_batch.groovy'. Translate the\n");
        sb.append("   parameters above into a batch YAML; the schema and a worked example\n");
        sb.append("   are in documentation/YAML_SCHEMA.md and HOW_TO_GUIDE section 19.\n\n");

        sb.append("Note on the QuPath 'Workflow' tab\n");
        sb.append("---------------------------------\n");
        sb.append("The QP-CAT step in the Workflow tab is an informational RECORD (a comment),\n");
        sb.append("by design -- it is recorded on EVERY image processed by the run, including a\n");
        sb.append("note when the labels came from a joint, cross-image run. It is deliberately\n");
        sb.append("not a one-click re-run: an extension could embed a runnable command (e.g.\n");
        sb.append("InstanSeg does), but a naive re-run would silently re-cluster a single image\n");
        sb.append("when the original was multi-image, producing different labels. Reproduce\n");
        sb.append("deliberately via routes 1-3 above; for servers use route 3 (YAML batch).\n");
        return sb.toString();
    }

    private static int measurementCount(ClusteringConfig config) {
        List<String> m = config.getSelectedMeasurements();
        return m == null ? 0 : m.size();
    }

    private static String mapToString(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append('}').toString();
    }
}
