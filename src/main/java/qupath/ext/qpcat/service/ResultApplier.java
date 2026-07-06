package qupath.ext.qpcat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.util.HashSet;
import java.util.List;
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
        Set<String> seeded = new HashSet<>();
        for (int i = 0; i < detections.size(); i++) {
            PathObject det = detections.get(i);
            int label = labels[i];
            if (label < 0) {
                det.setPathClass(PathClass.getNullClass());
                continue;
            }
            String name = namer.apply(label);
            PathClass pc = PathClass.fromString(name);
            if (seeded.add(name)) {
                pc.setColor(ClusterPalette.rgbFor(label));
            }
            det.setPathClass(pc);
        }
    }

    /**
     * The PathClass name for a cluster label under an optional namespace. Bare
     * "Cluster N" when the namespace is null/blank; otherwise "&lt;namespace&gt;: Cluster N"
     * (a QuPath derived class). The namespace is stripped of the ": " delimiter so
     * it round-trips through {@link PathClass#fromString(String)}.
     */
    public static String clusterClassName(String namespace, int label) {
        if (namespace == null || namespace.isBlank()) {
            return CLUSTER_PREFIX + label;
        }
        return namespace.replace(":", " ").trim() + ": " + CLUSTER_PREFIX + label;
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
        for (int i = 0; i < detections.size(); i++) {
            var ml = detections.get(i).getMeasurements();
            double[] row = embedding[i];
            for (int c = 0; c < row.length; c++) {
                ml.put(prefix + (c + 1), row[c]);
            }
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

        Set<String> seeded = new HashSet<>();
        for (int i = 0; i < detections.size(); i++) {
            PathObject det = detections.get(i);
            int label = labels[i];
            String name = (label >= 0 && label < phenotypeNames.length)
                    ? phenotypeNames[label] : "Unknown";
            PathClass pc = PathClass.fromString(name);
            if (label >= 0 && seeded.add(name)) {
                pc.setColor(ClusterPalette.rgbFor(label));
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
            detections.get(i).getMeasurements().put(measurementName, labels[i]);
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

        Set<Integer> seeded = new HashSet<>();
        for (int i = 0; i < detections.size(); i++) {
            PathObject det = detections.get(i);
            int label = labels[i];
            String subName = parentClusterName + "." + label;
            PathClass pc = PathClass.fromString(subName);
            if (label >= 0 && seeded.add(label)) {
                pc.setColor(ClusterPalette.rgbFor(label));
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
