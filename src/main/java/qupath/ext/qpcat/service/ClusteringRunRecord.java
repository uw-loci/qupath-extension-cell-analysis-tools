package qupath.ext.qpcat.service;

import qupath.ext.qpcat.model.AreaLevelSpec;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.lib.common.GeneralTools;

import qupath.ext.qpcat.model.ClusterNaming;
import qupath.ext.qpcat.model.SavedClusteringResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

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

    /**
     * Write the record for a rename/merge/split copy.
     *
     * <p>An edit copy is not produced by a run, so it has no config of its own and
     * never reached {@link #write}: the copy landed on disk with no human-readable
     * record at all, and the only way to see what an edit did was to diff two JSON
     * files. This states the parent, the operation, and every name that changed.
     *
     * @param resultsDir  directory holding the saved results
     * @param copyName    base name of the copy just written
     * @param sourceName  the result it was made from
     * @param derivedOp   what the edit did ("rename", "merge", "split", ...)
     * @param beforeNames label -&gt; display name BEFORE the edit
     * @param copy        the copy, carrying the labels and the new names
     */
    public static void writeEditRecord(Path resultsDir, String copyName, String sourceName,
                                       String derivedOp, Map<Integer, String> beforeNames,
                                       SavedClusteringResult copy) {
        // The labels are unchanged by an edit, so the parent's config still
        // describes how they were produced. Copy it so the copy is self-contained.
        try {
            Path srcConfig = resultsDir.resolve(sourceName + "_config.json");
            if (Files.exists(srcConfig)) {
                Files.copy(srcConfig, resultsDir.resolve(copyName + "_config.json"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.warn("Could not copy config sidecar for '{}': {}", copyName, e.getMessage());
        }
        try {
            Files.writeString(resultsDir.resolve(copyName + "_RUN_INFO.txt"),
                    buildEditInfo(copyName, sourceName, derivedOp, beforeNames, copy));
        } catch (IOException e) {
            logger.warn("Could not write run info for '{}': {}", copyName, e.getMessage());
        }
    }

    private static String buildEditInfo(String copyName, String sourceName, String derivedOp,
                                        Map<Integer, String> beforeNames,
                                        SavedClusteringResult copy) {
        StringBuilder sb = new StringBuilder();
        sb.append("QP-CAT cluster edit\n");
        sb.append("===================\n\n");
        sb.append("Result name : ").append(copyName).append('\n');
        sb.append("Derived     : ").append(derivedOp != null ? derivedOp : "edit")
                .append(" of '").append(sourceName).append("'\n");
        String ext = GeneralTools.getPackageVersion(ClusteringRunRecord.class);
        sb.append("QP-CAT      : ").append(ext != null ? ext : "(unknown)").append('\n');
        sb.append("QuPath      : ").append(GeneralTools.getVersion()).append("\n\n");

        sb.append("What changed\n");
        sb.append("------------\n");
        sb.append("An edit changes NAMES only. The per-cell cluster labels are identical to\n");
        sb.append("'").append(sourceName).append("', which is why the edit is reversible.\n\n");

        Set<Integer> labels = new TreeSet<>();
        int[] raw = copy.getClusterLabels();
        if (raw != null) {
            for (int lab : raw) {
                if (lab >= 0) labels.add(lab);
            }
        }
        boolean any = false;
        for (int lab : labels) {
            String before = beforeNames != null ? beforeNames.get(lab) : null;
            if (before == null) before = ClusterNaming.defaultName(lab, copy.clusterNameDigits());
            String after = copy.displayNameForLabel(lab);
            if (Objects.equals(before, after)) continue;
            any = true;
            sb.append(String.format("  Cluster %-4d %s  ->  %s%n", lab, quote(before), quote(after)));
        }
        if (!any) {
            sb.append("  (no name differs from the parent)\n");
        }

        // Which names now cover more than one cluster -- the merges, stated as
        // groups rather than left to be inferred from the per-label lines.
        Map<String, List<Integer>> byName = new LinkedHashMap<>();
        for (int lab : labels) {
            byName.computeIfAbsent(copy.displayNameForLabel(lab), n -> new ArrayList<>()).add(lab);
        }
        List<Map.Entry<String, List<Integer>>> groups = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : byName.entrySet()) {
            if (e.getValue().size() > 1) groups.add(e);
        }
        if (!groups.isEmpty()) {
            sb.append("\nMerged groups (one name over several clusters)\n");
            sb.append("---------------------------------------------\n");
            for (Map.Entry<String, List<Integer>> e : groups) {
                sb.append("  ").append(quote(e.getKey())).append("  =  ");
                for (int i = 0; i < e.getValue().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(ClusterNaming.defaultName(
                            e.getValue().get(i), copy.clusterNameDigits()));
                }
                sb.append('\n');
            }
        }

        sb.append("\nUndoing this edit\n");
        sb.append("-----------------\n");
        sb.append("Both results are on disk; neither is deleted by an edit.\n\n");
        sb.append("  Extensions > QP-CAT > Results & populations >\n");
        sb.append("  Modify cell populations (rename, merge, split, sub-cluster)...\n");
        sb.append("    -> select '").append(copyName).append("'\n");
        sb.append("    -> Step back to '").append(sourceName).append("'\n\n");
        sb.append("To split a merge apart without discarding the rest of this edit, select\n");
        sb.append("the merged cluster and use Split... instead of stepping back.\n\n");
        sb.append("The parent's full run record is '").append(sourceName)
                .append("_RUN_INFO.txt' in this\nfolder; the config beside this result is a copy of the parent's.\n");
        return sb.toString();
    }

    private static String quote(String s) {
        return "'" + s + "'";
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
            // Report the number of CLUSTERS, not the row count of clusterStats:
            // a noise row is not a population and counting it made a run that
            // found one cluster read as "3 clusters".
            sb.append("Outcome     : ").append(result.getNRealClusters())
                    .append(" cluster(s) over ").append(result.getNCells()).append(" cells");
            if (result.hasNoise()) {
                sb.append(String.format("  (%d cells, %.1f%%, left unclustered as noise)",
                        result.getNNoiseCells(),
                        100.0 * result.getNNoiseCells() / Math.max(1, result.getNCells())));
            }
            sb.append('\n');
            for (String w : result.getQualityWarnings()) {
                sb.append("WARNING     : ").append(w).append('\n');
            }
        }
        if (result != null && result.getDerivedOp() != null) {
            sb.append("Derived     : ").append(result.getDerivedOp());
            if (result.getDerivedFrom() != null && !result.getDerivedFrom().isBlank()) {
                sb.append("  of '").append(result.getDerivedFrom()).append('\'');
            }
            sb.append('\n');
        }
        sb.append("QP-CAT      : ").append(ext != null ? ext : "(unknown)").append('\n');
        sb.append("QuPath      : ").append(GeneralTools.getVersion()).append("\n\n");

        sb.append("Parameters\n");
        sb.append("----------\n");
        boolean fromObjects =
                config.getAlgorithm() == ClusteringConfig.Algorithm.EXISTING;
        if (fromObjects) {
            sb.append("Source           : current object classifications "
                    + "(no clustering algorithm ran)\n");
            if (result != null && result.getClusterNames() != null) {
                sb.append("Classes (").append(result.getClusterNames().size()).append("):\n");
                for (String name : result.getClusterNames().values()) {
                    sb.append("  - ").append(name).append('\n');
                }
            }
        }
        sb.append("Algorithm        : ").append(config.getAlgorithm().getDisplayName())
                .append("  (id: ").append(config.getAlgorithm().getId()).append(")\n");
        sb.append("Algorithm params : ").append(mapToString(config.getAlgorithmParams())).append('\n');
        sb.append("Normalization    : ").append(config.getNormalization().getId()).append('\n');
        sb.append("Embedding        : ").append(config.getEmbeddingMethod().getId());
        if (config.getEmbeddingParams() != null && !config.getEmbeddingParams().isEmpty()) {
            sb.append("  ").append(mapToString(config.getEmbeddingParams()));
        }
        sb.append('\n');
        sb.append("Batch correction : ").append(config.isEnableBatchCorrection());
        if (config.isEnableBatchCorrection()) {
            sb.append("  (batch key: ").append(config.getBatchKey()).append(')');
        }
        sb.append('\n');
        // Independent areas change the RESULT, not just the runtime: a graph
        // partitioned per TMA core produces different clusters from one built
        // across the whole slide. A record that omitted them would describe a
        // run nobody could reproduce.
        sb.append("Independent areas: ").append(describeAreaLevels(config)).append('\n');
        if (result != null && result.getAreaResolutionSummary() != null) {
            sb.append("      resolved to : ").append(result.getAreaResolutionSummary())
                    .append('\n');
        }
        sb.append("Spatial smoothing: ").append(config.isEnableSpatialSmoothing());
        if (config.isEnableSpatialSmoothing()) {
            sb.append("  (").append(config.getSpatialSmoothingIterations()).append(" iter, graph: ")
                    .append(config.getSpatialGraphType()).append(")");
        }
        sb.append('\n');
        // The precursor changes cluster labels, so a record headed "How to
        // reproduce this run" has to state whether it ran and what it did.
        sb.append("PCA precursor    : ").append(config.isPcaPrecursor());
        if (result != null && result.getPcaPrecursor() != null) {
            sb.append("  (").append(result.getPcaPrecursor()).append(")");
        } else if (config.isPcaPrecursor()) {
            sb.append("  (requested; did not engage -- too few features to reduce)");
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

        if (fromObjects) {
            // The standard steps below would be a false promise here: re-running
            // analyses whatever the objects carry THEN, not what they carried when
            // this was captured. Say so instead of offering steps that lie.
            sb.append("Reproducing this result\n");
            sb.append("-----------------------\n");
            sb.append("This result was read from the classifications on the objects at the\n");
            sb.append("time it was captured. It CANNOT be regenerated by re-running an\n");
            sb.append("algorithm -- there was no algorithm, and the objects may have been\n");
            sb.append("reclassified since.\n\n");
            sb.append("To look at it again without recomputing:\n");
            sb.append("   Extensions > QP-CAT > View Past Results... -> '")
                    .append(savedName).append("'\n\n");
            sb.append("Re-running the analysis on today's classifications will produce a\n");
            sb.append("DIFFERENT result if anything has been renamed, merged, sub-clustered\n");
            sb.append("or reclassified in the meantime.\n\n");
            return sb.toString();
        }

        String subParent = result != null ? result.getSubclusterParentClass() : null;
        if (subParent != null && !subParent.isBlank()) {
            String parentResult = result.getDerivedFrom();
            sb.append("How to reproduce this run\n");
            sb.append("-------------------------\n");
            sb.append("This is a SUB-CLUSTER run: only the cells classified as '")
                    .append(subParent).append("'\n");
            sb.append("were re-clustered, into '").append(subParent).append(".0', '")
                    .append(subParent).append(".1', ...\n\n");
            if (parentResult != null && !parentResult.isBlank()) {
                sb.append("Parent result : ").append(parentResult).append('\n');
                sb.append("Parent class  : ").append(subParent).append("\n\n");
                sb.append("The parent's own record is '").append(parentResult)
                        .append("_RUN_INFO.txt' in this folder.\n");
                sb.append("Re-applying the parent restores '").append(subParent)
                        .append("' over these sub-labels\n");
                sb.append("(Modify cell populations... -> select '").append(parentResult)
                        .append("' -> Put this version\n on the cells).\n\n");
            } else {
                sb.append("Parent class  : ").append(subParent).append('\n');
                sb.append("Parent result : (not recorded -- this run was not launched from a\n");
                sb.append("                saved result, so there is nothing to step back to)\n\n");
            }
            sb.append("1. Re-open the result (no recompute):\n");
            sb.append("   Extensions > QP-CAT > View Past Results... -> '")
                    .append(savedName).append("'\n\n");
            sb.append("2. Re-run from a script. Requires the cells to still carry '")
                    .append(subParent).append("',\n");
            sb.append("   so run it on the parent's labels, not on top of these sub-labels:\n\n");
            sb.append(subclusterScript(savedName, subParent, parentResult, config));
            sb.append('\n');
            sb.append("   Sub-clustering is not expressible in the YAML headless batch; the\n");
            sb.append("   script above is the headless route.\n\n");
            return sb.toString();
        }

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

    /**
     * A runnable Groovy re-run of a sub-cluster, for the run record.
     *
     * <p>Loads the config sidecar written beside this result rather than restating
     * the parameters, so the script cannot drift from what actually ran. The
     * project-wide form is included commented out because the two differ only in
     * scope, and picking the wrong one silently sub-clusters one image when the
     * original covered several.
     *
     * @param savedName    this result's base name; its {@code _config.json} sidecar
     * @param parentClass  the class that was sub-clustered
     * @param parentResult the saved result the class came from, or null
     * @param config       the run's config, used only to pick the scope shown first
     * @return an indented Groovy snippet
     */
    private static String subclusterScript(String savedName, String parentClass,
                                           String parentResult, ClusteringConfig config) {
        String parentArg = (parentResult != null && !parentResult.isBlank())
                ? "\"" + parentResult + "\"" : "null";
        boolean projectWide = config != null && config.isClusterEntireProject();
        StringBuilder g = new StringBuilder();
        g.append("   import qupath.ext.qpcat.controller.ClusteringWorkflow\n");
        g.append("   import qupath.ext.qpcat.service.ClusteringConfigManager\n");
        g.append("   import qupath.ext.qpcat.service.ClusteringResultManager\n");
        g.append("   import qupath.lib.gui.QuPathGUI\n\n");
        g.append("   def qupath = QuPathGUI.getInstance()\n");
        g.append("   def dir = ClusteringResultManager.getResultsDirectory(qupath.getProject())\n");
        g.append("   def config = ClusteringConfigManager.loadConfigFromFile(\n");
        g.append("           dir.resolve(\"").append(savedName).append("_config.json\"))\n");
        g.append("   def workflow = new ClusteringWorkflow(qupath)\n\n");
        String single = "   def result = workflow.runSubclustering(\n"
                + "           \"" + parentClass + "\", " + parentArg + ", config, { println it })\n";
        String project = "   def entries = qupath.getProject().getImageList()\n"
                + "   def result = workflow.runProjectSubclustering(\n"
                + "           \"" + parentClass + "\", entries, " + parentArg
                + ", config, { println it })\n";
        if (projectWide) {
            g.append("   // This run covered project images:\n").append(project);
            g.append("\n   // Current image only:\n").append(comment(single));
        } else {
            g.append("   // This run covered the current image only:\n").append(single);
            g.append("\n   // Across project images:\n").append(comment(project));
        }
        return g.toString();
    }

    /** Comment out every line of a snippet, keeping its indent. */
    private static String comment(String snippet) {
        StringBuilder out = new StringBuilder();
        for (String line : snippet.split("\n", -1)) {
            if (line.isBlank()) continue;
            // Keep the original indent so an uncommented block still reads as code.
            out.append("   // ").append(line.startsWith("   ") ? line.substring(3) : line)
                    .append('\n');
        }
        return out.toString();
    }

    /**
     * Human-readable description of the independent-area levels, e.g.
     * {@code "images > TMA cores > Annotations [Tissue]"}. Always names the
     * implicit images level, so the record shows the whole partition rather
     * than only the part the user configured.
     */
    private static String describeAreaLevels(ClusteringConfig config) {
        List<AreaLevelSpec> levels = config.getAreaLevels();
        if (!config.hasSubImageAreaLevels()) {
            return "images only (one area per image; cells within an image share a graph)";
        }
        StringBuilder sb = new StringBuilder();
        for (AreaLevelSpec spec : levels) {
            if (sb.length() > 0) {
                sb.append(" > ");
            }
            sb.append(spec.toString());
        }
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
