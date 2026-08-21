package qupath.ext.qpcat.batch;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.controller.ClusteringWorkflow;
import qupath.ext.qpcat.service.ApposeClusteringService;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Headless rule-based phenotyping for the YAML batch runner.
 *
 * <p>Mirrors {@link HeadlessClusteringWorkflow}: wraps {@link ClusteringWorkflow}
 * with a {@code null} {@code QuPathGUI} and dispatches through
 * {@link ClusteringWorkflow#runPhenotypingProject}, which already takes an
 * explicit image-entry list and now null-guards its one FX block.
 *
 * <p>Also converts the YAML rule shape into the two JSON payloads the Python task
 * expects. Those shapes differ in a way worth stating: the YAML carries a
 * z-score threshold <em>per rule</em> ({@code require_min_zscore} /
 * {@code exclude_max_zscore}), while {@code run_phenotyping.py} takes one gate
 * <em>per marker</em>, shared by every rule. Where two rules disagree about a
 * marker's threshold the first one wins and the conflict is reported, rather
 * than being resolved silently.
 */
public final class HeadlessPhenotypingWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(HeadlessPhenotypingWorkflow.class);

    /** Outcome of a headless phenotyping run, for the batch progress rows. */
    public static final class Outcome {
        private final int nPhenotypes;
        private final int nCells;
        private final List<String> warnings;

        Outcome(int nPhenotypes, int nCells, List<String> warnings) {
            this.nPhenotypes = nPhenotypes;
            this.nCells = nCells;
            this.warnings = warnings;
        }

        public int getNPhenotypes() { return nPhenotypes; }
        public int getNCells() { return nCells; }
        /** Non-fatal problems (e.g. conflicting per-marker gates). Never null. */
        public List<String> getWarnings() { return warnings; }
    }

    private final ClusteringWorkflow inner;

    public HeadlessPhenotypingWorkflow() {
        this.inner = new ClusteringWorkflow(null);
    }

    /**
     * Apply the YAML rules across the given project images.
     *
     * @param imageEntries  project images to phenotype (size &gt;= 1)
     * @param rules         YAML rule blocks, in priority order (first match wins)
     * @param normalization normalization id, e.g. {@code "zscore"}
     * @param measurements  measurement columns to use; null/empty = discover
     * @param progress      optional free-form progress callback
     */
    public Outcome runPhenotyping(
            List<ProjectImageEntry<BufferedImage>> imageEntries,
            List<BatchYamlSchema.PhenotypeRuleEntry> rules,
            String normalization,
            List<String> measurements,
            Consumer<String> progress) throws IOException {

        if (imageEntries == null || imageEntries.isEmpty()) {
            throw new IOException("HeadlessPhenotypingWorkflow: no images supplied");
        }
        if (rules == null || rules.isEmpty()) {
            throw new IOException("HeadlessPhenotypingWorkflow: no phenotype rules supplied");
        }

        List<String> warnings = new ArrayList<>();
        String rulesJson = buildRulesJson(rules);
        String gatesJson = buildGatesJson(rules, warnings);
        for (String w : warnings) {
            logger.warn("Phenotyping rules: {}", w);
        }

        // The GUI initializes the Appose service at startup; nothing does under
        // `QuPath script`, so start the worker on demand.
        ApposeClusteringService service = ApposeClusteringService.getInstance();
        if (!service.isAvailable()) {
            if (progress != null) progress.accept("Initializing QPCAT service...");
            service.initialize(progress);
        }

        // Measurements are resolved against the images themselves when the YAML
        // does not name them, matching what the dialog does from the open image.
        List<String> selected = (measurements == null || measurements.isEmpty())
                ? null : measurements;

        Map<String, Object> result = inner.runPhenotypingProject(
                imageEntries, selected, normalization, rulesJson, gatesJson, progress);

        int nPhenotypes = asInt(result.get("n_phenotypes"));
        int[] labels = (int[]) result.get("labels");
        return new Outcome(nPhenotypes, labels == null ? 0 : labels.length, warnings);
    }

    private static int asInt(Object o) {
        return (o instanceof Number) ? ((Number) o).intValue() : 0;
    }

    /**
     * YAML rules to the {@code phenotype_rules} payload:
     * {@code [{"cellType": "T_cell", "<marker>": "pos"|"neg", ...}, ...]}.
     * Order is preserved because {@code run_phenotyping.py} evaluates
     * first-match-wins.
     */
    static String buildRulesJson(List<BatchYamlSchema.PhenotypeRuleEntry> rules) {
        List<Map<String, String>> out = new ArrayList<>();
        for (BatchYamlSchema.PhenotypeRuleEntry r : rules) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("cellType", r.getName());
            if (r.getRequireMarkers() != null) {
                for (String marker : r.getRequireMarkers()) m.put(marker, "pos");
            }
            // Written BEFORE exclude so the same conflict warning covers it, and
            // AFTER require so a marker in both lists keeps the stricter "pos"
            // rather than being relaxed into an OR group.
            if (r.getAnyMarkers() != null) {
                for (String marker : r.getAnyMarkers()) {
                    m.putIfAbsent(marker, "anypos");
                }
            }
            if (r.getExcludeMarkers() != null) {
                for (String marker : r.getExcludeMarkers()) {
                    // A marker required positive AND excluded by the same rule is
                    // unsatisfiable; keep "pos" and let the caller see the warning
                    // rather than silently flipping the meaning.
                    m.putIfAbsent(marker, "neg");
                }
            }
            out.add(m);
        }
        return new Gson().toJson(out);
    }

    /**
     * YAML per-rule z-scores to the {@code gates_json} payload:
     * {@code {"<marker>": <threshold>, ...}}.
     * <p>
     * The Python side keeps ONE gate per marker for all rules, so a marker used
     * by several rules with different thresholds cannot be represented exactly.
     * First writer wins and the conflict is recorded in {@code warnings}; the
     * alternative -- last writer wins, silently -- would change which cells match
     * an earlier rule with no trace in the log.
     */
    static String buildGatesJson(List<BatchYamlSchema.PhenotypeRuleEntry> rules,
                                 List<String> warnings) {
        Map<String, Double> gates = new LinkedHashMap<>();
        Set<String> conflicted = new LinkedHashSet<>();
        for (BatchYamlSchema.PhenotypeRuleEntry r : rules) {
            if (r.getRequireMarkers() != null) {
                for (String marker : r.getRequireMarkers()) {
                    record(gates, conflicted, marker, r.getRequireMinZscore(), warnings, r.getName());
                }
            }
            if (r.getExcludeMarkers() != null) {
                for (String marker : r.getExcludeMarkers()) {
                    record(gates, conflicted, marker, r.getExcludeMaxZscore(), warnings, r.getName());
                }
            }
        }
        return new Gson().toJson(gates);
    }

    private static void record(Map<String, Double> gates, Set<String> conflicted,
                               String marker, Double value,
                               List<String> warnings, String ruleName) {
        if (marker == null || value == null) return;
        Double existing = gates.get(marker);
        if (existing == null) {
            gates.put(marker, value);
        } else if (!existing.equals(value) && conflicted.add(marker)) {
            warnings.add("marker '" + marker + "' has conflicting gates across rules ("
                    + existing + " vs " + value + " in rule '" + ruleName
                    + "'); using " + existing + " for every rule");
        }
    }
}
