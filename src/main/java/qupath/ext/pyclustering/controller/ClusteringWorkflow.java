package qupath.ext.pyclustering.controller;

import javafx.application.Platform;
import org.apposed.appose.NDArray;
import org.apposed.appose.Service.Task;
import org.apposed.appose.Service.ResponseType;
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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import qupath.lib.projects.ProjectImageEntry;

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
     * Runs clustering across multiple project images simultaneously.
     * Detections from all selected images are combined, clustered together
     * for global consistency, then results are written back per-image.
     *
     * @param imageEntries       project image entries to include
     * @param config             clustering configuration
     * @param progressCallback   optional callback for progress messages
     * @return the clustering result
     * @throws IOException if clustering fails
     */
    public ClusteringResult runProjectClustering(
            List<ProjectImageEntry<BufferedImage>> imageEntries,
            ClusteringConfig config,
            Consumer<String> progressCallback) throws IOException {

        if (imageEntries == null || imageEntries.isEmpty()) {
            throw new IOException("No project images selected for clustering.");
        }

        report(progressCallback, "Loading detections from " + imageEntries.size() + " images...");

        // Build detection groups from each image
        List<MeasurementExtractor.ImageDetectionGroup> groups = new ArrayList<>();
        for (int idx = 0; idx < imageEntries.size(); idx++) {
            ProjectImageEntry<BufferedImage> entry = imageEntries.get(idx);
            report(progressCallback, "Loading image " + (idx + 1) + "/" + imageEntries.size()
                    + ": " + entry.getImageName());

            ImageData<BufferedImage> imageData;
            try {
                imageData = entry.readImageData();
            } catch (Exception e) {
                logger.warn("Failed to read image data for {}: {}", entry.getImageName(), e.getMessage());
                continue;
            }

            Collection<PathObject> detections = imageData.getHierarchy().getDetectionObjects();
            if (detections.isEmpty()) {
                logger.info("Skipping {} - no detections", entry.getImageName());
                continue;
            }

            groups.add(new MeasurementExtractor.ImageDetectionGroup(
                    entry, imageData, detections));
            logger.info("Loaded {} detections from {}", detections.size(), entry.getImageName());
        }

        if (groups.isEmpty()) {
            throw new IOException("No detection objects found in any selected images. Run cell detection first.");
        }

        // Extract measurements across all images
        report(progressCallback, "Extracting measurements...");
        MeasurementExtractor extractor = new MeasurementExtractor();
        MeasurementExtractor.ExtractionResult extraction =
                extractor.extractMultiImage(groups, config.getSelectedMeasurements());

        logger.info("Combined extraction: {} cells x {} measurements across {} images",
                extraction.getNCells(), extraction.getNMeasurements(),
                extraction.getImageSegments().size());

        // Run clustering via Appose
        report(progressCallback, "Sending data to Python (" + extraction.getNCells()
                + " cells x " + extraction.getNMeasurements() + " markers from "
                + extraction.getImageSegments().size() + " images)...");

        ClusteringResult result;
        try {
            result = ApposeClusteringService.withExtensionClassLoader(() ->
                    executeClusteringTask(extraction, config, progressCallback));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Clustering failed: " + e.getMessage(), e);
        }

        // Apply results back per-image and save
        report(progressCallback, "Applying results to project images...");
        ResultApplier applier = new ResultApplier();

        for (MeasurementExtractor.ImageSegment segment : extraction.getImageSegments()) {
            int start = segment.getStartIndex();
            int end = segment.getEndIndex();

            // Get sub-list of detections and labels for this image
            List<PathObject> segmentDetections = extraction.getDetections().subList(start, end);
            int[] segmentLabels = new int[end - start];
            System.arraycopy(result.getClusterLabels(), start, segmentLabels, 0, end - start);

            applier.applyClusterLabels(segmentDetections, segmentLabels);

            if (result.hasEmbedding()) {
                String prefix = ResultApplier.getEmbeddingPrefix(
                        config.getEmbeddingMethod().getId());
                double[][] segmentEmbedding = new double[end - start][2];
                for (int i = 0; i < end - start; i++) {
                    segmentEmbedding[i] = result.getEmbedding()[start + i];
                }
                applier.applyEmbedding(segmentDetections, segmentEmbedding, prefix);
            }

            // Save image data back to the project
            @SuppressWarnings("unchecked")
            ProjectImageEntry<BufferedImage> entry =
                    (ProjectImageEntry<BufferedImage>) segment.getImageEntry();
            @SuppressWarnings("unchecked")
            ImageData<BufferedImage> imageData =
                    (ImageData<BufferedImage>) segment.getImageData();

            try {
                entry.saveImageData(imageData);
                logger.info("Saved clustering results for {} ({} detections)",
                        entry.getImageName(), segment.getCount());
            } catch (Exception e) {
                logger.error("Failed to save image data for {}: {}",
                        entry.getImageName(), e.getMessage());
            }

            report(progressCallback, "Saved results for " + entry.getImageName());
        }

        // Fire hierarchy update for the currently open image (if it was clustered)
        Platform.runLater(() -> {
            ImageData<BufferedImage> currentImageData = qupath.getImageData();
            if (currentImageData != null) {
                currentImageData.getHierarchy().fireHierarchyChangedEvent(this);
            }
        });

        report(progressCallback, "Project clustering complete: " + result.getNClusters()
                + " clusters found for " + result.getNCells() + " cells across "
                + extraction.getImageSegments().size() + " images.");

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

        // Create temp directory for plots if requested
        Path plotDir = null;
        if (config.isGeneratePlots()) {
            try {
                plotDir = Files.createTempDirectory("pyclustering-plots-");
            } catch (IOException e) {
                logger.warn("Failed to create plot directory: {}", e.getMessage());
            }
        }

        // Create spatial coordinates NDArray if spatial analysis is enabled or BANKSY algorithm
        NDArray spatialCoordsNd = null;
        boolean needsSpatialCoords = config.isEnableSpatialAnalysis()
                || config.getAlgorithm() == ClusteringConfig.Algorithm.BANKSY;
        if (needsSpatialCoords) {
            double[][] centroids = MeasurementExtractor.extractCentroids(
                    extraction.getDetections());
            NDArray.Shape spatialShape = new NDArray.Shape(
                    NDArray.Shape.Order.C_ORDER, nCells, 2);
            spatialCoordsNd = new NDArray(NDArray.DType.FLOAT64, spatialShape);
            var spatialBuf = spatialCoordsNd.buffer().asDoubleBuffer();
            for (int i = 0; i < nCells; i++) {
                spatialBuf.put(centroids[i]);
            }
        }

        // Compute batch labels for multi-image batch correction
        List<Integer> batchLabels = null;
        if (config.isEnableBatchCorrection() && extraction.isMultiImage()) {
            batchLabels = new ArrayList<>();
            for (int segIdx = 0; segIdx < extraction.getImageSegments().size(); segIdx++) {
                MeasurementExtractor.ImageSegment seg = extraction.getImageSegments().get(segIdx);
                for (int i = 0; i < seg.getCount(); i++) {
                    batchLabels.add(segIdx);
                }
            }
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
        inputs.put("top_n_markers", config.getTopNMarkers());
        inputs.put("generate_plots", plotDir != null);
        if (plotDir != null) {
            inputs.put("output_dir", plotDir.toString());
        }
        if (spatialCoordsNd != null) {
            inputs.put("spatial_coords", spatialCoordsNd);
        }
        if (batchLabels != null) {
            inputs.put("enable_batch_correction", true);
            inputs.put("batch_labels", batchLabels);
        }

        NDArray labelsNd = null;
        NDArray embNd = null;
        NDArray statsNd = null;
        NDArray pagaNd = null;
        NDArray nhoodNd = null;

        try {
            // Run the task with progress updates
            ApposeClusteringService service = ApposeClusteringService.getInstance();
            Task task = service.runTaskWithListener("run_clustering", inputs, event -> {
                if (event.responseType == ResponseType.UPDATE && event.message != null) {
                    report(progressCallback, event.message);
                }
            });

            // Parse core outputs
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

            ClusteringResult result = new ClusteringResult(labels, nClusters, embedding,
                    clusterStats, extraction.getMeasurementNames());

            // Parse post-analysis outputs
            if (task.outputs.containsKey("marker_rankings")) {
                result.setMarkerRankingsJson((String) task.outputs.get("marker_rankings"));
                logger.info("Received marker rankings for {} clusters", nClusters);
            }

            if (task.outputs.containsKey("paga_connectivity")) {
                pagaNd = (NDArray) task.outputs.get("paga_connectivity");
                double[][] pagaConn = new double[nClusters][nClusters];
                var pagaBuf = pagaNd.buffer().asDoubleBuffer();
                for (int i = 0; i < nClusters; i++) {
                    pagaBuf.get(pagaConn[i]);
                }
                result.setPagaConnectivity(pagaConn);

                String pagaNamesJson = (String) task.outputs.get("paga_cluster_names");
                if (pagaNamesJson != null) {
                    Gson gson = new Gson();
                    List<String> namesList = gson.fromJson(pagaNamesJson,
                            new TypeToken<List<String>>(){}.getType());
                    result.setPagaClusterNames(namesList.toArray(new String[0]));
                }
                logger.info("Received PAGA connectivity ({} x {})", nClusters, nClusters);
            }

            if (task.outputs.containsKey("plot_paths")) {
                String plotPathsJson = (String) task.outputs.get("plot_paths");
                Gson gson = new Gson();
                Map<String, String> paths = gson.fromJson(plotPathsJson,
                        new TypeToken<Map<String, String>>(){}.getType());
                result.setPlotPaths(paths);
                logger.info("Received {} analysis plots", paths.size());
            }

            // Parse spatial analysis outputs
            if (task.outputs.containsKey("nhood_enrichment")) {
                nhoodNd = (NDArray) task.outputs.get("nhood_enrichment");
                int nhoodSize = nClusters;
                double[][] nhoodData = new double[nhoodSize][nhoodSize];
                var nhoodBuf = nhoodNd.buffer().asDoubleBuffer();
                for (int i = 0; i < nhoodSize; i++) {
                    nhoodBuf.get(nhoodData[i]);
                }
                result.setNhoodEnrichment(nhoodData);

                String nhoodNamesJson = (String) task.outputs.get("nhood_cluster_names");
                if (nhoodNamesJson != null) {
                    Gson gson = new Gson();
                    List<String> namesList = gson.fromJson(nhoodNamesJson,
                            new TypeToken<List<String>>(){}.getType());
                    result.setNhoodClusterNames(namesList.toArray(new String[0]));
                }
                logger.info("Received neighborhood enrichment ({} x {})",
                        nhoodSize, nhoodSize);
            }

            if (task.outputs.containsKey("spatial_autocorr")) {
                result.setSpatialAutocorrJson(
                        (String) task.outputs.get("spatial_autocorr"));
                logger.info("Received spatial autocorrelation results");
            }

            return result;
        } finally {
            // Release all shared memory
            measurementsNd.close();
            if (spatialCoordsNd != null) spatialCoordsNd.close();
            if (labelsNd != null) labelsNd.close();
            if (embNd != null) embNd.close();
            if (statsNd != null) statsNd.close();
            if (pagaNd != null) pagaNd.close();
            if (nhoodNd != null) nhoodNd.close();
        }
    }

    private static void report(Consumer<String> callback, String message) {
        if (callback != null) callback.accept(message);
    }
}
