package qupath.ext.pyclustering.controller;

import javafx.application.Platform;
import org.apposed.appose.NDArray;
import org.apposed.appose.Service.Task;
import org.apposed.appose.Service.ResponseType;
import org.apposed.appose.TaskEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.pyclustering.model.ClusteringConfig;
import qupath.ext.pyclustering.model.ClusteringResult;
import qupath.ext.pyclustering.service.ApposeClusteringService;
import qupath.ext.pyclustering.service.MeasurementExtractor;
import qupath.ext.pyclustering.service.ResultApplier;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Orchestrates the end-to-end clustering workflow:
 * extract measurements -> send to Python -> apply results.
 */
public class ClusteringWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(ClusteringWorkflow.class);

    private final QuPathGUI qupath;

    public ClusteringWorkflow(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /**
     * Runs clustering on detections from the current image using the given configuration.
     * This method should be called from a background thread.
     *
     * @param config           clustering configuration
     * @param progressCallback optional callback for progress messages (may be null)
     * @return the clustering result
     * @throws IOException if clustering fails
     */
    public ClusteringResult runClustering(ClusteringConfig config,
                                           Consumer<String> progressCallback) throws IOException {
        report(progressCallback, "Extracting measurements...");

        // Get detections from the current image
        ImageData<BufferedImage> imageData = qupath.getImageData();
        if (imageData == null) {
            throw new IOException("No image is open");
        }

        PathObjectHierarchy hierarchy = imageData.getHierarchy();
        Collection<PathObject> selected = hierarchy.getSelectionModel().getSelectedObjects();

        // If no selection, use all detections
        Collection<PathObject> detections;
        if (selected.isEmpty() || selected.stream().noneMatch(p -> p.isDetection())) {
            detections = new ArrayList<>(hierarchy.getDetectionObjects());
            logger.info("No detections selected - using all {} detections", detections.size());
        } else {
            // If annotations are selected, get detections within them
            List<PathObject> selectedAnnotations = selected.stream()
                    .filter(PathObject::isAnnotation)
                    .toList();
            if (!selectedAnnotations.isEmpty()) {
                detections = new ArrayList<>();
                for (PathObject annotation : selectedAnnotations) {
                    detections.addAll(hierarchy.getAllDetectionsForROI(
                            annotation.getROI()));
                }
                logger.info("Using {} detections from {} selected annotations",
                        detections.size(), selectedAnnotations.size());
            } else {
                // Use selected detections directly
                detections = selected.stream()
                        .filter(PathObject::isDetection)
                        .toList();
                logger.info("Using {} selected detections", detections.size());
            }
        }

        if (detections.isEmpty()) {
            throw new IOException("No detection objects found. Run cell detection first.");
        }

        // Extract measurements
        MeasurementExtractor extractor = new MeasurementExtractor();
        MeasurementExtractor.ExtractionResult extraction = extractor.extract(
                detections, config.getSelectedMeasurements());

        logger.info("Extracted {} cells x {} measurements",
                extraction.getNCells(), extraction.getNMeasurements());

        // Convert to NDArray for Appose transfer
        report(progressCallback, "Sending data to Python (" + extraction.getNCells()
                + " cells x " + extraction.getNMeasurements() + " markers)...");

        ClusteringResult result;
        try {
            result = ApposeClusteringService.withExtensionClassLoader(() ->
                    executeClusteringTask(extraction, config, progressCallback));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Clustering failed: " + e.getMessage(), e);
        }

        // Apply results back to QuPath
        report(progressCallback, "Applying results to QuPath...");

        ResultApplier applier = new ResultApplier();
        applier.applyClusterLabels(extraction.getDetections(), result.getClusterLabels());

        if (result.hasEmbedding()) {
            String prefix = ResultApplier.getEmbeddingPrefix(
                    config.getEmbeddingMethod().getId());
            applier.applyEmbedding(extraction.getDetections(), result.getEmbedding(), prefix);
        }

        // Fire hierarchy update on FX thread
        Platform.runLater(() -> {
            ImageData<BufferedImage> currentImageData = qupath.getImageData();
            if (currentImageData != null) {
                currentImageData.getHierarchy().fireHierarchyChangedEvent(this);
            }
        });

        report(progressCallback, "Clustering complete: " + result.getNClusters()
                + " clusters found for " + result.getNCells() + " cells.");

        return result;
    }

    /**
     * Executes the clustering task via Appose. Must be called with TCCL set.
     */
    private ClusteringResult executeClusteringTask(
            MeasurementExtractor.ExtractionResult extraction,
            ClusteringConfig config,
            Consumer<String> progressCallback) throws IOException {

        int nCells = extraction.getNCells();
        int nMeasurements = extraction.getNMeasurements();
        double[][] data = extraction.getData();

        // Create NDArray for measurement data (input -- closed after task completes)
        NDArray.Shape shape = new NDArray.Shape(NDArray.Shape.Order.C_ORDER, nCells, nMeasurements);
        NDArray measurementsNd = new NDArray(NDArray.DType.FLOAT64, shape);
        var buf = measurementsNd.buffer().asDoubleBuffer();
        for (int i = 0; i < nCells; i++) {
            buf.put(data[i]);
        }

        // Build inputs map
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("measurements", measurementsNd);
        inputs.put("marker_names", List.of(extraction.getMeasurementNames()));
        inputs.put("algorithm", config.getAlgorithm().getId());
        inputs.put("algorithm_params", config.getAlgorithmParams());
        inputs.put("normalization", config.getNormalization().getId());
        inputs.put("embedding_method", config.getEmbeddingMethod().getId());
        inputs.put("embedding_params", config.getEmbeddingParams());

        NDArray labelsNd = null;
        NDArray embNd = null;
        NDArray statsNd = null;

        try {
            // Run the task with progress updates
            ApposeClusteringService service = ApposeClusteringService.getInstance();
            Task task = service.runTaskWithListener("run_clustering", inputs, event -> {
                if (event.responseType == ResponseType.UPDATE && event.message != null) {
                    report(progressCallback, event.message);
                }
            });

            // Parse outputs -- extract data from NDArrays into Java arrays,
            // then close the NDArrays to release shared memory
            labelsNd = (NDArray) task.outputs.get("cluster_labels");
            int nClusters = ((Number) task.outputs.get("n_clusters")).intValue();

            int[] labels = new int[nCells];
            labelsNd.buffer().asIntBuffer().get(labels);

            double[][] embedding = null;
            if (task.outputs.containsKey("embedding")) {
                embNd = (NDArray) task.outputs.get("embedding");
                embedding = new double[nCells][2];
                var embBuf = embNd.buffer().asDoubleBuffer();
                for (int i = 0; i < nCells; i++) {
                    embBuf.get(embedding[i]);
                }
            }

            statsNd = (NDArray) task.outputs.get("cluster_stats");
            double[][] clusterStats = new double[nClusters][nMeasurements];
            var statsBuf = statsNd.buffer().asDoubleBuffer();
            for (int i = 0; i < nClusters; i++) {
                statsBuf.get(clusterStats[i]);
            }

            return new ClusteringResult(labels, nClusters, embedding,
                    clusterStats, extraction.getMeasurementNames());
        } finally {
            // Release all shared memory
            measurementsNd.close();
            if (labelsNd != null) labelsNd.close();
            if (embNd != null) embNd.close();
            if (statsNd != null) statsNd.close();
        }
    }

    private static void report(Consumer<String> callback, String message) {
        if (callback != null) callback.accept(message);
    }
}
