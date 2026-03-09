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
import qupath.ext.pyclustering.service.OperationLogger;
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
import java.util.stream.Collectors;

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

    private static void report(Consumer<String> callback, String message) {
        if (callback != null) callback.accept(message);
    }
}
