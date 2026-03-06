package qupath.ext.pyclustering.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.util.List;

/**
 * Applies clustering results (cluster labels, embeddings) back to
 * QuPath detection objects as classifications and measurements.
 */
public class ResultApplier {

    private static final Logger logger = LoggerFactory.getLogger(ResultApplier.class);

    /** Prefix for cluster classification names. */
    private static final String CLUSTER_PREFIX = "Cluster ";

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
        if (detections.size() != labels.length) {
            throw new IllegalArgumentException(
                    "Detection count (" + detections.size()
                    + ") does not match label count (" + labels.length + ")");
        }

        int applied = 0;
        for (int i = 0; i < detections.size(); i++) {
            PathObject det = detections.get(i);
            int label = labels[i];

            if (label < 0) {
                det.setPathClass(PathClass.getNullClass());
            } else {
                det.setPathClass(PathClass.fromString(CLUSTER_PREFIX + label));
            }
            applied++;
        }

        logger.info("Applied cluster labels to {} detections", applied);
    }

    /**
     * Applies 2D embedding coordinates as measurements on detections.
     *
     * @param detections ordered list of detections
     * @param embedding  2D array [nCells][2] of embedding coordinates
     * @param prefix     name prefix for the measurements (e.g., "UMAP", "PCA", "tSNE")
     */
    public void applyEmbedding(List<PathObject> detections, double[][] embedding, String prefix) {
        if (embedding == null) return;

        if (detections.size() != embedding.length) {
            throw new IllegalArgumentException(
                    "Detection count (" + detections.size()
                    + ") does not match embedding row count (" + embedding.length + ")");
        }

        String name1 = prefix + "1";
        String name2 = prefix + "2";

        for (int i = 0; i < detections.size(); i++) {
            var ml = detections.get(i).getMeasurements();
            ml.put(name1, embedding[i][0]);
            ml.put(name2, embedding[i][1]);
        }

        logger.info("Applied {} embedding to {} detections", prefix, detections.size());
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
}
