package qupath.ext.qpcat.controller;

import javafx.application.Platform;
import org.apposed.appose.NDArray;
import org.apposed.appose.Service.Task;
import org.apposed.appose.Service.ResponseType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.ext.qpcat.service.ApposeClusteringService;
import qupath.ext.qpcat.service.MeasurementExtractor;
import qupath.ext.qpcat.service.OperationLogger;
import qupath.ext.qpcat.service.ResultApplier;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import qupath.lib.images.servers.ImageServer;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;

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
        long startTime = System.currentTimeMillis();
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

        // Skip label application for embedding-only mode
        if (config.getAlgorithm() != ClusteringConfig.Algorithm.NONE) {
            applier.applyClusterLabels(extraction.getDetections(), result.getClusterLabels());
        }

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

        String completeMsg = config.getAlgorithm() == ClusteringConfig.Algorithm.NONE
                ? "Embedding computed for " + result.getNCells() + " cells."
                : "Clustering complete: " + result.getNClusters()
                    + " clusters found for " + result.getNCells() + " cells.";
        report(progressCallback, completeMsg);

        // Audit trail
        long elapsed = System.currentTimeMillis() - startTime;
        String opType = config.getAlgorithm() == ClusteringConfig.Algorithm.NONE
                ? "EMBEDDING" : "CLUSTERING";
        OperationLogger.getInstance().logOperation(opType,
                OperationLogger.clusteringParams(
                        config.getAlgorithm().getDisplayName(),
                        config.getAlgorithmParams(),
                        config.getNormalization().getId(),
                        config.getEmbeddingMethod().getId(),
                        extraction.getNMeasurements(),
                        extraction.getNCells(),
                        config.isEnableSpatialAnalysis(),
                        config.isEnableBatchCorrection()),
                completeMsg, elapsed);

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

        long startTime = System.currentTimeMillis();

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

        String completeMsg = "Project clustering complete: " + result.getNClusters()
                + " clusters found for " + result.getNCells() + " cells across "
                + extraction.getImageSegments().size() + " images.";
        report(progressCallback, completeMsg);

        // Audit trail
        long elapsed = System.currentTimeMillis() - startTime;
        OperationLogger.getInstance().logOperation("PROJECT CLUSTERING",
                OperationLogger.projectClusteringParams(
                        config.getAlgorithm().getDisplayName(),
                        config.getAlgorithmParams(),
                        config.getNormalization().getId(),
                        config.getEmbeddingMethod().getId(),
                        extraction.getNMeasurements(),
                        extraction.getNCells(),
                        extraction.getImageSegments().size(),
                        config.isEnableBatchCorrection()),
                completeMsg, elapsed);

        return result;
    }

    /**
     * Runs phenotyping on detections from the current image using user-defined rules.
     * This method should be called from a background thread.
     *
     * @param selectedMeasurements marker measurements to use for phenotyping
     * @param normalization        normalization method id ("zscore", "minmax", "percentile", "none")
     * @param phenotypeRulesJson   JSON string of phenotype rules
     * @param gatesJson            JSON string of per-marker gate thresholds
     * @param progressCallback     optional callback for progress messages
     * @return map with "labels" (int[]), "phenotype_names" (String[]),
     *         "n_phenotypes" (Integer), "phenotype_counts" (String JSON)
     * @throws IOException if phenotyping fails
     */
    public Map<String, Object> runPhenotyping(
            List<String> selectedMeasurements,
            String normalization,
            String phenotypeRulesJson,
            String gatesJson,
            Consumer<String> progressCallback) throws IOException {

        long startTime = System.currentTimeMillis();
        report(progressCallback, "Extracting measurements...");

        ImageData<BufferedImage> imageData = qupath.getImageData();
        if (imageData == null) {
            throw new IOException("No image is open");
        }

        PathObjectHierarchy hierarchy = imageData.getHierarchy();
        List<PathObject> detections = new ArrayList<>(hierarchy.getDetectionObjects());
        if (detections.isEmpty()) {
            throw new IOException("No detection objects found. Run cell detection first.");
        }

        MeasurementExtractor extractor = new MeasurementExtractor();
        MeasurementExtractor.ExtractionResult extraction =
                extractor.extract(detections, selectedMeasurements);

        logger.info("Extracted {} cells x {} measurements for phenotyping",
                extraction.getNCells(), extraction.getNMeasurements());

        report(progressCallback, "Sending data to Python (" + extraction.getNCells()
                + " cells x " + extraction.getNMeasurements() + " markers)...");

        Map<String, Object> resultMap;
        try {
            resultMap = ApposeClusteringService.withExtensionClassLoader(() ->
                    executePhenotypingTask(extraction, normalization,
                            phenotypeRulesJson, gatesJson, progressCallback));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Phenotyping failed: " + e.getMessage(), e);
        }

        // Apply labels back to QuPath
        report(progressCallback, "Applying phenotype labels...");
        int[] labels = (int[]) resultMap.get("labels");
        String[] phenotypeNames = (String[]) resultMap.get("phenotype_names");

        ResultApplier applier = new ResultApplier();
        applier.applyPhenotypeLabels(extraction.getDetections(), labels, phenotypeNames);

        // Fire hierarchy update on FX thread
        Platform.runLater(() -> {
            ImageData<BufferedImage> currentImageData = qupath.getImageData();
            if (currentImageData != null) {
                currentImageData.getHierarchy().fireHierarchyChangedEvent(this);
            }
        });

        String completeMsg = "Phenotyping complete: " + resultMap.get("n_phenotypes")
                + " phenotypes assigned to " + extraction.getNCells() + " cells.";
        report(progressCallback, completeMsg);

        // Audit trail -- count rules from the JSON (each array element is a rule)
        int ruleCount = 0;
        try {
            List<?> ruleList = new Gson().fromJson(phenotypeRulesJson, List.class);
            ruleCount = ruleList != null ? ruleList.size() : 0;
        } catch (Exception ignored) {}
        long elapsed = System.currentTimeMillis() - startTime;
        OperationLogger.getInstance().logOperation("PHENOTYPING",
                OperationLogger.phenotypingParams(
                        normalization,
                        selectedMeasurements.size(),
                        ruleCount,
                        extraction.getNCells(),
                        selectedMeasurements),
                completeMsg, elapsed);

        return resultMap;
    }

    /**
     * Computes per-marker histograms and auto-thresholds.
     * This method should be called from a background thread.
     *
     * @param selectedMeasurements marker measurements to compute thresholds for
     * @param normalization        normalization method id
     * @param progressCallback     optional callback for progress messages
     * @return JSON string with per-marker histogram data and auto-thresholds
     * @throws IOException if computation fails
     */
    public String computeThresholds(
            List<String> selectedMeasurements,
            String normalization,
            Consumer<String> progressCallback) throws IOException {

        long startTime = System.currentTimeMillis();
        report(progressCallback, "Extracting measurements for thresholds...");

        ImageData<BufferedImage> imageData = qupath.getImageData();
        if (imageData == null) {
            throw new IOException("No image is open");
        }

        List<PathObject> detections = new ArrayList<>(
                imageData.getHierarchy().getDetectionObjects());
        if (detections.isEmpty()) {
            throw new IOException("No detection objects found.");
        }

        MeasurementExtractor extractor = new MeasurementExtractor();
        MeasurementExtractor.ExtractionResult extraction =
                extractor.extract(detections, selectedMeasurements);

        report(progressCallback, "Computing thresholds (" + extraction.getNCells()
                + " cells x " + extraction.getNMeasurements() + " markers)...");

        try {
            String result = ApposeClusteringService.withExtensionClassLoader(() ->
                    executeThresholdTask(extraction, normalization, progressCallback));

            // Audit trail
            long elapsed = System.currentTimeMillis() - startTime;
            OperationLogger.getInstance().logOperation("COMPUTE THRESHOLDS",
                    OperationLogger.thresholdParams(
                            normalization,
                            extraction.getNMeasurements(),
                            extraction.getNCells()),
                    "Thresholds computed for " + extraction.getNMeasurements() + " markers",
                    elapsed);

            return result;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Threshold computation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Executes the threshold computation task via Appose. Must be called with TCCL set.
     */
    private String executeThresholdTask(
            MeasurementExtractor.ExtractionResult extraction,
            String normalization,
            Consumer<String> progressCallback) throws IOException {

        int nCells = extraction.getNCells();
        int nMeasurements = extraction.getNMeasurements();
        double[][] data = extraction.getData();

        NDArray.Shape shape = new NDArray.Shape(NDArray.Shape.Order.C_ORDER, nCells, nMeasurements);
        NDArray measurementsNd = new NDArray(NDArray.DType.FLOAT64, shape);
        var buf = measurementsNd.buffer().asDoubleBuffer();
        for (int i = 0; i < nCells; i++) {
            buf.put(data[i]);
        }

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("measurements", measurementsNd);
        inputs.put("marker_names", List.of(extraction.getMeasurementNames()));
        inputs.put("normalization", normalization);

        try {
            ApposeClusteringService service = ApposeClusteringService.getInstance();
            Task task = service.runTaskWithListener("compute_thresholds", inputs, event -> {
                if (event.responseType == ResponseType.UPDATE && event.message != null) {
                    report(progressCallback, event.message);
                }
            });

            return (String) task.outputs.get("histograms_json");
        } finally {
            measurementsNd.close();
        }
    }

    /**
     * Executes the phenotyping task via Appose. Must be called with TCCL set.
     */
    private Map<String, Object> executePhenotypingTask(
            MeasurementExtractor.ExtractionResult extraction,
            String normalization,
            String phenotypeRulesJson,
            String gatesJson,
            Consumer<String> progressCallback) throws IOException {

        int nCells = extraction.getNCells();
        int nMeasurements = extraction.getNMeasurements();
        double[][] data = extraction.getData();

        NDArray.Shape shape = new NDArray.Shape(NDArray.Shape.Order.C_ORDER, nCells, nMeasurements);
        NDArray measurementsNd = new NDArray(NDArray.DType.FLOAT64, shape);
        var buf = measurementsNd.buffer().asDoubleBuffer();
        for (int i = 0; i < nCells; i++) {
            buf.put(data[i]);
        }

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("measurements", measurementsNd);
        inputs.put("marker_names", List.of(extraction.getMeasurementNames()));
        inputs.put("normalization", normalization);
        inputs.put("phenotype_rules", phenotypeRulesJson);
        inputs.put("gates_json", gatesJson);

        NDArray labelsNd = null;

        try {
            ApposeClusteringService service = ApposeClusteringService.getInstance();
            Task task = service.runTaskWithListener("run_phenotyping", inputs, event -> {
                if (event.responseType == ResponseType.UPDATE && event.message != null) {
                    report(progressCallback, event.message);
                }
            });

            // Parse outputs
            labelsNd = (NDArray) task.outputs.get("phenotype_labels");
            int nPhenotypes = ((Number) task.outputs.get("n_phenotypes")).intValue();
            String phenotypeNamesJson = (String) task.outputs.get("phenotype_names");
            String phenotypeCountsJson = (String) task.outputs.get("phenotype_counts");

            int[] labels = new int[nCells];
            labelsNd.buffer().asIntBuffer().get(labels);

            Gson gson = new Gson();
            List<String> namesList = gson.fromJson(phenotypeNamesJson,
                    new TypeToken<List<String>>(){}.getType());

            Map<String, Object> result = new HashMap<>();
            result.put("labels", labels);
            result.put("phenotype_names", namesList.toArray(new String[0]));
            result.put("n_phenotypes", nPhenotypes);
            result.put("phenotype_counts", phenotypeCountsJson);

            return result;
        } finally {
            measurementsNd.close();
            if (labelsNd != null) labelsNd.close();
        }
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
                plotDir = Files.createTempDirectory("qpcat-plots-");
            } catch (IOException e) {
                logger.warn("Failed to create plot directory: {}", e.getMessage());
            }
        }

        // Create spatial coordinates NDArray if spatial analysis, smoothing, or BANKSY
        NDArray spatialCoordsNd = null;
        boolean needsSpatialCoords = config.isEnableSpatialAnalysis()
                || config.isEnableSpatialSmoothing()
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
        if (config.isEnableSpatialSmoothing()) {
            inputs.put("enable_spatial_smoothing", true);
            inputs.put("spatial_smoothing_iterations", config.getSpatialSmoothingIterations());
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

    /**
     * Runs sub-clustering on detections within a specific parent cluster.
     * The parent cluster detections are re-clustered and assigned hierarchical labels
     * (e.g., "Cluster 3.0", "Cluster 3.1").
     *
     * @param parentClusterName  the parent cluster classification (e.g., "Cluster 3")
     * @param config             clustering configuration to use for sub-clustering
     * @param progressCallback   optional callback for progress messages
     * @return the clustering result for the sub-cluster
     * @throws IOException if sub-clustering fails
     */
    public ClusteringResult runSubclustering(
            String parentClusterName,
            ClusteringConfig config,
            Consumer<String> progressCallback) throws IOException {

        long startTime = System.currentTimeMillis();
        report(progressCallback, "Extracting detections from " + parentClusterName + "...");

        ImageData<BufferedImage> imageData = qupath.getImageData();
        if (imageData == null) {
            throw new IOException("No image is open");
        }

        // Filter detections by parent cluster classification
        List<PathObject> parentDetections = imageData.getHierarchy().getDetectionObjects()
                .stream()
                .filter(det -> {
                    var pc = det.getPathClass();
                    return pc != null && pc.toString().equals(parentClusterName);
                })
                .collect(java.util.stream.Collectors.toList());

        if (parentDetections.isEmpty()) {
            throw new IOException("No detections found with classification '"
                    + parentClusterName + "'");
        }

        logger.info("Sub-clustering {} detections from {}", parentDetections.size(), parentClusterName);

        // Extract measurements
        MeasurementExtractor extractor = new MeasurementExtractor();
        MeasurementExtractor.ExtractionResult extraction =
                extractor.extract(parentDetections, config.getSelectedMeasurements());

        report(progressCallback, "Sub-clustering " + extraction.getNCells()
                + " cells from " + parentClusterName + "...");

        // Run clustering via Appose
        ClusteringResult result;
        try {
            result = ApposeClusteringService.withExtensionClassLoader(() ->
                    executeClusteringTask(extraction, config, progressCallback));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Sub-clustering failed: " + e.getMessage(), e);
        }

        // Apply hierarchical sub-cluster labels
        report(progressCallback, "Applying sub-cluster labels...");
        ResultApplier applier = new ResultApplier();
        applier.applySubclusterLabels(extraction.getDetections(),
                result.getClusterLabels(), parentClusterName);

        if (result.hasEmbedding()) {
            String prefix = ResultApplier.getEmbeddingPrefix(
                    config.getEmbeddingMethod().getId());
            applier.applyEmbedding(extraction.getDetections(), result.getEmbedding(), prefix);
        }

        // Fire hierarchy update
        Platform.runLater(() -> {
            ImageData<BufferedImage> currentImageData = qupath.getImageData();
            if (currentImageData != null) {
                currentImageData.getHierarchy().fireHierarchyChangedEvent(this);
            }
        });

        String completeMsg = "Sub-clustering complete: " + result.getNClusters()
                + " sub-clusters in " + parentClusterName;
        report(progressCallback, completeMsg);

        // Audit trail
        long elapsed = System.currentTimeMillis() - startTime;
        OperationLogger.getInstance().logOperation("SUB-CLUSTERING",
                OperationLogger.subclusteringParams(
                        parentClusterName,
                        config.getAlgorithm().getDisplayName(),
                        extraction.getNCells()),
                completeMsg, elapsed);

        return result;
    }

    /**
     * Exports current image data as AnnData (.h5ad) file.
     * Includes measurements, cluster labels, phenotype labels, embedding, and spatial coordinates.
     *
     * @param selectedMeasurements measurements to include (null for all)
     * @param outputPath           path to write the .h5ad file
     * @param progressCallback     optional callback for progress messages
     * @throws IOException if export fails
     */
    public void exportAnnData(
            List<String> selectedMeasurements,
            String outputPath,
            Consumer<String> progressCallback) throws IOException {

        long startTime = System.currentTimeMillis();
        report(progressCallback, "Preparing AnnData export...");

        ImageData<BufferedImage> imageData = qupath.getImageData();
        if (imageData == null) {
            throw new IOException("No image is open");
        }

        List<PathObject> detections = new ArrayList<>(
                imageData.getHierarchy().getDetectionObjects());
        if (detections.isEmpty()) {
            throw new IOException("No detection objects found.");
        }

        // Determine measurements to export
        if (selectedMeasurements == null || selectedMeasurements.isEmpty()) {
            selectedMeasurements = MeasurementExtractor.getAllMeasurements(detections);
        }

        MeasurementExtractor extractor = new MeasurementExtractor();
        MeasurementExtractor.ExtractionResult extraction =
                extractor.extract(detections, selectedMeasurements);

        report(progressCallback, "Exporting " + extraction.getNCells() + " cells x "
                + extraction.getNMeasurements() + " markers...");

        try {
            ApposeClusteringService.withExtensionClassLoader(() -> {
                executeAnnDataExport(extraction, outputPath, progressCallback);
                return null;
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("AnnData export failed: " + e.getMessage(), e);
        }

        // Audit trail
        long elapsed = System.currentTimeMillis() - startTime;
        OperationLogger.getInstance().logOperation("EXPORT ANNDATA",
                OperationLogger.exportParams(
                        outputPath,
                        extraction.getNCells(),
                        extraction.getNMeasurements()),
                "Exported " + extraction.getNCells() + " cells x "
                        + extraction.getNMeasurements() + " markers",
                elapsed);

        report(progressCallback, "AnnData exported to " + outputPath);
    }

    /**
     * Executes the AnnData export task via Appose. Must be called with TCCL set.
     */
    private void executeAnnDataExport(
            MeasurementExtractor.ExtractionResult extraction,
            String outputPath,
            Consumer<String> progressCallback) throws IOException {

        int nCells = extraction.getNCells();
        int nMeasurements = extraction.getNMeasurements();
        double[][] data = extraction.getData();

        // Create measurement NDArray
        NDArray.Shape shape = new NDArray.Shape(NDArray.Shape.Order.C_ORDER, nCells, nMeasurements);
        NDArray measurementsNd = new NDArray(NDArray.DType.FLOAT64, shape);
        var buf = measurementsNd.buffer().asDoubleBuffer();
        for (int i = 0; i < nCells; i++) {
            buf.put(data[i]);
        }

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("measurements", measurementsNd);
        inputs.put("marker_names", List.of(extraction.getMeasurementNames()));
        inputs.put("output_path", outputPath);

        // Extract cluster labels from current PathClass
        List<Integer> clusterLabels = new ArrayList<>();
        List<String> phenotypeLabels = new ArrayList<>();
        boolean hasCluster = false;
        boolean hasPhenotype = false;

        for (PathObject det : extraction.getDetections()) {
            var pc = det.getPathClass();
            String className = pc != null ? pc.toString() : "";

            if (className.startsWith("Cluster ")) {
                try {
                    int label = Integer.parseInt(className.substring("Cluster ".length()).split("\\.")[0]);
                    clusterLabels.add(label);
                    hasCluster = true;
                } catch (NumberFormatException e) {
                    clusterLabels.add(-1);
                }
                phenotypeLabels.add(className);
            } else if (!className.isEmpty()) {
                clusterLabels.add(-1);
                phenotypeLabels.add(className);
                hasPhenotype = true;
            } else {
                clusterLabels.add(-1);
                phenotypeLabels.add("Unknown");
            }
        }

        if (hasCluster) {
            inputs.put("cluster_labels", clusterLabels);
        }
        if (hasPhenotype || hasCluster) {
            inputs.put("phenotype_labels", phenotypeLabels);
        }

        // Extract embedding coordinates if present
        double[][] embedding = extractExistingEmbedding(extraction.getDetections());
        NDArray embNd = null;
        if (embedding != null) {
            NDArray.Shape embShape = new NDArray.Shape(NDArray.Shape.Order.C_ORDER, nCells, 2);
            embNd = new NDArray(NDArray.DType.FLOAT64, embShape);
            var embBuf = embNd.buffer().asDoubleBuffer();
            for (int i = 0; i < nCells; i++) {
                embBuf.put(embedding[i]);
            }
            inputs.put("embedding", embNd);
        }

        // Extract spatial coordinates (centroids)
        double[][] centroids = MeasurementExtractor.extractCentroids(extraction.getDetections());
        NDArray.Shape spatialShape = new NDArray.Shape(NDArray.Shape.Order.C_ORDER, nCells, 2);
        NDArray spatialNd = new NDArray(NDArray.DType.FLOAT64, spatialShape);
        var spatialBuf = spatialNd.buffer().asDoubleBuffer();
        for (int i = 0; i < nCells; i++) {
            spatialBuf.put(centroids[i]);
        }
        inputs.put("spatial_coords", spatialNd);

        try {
            ApposeClusteringService service = ApposeClusteringService.getInstance();
            Task task = service.runTaskWithListener("export_anndata", inputs, event -> {
                if (event.responseType == ResponseType.UPDATE && event.message != null) {
                    report(progressCallback, event.message);
                }
            });

            boolean success = (boolean) task.outputs.get("success");
            if (!success) {
                throw new IOException("AnnData export reported failure");
            }

            int exportedCells = ((Number) task.outputs.get("n_cells")).intValue();
            int exportedMarkers = ((Number) task.outputs.get("n_markers")).intValue();
            logger.info("Exported AnnData: {} cells x {} markers to {}",
                    exportedCells, exportedMarkers, outputPath);
        } finally {
            measurementsNd.close();
            if (embNd != null) embNd.close();
            spatialNd.close();
        }
    }

    /**
     * Extracts existing embedding coordinates (UMAP1/UMAP2 or PCA1/PCA2 or tSNE1/tSNE2)
     * from detection measurements. Returns null if no embedding found.
     */
    private double[][] extractExistingEmbedding(List<PathObject> detections) {
        // Try UMAP, PCA, tSNE in order
        String[][] prefixes = {{"UMAP1", "UMAP2"}, {"PCA1", "PCA2"}, {"tSNE1", "tSNE2"}};

        for (String[] pair : prefixes) {
            PathObject first = detections.get(0);
            if (first.getMeasurements().containsKey(pair[0])
                    && first.getMeasurements().containsKey(pair[1])) {
                double[][] emb = new double[detections.size()][2];
                for (int i = 0; i < detections.size(); i++) {
                    var ml = detections.get(i).getMeasurements();
                    emb[i][0] = ml.getOrDefault(pair[0], 0.0).doubleValue();
                    emb[i][1] = ml.getOrDefault(pair[1], 0.0).doubleValue();
                }
                logger.info("Found existing embedding: {}/{}", pair[0], pair[1]);
                return emb;
            }
        }
        return null;
    }

    // ==================== Shared Tile Reading ====================

    /**
     * Reads RGB tile images centered on each detection's centroid and packs them
     * into a flat byte array suitable for transfer to Python via Appose NDArray.
     * <p>
     * The output array has shape (nDetections, tileSize, tileSize, 3) in row-major order,
     * with each pixel stored as R, G, B bytes. Out-of-bounds regions are zero-filled.
     *
     * @param server           the image server to read tiles from
     * @param detections       detection objects whose centroids define tile centers
     * @param tileSize         side length of each square tile in pixels
     * @param progressCallback optional progress callback (may be null)
     * @return packed RGB byte array of all tiles
     */
    private byte[] readTilesAroundCentroids(
            ImageServer<BufferedImage> server,
            List<PathObject> detections,
            int tileSize,
            Consumer<String> progressCallback) {

        int nCells = detections.size();
        int halfTile = tileSize / 2;
        byte[] tileData = new byte[nCells * tileSize * tileSize * 3];

        for (int i = 0; i < nCells; i++) {
            PathObject det = detections.get(i);
            double cx = det.getROI().getCentroidX();
            double cy = det.getROI().getCentroidY();

            int x = Math.max(0, (int) cx - halfTile);
            int y = Math.max(0, (int) cy - halfTile);

            // Clamp to image bounds
            x = Math.min(x, Math.max(0, server.getWidth() - tileSize));
            y = Math.min(y, Math.max(0, server.getHeight() - tileSize));

            int readW = Math.min(tileSize, server.getWidth() - x);
            int readH = Math.min(tileSize, server.getHeight() - y);

            try {
                RegionRequest request = RegionRequest.createInstance(
                        server.getPath(), 1.0, x, y, readW, readH);
                BufferedImage tile = server.readRegion(request);

                int offset = i * tileSize * tileSize * 3;
                for (int ty = 0; ty < tileSize; ty++) {
                    for (int tx = 0; tx < tileSize; tx++) {
                        if (tx < tile.getWidth() && ty < tile.getHeight()) {
                            int rgb = tile.getRGB(tx, ty);
                            tileData[offset++] = (byte) ((rgb >> 16) & 0xFF);
                            tileData[offset++] = (byte) ((rgb >> 8) & 0xFF);
                            tileData[offset++] = (byte) (rgb & 0xFF);
                        } else {
                            tileData[offset++] = 0;
                            tileData[offset++] = 0;
                            tileData[offset++] = 0;
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to read tile for detection {}: {}", i, e.getMessage());
            }

            if ((i + 1) % 500 == 0) {
                report(progressCallback, "Read " + (i + 1) + "/" + nCells + " tiles...");
            }
        }

        return tileData;
    }

    /**
     * Reads multi-channel tile images centered on each detection's centroid.
     * Returns float32 data packed as (nDetections, totalChannels, tileSize, tileSize)
     * in row-major (C-order) layout, suitable for PyTorch conv layers (NCHW).
     * <p>
     * Uses raster.getSampleFloat() to support any bit depth (8-bit, 16-bit, 32-bit).
     * Follows the multi-channel reading pattern from the DL pixel classifier extension.
     * <p>
     * When {@code includeMask} is true, appends one extra channel containing a binary
     * mask (1.0 inside the target cell's ROI, 0.0 outside). This follows the CellSighter
     * approach (Amitay et al. 2023, Nature Communications) where the mask acts as an
     * attention guide so the network knows which cell to classify while preserving
     * contextual information from neighboring cells.
     *
     * @param server           the image server to read tiles from
     * @param detections       detections whose centroids define tile centers
     * @param tileSize         side length of each square tile in pixels
     * @param includeMask      if true, append a binary cell mask channel
     * @param progressCallback optional progress callback
     * @return float array packed as NCHW (channels = image channels + mask if enabled)
     */
    private float[] readMultiChannelTilesAroundCentroids(
            ImageServer<BufferedImage> server,
            List<PathObject> detections,
            int tileSize,
            boolean includeMask,
            Consumer<String> progressCallback) {

        int nCells = detections.size();
        int imageChannels = server.nChannels();
        int totalChannels = imageChannels + (includeMask ? 1 : 0);
        int halfTile = tileSize / 2;
        long tileArraySize = (long) nCells * totalChannels * tileSize * tileSize;
        if (tileArraySize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Tile data too large: " + nCells + " cells * " + totalChannels
                    + " channels * " + tileSize + "x" + tileSize
                    + " = " + tileArraySize + " floats (max " + Integer.MAX_VALUE
                    + "). Reduce tile size, number of cells, or use measurement mode.");
        }
        float[] tileData = new float[(int) tileArraySize];

        for (int i = 0; i < nCells; i++) {
            PathObject det = detections.get(i);
            double cx = det.getROI().getCentroidX();
            double cy = det.getROI().getCentroidY();

            int x = Math.max(0, (int) cx - halfTile);
            int y = Math.max(0, (int) cy - halfTile);
            x = Math.min(x, Math.max(0, server.getWidth() - tileSize));
            y = Math.min(y, Math.max(0, server.getHeight() - tileSize));

            int readW = Math.min(tileSize, server.getWidth() - x);
            int readH = Math.min(tileSize, server.getHeight() - y);

            try {
                RegionRequest request = RegionRequest.createInstance(
                        server.getPath(), 1.0, x, y, readW, readH);
                BufferedImage tile = server.readRegion(request);
                var raster = tile.getRaster();

                // Pack image channels as NCHW: [cell_idx][channel][y][x]
                int cellOffset = i * totalChannels * tileSize * tileSize;
                for (int c = 0; c < imageChannels; c++) {
                    int channelOffset = cellOffset + c * tileSize * tileSize;
                    for (int ty = 0; ty < tileSize; ty++) {
                        for (int tx = 0; tx < tileSize; tx++) {
                            if (tx < tile.getWidth() && ty < tile.getHeight()) {
                                tileData[channelOffset + ty * tileSize + tx] =
                                        raster.getSampleFloat(tx, ty, c);
                            }
                        }
                    }
                }

                // Add binary cell mask channel (last channel)
                if (includeMask) {
                    java.awt.Shape roiShape = det.getROI().getShape();
                    int maskOffset = cellOffset + imageChannels * tileSize * tileSize;
                    for (int ty = 0; ty < tileSize; ty++) {
                        for (int tx = 0; tx < tileSize; tx++) {
                            // Convert tile pixel coords to image coords
                            double imgX = x + tx;
                            double imgY = y + ty;
                            if (roiShape.contains(imgX, imgY)) {
                                tileData[maskOffset + ty * tileSize + tx] = 1.0f;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to read tile for detection {}: {}", i, e.getMessage());
            }

            if ((i + 1) % 500 == 0) {
                report(progressCallback, "Read " + (i + 1) + "/" + nCells + " tiles...");
            }
        }

        return tileData;
    }

    // ==================== Feature Extraction (Foundation Models) ====================

    /**
     * Extracts foundation model features from tile images around each cell centroid.
     * Features are stored as measurements (FM_0, FM_1, ...) on each detection.
     * <p>
     * Integration approach inspired by LazySlide (MIT License).
     * Zheng, Y. et al. Nature Methods (2026).
     * <a href="https://doi.org/10.1038/s41592-026-03044-7">doi:10.1038/s41592-026-03044-7</a>
     *
     * @param modelName        foundation model identifier
     * @param tileSize         tile size in pixels around each centroid
     * @param batchSize        inference batch size
     * @param hfToken          HuggingFace auth token (may be null)
     * @param progressCallback optional progress callback
     * @return embedding dimensionality
     * @throws IOException if extraction fails
     */
    public int runFeatureExtraction(String modelName, int tileSize, int batchSize,
                                     String hfToken,
                                     Consumer<String> progressCallback) throws IOException {

        long startTime = System.currentTimeMillis();
        report(progressCallback, "Preparing tile images...");

        ImageData<BufferedImage> imageData = qupath.getImageData();
        if (imageData == null) throw new IOException("No image is open");

        ImageServer<BufferedImage> server = imageData.getServer();
        List<PathObject> detections = new ArrayList<>(
                imageData.getHierarchy().getDetectionObjects());

        if (detections.isEmpty())
            throw new IOException("No detections found. Run cell detection first.");

        int nCells = detections.size();

        report(progressCallback, "Reading " + nCells + " tile images (" + tileSize + "x" + tileSize + ")...");
        byte[] tileData = readTilesAroundCentroids(server, detections, tileSize, progressCallback);

        report(progressCallback, "Sending tiles to Python for feature extraction...");

        // Create NDArray for tile data
        int embedDim;
        try {
            embedDim = ApposeClusteringService.withExtensionClassLoader(() -> {
                NDArray.Shape shape = new NDArray.Shape(
                        NDArray.Shape.Order.C_ORDER, nCells, tileSize, tileSize, 3);
                NDArray tilesNd = new NDArray(NDArray.DType.INT8, shape);
                tilesNd.buffer().put(tileData);

                Map<String, Object> inputs = new HashMap<>();
                inputs.put("tile_images", tilesNd);
                inputs.put("model_name", modelName);
                inputs.put("batch_size", batchSize);
                if (hfToken != null) {
                    inputs.put("hf_token", hfToken);
                }

                ApposeClusteringService service = ApposeClusteringService.getInstance();
                Task task = service.runTaskWithListener("extract_features", inputs, event -> {
                    if (event.responseType == ResponseType.UPDATE && event.message != null) {
                        report(progressCallback, event.message);
                    }
                });

                // Parse results
                NDArray featuresNd = (NDArray) task.outputs.get("features");
                int dim = ((Number) task.outputs.get("embed_dim")).intValue();

                // Read features into array
                float[] featuresBuf = new float[nCells * dim];
                featuresNd.buffer().asFloatBuffer().get(featuresBuf);

                // Apply features as measurements on detections
                report(progressCallback, "Applying " + dim + "-d features as measurements...");
                for (int i = 0; i < nCells; i++) {
                    PathObject det = detections.get(i);
                    var ml = det.getMeasurementList();
                    for (int d = 0; d < dim; d++) {
                        ml.put("FM_" + d, featuresBuf[i * dim + d]);
                    }
                }

                // Cleanup
                try { tilesNd.close(); } catch (Exception ignored) {}
                try { featuresNd.close(); } catch (Exception ignored) {}

                return dim;
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Feature extraction failed: " + e.getMessage(), e);
        }

        // Fire hierarchy update
        Platform.runLater(() -> {
            ImageData<BufferedImage> currentImageData = qupath.getImageData();
            if (currentImageData != null) {
                currentImageData.getHierarchy().fireHierarchyChangedEvent(this);
            }
        });

        long elapsed = System.currentTimeMillis() - startTime;
        String msg = "Feature extraction: " + embedDim + "-dim from " + modelName
                + " for " + nCells + " cells";
        report(progressCallback, msg);
        OperationLogger.getInstance().logOperation("FEATURE_EXTRACTION",
                Map.of("Model", modelName,
                       "TileSize", String.valueOf(tileSize),
                       "EmbedDim", String.valueOf(embedDim),
                       "Cells", String.valueOf(nCells)),
                msg, elapsed);

        return embedDim;
    }

    // ==================== Zero-Shot Phenotyping ====================

    /**
     * Runs zero-shot phenotyping using a vision-language model (BiomedCLIP).
     * Tile images around cell centroids are scored against text prompts via
     * cosine similarity.
     * <p>
     * Uses BiomedCLIP (MIT License, Microsoft).
     * Approach inspired by LazySlide (MIT License).
     * Zheng, Y. et al. Nature Methods (2026).
     * <a href="https://doi.org/10.1038/s41592-026-03044-7">doi:10.1038/s41592-026-03044-7</a>
     *
     * @param phenotypeNames   display names for each phenotype
     * @param phenotypePrompts text prompts for each phenotype
     * @param tileSize         tile size in pixels
     * @param batchSize        inference batch size
     * @param minSimilarity    minimum cosine similarity for assignment
     * @param mode             "argmax" or "scores"
     * @param progressCallback optional progress callback
     * @return result map with phenotype_counts, labels, etc.
     * @throws IOException if phenotyping fails
     */
    public Map<String, Object> runZeroShotPhenotyping(
            List<String> phenotypeNames,
            List<String> phenotypePrompts,
            int tileSize, int batchSize,
            double minSimilarity, String mode,
            Consumer<String> progressCallback) throws IOException {

        long startTime = System.currentTimeMillis();
        report(progressCallback, "Preparing tile images for zero-shot phenotyping...");

        ImageData<BufferedImage> imageData = qupath.getImageData();
        if (imageData == null) throw new IOException("No image is open");

        ImageServer<BufferedImage> server = imageData.getServer();
        List<PathObject> detections = new ArrayList<>(
                imageData.getHierarchy().getDetectionObjects());

        if (detections.isEmpty())
            throw new IOException("No detections found. Run cell detection first.");

        int nCells = detections.size();

        report(progressCallback, "Reading " + nCells + " tile images...");
        byte[] tileData = readTilesAroundCentroids(server, detections, tileSize, progressCallback);

        report(progressCallback, "Running zero-shot phenotyping via BiomedCLIP...");

        Map<String, Object> resultMap = new HashMap<>();
        try {
            ApposeClusteringService.withExtensionClassLoader(() -> {
                NDArray.Shape shape = new NDArray.Shape(
                        NDArray.Shape.Order.C_ORDER, nCells, tileSize, tileSize, 3);
                NDArray tilesNd = new NDArray(NDArray.DType.INT8, shape);
                tilesNd.buffer().put(tileData);

                Map<String, Object> inputs = new HashMap<>();
                inputs.put("tile_images", tilesNd);
                inputs.put("phenotype_prompts", phenotypePrompts);
                inputs.put("phenotype_names", phenotypeNames);
                inputs.put("batch_size", batchSize);
                inputs.put("min_similarity", minSimilarity);
                inputs.put("assignment_mode", mode);

                ApposeClusteringService service = ApposeClusteringService.getInstance();
                Task task = service.runTaskWithListener(
                        "zero_shot_phenotyping", inputs, event -> {
                    if (event.responseType == ResponseType.UPDATE && event.message != null) {
                        report(progressCallback, event.message);
                    }
                });

                // Parse results
                NDArray labelsNd = (NDArray) task.outputs.get("phenotype_labels");
                int[] labels = new int[nCells];
                labelsNd.buffer().asIntBuffer().get(labels);

                String countsJson = String.valueOf(task.outputs.get("phenotype_counts"));
                String namesJson = String.valueOf(task.outputs.get("phenotype_names_out"));
                resultMap.put("phenotype_counts", countsJson);
                resultMap.put("labels", labels);

                // Apply labels as PathClass on detections
                report(progressCallback, "Applying phenotype labels...");
                ResultApplier applier = new ResultApplier();
                applier.applyPhenotypeLabels(detections, labels,
                        phenotypeNames.toArray(new String[0]));

                // If soft mode, also store similarity scores as measurements
                if ("scores".equals(mode)) {
                    NDArray simNd = (NDArray) task.outputs.get("similarity_scores");
                    if (simNd != null) {
                        int nPhenotypes = phenotypeNames.size();
                        float[] simBuf = new float[nCells * nPhenotypes];
                        simNd.buffer().asFloatBuffer().get(simBuf);

                        for (int i = 0; i < nCells; i++) {
                            var ml = detections.get(i).getMeasurementList();
                            for (int p = 0; p < nPhenotypes; p++) {
                                ml.put("ZS_" + phenotypeNames.get(p),
                                        simBuf[i * nPhenotypes + p]);
                            }
                        }
                        try { simNd.close(); } catch (Exception ignored) {}
                    }
                }

                try { tilesNd.close(); } catch (Exception ignored) {}
                try { labelsNd.close(); } catch (Exception ignored) {}
                return null;
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Zero-shot phenotyping failed: " + e.getMessage(), e);
        }

        // Fire hierarchy update
        Platform.runLater(() -> {
            ImageData<BufferedImage> currentImageData = qupath.getImageData();
            if (currentImageData != null) {
                currentImageData.getHierarchy().fireHierarchyChangedEvent(this);
            }
        });

        long elapsed = System.currentTimeMillis() - startTime;
        String msg = "Zero-shot: " + phenotypeNames.size() + " phenotypes for " + nCells + " cells";
        report(progressCallback, msg);
        OperationLogger.getInstance().logOperation("ZERO_SHOT_PHENOTYPING",
                Map.of("Phenotypes", String.valueOf(phenotypeNames.size()),
                       "TileSize", String.valueOf(tileSize),
                       "MinSimilarity", String.valueOf(minSimilarity),
                       "Mode", mode,
                       "Cells", String.valueOf(nCells)),
                msg, elapsed);

        return resultMap;
    }

    // ==================== Autoencoder Training & Inference [TEST FEATURE] ====================

    /**
     * Extracts class labels for detections from multiple sources.
     * <p>
     * Label sources (any combination):
     * <ul>
     *   <li><b>Locked annotations:</b> detections inside a locked, classified annotation
     *       inherit the annotation's class. Most detections in the region get labeled.</li>
     *   <li><b>Point annotations:</b> each point in a classified points annotation
     *       labels the nearest detection within 50 pixels.</li>
     *   <li><b>Detection classifications:</b> existing PathClass on detections
     *       (excluding "Cluster *" from prior clustering runs).</li>
     * </ul>
     * Priority: detection class > locked annotation > point annotation (if multiple
     * sources label the same cell, detection class wins).
     *
     * @param hierarchy      the object hierarchy for spatial queries
     * @param detections     ordered detection list
     * @param classNames     output: populated with discovered class names in order
     * @param useLocked      include labels from locked annotations
     * @param usePoints      include labels from point annotations
     * @param useDetections  include labels from existing detection classifications
     * @return int array with class index per detection (-1 = unlabeled)
     */
    private static int[] extractClassLabels(PathObjectHierarchy hierarchy,
                                             List<PathObject> detections,
                                             List<String> classNames,
                                             boolean useLocked,
                                             boolean usePoints,
                                             boolean useDetections) {
        int n = detections.size();
        String[] assignedClass = new String[n];

        // Build detection index for spatial lookup
        Map<PathObject, Integer> detectionIndex = new HashMap<>();
        for (int i = 0; i < n; i++) {
            detectionIndex.put(detections.get(i), i);
        }

        // Source 1: Locked annotations -> label all detections inside
        if (useLocked) {
            for (PathObject annotation : hierarchy.getAnnotationObjects()) {
                if (!annotation.isLocked()) continue;
                PathClass pc = annotation.getPathClass();
                if (pc == null || pc == PathClass.getNullClass()) continue;
                String className = pc.toString();
                if (className.startsWith("Cluster ")) continue;

                // Find detections inside this annotation
                Collection<PathObject> inside =
                        hierarchy.getAllDetectionsForROI(annotation.getROI());
                for (PathObject det : inside) {
                    Integer idx = detectionIndex.get(det);
                    if (idx != null && assignedClass[idx] == null) {
                        assignedClass[idx] = className;
                    }
                }
            }
            int lockedCount = 0;
            for (String s : assignedClass) if (s != null) lockedCount++;
            if (lockedCount > 0)
                logger.info("Labels from locked annotations: {} cells", lockedCount);
        }

        // Source 2: Point annotations -> label nearest detection to each point
        if (usePoints) {
            int pointLabeled = 0;
            for (PathObject annotation : hierarchy.getAnnotationObjects()) {
                if (annotation.getROI() == null || !annotation.getROI().isPoint()) continue;
                PathClass pc = annotation.getPathClass();
                if (pc == null || pc == PathClass.getNullClass()) continue;
                String className = pc.toString();
                if (className.startsWith("Cluster ")) continue;

                for (var point : annotation.getROI().getAllPoints()) {
                    double px = point.getX();
                    double py = point.getY();

                    // Find nearest detection within 50 pixels
                    double bestDist = 50.0 * 50.0;
                    int bestIdx = -1;
                    for (int i = 0; i < n; i++) {
                        double cx = detections.get(i).getROI().getCentroidX();
                        double cy = detections.get(i).getROI().getCentroidY();
                        double dist = (cx - px) * (cx - px) + (cy - py) * (cy - py);
                        if (dist < bestDist) {
                            bestDist = dist;
                            bestIdx = i;
                        }
                    }
                    if (bestIdx >= 0 && assignedClass[bestIdx] == null) {
                        assignedClass[bestIdx] = className;
                        pointLabeled++;
                    }
                }
            }
            if (pointLabeled > 0)
                logger.info("Labels from point annotations: {} cells", pointLabeled);
        }

        // Source 3: Detection classifications (overrides other sources)
        // Unclassified (null PathClass or NULL_CLASS) is treated as a valid
        // "Unclassified" class -- the classifier needs to learn what "not any
        // specific type" looks like. Cluster labels from prior runs are still skipped.
        if (useDetections) {
            int detLabeled = 0;
            for (int i = 0; i < n; i++) {
                PathClass pc = detections.get(i).getPathClass();
                String name;
                if (pc == null || pc == PathClass.getNullClass()) {
                    name = "Unclassified";
                } else {
                    name = pc.toString();
                    if (name.startsWith("Cluster ")) continue;
                }
                assignedClass[i] = name;
                detLabeled++;
            }
            if (detLabeled > 0)
                logger.info("Labels from detection classes: {} cells", detLabeled);
        }

        // Discover unique class names
        LinkedHashSet<String> uniqueClasses = new LinkedHashSet<>();
        for (String s : assignedClass) {
            if (s != null) uniqueClasses.add(s);
        }
        classNames.addAll(uniqueClasses);

        // Map to integer indices
        List<String> nameList = new ArrayList<>(classNames);
        int[] labels = new int[n];
        for (int i = 0; i < n; i++) {
            labels[i] = assignedClass[i] != null ? nameList.indexOf(assignedClass[i]) : -1;
        }
        return labels;
    }

    /**
     * [TEST FEATURE] Trains a VAE classifier on detections from the current image.
     *
     * @param selectedMeasurements measurements to use as input features
     * @param normalization        normalization method id
     * @param latentDim            latent space dimensionality
     * @param epochs               training epochs
     * @param learningRate         optimizer learning rate
     * @param batchSize            training batch size
     * @param supervisionWeight    weight of classification loss
     * @param progressCallback     optional progress callback
     * @return result map with model_state, class_names, accuracy, n_classes
     * @throws IOException if training fails
     */
    /**
     * [TEST FEATURE] Trains a VAE classifier on detections from the current image.
     *
     * @param selectedMeasurements measurements to use (for measurement mode)
     * @param normalization        normalization method id
     * @param latentDim            latent space dimensionality
     * @param epochs               training epochs
     * @param learningRate         optimizer learning rate
     * @param batchSize            training batch size
     * @param supervisionWeight    weight of classification loss
     * @param inputMode            "measurements" or "tiles"
     * @param tileSize             tile size for pixel mode (ignored in measurement mode)
     * @param includeCellMask      if true, add cell ROI mask as extra channel (tile mode only)
     * @param progressCallback     optional progress callback
     * @return result map with model_state, class_names, accuracy, n_classes
     * @throws IOException if training fails
     */
    /**
     * [TEST FEATURE] Trains a VAE classifier on detections from selected project images.
     *
     * @param selectedImages       project images to include in training (null = current image only)
     * @param selectedMeasurements measurements to use (for measurement mode)
     * @param normalization        normalization method id
     * @param latentDim            latent space dimensionality
     * @param epochs               training epochs
     * @param learningRate         optimizer learning rate
     * @param batchSize            training batch size
     * @param supervisionWeight    weight of classification loss
     * @param inputMode            "measurements" or "tiles"
     * @param tileSize             tile size for pixel mode
     * @param includeCellMask      if true, add cell ROI mask as extra channel
     * @param validationSplit      fraction held out for validation
     * @param earlyStoppingPatience patience for early stopping (0 = disabled)
     * @param enableClassWeights   use inverse-frequency class weights
     * @param enableAugmentation   apply data augmentation
     * @param labelFromLocked      read labels from locked annotations
     * @param labelFromPoints      read labels from point annotations
     * @param labelFromDetections  read labels from detection classifications
     * @param cellsOnly            filter to cell objects only
     * @param progressCallback     optional progress callback
     * @return result map with model_state, class_names, accuracy, n_classes
     */
    public Map<String, Object> runAutoencoderTraining(
            List<ProjectImageEntry<BufferedImage>> selectedImages,
            List<String> selectedMeasurements, String normalization,
            int latentDim, int epochs, double learningRate,
            int batchSize, double supervisionWeight,
            String inputMode, int tileSize, boolean includeCellMask,
            double validationSplit, int earlyStoppingPatience,
            boolean enableClassWeights, boolean enableAugmentation,
            boolean labelFromLocked, boolean labelFromPoints, boolean labelFromDetections,
            boolean cellsOnly,
            Consumer<String> progressCallback) throws IOException {

        long startTime = System.currentTimeMillis();
        boolean useTiles = "tiles".equals(inputMode);

        // Gather detections from all selected images
        List<PathObject> allDetections = new ArrayList<>();
        List<MeasurementExtractor.ImageDetectionGroup> groups = new ArrayList<>();
        List<String> classNames = new ArrayList<>();
        List<Integer> allLabels = new ArrayList<>();

        // Build image list: use selected entries, or fall back to current image
        List<ImageData<BufferedImage>> imageDatas = new ArrayList<>();
        if (selectedImages != null && !selectedImages.isEmpty()) {
            for (int idx = 0; idx < selectedImages.size(); idx++) {
                ProjectImageEntry<BufferedImage> entry = selectedImages.get(idx);
                report(progressCallback, "Loading image " + (idx + 1) + "/"
                        + selectedImages.size() + ": " + entry.getImageName());
                try {
                    // Use live ImageData for current image
                    var currentData = qupath.getImageData();
                    var currentEntry = (qupath.getProject() != null && currentData != null)
                            ? qupath.getProject().getEntry(currentData) : null;
                    if (currentEntry != null && currentEntry.equals(entry)) {
                        imageDatas.add(currentData);
                    } else {
                        imageDatas.add(entry.readImageData());
                    }
                } catch (Exception e) {
                    logger.warn("Failed to read {}: {}", entry.getImageName(), e.getMessage());
                }
            }
        } else {
            ImageData<BufferedImage> current = qupath.getImageData();
            if (current == null) throw new IOException("No image is open");
            imageDatas.add(current);
        }

        if (imageDatas.isEmpty()) throw new IOException("No images could be loaded.");

        // Collect detections and labels from each image
        for (ImageData<BufferedImage> imageData : imageDatas) {
            PathObjectHierarchy hierarchy = imageData.getHierarchy();
            List<PathObject> dets = new ArrayList<>(hierarchy.getDetectionObjects());
            if (cellsOnly) dets.removeIf(d -> !d.isCell());
            if (dets.isEmpty()) continue;

            // Extract labels for this image's detections
            List<String> imgClassNames = new ArrayList<>();
            int[] imgLabels = extractClassLabels(hierarchy, dets, imgClassNames,
                    labelFromLocked, labelFromPoints, labelFromDetections);

            // Merge class names (maintain consistent ordering across images)
            for (String cn : imgClassNames) {
                if (!classNames.contains(cn)) classNames.add(cn);
            }

            // Re-map labels to the merged class name order
            for (int i = 0; i < dets.size(); i++) {
                if (imgLabels[i] >= 0) {
                    String cn = imgClassNames.get(imgLabels[i]);
                    allLabels.add(classNames.indexOf(cn));
                } else {
                    allLabels.add(-1);
                }
            }

            allDetections.addAll(dets);
            groups.add(new MeasurementExtractor.ImageDetectionGroup(
                    null, imageData, dets));
        }

        if (allDetections.isEmpty())
            throw new IOException("No " + (cellsOnly ? "cell objects" : "detections")
                    + " found in selected images.");

        int[] classLabels = allLabels.stream().mapToInt(Integer::intValue).toArray();
        int nLabeled = 0;
        for (int l : classLabels) if (l >= 0) nLabeled++;
        logger.info("[TEST] Autoencoder ({}): {} cells from {} images, {} labeled, {} classes",
                inputMode, allDetections.size(), imageDatas.size(), nLabeled, classNames.size());

        // For measurement mode, extract measurements across all images
        MeasurementExtractor.ExtractionResult extraction = null;
        if (!useTiles) {
            report(progressCallback, "Extracting measurements from "
                    + imageDatas.size() + " images...");
            MeasurementExtractor extractor = new MeasurementExtractor();
            extraction = extractor.extractMultiImage(groups, selectedMeasurements);
        }

        // For tile mode, write tiles to a temp file that Python memory-maps.
        // This scales to any dataset size without holding all tiles in Java memory.
        Path tileTempFile = null;
        int nChannels = 0;
        if (useTiles) {
            // Determine channel count from first image
            for (ImageData<BufferedImage> imageData : imageDatas) {
                nChannels = imageData.getServer().nChannels();
                if (includeCellMask) nChannels++;
                break;
            }

            // Store temp file inside the project folder (can be several GB)
            Path tempDir = getProjectTempDir();
            tileTempFile = Files.createTempFile(tempDir, "qpcat_tiles_", ".bin");
            long totalFloats = 0;

            try (var raf = new java.io.RandomAccessFile(tileTempFile.toFile(), "rw")) {
                for (ImageData<BufferedImage> imageData : imageDatas) {
                    ImageServer<BufferedImage> server = imageData.getServer();
                    List<PathObject> dets = new ArrayList<>(
                            imageData.getHierarchy().getDetectionObjects());
                    if (cellsOnly) dets.removeIf(d -> !d.isCell());
                    if (dets.isEmpty()) continue;

                    report(progressCallback, "Writing tiles from "
                            + server.getMetadata().getName()
                            + " (" + dets.size() + " cells)...");

                    // Process in batches of 500 to limit per-batch memory
                    int tileBatchSize = 500;
                    for (int batchStart = 0; batchStart < dets.size(); batchStart += tileBatchSize) {
                        int batchEnd = Math.min(batchStart + tileBatchSize, dets.size());
                        List<PathObject> batch = dets.subList(batchStart, batchEnd);
                        float[] batchTiles = readMultiChannelTilesAroundCentroids(
                                server, batch, tileSize, includeCellMask, null);

                        // Write floats as little-endian bytes (numpy float32 format)
                        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(batchTiles.length * 4);
                        bb.order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        bb.asFloatBuffer().put(batchTiles);
                        raf.write(bb.array());
                        totalFloats += batchTiles.length;
                    }
                }
            }

            long expectedFloats = (long) allDetections.size() * nChannels * tileSize * tileSize;
            logger.info("Tile data written to temp file: expected {} floats ({} MB)",
                    expectedFloats, expectedFloats * 4 / (1024 * 1024));
        }

        int nCells = allDetections.size();
        report(progressCallback, "Training autoencoder (" + nCells
                + " cells, " + nLabeled + " labeled)...");

        Map<String, Object> resultMap = new HashMap<>();
        final MeasurementExtractor.ExtractionResult finalExtraction = extraction;
        final Path finalTileTempFile = tileTempFile;
        final int finalNChannels = nChannels;
        try {
            ApposeClusteringService.withExtensionClassLoader(() -> {
                Map<String, Object> inputs = new HashMap<>();
                inputs.put("input_mode", inputMode);
                inputs.put("labels", classLabels.length > 0
                        ? toIntList(classLabels) : List.of());
                inputs.put("label_names", classNames);
                inputs.put("latent_dim", latentDim);
                inputs.put("n_epochs", epochs);
                inputs.put("learning_rate", learningRate);
                inputs.put("batch_size", batchSize);
                inputs.put("supervision_weight", supervisionWeight);
                inputs.put("normalization", normalization);
                inputs.put("validation_split", validationSplit);
                inputs.put("early_stopping_patience", earlyStoppingPatience);
                inputs.put("enable_class_weights", enableClassWeights);
                inputs.put("enable_augmentation", enableAugmentation);

                if (!useTiles) {
                    // Measurement mode
                    int nMeasurements = finalExtraction.getNMeasurements();
                    NDArray.Shape shape = new NDArray.Shape(
                            NDArray.Shape.Order.C_ORDER, nCells, nMeasurements);
                    NDArray measurementsNd = new NDArray(NDArray.DType.FLOAT64, shape);
                    var buf = measurementsNd.buffer().asDoubleBuffer();
                    for (double[] row : finalExtraction.getData()) buf.put(row);
                    inputs.put("measurements", measurementsNd);
                    inputs.put("marker_names", List.of(finalExtraction.getMeasurementNames()));
                } else {
                    // Tile mode: pass file path for Python to memory-map
                    inputs.put("tile_file_path", finalTileTempFile.toAbsolutePath().toString());
                    inputs.put("n_cells", nCells);
                    inputs.put("n_channels", finalNChannels);
                    inputs.put("tile_size", tileSize);
                }

                ApposeClusteringService service = ApposeClusteringService.getInstance();
                Task task = service.runTaskWithListener("train_autoencoder", inputs, event -> {
                    if (event.responseType == ResponseType.UPDATE && event.message != null) {
                        report(progressCallback, event.message);
                    }
                });

                // Parse results
                NDArray latentNd = (NDArray) task.outputs.get("latent_features");
                NDArray predNd = (NDArray) task.outputs.get("predicted_labels");
                NDArray confNd = (NDArray) task.outputs.get("prediction_confidence");

                float[] latentBuf = new float[nCells * latentDim];
                latentNd.buffer().asFloatBuffer().get(latentBuf);
                int[] predLabels = new int[nCells];
                predNd.buffer().asIntBuffer().get(predLabels);
                float[] confidence = new float[nCells];
                confNd.buffer().asFloatBuffer().get(confidence);

                // Apply latent features as measurements
                List<PathObject> targetDetections = useTiles
                        ? allDetections : finalExtraction.getDetections();
                for (int i = 0; i < nCells; i++) {
                    var ml = targetDetections.get(i).getMeasurements();
                    for (int d = 0; d < latentDim; d++) {
                        ml.put("AE_" + d, (double) latentBuf[i * latentDim + d]);
                    }
                    ml.put("AE_confidence", (double) confidence[i]);
                }

                // Apply predicted labels
                if (!classNames.isEmpty()) {
                    ResultApplier applier = new ResultApplier();
                    applier.applyPhenotypeLabels(targetDetections,
                            predLabels, classNames.toArray(new String[0]));
                }

                resultMap.put("model_state",
                        String.valueOf(task.outputs.get("model_state_base64")));
                resultMap.put("class_names", classNames.toArray(new String[0]));
                resultMap.put("accuracy", task.outputs.get("final_class_accuracy"));
                resultMap.put("n_classes", task.outputs.get("n_classes"));

                try { latentNd.close(); } catch (Exception ignored) {}
                try { predNd.close(); } catch (Exception ignored) {}
                try { confNd.close(); } catch (Exception ignored) {}

                return null;
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Autoencoder training failed: " + e.getMessage(), e);
        }

        // Save results for non-current images; fire hierarchy update for current
        if (selectedImages != null) {
            report(progressCallback, "Saving results to " + imageDatas.size() + " images...");
            for (int i = 0; i < selectedImages.size() && i < imageDatas.size(); i++) {
                var currentData = qupath.getImageData();
                var currentEntry = (qupath.getProject() != null && currentData != null)
                        ? qupath.getProject().getEntry(currentData) : null;
                if (currentEntry != null && currentEntry.equals(selectedImages.get(i))) {
                    // Current image: already modified in-memory, just fire update
                    continue;
                }
                try {
                    selectedImages.get(i).saveImageData(imageDatas.get(i));
                    logger.info("Saved training results for {}",
                            selectedImages.get(i).getImageName());
                } catch (Exception e) {
                    logger.error("Failed to save {}: {}",
                            selectedImages.get(i).getImageName(), e.getMessage());
                }
            }
        }

        Platform.runLater(() -> {
            ImageData<BufferedImage> current = qupath.getImageData();
            if (current != null) {
                current.getHierarchy().fireHierarchyChangedEvent(this);
            }
        });

        long elapsed = System.currentTimeMillis() - startTime;
        OperationLogger.getInstance().logOperation("AUTOENCODER_TRAIN",
                Map.of("Cells", String.valueOf(nCells),
                       "Images", String.valueOf(imageDatas.size()),
                       "Labeled", String.valueOf(nLabeled),
                       "Classes", String.valueOf(classNames.size()),
                       "LatentDim", String.valueOf(latentDim),
                       "Epochs", String.valueOf(epochs)),
                "[TEST] Autoencoder trained", elapsed);

        // Clean up tile temp file
        deleteTempFile(tileTempFile);

        return resultMap;
    }

    /**
     * [TEST FEATURE] Applies a trained autoencoder to all images in a project.
     *
     * @param imageEntries     project images to apply to
     * @param measurements     measurement names (must match training)
     * @param modelStateBase64 base64-encoded model checkpoint
     * @param classNames       class names from training
     * @param progressCallback optional progress callback
     * @throws IOException if application fails
     */
    public void applyAutoencoderToProject(
            List<ProjectImageEntry<BufferedImage>> imageEntries,
            List<String> measurements,
            String modelStateBase64,
            String[] classNames,
            String inputMode, int tileSize, boolean includeCellMask,
            boolean cellsOnly,
            Consumer<String> progressCallback) throws IOException {

        long startTime = System.currentTimeMillis();
        int totalApplied = 0;

        for (int idx = 0; idx < imageEntries.size(); idx++) {
            ProjectImageEntry<BufferedImage> entry = imageEntries.get(idx);
            report(progressCallback, "Processing image " + (idx + 1) + "/"
                    + imageEntries.size() + ": " + entry.getImageName());

            // Use in-memory ImageData for the currently-open image to ensure
            // modifications are visible in the QuPath viewer and not overwritten
            // when the user saves via QuPath's normal save mechanism.
            ImageData<BufferedImage> imageData;
            boolean isCurrentImage = false;
            try {
                var currentData = qupath.getImageData();
                var currentEntry = (qupath.getProject() != null && currentData != null)
                        ? qupath.getProject().getEntry(currentData) : null;
                if (currentEntry != null && currentEntry.equals(entry)) {
                    imageData = currentData;
                    isCurrentImage = true;
                } else {
                    imageData = entry.readImageData();
                }
            } catch (Exception e) {
                logger.warn("Failed to read {}: {}", entry.getImageName(), e.getMessage());
                continue;
            }

            List<PathObject> allDetections = new ArrayList<>(
                    imageData.getHierarchy().getDetectionObjects());
            if (cellsOnly) {
                allDetections.removeIf(d -> !d.isCell());
            }
            if (allDetections.isEmpty()) {
                logger.info("Skipping {} - no {} found", entry.getImageName(),
                        cellsOnly ? "cell objects" : "detections");
                continue;
            }

            boolean useTiles = "tiles".equals(inputMode);

            // Filter out objects with missing measurements (measurement mode only)
            List<PathObject> detections;
            int skippedMissing = 0;
            if (!useTiles && measurements != null && !measurements.isEmpty()) {
                detections = new ArrayList<>();
                for (PathObject det : allDetections) {
                    boolean hasMissing = false;
                    for (String m : measurements) {
                        Number val = det.getMeasurements().get(m);
                        if (val == null) {
                            hasMissing = true;
                            break;
                        }
                    }
                    if (hasMissing) {
                        skippedMissing++;
                    } else {
                        detections.add(det);
                    }
                }
                if (skippedMissing > 0) {
                    logger.error("{}: {} out of {} objects did not have all needed measurements "
                            + "-- classification was not changed for these objects",
                            entry.getImageName(), skippedMissing, allDetections.size());
                }
                if (detections.isEmpty()) {
                    logger.error("{}: ALL objects missing measurements -- skipping image",
                            entry.getImageName());
                    continue;
                }
            } else {
                detections = allDetections;
            }

            // Prepare input data based on mode
            MeasurementExtractor.ExtractionResult extraction = null;
            Path inferTileFile = null;
            int nChannels = 0;
            if (useTiles) {
                ImageServer<BufferedImage> server = imageData.getServer();
                nChannels = server.nChannels();
                if (includeCellMask) nChannels++;

                // Write tiles to temp file in batches (same pattern as training)
                inferTileFile = Files.createTempFile(
                        getProjectTempDir(), "qpcat_infer_", ".bin");
                try (var raf = new java.io.RandomAccessFile(inferTileFile.toFile(), "rw")) {
                    int batchSz = 500;
                    for (int bs = 0; bs < detections.size(); bs += batchSz) {
                        int be = Math.min(bs + batchSz, detections.size());
                        float[] batch = readMultiChannelTilesAroundCentroids(
                                server, detections.subList(bs, be), tileSize, includeCellMask, null);
                        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(batch.length * 4);
                        bb.order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        bb.asFloatBuffer().put(batch);
                        raf.write(bb.array());
                    }
                }
            } else {
                MeasurementExtractor extractor = new MeasurementExtractor();
                try {
                    extraction = extractor.extract(detections, measurements);
                } catch (Exception e) {
                    logger.warn("Failed to extract from {}: {}", entry.getImageName(), e.getMessage());
                    continue;
                }
            }

            final MeasurementExtractor.ExtractionResult finalExtraction = extraction;
            final Path finalInferTileFile = inferTileFile;
            final int finalNChannels = nChannels;

            try {
                ApposeClusteringService.withExtensionClassLoader(() -> {
                    int nCells = detections.size();
                    Map<String, Object> inputs = new HashMap<>();
                    inputs.put("model_state_base64", modelStateBase64);

                    if (useTiles) {
                        inputs.put("tile_file_path",
                                finalInferTileFile.toAbsolutePath().toString());
                        inputs.put("n_cells", nCells);
                        inputs.put("n_channels", finalNChannels);
                        inputs.put("tile_size", tileSize);
                    } else {
                        int nMeasurements = finalExtraction.getNMeasurements();
                        NDArray.Shape shape = new NDArray.Shape(
                                NDArray.Shape.Order.C_ORDER, nCells, nMeasurements);
                        NDArray measurementsNd = new NDArray(NDArray.DType.FLOAT64, shape);
                        var buf = measurementsNd.buffer().asDoubleBuffer();
                        for (double[] row : finalExtraction.getData()) buf.put(row);
                        inputs.put("measurements", measurementsNd);
                        inputs.put("marker_names", List.of(finalExtraction.getMeasurementNames()));
                    }

                    ApposeClusteringService service = ApposeClusteringService.getInstance();
                    Task task = service.runTaskWithListener("infer_autoencoder", inputs, event -> {
                        if (event.responseType == ResponseType.UPDATE && event.message != null) {
                            report(progressCallback, event.message);
                        }
                    });

                    NDArray latentNd = (NDArray) task.outputs.get("latent_features");
                    NDArray predNd = (NDArray) task.outputs.get("predicted_labels");
                    NDArray confNd = (NDArray) task.outputs.get("prediction_confidence");

                    // Infer latent dim from buffer size
                    int latentBufSize = latentNd.buffer().asFloatBuffer().remaining();
                    int latentDim = latentBufSize / nCells;

                    float[] latentBuf = new float[nCells * latentDim];
                    latentNd.buffer().asFloatBuffer().get(latentBuf);
                    int[] predLabels = new int[nCells];
                    predNd.buffer().asIntBuffer().get(predLabels);
                    float[] confidence = new float[nCells];
                    confNd.buffer().asFloatBuffer().get(confidence);

                    // Apply to detections
                    List<PathObject> targetDets = useTiles
                            ? detections
                            : finalExtraction.getDetections();
                    for (int i = 0; i < nCells; i++) {
                        var ml = targetDets.get(i).getMeasurements();
                        for (int d = 0; d < latentDim; d++) {
                            ml.put("AE_" + d, (double) latentBuf[i * latentDim + d]);
                        }
                        ml.put("AE_confidence", (double) confidence[i]);
                    }

                    if (classNames != null && classNames.length > 0) {
                        ResultApplier applier = new ResultApplier();
                        applier.applyPhenotypeLabels(targetDets,
                                predLabels, classNames);
                    }

                    try { latentNd.close(); } catch (Exception ignored) {}
                    try { predNd.close(); } catch (Exception ignored) {}
                    try { confNd.close(); } catch (Exception ignored) {}

                    return null;
                });
            } catch (Exception e) {
                logger.error("Failed to apply autoencoder to {}: {}",
                        entry.getImageName(), e.getMessage());
                continue;
            }

            // Save results: for current image, modifications are already in the live
            // hierarchy (viewer updates immediately). For other images, save to disk.
            if (isCurrentImage) {
                totalApplied++;
                logger.info("Applied autoencoder to current image {} ({} detections, in-memory)",
                        entry.getImageName(), detections.size());
            } else {
                try {
                    entry.saveImageData(imageData);
                    totalApplied++;
                    logger.info("Saved autoencoder results for {} ({} detections)",
                            entry.getImageName(), detections.size());
                } catch (Exception e) {
                    logger.error("Failed to save {}: {}", entry.getImageName(), e.getMessage());
                }
            }

            // Clean up per-image tile temp file
            if (useTiles) deleteTempFile(inferTileFile);
        }

        // Fire hierarchy update for currently open image
        Platform.runLater(() -> {
            ImageData<BufferedImage> current = qupath.getImageData();
            if (current != null) {
                current.getHierarchy().fireHierarchyChangedEvent(this);
            }
        });

        long elapsed = System.currentTimeMillis() - startTime;
        String msg = "[TEST] Autoencoder applied to " + totalApplied + "/"
                + imageEntries.size() + " images";
        report(progressCallback, msg);
        OperationLogger.getInstance().logOperation("AUTOENCODER_PROJECT_APPLY",
                Map.of("Images", String.valueOf(imageEntries.size()),
                       "Applied", String.valueOf(totalApplied)),
                msg, elapsed);
    }

    /** Converts int[] to List<Integer> for Appose JSON serialization. */
    private static List<Integer> toIntList(int[] arr) {
        List<Integer> list = new ArrayList<>(arr.length);
        for (int v : arr) list.add(v);
        return list;
    }

    /**
     * Returns the temp directory inside the project folder for large temp files.
     * Creates .qpcat_temp/ if it doesn't exist. Requires a project to be open.
     */
    private Path getProjectTempDir() throws IOException {
        if (qupath.getProject() == null) {
            throw new IOException("A project must be open for tile-based training "
                    + "(temp files are stored in the project folder).");
        }
        Path projectDir = qupath.getProject().getPath().getParent();
        Path tempDir = projectDir.resolve(".qpcat_temp");
        Files.createDirectories(tempDir);
        return tempDir;
    }

    /** Deletes a temp file if it exists. Logs on failure rather than throwing. */
    private static void deleteTempFile(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                logger.warn("Failed to delete temp file {}: {}", file, e.getMessage());
            }
        }
    }

    private static void report(Consumer<String> callback, String message) {
        if (callback != null) callback.accept(message);
    }
}
