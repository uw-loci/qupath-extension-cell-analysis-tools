package qupath.ext.qpcat.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.controller.ClusteringWorkflow;
import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * Headless clustering facade for the YAML batch runner.
 *
 * <p>Wraps {@link ClusteringWorkflow} with a {@code null} {@code QuPathGUI}
 * reference so it can run without an open viewer. Dispatch occurs through
 * {@link ClusteringWorkflow#runProjectClustering(List, ClusteringConfig,
 * Consumer)} -- the project-clustering entry already accepts an explicit
 * image-entry list and only touches the GUI inside one
 * {@code Platform.runLater} block (which is null-guarded for the
 * headless case in {@link ClusteringWorkflow}).</p>
 *
 * <p>Design choice (Phase 2): parallel facade, not callback refactor.
 * Cost: ~80 LOC for the facade plus the targeted null-guard added to
 * {@code ClusteringWorkflow.runProjectClustering(...)}. Single-image
 * methods ({@code runClustering}, {@code runPhenotyping}, etc.) on the
 * underlying class remain GUI-only -- the YAML batch never calls them.</p>
 *
 * <p>Sub-clustering, AnnData export, threshold compute, and the
 * autoencoder path stay out of scope for v1 YAML batch. They are
 * reachable only via the GUI dialogs.</p>
 */
public final class HeadlessClusteringWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(HeadlessClusteringWorkflow.class);

    private final ClusteringWorkflow inner;

    public HeadlessClusteringWorkflow() {
        this.inner = new ClusteringWorkflow(null);
    }

    /**
     * Run clustering across the given project images using the supplied
     * configuration. Returns the combined {@link ClusteringResult};
     * per-image label application is performed inside
     * {@link ClusteringWorkflow#runProjectClustering(List, ClusteringConfig,
     * Consumer)}.
     *
     * @param imageEntries   project image entries to cluster (size >= 1)
     * @param config         clustering configuration
     * @param progressMessage optional callback receiving free-form progress
     *                       strings (may be null)
     */
    public ClusteringResult runClustering(
            List<ProjectImageEntry<BufferedImage>> imageEntries,
            ClusteringConfig config,
            Consumer<String> progressMessage) throws IOException {
        if (imageEntries == null || imageEntries.isEmpty()) {
            throw new IOException("HeadlessClusteringWorkflow: no images supplied");
        }
        if (config == null) {
            throw new IOException("HeadlessClusteringWorkflow: clustering config is null");
        }
        long start = System.currentTimeMillis();
        ClusteringResult result = inner.runProjectClustering(
                imageEntries, config, progressMessage);
        logger.info("[headless-cluster] {} images, {} clusters, {} cells, {} ms",
                imageEntries.size(), result.getNClusters(), result.getNCells(),
                System.currentTimeMillis() - start);
        return result;
    }
}
