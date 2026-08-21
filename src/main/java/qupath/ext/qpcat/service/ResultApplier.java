package qupath.ext.qpcat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies clustering results (cluster labels, embeddings) back to
 * QuPath detection objects as classifications and measurements.
 */
public class ResultApplier {

    private static final Logger logger = LoggerFactory.getLogger(ResultApplier.class);

    /** Prefix for cluster classification names. */
    private static final String CLUSTER_PREFIX = "Cluster ";

    /** Measurement name for the cellular-neighborhood id. */
    public static final String NEIGHBORHOOD_MEASUREMENT = "QPCAT CN";

    /** Measurement names for embedding coordinates. */
    private static final String EMBED_1 = "UMAP1";
    private static final String EMBED_2 = "UMAP2";

    /**
     * Applies cluster labels to detections as PathClass classifications.
     * <p>
     * Each detection is assigned a classification like "Cluster 0", "Cluster 1", etc.
     * Noise points (label -1 from HDBSCAN) are classified as "Unclassified".
     *
     * @param detections ordered list of detections (same order as labels)
     * @param labels     cluster label for each detection
     */
    public void applyClusterLabels(List<PathObject> detections, int[] labels) {
        applyClusterLabels(detections, labels, null);
    }

    /**
     * As {@link #applyClusterLabels(List, int[])} but namespaces the class names by
     * {@code namespace} (e.g. a saved-result name) so labels from different results
     * can coexist on the same detections without colliding on a shared "Cluster N".
     * When {@code namespace} is null/blank the classes are the bare "Cluster N".
     *
     * @param namespace class-name namespace, or null for bare "Cluster N"
     */
    public void applyClusterLabels(List<PathObject> detections, int[] labels, String namespace) {
        applyClusterLabelsNamed(detections, labels, label -> clusterClassName(namespace, label));
        logger.info("Applied cluster labels to {} detections{}", labels.length,
                (namespace != null && !namespace.isBlank()) ? " (namespace '" + namespace + "')" : "");
    }

    /**
     * Applies cluster labels to detections, deriving each non-noise label's
     * class name from {@code namer}. Generalizes {@link #applyClusterLabels(List, int[], String)}
     * (whose namer is "Cluster N"/"ns: Cluster N") to arbitrary per-label names --
     * used by "Manage Clusters" to rename ("Cluster 3" -&gt; "Tumor") or merge (two
     * labels -&gt; one name) across a saved result's scope. Noise (label &lt; 0) becomes
     * unclassified. The canonical palette color is seeded once per distinct NAME
     * (keyed on the first label that maps to it), so unrenamed clusters keep their
     * colors and a merged class takes the color of its first constituent; a later
     * {@link #applyClusterColors} pass restores any user-customized colors.
     *
     * @param namer maps a non-negative label to its PathClass name (never null/blank)
     */
    public void applyClusterLabelsNamed(List<PathObject> detections, int[] labels,
                                        java.util.function.IntFunction<String> namer) {
        if (detections.size() != labels.length) {
            throw new IllegalArgumentException(
                    "Detection count (" + detections.size()
                    + ") does not match label count (" + labels.length + ")");
        }
        // Resolve each DISTINCT label to its PathClass once. Doing the string
        // concatenation and PathClass.fromString lookup inside the loop costs one
        // of each per cell -- at a million cells that is a million throwaway
        // strings and map lookups for what is a handful of distinct classes.
        Set<String> replaced = existingClassifications(detections);
        if (!replaced.isEmpty()) {
            logger.warn("Replacing existing classification(s) on {} detection(s): {}. Cluster "
                            + "labels are written as the PathClass, so any earlier "
                            + "classification on these cells is overwritten.",
                    detections.size(), String.join(", ", replaced));
        }

        Map<Integer, PathClass> byLabel = new HashMap<>();
        Set<String> seeded = new HashSet<>();
        for (int i = 0; i < detections.size(); i++) {
            PathObject det = detections.get(i);
            int label = labels[i];
            if (label < 0) {
                // resetPathClass(), NOT setPathClass(getNullClass()): QuPath's
                // PathROIObject logs a deprecation WARN for every call with the
                // null class. HDBSCAN routinely labels tens of thousands of
                // cells as noise, so that path floods the log one line per cell
                // (67,228 lines on a 304k-cell TMA run) and buries the real
                // messages, including this run's own quality warnings.
                det.resetPathClass();
                continue;
            }
            PathClass pc = byLabel.get(label);
            if (pc == null) {
                String name = namer.apply(label);
                pc = PathClass.fromString(name);
                if (seeded.add(name)) {
                    pc.setColor(ClusterPalette.rgbFor(label));
                }
                byLabel.put(label, pc);
            }
            det.setPathClass(pc);
        }
    }


    // ---- Overwrite guards -------------------------------------------------
    //
    // Adding measurements is the product; QUIETLY REPLACING data QP-CAT did not
    // create is not. Neither probe changes anything -- they read, and the caller
    // logs. Refusing outright would break the legitimate case of re-running
    // clustering over its own previous output, which is why these warn instead.

    /**
     * Which of {@code names} already carry a value on at least one detection.
     * <p>
     * Embedding columns are written under generic names ("UMAP1", "PCA1",
     * "tSNE1"), so they can collide with a previous run, another extension, or
     * the user's own script. Read-only.
     */
    public static List<String> preexistingMeasurements(List<PathObject> detections,
                                                       String... names) {
        List<String> found = new ArrayList<>();
        if (detections == null || names == null) {
            return found;
        }
        for (String name : names) {
            for (PathObject det : detections) {
                if (det.getMeasurements().containsKey(name)) {
                    found.add(name);
                    break;
                }
            }
        }
        return found;
    }

    /**
     * Distinct classifications already on these detections, which applying
     * cluster labels will replace. Read-only.
     * <p>
     * No attempt is made to tell "ours" from "theirs": a renamed or merged
     * result carries arbitrary names, so any such test would be wrong exactly
     * when it mattered. The caller reports what is there and lets the user
     * judge -- seeing "Cluster 0, Cluster 1" is obviously a re-run, seeing
     * "Tumor, Stroma" is a warning worth reading.
     */
    public static Set<String> existingClassifications(List<PathObject> detections) {
        Set<String> names = new LinkedHashSet<>();
        if (detections == null) {
            return names;
        }
        for (PathObject det : detections) {
            PathClass pc = det.getPathClass();
            if (pc != null && pc != PathClass.getNullClass()) {
                names.add(pc.toString());
            }
        }
        return names;
    }

    /** Logs a warning naming any measurement keys about to be replaced. */
    private static void warnOnMeasurementOverwrite(List<PathObject> detections, String[] names) {
        List<String> clash = preexistingMeasurements(detections, names);
        if (!clash.isEmpty()) {
            logger.warn("Overwriting {} existing measurement column(s): {}. If these came from "
                            + "another tool or an earlier run you wanted to keep, re-run with a "
                            + "custom embedding name so the new columns get their own prefix.",
                    clash.size(), String.join(", ", clash));
        }
    }

    /**
     * The PathClass name for a cluster label under an optional namespace. Bare
     * "Cluster N" when the namespace is null/blank; otherwise "&lt;namespace&gt;: Cluster N"
     * (a QuPath derived class). The namespace is stripped of the ": " delimiter so
     * it round-trips through {@link PathClass#fromString(String)}.
     */
    public static String clusterClassName(String namespace, int label) {
        return clusterClassName(namespace, CLUSTER_PREFIX + label);
    }

    /**
     * As {@link #clusterClassName(String, int)} but for a cluster's DISPLAY name
     * rather than its raw label -- so re-applying a result that was renamed gives
     * "&lt;namespace&gt;: Tumor", matching the palette that was saved alongside it
     * (which is keyed by the same display name).
     */
    public static String clusterClassName(String namespace, String baseName) {
        String base = (baseName == null || baseName.isBlank()) ? "Cluster" : baseName.trim();
        if (namespace == null || namespace.isBlank()) {
            return base;
        }
        return namespace.replace(":", " ").trim() + ": " + base;
    }

    /**
     * Restore a saved palette by setting each named cluster class's color. Used
     * when reopening a saved result or re-applying it to detections, so a user's
     * customized colors survive round-trips. Ignores null/empty input.
     *
     * @param clusterColors class-name -> packed 0xRRGGBB (as stored in the result)
     */
    public void applyClusterColors(java.util.Map<String, Integer> clusterColors) {
        applyClusterColors(clusterColors, null);
    }

    /**
     * As {@link #applyClusterColors(java.util.Map)} but applies the palette to the
     * namespaced classes ("&lt;namespace&gt;: Cluster N"). The palette keys are always the
     * bare "Cluster N" (namespace-independent), so the same saved palette restores
     * correctly whether the result was applied bare or under a namespace.
     */
    public void applyClusterColors(java.util.Map<String, Integer> clusterColors, String namespace) {
        if (clusterColors == null || clusterColors.isEmpty()) return;
        String ns = (namespace == null || namespace.isBlank()) ? null : namespace.replace(":", " ").trim();
        int n = 0;
        for (var e : clusterColors.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            String name = ns == null ? e.getKey() : ns + ": " + e.getKey();
            PathClass.fromString(name).setColor(e.getValue());
            n++;
        }
        logger.info("Restored {} saved cluster colors{}", n,
                ns != null ? " (namespace '" + ns + "')" : "");
    }

    /**
     * Applies embedding coordinates as measurements on detections.
     * <p>
     * Writes one measurement per embedding column, named {@code <prefix>1},
     * {@code <prefix>2}, ... The column count is taken from the embedding rows,
     * so a 2-component embedding writes NAME1/NAME2 (unchanged) and a
     * 3-component embedding also writes NAME3 -- a genuine third axis for
     * downstream 3D viewers.
     *
     * @param detections ordered list of detections
     * @param embedding  2D array [nCells][nComponents] of embedding coordinates
     * @param prefix     name prefix for the measurements (e.g., "UMAP", "PCA", "tSNE")
     */
    public void applyEmbedding(List<PathObject> detections, double[][] embedding, String prefix) {
        if (embedding == null) return;

        if (detections.size() != embedding.length) {
            throw new IllegalArgumentException(
                    "Detection count (" + detections.size()
                    + ") does not match embedding row count (" + embedding.length + ")");
        }

        int nComponents = embedding.length > 0 ? embedding[0].length : 0;
        // Build the measurement names once rather than concatenating per cell.
        String[] names = new String[nComponents];
        for (int c = 0; c < nComponents; c++) {
            names[c] = prefix + (c + 1);
        }

        warnOnMeasurementOverwrite(detections, names);

        for (int i = 0; i < detections.size(); i++) {
            var ml = detections.get(i).getMeasurementList();
            double[] row = embedding[i];
            for (int c = 0; c < row.length && c < names.length; c++) {
                ml.put(names[c], row[c]);
            }
            ml.close();
        }

        logger.info("Applied {} embedding ({} components) to {} detections",
                prefix, nComponents, detections.size());
    }

    /**
     * Applies phenotype labels to detections as PathClass classifications.
     * <p>
     * Each detection is assigned a classification matching the phenotype name
     * (e.g., "CD8+ T Cell", "Macrophage", "Unknown").
     *
     * @param detections     ordered list of detections (same order as labels)
     * @param labels         phenotype label index for each detection
     * @param phenotypeNames ordered array of phenotype names (index matches label value)
     */
    public void applyPhenotypeLabels(List<PathObject> detections, int[] labels,
                                      String[] phenotypeNames) {
        if (detections.size() != labels.length) {
            throw new IllegalArgumentException(
                    "Detection count (" + detections.size()
                    + ") does not match label count (" + labels.length + ")");
        }

        // One PathClass lookup per distinct phenotype, not per cell.
        Map<Integer, PathClass> byLabel = new HashMap<>();
        Set<String> seeded = new HashSet<>();
        for (int i = 0; i < detections.size(); i++) {
            PathObject det = detections.get(i);
            int label = labels[i];
            PathClass pc = byLabel.get(label);
            if (pc == null) {
                String name = (label >= 0 && label < phenotypeNames.length)
                        ? phenotypeNames[label] : "Unknown";
                pc = PathClass.fromString(name);
                if (label >= 0 && seeded.add(name)) {
                    pc.setColor(ClusterPalette.rgbFor(label));
                }
                byLabel.put(label, pc);
            }
            det.setPathClass(pc);
        }

        logger.info("Applied phenotype labels to {} detections", detections.size());
    }

    /**
     * Applies cellular-neighborhood ids to detections as a numeric measurement,
     * leaving each cell's classification (the cell-type input to the analysis)
     * intact. Color cells by neighborhood with QuPath's measurement maps.
     *
     * @param detections      ordered list of detections (same order as labels)
     * @param labels          neighborhood id for each detection
     * @param measurementName the measurement to write (e.g. "QPCAT CN")
     */
    public void applyNeighborhoodMeasurement(List<PathObject> detections, int[] labels,
                                             String measurementName) {
        if (detections.size() != labels.length) {
            throw new IllegalArgumentException(
                    "Detection count (" + detections.size()
                    + ") does not match label count (" + labels.length + ")");
        }
        for (int i = 0; i < detections.size(); i++) {
            var ml = detections.get(i).getMeasurementList();
            ml.put(measurementName, labels[i]);
            ml.close();
        }
        logger.info("Applied neighborhood measurement '{}' to {} detections",
                measurementName, detections.size());
    }

    /**
     * Applies sub-cluster labels to detections as hierarchical PathClass classifications.
     * <p>
     * Each detection is assigned a classification like "Cluster 3.0", "Cluster 3.1", etc.,
     * preserving the parent cluster identity in the label.
     *
     * @param detections       ordered list of detections (same order as labels)
     * @param labels           sub-cluster label for each detection
     * @param parentClusterName the parent cluster name (e.g., "Cluster 3")
     */
    public void applySubclusterLabels(List<PathObject> detections, int[] labels,
                                       String parentClusterName) {
        if (detections.size() != labels.length) {
            throw new IllegalArgumentException(
                    "Detection count (" + detections.size()
                    + ") does not match label count (" + labels.length + ")");
        }

        // One name build + PathClass lookup per distinct sub-cluster, not per cell.
        Map<Integer, PathClass> byLabel = new HashMap<>();
        for (int i = 0; i < detections.size(); i++) {
            PathObject det = detections.get(i);
            int label = labels[i];
            PathClass pc = byLabel.get(label);
            if (pc == null) {
                pc = PathClass.fromString(parentClusterName + "." + label);
                if (label >= 0) {
                    pc.setColor(ClusterPalette.rgbFor(label));
                }
                byLabel.put(label, pc);
            }
            det.setPathClass(pc);
        }

        logger.info("Applied sub-cluster labels to {} detections (parent: {})",
                detections.size(), parentClusterName);
    }

    /**
     * Convenience method to get the embedding measurement prefix for a given method.
     */
    public static String getEmbeddingPrefix(String embeddingMethod) {
        return switch (embeddingMethod) {
            case "umap" -> "UMAP";
            case "pca" -> "PCA";
            case "tsne" -> "tSNE";
            default -> embeddingMethod.toUpperCase();
        };
    }

    /**
     * Embedding measurement prefix honoring an optional user-supplied name. When
     * {@code customName} is non-blank it is used (sanitized to measurement-safe
     * characters) so two runs of the same method can coexist (e.g. "UMAP_k15" ->
     * "UMAP_k151"/"UMAP_k152"); otherwise the method default is used.
     */
    public static String getEmbeddingPrefix(String embeddingMethod, String customName) {
        if (customName != null && !customName.isBlank()) {
            return sanitizePrefix(customName);
        }
        return getEmbeddingPrefix(embeddingMethod);
    }

    /** Keep only measurement-safe characters in a user-supplied embedding name. */
    public static String sanitizePrefix(String name) {
        String s = name.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        return s.isBlank() ? "EMB" : s;
    }
}
