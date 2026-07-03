package qupath.ext.qpcat.controller;

import javafx.application.Platform;
import org.apposed.appose.NDArray;
import org.apposed.appose.Service.Task;
import org.apposed.appose.Service.ResponseType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.CellRef;
import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.ext.qpcat.preferences.QpcatPreferences;
import qupath.ext.qpcat.model.SavedClusteringResult;
import qupath.ext.qpcat.scripting.SpatialConnectionsScripts;
import qupath.ext.qpcat.service.ApposeClusteringService;
import qupath.ext.qpcat.service.ClusteringResultManager;
import qupath.ext.qpcat.service.ClusteringRunRecord;
import qupath.ext.qpcat.service.MeasurementExtractor;
import qupath.ext.qpcat.service.OperationLogger;
import qupath.ext.qpcat.service.ResultApplier;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;
import qupath.lib.projects.Project;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.DefaultPathObjectConnectionGroup;
import qupath.lib.objects.PathObjectConnectionGroup;
import qupath.lib.objects.PathObjectConnections;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;

import qupath.lib.projects.ProjectImageEntry;

/**
 * Orchestrates the end-to-end clustering workflow:
 * extract measurements -> send to Python -> apply results.
 */
public class ClusteringWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(ClusteringWorkflow.class);

    /**
     * F3: measurement-name prefixes that must be excluded from the
     * QP-CAT component-aggregate fan-out. Includes QP-CAT's own writeback
     * columns plus the embedding / cluster / featurization columns that
     * other paths attach to detections; averaging "UMAP1" or "Cluster"
     * by component produces meaningless rerun-on-rerun feedback columns.
     * Match is via {@link String#startsWith(String)} so suffix variants
     * (e.g. {@code FM_tile_0}, {@code Cluster ID}) are caught.
     */
    private static final String[] FEEDBACK_GUARD_PREFIXES = {
            "QPCAT spatial: ",
            "QPCAT component: ",
            "UMAP",
            "tSNE",
            "PCA",
            "Cluster",
            "FM_",
            "ZS_",
            "AE_",
    };

    /**
     * F3: returns true when the measurement name is feedback-prone and
     * should be excluded from per-component aggregates.
     */
    private static boolean isFeedbackProneMeasurementName(String name) {
        if (name == null) return false;
        for (String prefix : FEEDBACK_GUARD_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private final QuPathGUI qupath;

    /**
     * Optional callback receiving a determinate progress fraction (0..1) for the
     * clustering task, derived from the Python side's task.update(current,
     * maximum). Lets a dialog show a real progress bar instead of a perpetually
     * indeterminate one. Null when not set (headless / no UI).
     */
    private java.util.function.DoubleConsumer progressFractionCallback;

    /** Set the determinate-progress callback (0..1). Pass null to clear. */
    public void setProgressFractionCallback(java.util.function.DoubleConsumer cb) {
        this.progressFractionCallback = cb;
    }

    /**
     * Phase-token callback for a phase checklist (e.g. "normalize", "cluster").
     * Separate from the status-message callback so the user-facing message stays
     * clean for every consumer. Null when not set.
     */
    private Consumer<String> phaseCallback;

    /** Set the phase-token callback. Pass null to clear. */
    public void setPhaseCallback(Consumer<String> cb) {
        this.phaseCallback = cb;
    }

    /** What to do after the spatial-stats time estimate is shown to the user. */
    public enum SpatialDecision { CONTINUE, SKIP_SPATIAL, CANCEL }

    /**
     * Optional decider invoked (on the calling/background thread) with the
     * estimated spatial-stats runtime in SECONDS -- null if it could not be
     * estimated -- BEFORE the main clustering runs, when any spatial statistic
     * is enabled. It returns whether to run the spatial stats, skip them (run
     * clustering only), or cancel the whole run. Null = run without prompting.
     */
    private java.util.function.Function<Double, SpatialDecision> spatialEstimateDecider;

    public void setSpatialEstimateDecider(
            java.util.function.Function<Double, SpatialDecision> decider) {
        this.spatialEstimateDecider = decider;
    }

    private volatile Task currentTask;
    private volatile boolean cancelled;

    /**
     * Average connections-per-cell (graph degree) above which the viewer overlay
     * is warned about: denser than this and the edges render as an unhelpful
     * white mass (kNN at k>4 or Delaunay, ~6, always exceed it).
     */
    private static final double OVERLAY_DENSITY_WARN = 4.0;

    /**
     * Request cancellation of the in-flight task (estimate probe or clustering).
     * Safe to call from any thread. After a cancel, the run returns WITHOUT
     * applying any labels/measurements to objects, so the project is never left
     * half-written.
     */
    public void requestCancel() {
        cancelled = true;
        Task t = currentTask;
        if (t != null) {
            try {
                t.cancel();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    public boolean wasCancelled() {
        return cancelled;
    }

    private boolean spatialStatsEnabled(ClusteringConfig config) {
        return config.isEnableSpatialAnalysis() || config.isAnySpatialStatEnabled();
    }

    /** Turn off every spatial statistic on the config (used when the user picks "skip"). */
    private void disableSpatialStats(ClusteringConfig config) {
        config.setEnableSpatialAnalysis(false);
        config.setEnableRipley(false);
        config.setEnableGeary(false);
        config.setEnableCoOccurrencePairwise(false);
        config.setEnableCoOccurrenceOneVsRest(false);
    }

    /**
     * Construct a workflow bound to a live QuPathGUI. The GUI is required
     * for the single-image entry points ({@link #runClustering},
     * {@link #runPhenotyping}, {@link #computeThresholds},
     * {@link #runSubClustering}, {@link #exportAnnData}) which all read
     * {@code qupath.getImageData()}.
     *
     * <p>For headless dispatch (YAML batch), pass {@code null} and call
     * only {@link #runProjectClustering(List, ClusteringConfig,
     * java.util.function.Consumer)}. The internal FX-thread hierarchy
     * notifications are null-safe when {@code qupath} is null.</p>
     */
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
        reportPhase(progressCallback, "extract", "Extracting measurements...");

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

        // Spatial-stats time estimate + skip/cancel prompt. The heavy permutation
        // stats can take a long time at scale, so probe a few subsample sizes,
        // extrapolate to the full count, and let the user decide BEFORE anything
        // is computed in full or written to objects.
        if (spatialStatsEnabled(config) && spatialEstimateDecider != null) {
            Double estSeconds = null;
            try {
                final MeasurementExtractor.ExtractionResult ex = extraction;
                final ClusteringConfig cfg = config;
                estSeconds = ApposeClusteringService.withExtensionClassLoader(() ->
                        estimateSpatialSeconds(ex, cfg, progressCallback));
            } catch (Exception e) {
                logger.warn("Spatial-time estimate failed (continuing without it): {}",
                        e.getMessage());
            }
            if (cancelled) {
                throw new IOException("Clustering cancelled before results were applied.");
            }
            SpatialDecision decision = spatialEstimateDecider.apply(estSeconds);
            if (decision == SpatialDecision.CANCEL) {
                cancelled = true;
                throw new IOException("Clustering cancelled before results were applied.");
            } else if (decision == SpatialDecision.SKIP_SPATIAL) {
                disableSpatialStats(config);
                report(progressCallback, "Skipping spatial statistics.");
            }
        }

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

        // Per-cell back-references for plot-click navigation + representative crops.
        // Single-image run: segments carry no entry, so supply the open image's
        // id (looked up from the project) + name as the fallback.
        String fbName = imageData.getServer().getMetadata().getName();
        String fbId = null;
        var projectForRefs = qupath.getProject();
        if (projectForRefs != null) {
            var openEntry = projectForRefs.getEntry(imageData);
            if (openEntry != null) fbId = openEntry.getID();
        }
        result.setCellRefs(buildCellRefs(extraction, fbId, fbName));

        // Apply results back to QuPath
        reportPhase(progressCallback, "apply", "Applying results to QuPath...");

        ResultApplier applier = new ResultApplier();

        // Skip label application for embedding-only mode
        if (config.getAlgorithm() != ClusteringConfig.Algorithm.NONE) {
            applier.applyClusterLabels(extraction.getDetections(), result.getClusterLabels());
        }

        if (result.hasEmbedding()) {
            String prefix = ResultApplier.getEmbeddingPrefix(
                    config.getEmbeddingMethod().getId(), embeddingName(config));
            applier.applyEmbedding(extraction.getDetections(), result.getEmbedding(), prefix);
        }

        // v0.3 spatial graph overlay: build PathObjectConnections + write
        // QPCAT spatial: / QPCAT component: measurements per the config
        // toggles. Runs on the current background thread; the helper
        // fires the hierarchy change event on the FX thread itself.
        if (result.hasSpatialGraphPayload()) {
            applySpatialGraphPayload(imageData, extraction.getDetections(), config,
                    result.getSpatialGraphPayload());
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

        // Auto-save so the result is always reloadable via "View Past Results",
        // and record the run in QuPath's native command-history Workflow.
        String scopeKey = (fbId != null) ? fbId : fbName;
        autoSaveResult(result, config, scopeKey, fbName);
        recordClusteringWorkflowStep(imageData, config, result, 1);

        return result;
    }

    /**
     * Build per-cell back-references (image id/name + ROI centroid + bbox half-extent)
     * index-aligned with the extraction's detection list. Walks the image segments so
     * each cell is tagged with its source image. For single-image runs the segment
     * carries no entry, so {@code fallbackId}/{@code fallbackName} (the open image)
     * are used for every cell.
     */
    @SuppressWarnings("unchecked")
    private CellRef[] buildCellRefs(MeasurementExtractor.ExtractionResult extraction,
                                    String fallbackId, String fallbackName) {
        List<PathObject> detections = extraction.getDetections();
        CellRef[] refs = new CellRef[detections.size()];
        for (MeasurementExtractor.ImageSegment seg : extraction.getImageSegments()) {
            String imageId = fallbackId;
            String imageName = fallbackName;
            Object entryObj = seg.getImageEntry();
            if (entryObj instanceof ProjectImageEntry) {
                ProjectImageEntry<BufferedImage> entry = (ProjectImageEntry<BufferedImage>) entryObj;
                imageId = entry.getID();
                imageName = entry.getImageName();
            }
            for (int i = seg.getStartIndex(); i < seg.getEndIndex(); i++) {
                var roi = detections.get(i).getROI();
                double half = 0.5 * Math.max(roi.getBoundsWidth(), roi.getBoundsHeight());
                refs[i] = new CellRef(imageId, imageName,
                        roi.getCentroidX(), roi.getCentroidY(), half);
            }
        }
        return refs;
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
    /**
     * Close the native reader behind an {@link ImageData} we opened via
     * {@code readImageData()}. Never call this on the live GUI ImageData -- only on
     * detached copies we read ourselves. Quiet: logs and swallows any close failure so
     * cleanup never masks the real result/exception.
     */
    static void closeReadImageData(Object imageData) {
        if (imageData instanceof ImageData) {
            try {
                ((ImageData<?>) imageData).getServer().close();
            } catch (Exception e) {
                logger.warn("Failed to close image reader: {}", e.getMessage());
            }
        }
    }

    /** Close every detached ImageData held by a detection-group list. */
    static void closeGroups(List<MeasurementExtractor.ImageDetectionGroup> groups) {
        if (groups == null) return;
        for (MeasurementExtractor.ImageDetectionGroup g : groups) {
            if (g != null) closeReadImageData(g.imageData);
        }
    }

    /**
     * Close every ImageData in the list EXCEPT the live GUI one (identity match on
     * {@code qupath.getImageData()}), which the GUI owns. Use for lists that mix the
     * open image with detached copies read via {@code readImageData()}.
     */
    private void closeReadImageDatas(List<ImageData<BufferedImage>> imageDatas) {
        if (imageDatas == null) return;
        ImageData<BufferedImage> live = (qupath != null) ? qupath.getImageData() : null;
        for (ImageData<BufferedImage> d : imageDatas) {
            if (d != null && d != live) closeReadImageData(d);
        }
    }

    public ClusteringResult runProjectClustering(
            List<ProjectImageEntry<BufferedImage>> imageEntries,
            ClusteringConfig config,
            Consumer<String> progressCallback) throws IOException {

        long startTime = System.currentTimeMillis();

        if (imageEntries == null || imageEntries.isEmpty()) {
            throw new IOException("No project images selected for clustering.");
        }

        reportPhase(progressCallback, "load",
                "Loading detections from " + imageEntries.size() + " images...");

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
        try {

        // Extract measurements across all images
        reportPhase(progressCallback, "extract", "Extracting measurements...");
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

        // Per-cell back-references: multi-image segments each carry their own
        // ProjectImageEntry, so no fallback id/name is needed.
        result.setCellRefs(buildCellRefs(extraction, null, null));

        // Apply results back per-image and save
        reportPhase(progressCallback, "apply", "Applying results to project images...");
        ResultApplier applier = new ResultApplier();

        // D1 note (v0.3.4): the project path builds a single global spatial
        // graph over concatenated per-image coordinates. v0.3.4 fixes the
        // per-cell numNeighbors + distance aggregates to recompute per-image
        // from the sliced edges, but triangle areas and connected-component
        // labels are still global-graph artefacts. The overlay PathObjectConnections
        // per-image only show the in-segment edges, so visual results match
        // the corrected aggregates. Document in CHANGELOG; no runtime dialog.
        if (result.hasSpatialGraphPayload()
                && extraction.getImageSegments() != null
                && extraction.getImageSegments().size() > 1) {
            logger.info("Multi-image clustering: per-cell aggregates will be recomputed"
                    + " per-image from sliced edges. Triangle areas + component labels"
                    + " stay global-graph values.");
        }

        final int nImages = extraction.getImageSegments().size();

        // Decide the spatial-graph overlay push ONCE for the whole set (one prompt, not
        // one per image) and gather per-image average connections for a single summary.
        Boolean overlayProceed = null;
        List<String> graphPerImage = new ArrayList<>();
        double overlaySumAvg = 0.0;
        if (result.hasSpatialGraphPayload()
                && config.isPushConnectionsToViewer()
                && result.getSpatialGraphPayload().hasEdgeCoo()) {
            int overlayThreshold = config.getConnectionsPromptThreshold();
            boolean anyDense = false, anyLarge = false;
            double maxAvg = 0.0;
            int maxEdges = 0;
            for (MeasurementExtractor.ImageSegment seg : extraction.getImageSegments()) {
                ClusteringResult.SpatialGraphPayload sliced =
                        result.getSpatialGraphPayload().slice(seg.getStartIndex(), seg.getEndIndex());
                int nCells = seg.getCount();
                int segEdges = sliced.hasEdgeCoo() ? sliced.getEdgeRow().length : 0;
                double avg = nCells > 0 ? (2.0 * segEdges) / nCells : 0.0;
                graphPerImage.add(String.format(java.util.Locale.US,
                        "%s: %.1f connections/cell (%d edges)",
                        segmentImageName(seg), avg, segEdges));
                overlaySumAvg += avg;
                if (avg > OVERLAY_DENSITY_WARN) anyDense = true;
                if (overlayThreshold > 0 && segEdges > overlayThreshold) anyLarge = true;
                if (avg > maxAvg) maxAvg = avg;
                if (segEdges > maxEdges) maxEdges = segEdges;
            }
            overlayProceed = (anyDense || anyLarge)
                    ? confirmOverlayPushBatch(nImages, maxAvg, maxEdges, overlayThreshold,
                            anyDense, anyLarge)
                    : Boolean.TRUE;
        }

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
                        config.getEmbeddingMethod().getId(), embeddingName(config));
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

            // v0.3 spatial graph overlay -- per-segment slice + apply.
            if (result.hasSpatialGraphPayload()) {
                ClusteringResult.SpatialGraphPayload sliced =
                        result.getSpatialGraphPayload().slice(start, end);
                applySpatialGraphPayload(imageData,
                        new ArrayList<>(segmentDetections), config, sliced, overlayProceed);
            }

            // Record the run in EVERY processed image's command-history Workflow,
            // so each image carries an audit trail of how its cluster labels were
            // produced -- including the explicit note that they came from a joint,
            // cross-image run (see recordClusteringWorkflowStep). Added before
            // saveImageData so it persists with the result.
            recordClusteringWorkflowStep(imageData, config, result, nImages);

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

        // ONE spatial-graph summary AFTER the loop (never a popup per image): list the
        // average connections per image plus the mean across the run.
        if (!graphPerImage.isEmpty()) {
            double meanAvg = overlaySumAvg / graphPerImage.size();
            logger.info("Spatial graph per-image averages: {}", String.join("; ", graphPerImage));
            reportSpatialGraphSummary(graphPerImage, meanAvg);
        }

        // Fire hierarchy update for the currently open image (if it was clustered).
        // Null-safe: headless dispatch (YAML batch) passes a null QuPathGUI, in
        // which case there is no FX viewer to notify -- the per-image saves
        // above already persisted the labels.
        if (qupath != null) {
            Platform.runLater(() -> {
                ImageData<BufferedImage> currentImageData = qupath.getImageData();
                if (currentImageData != null) {
                    currentImageData.getHierarchy().fireHierarchyChangedEvent(this);
                }
            });
        }

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

        // Auto-save (project scope). The per-image Workflow records were written
        // inside the save loop above (so every processed image carries one). Also
        // record on the currently open LIVE image instance so the Workflow tab
        // updates immediately without a reload.
        String projectScopeLabel = nImages + " project image" + (nImages == 1 ? "" : "s");
        autoSaveResult(result, config, SavedClusteringResult.PROJECT_SCOPE_KEY, projectScopeLabel);
        if (qupath != null) {
            ImageData<BufferedImage> openImageData = qupath.getImageData();
            if (openImageData != null) {
                recordClusteringWorkflowStep(openImageData, config, result, nImages);
            }
        }

        return result;
        } finally {
            // All groups here are detached copies read via readImageData(); close them.
            closeGroups(groups);
        }
    }

    /**
     * Auto-save a freshly computed result to the project so it can always be
     * reopened via "View Past Results", then stash the save location / size /
     * scope count on the result for the dialog footer + over-5 warning.
     * Best-effort: a save failure is logged but never fails the run.
     */
    private void autoSaveResult(ClusteringResult result, ClusteringConfig config,
                                String scopeKey, String scopeLabel) {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            // No project: results live only on the objects + audit log. Nothing
            // to auto-save into; the dialog still opens for live inspection.
            return;
        }
        try {
            String savedName = ClusteringResultManager.saveResultAuto(project, result,
                    config.getAlgorithm().getDisplayName(),
                    config.getNormalization().getId(),
                    config.getEmbeddingMethod().getId(),
                    scopeKey, scopeLabel);
            Path resultsDir = ClusteringResultManager.getResultsDirectory(project);
            long size = ClusteringResultManager.resultSize(project, savedName);
            int scopeCount = ClusteringResultManager.countResultsForScope(project, scopeKey);

            result.setSavedName(savedName);
            result.setSavedPath(resultsDir.resolve(savedName + ".json").toString());
            result.setSavedSizeBytes(size);
            result.setSavedScopeCount(scopeCount);
            result.setSavedScopeLabel(scopeLabel);

            // Reproducibility artifacts: the exact config (re-loadable in the
            // dialog) + a human-readable run record next to the result.
            ClusteringRunRecord.write(resultsDir, savedName, config, result, scopeLabel);

            OperationLogger.getInstance().logEvent("RESULTS AUTO-SAVED",
                    "Saved '" + savedName + "' (" + result.getNClusters() + " clusters, "
                    + result.getNCells() + " cells) to " + resultsDir
                    + "; " + scopeCount + " result(s) for scope '" + scopeLabel + "'");
        } catch (Exception e) {
            logger.warn("Auto-save of clustering result failed: {}", e.getMessage());
        }
    }

    /**
     * Record the clustering run as a step in QuPath's native command-history
     * Workflow (the Workflow tab; exportable as a script). No clustering
     * scripting facade exists, so the step body is an informational, ASCII-only
     * comment that records the parameters and how to reopen the saved result.
     * Best-effort: a failure here never fails the run.
     */
    private void recordClusteringWorkflowStep(ImageData<BufferedImage> imageData,
                                              ClusteringConfig config,
                                              ClusteringResult result,
                                              int nImages) {
        if (imageData == null) return;
        try {
            String algo = config.getAlgorithm().getDisplayName();
            Map<String, Object> algoParams = config.getAlgorithmParams();
            String paramsStr = (algoParams != null) ? algoParams.toString() : "{}";
            boolean crossImage = nImages > 1;
            String scope = crossImage
                    ? ("joint run across " + nImages + " images")
                    : "current image only";
            String saved = (result.getSavedName() != null)
                    ? result.getSavedName() : "(not saved - no project open)";

            List<String> lines = new ArrayList<>();
            lines.add("// QP-CAT clustering -- " + scope);
            lines.add("// This Workflow step is an informational RECORD (a comment), by design.");
            lines.add("//   It is not a runnable command. Reproduce via the routes below.");
            if (crossImage) {
                lines.add("// IMPORTANT: the cluster labels on THIS image were computed JOINTLY");
                lines.add("//   with " + (nImages - 1) + " other image(s) (" + result.getNCells()
                        + " cells total). Re-clustering this image on its own will produce");
                lines.add("//   DIFFERENT labels -- to reproduce, run across the same image set.");
            }
            lines.add("// Algorithm: " + algo + "  params: " + paramsStr);
            lines.add("// Normalization: " + config.getNormalization().getId()
                    + "  Embedding: " + config.getEmbeddingMethod().getId());
            lines.add("// Result: " + result.getNClusters() + " clusters, "
                    + result.getNCells() + " cells"
                    + (crossImage ? " across " + nImages + " images" : ""));
            lines.add("// Reproduce: Extensions > QPCAT > View Past Results -> '" + saved + "',");
            lines.add("//   or Load Config from file -> '" + saved + "_config.json' (then set the");
            lines.add("//   SAME scope), or the headless YAML batch (see YAML_SCHEMA.md).");

            String stepName = "QP-CAT: clustering (" + algo + ", "
                    + result.getNClusters() + " clusters"
                    + (crossImage ? ", " + nImages + " images" : "") + ")";
            imageData.getHistoryWorkflow().addStep(
                    new DefaultScriptableWorkflowStep(stepName, String.join("\n", lines)));
        } catch (Exception e) {
            logger.warn("Failed to record clustering workflow step: {}", e.getMessage());
        }
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
     * Runs phenotyping across all detections in the given project images, using
     * the same rules and gates for every image. Cells from every image are
     * combined and normalized together (global gating -- a "pos" threshold means
     * the same thing project-wide, matching multi-image clustering), then labels
     * are written back and saved per image.
     *
     * <p>Mirrors {@link #runProjectClustering} for the project scope, but for the
     * rule-based phenotyping task. Must be called from a background thread.</p>
     *
     * @param imageEntries         project image entries to phenotype
     * @param selectedMeasurements marker measurements to use
     * @param normalization        normalization method id
     * @param phenotypeRulesJson   JSON string of phenotype rules
     * @param gatesJson            JSON string of per-marker gate thresholds
     * @param progressCallback     optional callback for progress messages
     * @return same result map as {@link #runPhenotyping}
     * @throws IOException if phenotyping fails or no detections are found
     */
    public Map<String, Object> runPhenotypingProject(
            List<ProjectImageEntry<BufferedImage>> imageEntries,
            List<String> selectedMeasurements,
            String normalization,
            String phenotypeRulesJson,
            String gatesJson,
            Consumer<String> progressCallback) throws IOException {

        long startTime = System.currentTimeMillis();

        if (imageEntries == null || imageEntries.isEmpty()) {
            throw new IOException("No project images selected for phenotyping.");
        }

        List<MeasurementExtractor.ImageDetectionGroup> groups =
                loadProjectDetectionGroups(imageEntries, progressCallback);
        try {

        report(progressCallback, "Extracting measurements...");
        MeasurementExtractor extractor = new MeasurementExtractor();
        MeasurementExtractor.ExtractionResult extraction =
                extractor.extractMultiImage(groups, selectedMeasurements);

        logger.info("Combined extraction: {} cells x {} measurements across {} images",
                extraction.getNCells(), extraction.getNMeasurements(),
                extraction.getImageSegments().size());

        report(progressCallback, "Sending data to Python (" + extraction.getNCells()
                + " cells x " + extraction.getNMeasurements() + " markers from "
                + extraction.getImageSegments().size() + " images)...");

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

        report(progressCallback, "Applying phenotype labels to project images...");
        int[] labels = (int[]) resultMap.get("labels");
        String[] phenotypeNames = (String[]) resultMap.get("phenotype_names");
        ResultApplier applier = new ResultApplier();

        for (MeasurementExtractor.ImageSegment segment : extraction.getImageSegments()) {
            int start = segment.getStartIndex();
            int end = segment.getEndIndex();

            List<PathObject> segmentDetections = extraction.getDetections().subList(start, end);
            int[] segmentLabels = new int[end - start];
            System.arraycopy(labels, start, segmentLabels, 0, end - start);
            applier.applyPhenotypeLabels(segmentDetections, segmentLabels, phenotypeNames);

            @SuppressWarnings("unchecked")
            ProjectImageEntry<BufferedImage> entry =
                    (ProjectImageEntry<BufferedImage>) segment.getImageEntry();
            @SuppressWarnings("unchecked")
            ImageData<BufferedImage> imageData =
                    (ImageData<BufferedImage>) segment.getImageData();
            try {
                entry.saveImageData(imageData);
                logger.info("Saved phenotyping results for {} ({} detections)",
                        entry.getImageName(), segment.getCount());
            } catch (Exception e) {
                logger.error("Failed to save image data for {}: {}",
                        entry.getImageName(), e.getMessage());
            }
            report(progressCallback, "Saved results for " + entry.getImageName());
        }

        // Fire hierarchy update for the currently open image (if it was phenotyped).
        Platform.runLater(() -> {
            ImageData<BufferedImage> currentImageData = qupath.getImageData();
            if (currentImageData != null) {
                currentImageData.getHierarchy().fireHierarchyChangedEvent(this);
            }
        });

        String completeMsg = "Project phenotyping complete: " + resultMap.get("n_phenotypes")
                + " phenotypes assigned to " + extraction.getNCells() + " cells across "
                + extraction.getImageSegments().size() + " images.";
        report(progressCallback, completeMsg);

        int ruleCount = 0;
        try {
            List<?> ruleList = new Gson().fromJson(phenotypeRulesJson, List.class);
            ruleCount = ruleList != null ? ruleList.size() : 0;
        } catch (Exception ignored) {}
        long elapsed = System.currentTimeMillis() - startTime;
        OperationLogger.getInstance().logOperation("PHENOTYPING (project)",
                OperationLogger.phenotypingParams(
                        normalization, selectedMeasurements.size(), ruleCount,
                        extraction.getNCells(), selectedMeasurements),
                completeMsg, elapsed);

        return resultMap;
        } finally {
            // Detection groups here are detached copies read by loadProjectDetectionGroups.
            closeGroups(groups);
        }
    }

    /**
     * Read detections from each project image into combined-extraction groups,
     * skipping images that fail to load or have no detections. Shared by the
     * project-scope phenotyping and threshold paths so they pool the SAME cells.
     *
     * @throws IOException if no image yielded any detections
     */
    private List<MeasurementExtractor.ImageDetectionGroup> loadProjectDetectionGroups(
            List<ProjectImageEntry<BufferedImage>> imageEntries,
            Consumer<String> progressCallback) throws IOException {
        report(progressCallback, "Loading detections from " + imageEntries.size() + " images...");
        List<MeasurementExtractor.ImageDetectionGroup> groups = new ArrayList<>();
        for (int idx = 0; idx < imageEntries.size(); idx++) {
            ProjectImageEntry<BufferedImage> entry = imageEntries.get(idx);
            report(progressCallback, "Loading image " + (idx + 1) + "/" + imageEntries.size()
                    + ": " + entry.getImageName());
            ImageData<BufferedImage> imageData;
            try {
                imageData = entry.readImageData();
            } catch (Exception e) {
                logger.warn("Failed to read image data for {}: {}",
                        entry.getImageName(), e.getMessage());
                continue;
            }
            Collection<PathObject> detections = imageData.getHierarchy().getDetectionObjects();
            if (detections.isEmpty()) {
                logger.info("Skipping {} - no detections", entry.getImageName());
                continue;
            }
            groups.add(new MeasurementExtractor.ImageDetectionGroup(entry, imageData, detections));
            logger.info("Loaded {} detections from {}", detections.size(), entry.getImageName());
        }
        if (groups.isEmpty()) {
            throw new IOException("No detection objects found in any selected images. "
                    + "Run cell detection first.");
        }
        return groups;
    }

    /**
     * Compute per-marker histograms and auto-thresholds across MULTIPLE project
     * images, pooling and normalizing their cells together exactly as
     * {@link #runPhenotypingProject} does -- so the gates you set match the
     * distribution the run actually gates on. Falls back to the single-image
     * {@link #computeThresholds} when the entry list is null/empty.
     */
    public String computeThresholdsProject(
            List<ProjectImageEntry<BufferedImage>> imageEntries,
            List<String> selectedMeasurements,
            String normalization,
            Consumer<String> progressCallback) throws IOException {
        if (imageEntries == null || imageEntries.isEmpty()) {
            return computeThresholds(selectedMeasurements, normalization, progressCallback);
        }
        long startTime = System.currentTimeMillis();
        List<MeasurementExtractor.ImageDetectionGroup> groups =
                loadProjectDetectionGroups(imageEntries, progressCallback);
        try {
            MeasurementExtractor extractor = new MeasurementExtractor();
            MeasurementExtractor.ExtractionResult extraction =
                    extractor.extractMultiImage(groups, selectedMeasurements);
            report(progressCallback, "Computing thresholds (" + extraction.getNCells()
                    + " cells x " + extraction.getNMeasurements() + " markers across "
                    + extraction.getImageSegments().size() + " images)...");
            String result = ApposeClusteringService.withExtensionClassLoader(() ->
                    executeThresholdTask(extraction, normalization, progressCallback));
            long elapsed = System.currentTimeMillis() - startTime;
            OperationLogger.getInstance().logOperation("COMPUTE THRESHOLDS (project)",
                    OperationLogger.thresholdParams(normalization,
                            extraction.getNMeasurements(), extraction.getNCells()),
                    "Thresholds computed for " + extraction.getNMeasurements()
                            + " markers across " + extraction.getImageSegments().size() + " images",
                    elapsed);
            return result;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Threshold computation failed: " + e.getMessage(), e);
        } finally {
            // Detection groups here are detached copies read by loadProjectDetectionGroups.
            closeGroups(groups);
        }
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
        inputs.put("histogram_bins", QpcatPreferences.getPhenoHistogramBins());
        inputs.put("min_valid_values", QpcatPreferences.getPhenoMinValidValues());
        inputs.put("gmm_max_iter", QpcatPreferences.getPhenoGmmMaxIter());
        inputs.put("gamma_std_multiplier", QpcatPreferences.getPhenoGammaStdMultiplier());

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
        inputs.put("pheno_gate_max", QpcatPreferences.getPhenoGateMax());

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
            if (task.outputs.containsKey("unknown_breakdown")) {
                result.put("unknown_breakdown", task.outputs.get("unknown_breakdown"));
            }

            return result;
        } finally {
            measurementsNd.close();
            if (labelsNd != null) labelsNd.close();
        }
    }

    /**
     * Probe how long the enabled spatial statistics will take on the full
     * dataset: runs them on small subsamples (100/1000/2000 cells) and
     * extrapolates. Returns the estimated seconds, or null if it could not be
     * estimated. Must be called with the extension class loader set (Appose).
     */
    private Double estimateSpatialSeconds(MeasurementExtractor.ExtractionResult extraction,
                                          ClusteringConfig config,
                                          Consumer<String> progressCallback) throws IOException {
        int nCells = extraction.getNCells();
        int nMeasurements = extraction.getNMeasurements();
        double[][] data = extraction.getData();

        NDArray.Shape shape = new NDArray.Shape(NDArray.Shape.Order.C_ORDER, nCells, nMeasurements);
        NDArray measurementsNd = new NDArray(NDArray.DType.FLOAT64, shape);
        var mbuf = measurementsNd.buffer().asDoubleBuffer();
        for (int i = 0; i < nCells; i++) {
            mbuf.put(data[i]);
        }

        double[][] centroids = MeasurementExtractor.extractCentroids(extraction.getDetections());
        NDArray.Shape spatialShape = new NDArray.Shape(NDArray.Shape.Order.C_ORDER, nCells, 2);
        NDArray spatialNd = new NDArray(NDArray.DType.FLOAT64, spatialShape);
        var sbuf = spatialNd.buffer().asDoubleBuffer();
        for (int i = 0; i < nCells; i++) {
            sbuf.put(centroids[i]);
        }

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("measurements", measurementsNd);
        inputs.put("marker_names", List.of(extraction.getMeasurementNames()));
        inputs.put("spatial_coords", spatialNd);
        inputs.put("normalization", config.getNormalization().getId());
        inputs.put("enable_spatial_analysis", config.isEnableSpatialAnalysis());
        inputs.put("enable_ripley", config.isEnableRipley());
        inputs.put("enable_geary", config.isEnableGeary());
        inputs.put("enable_co_occurrence_pairwise", config.isEnableCoOccurrencePairwise());
        inputs.put("enable_co_occurrence_one_vs_rest", config.isEnableCoOccurrenceOneVsRest());
        inputs.put("spatial_graph_type", config.getSpatialGraphType());
        inputs.put("spatial_graph_k", config.getSpatialGraphK());
        inputs.put("spatial_graph_radius", config.getSpatialGraphRadius());
        inputs.put("spatial_graph_delaunay_max_edge", resolveDelaunayMaxEdgePixels(config));
        inputs.put("spatial_permutations", config.getSpatialPermutations());

        Task task = ApposeClusteringService.getInstance().runTaskWithListener(
                "estimate_spatial_time", inputs,
                event -> {
                    if (event.responseType == ResponseType.UPDATE && event.message != null) {
                        report(progressCallback, event.message);
                    }
                },
                t -> currentTask = t);

        Object est = task.outputs.get("estimate_seconds");
        if (est == null) {
            return null;
        }
        // Python emits json.dumps(estimate_seconds): a number string or "null".
        String s = String.valueOf(est).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
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

        // Create spatial coordinates NDArray if spatial analysis, smoothing,
        // BANKSY, or any v1 spatial statistic is enabled.
        NDArray spatialCoordsNd = null;
        boolean needsSpatialCoords = config.isEnableSpatialAnalysis()
                || config.isEnableSpatialSmoothing()
                || config.isAnySpatialStatEnabled()
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

        // For multi-image runs, always tell Python which image each cell belongs to (and
        // the image names) so the "Spatial distribution" plot is split PER IMAGE instead
        // of overlaying cells from different images that share no coordinate frame. This
        // is independent of batch correction.
        if (extraction.isMultiImage()) {
            List<Integer> imageLabels = new ArrayList<>();
            List<String> imageNames = new ArrayList<>();
            for (int segIdx = 0; segIdx < extraction.getImageSegments().size(); segIdx++) {
                MeasurementExtractor.ImageSegment seg = extraction.getImageSegments().get(segIdx);
                imageNames.add(segmentImageName(seg));
                for (int i = 0; i < seg.getCount(); i++) imageLabels.add(segIdx);
            }
            inputs.put("image_labels", imageLabels);
            inputs.put("image_names", imageNames);
        }

        // Preference-backed defaults (overridable via QP-CAT preferences UI)
        inputs.put("spatial_knn", QpcatPreferences.getClusterSpatialKnn());
        inputs.put("tsne_perplexity_default", QpcatPreferences.getClusterTsnePerplexity());
        inputs.put("hdbscan_min_samples_default", QpcatPreferences.getClusterHdbscanMinSamples());
        inputs.put("minibatch_kmeans_batch_size", QpcatPreferences.getClusterMiniBatchSize());
        inputs.put("banksy_pca_dims_default", QpcatPreferences.getClusterBanksyPcaDims());
        inputs.put("plot_dpi", QpcatPreferences.getClusterPlotDpi());

        // ---- Spatial stats expansion (v1) inputs ----
        // Always pass; the Python side gates the heavy work on per-statistic flags.
        inputs.put("spatial_graph_type", config.getSpatialGraphType());
        inputs.put("spatial_graph_k", config.getSpatialGraphK());
        inputs.put("spatial_graph_radius", config.getSpatialGraphRadius());
        // v0.3: prefer the canonical micron value when calibration is
        // available; otherwise fall back to the pixel preference.
        inputs.put("spatial_graph_delaunay_max_edge",
                resolveDelaunayMaxEdgePixels(config));
        inputs.put("spatial_permutations", config.getSpatialPermutations());
        inputs.put("use_squidpy_graph_for_smoothing",
                QpcatPreferences.isSpatialUseSquidpyGraphForSmoothing());
        // Phase 5 enhancement: PNG output for spatial-stats plots so the
        // Multi-Figure Batch Export dialog (Feature B) can pick them up.
        inputs.put("spatial_persist_plots",
                QpcatPreferences.isSpatialPersistPlots());
        // Gate neighborhood-enrichment + Moran's I on the explicit spatial-
        // analysis choice. Without this they run whenever spatial coordinates
        // are present -- which is ALWAYS true for BANKSY -- so a BANKSY run would
        // trigger them (and the nhood permutation test) uninvited.
        inputs.put("enable_spatial_analysis", config.isEnableSpatialAnalysis());
        inputs.put("enable_ripley", config.isEnableRipley());
        inputs.put("enable_geary", config.isEnableGeary());
        inputs.put("enable_co_occurrence_pairwise",
                config.isEnableCoOccurrencePairwise());
        inputs.put("enable_co_occurrence_one_vs_rest",
                config.isEnableCoOccurrenceOneVsRest());
        // ---- Spatial graph overlay (v0.3) inputs ----
        inputs.put("write_node_measurements", config.isWriteNodeMeasurements());
        inputs.put("write_component_measurements", config.isWriteComponentMeasurements());
        // F1: pass pixel size so the Python helper scales QPCAT spatial:
        // distance + triangle-area outputs to microns when calibration is
        // present. Falls back to 1.0 (pixels) when no calibration is
        // available, matching the v0.2.7 spatial-stats unit treatment.
        inputs.put("pixel_size_um", resolveSpatialPixelSizeUm(extraction));

        NDArray labelsNd = null;
        NDArray embNd = null;
        NDArray statsNd = null;
        NDArray pagaNd = null;
        NDArray nhoodNd = null;

        try {
            // Run the task with progress updates
            ApposeClusteringService service = ApposeClusteringService.getInstance();
            Task task = service.runTaskWithListener("run_clustering", inputs, event -> {
                if (event.responseType == ResponseType.UPDATE) {
                    if (event.message != null) {
                        // Python prefixes "token|text" so we can advance the phase
                        // checklist; strip it so the status message stays clean.
                        String m = event.message;
                        int bar = m.indexOf('|');
                        if (bar > 0) {
                            if (phaseCallback != null) phaseCallback.accept(m.substring(0, bar));
                            report(progressCallback, m.substring(bar + 1));
                        } else {
                            report(progressCallback, m);
                        }
                    }
                    // Determinate progress: the Python side sends current/maximum
                    // at each phase. maximum == 0 means "no numeric progress"
                    // (leave the bar where it is) so it never reverts to bouncing.
                    if (event.maximum > 0 && progressFractionCallback != null) {
                        double frac = (double) event.current / event.maximum;
                        progressFractionCallback.accept(Math.max(0.0, Math.min(1.0, frac)));
                    }
                }
            }, t -> currentTask = t);

            // If the user cancelled (button -> requestCancel), do NOT parse or
            // apply any results -- bail before anything touches the objects.
            if (cancelled) {
                throw new IOException("Clustering cancelled before results were applied.");
            }

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

            if (task.outputs.containsKey("representatives")) {
                String repJson = (String) task.outputs.get("representatives");
                result.setRepresentativesJson(repJson);
                logger.info("Received representative-cell indices ({} chars)",
                        repJson != null ? repJson.length() : 0);
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

            // ---- Spatial stats expansion (v1) outputs ----
            // Each output is independent; the Java side checks per-key
            // existence rather than a bundled flag so a partial Python
            // success still surfaces the statistics that did complete.
            if (task.outputs.containsKey("spatial_graph_type")) {
                result.setSpatialGraphType(
                        (String) task.outputs.get("spatial_graph_type"));
            }

            int spatialPermsUsed = 0;
            if (task.outputs.containsKey("spatial_n_permutations")) {
                spatialPermsUsed = ((Number) task.outputs.get(
                        "spatial_n_permutations")).intValue();
            }

            Gson spatialGson = new Gson();
            long spatialStartTs = System.currentTimeMillis();

            if (task.outputs.containsKey("ripley")) {
                try {
                    qupath.ext.qpcat.model.RipleyResult ripley = parseRipley(
                            (String) task.outputs.get("ripley"), spatialGson);
                    result.setRipley(ripley);
                    logger.info("Received Ripley K/L for {} clusters",
                            ripley.pairCount());
                    OperationLogger.getInstance().logOperation(
                            "SPATIAL STATS RIPLEY",
                            OperationLogger.spatialStatsParams(
                                    "Ripley K/L",
                                    result.getSpatialGraphType(),
                                    spatialPermsUsed > 0 ? spatialPermsUsed
                                            : ripley.getNPermutations(),
                                    nCells),
                            "Curves for " + ripley.pairCount() + " clusters",
                            System.currentTimeMillis() - spatialStartTs);
                } catch (Exception e) {
                    logger.warn("Failed to parse Ripley result: {}", e.getMessage());
                }
            } else if (task.outputs.containsKey("ripley_error")) {
                // Ripley was requested but its extraction failed. The Python side
                // deliberately did NOT emit zero-filled curves (which would look like a
                // real null result), so surface the failure instead of silently
                // dropping the panel.
                String msg = String.valueOf(task.outputs.get("ripley_error"));
                logger.error("Ripley K/L failed: {}", msg);
                OperationLogger.getInstance().logEvent("SPATIAL STATS RIPLEY FAILED", msg);
            }

            if (task.outputs.containsKey("geary_c")) {
                try {
                    qupath.ext.qpcat.model.GearyCResult geary = parseGeary(
                            (String) task.outputs.get("geary_c"), spatialGson);
                    result.setGeary(geary);
                    logger.info("Received Geary's C for {} markers",
                            geary.measurementCount());
                    OperationLogger.getInstance().logOperation(
                            "SPATIAL STATS GEARY",
                            OperationLogger.spatialStatsParams(
                                    "Geary C",
                                    result.getSpatialGraphType(),
                                    spatialPermsUsed > 0 ? spatialPermsUsed
                                            : geary.getNPermutations(),
                                    nCells),
                            geary.measurementCount() + " markers scored",
                            System.currentTimeMillis() - spatialStartTs);
                } catch (Exception e) {
                    logger.warn("Failed to parse Geary's C result: {}", e.getMessage());
                }
            }

            if (task.outputs.containsKey("co_occurrence_pairwise")) {
                try {
                    qupath.ext.qpcat.model.CoOccurrenceResult coOcc = parseCoOccurrence(
                            (String) task.outputs.get("co_occurrence_pairwise"),
                            spatialGson);
                    result.setCoOccurrencePairwise(coOcc);
                    logger.info("Received co-occurrence (pairwise)");
                    OperationLogger.getInstance().logOperation(
                            "SPATIAL STATS COOC PAIRWISE",
                            OperationLogger.spatialStatsParams(
                                    "Co-occurrence (pairwise)",
                                    result.getSpatialGraphType(),
                                    spatialPermsUsed > 0 ? spatialPermsUsed
                                            : coOcc.getNPermutations(),
                                    nCells),
                            coOcc.cellCount() + " clusters",
                            System.currentTimeMillis() - spatialStartTs);
                } catch (Exception e) {
                    logger.warn("Failed to parse co-occurrence pairwise: {}",
                            e.getMessage());
                }
            }

            if (task.outputs.containsKey("co_occurrence_one_vs_rest")) {
                try {
                    qupath.ext.qpcat.model.CoOccurrenceResult coOcc = parseCoOccurrence(
                            (String) task.outputs.get("co_occurrence_one_vs_rest"),
                            spatialGson);
                    result.setCoOccurrenceOneVsRest(coOcc);
                    logger.info("Received co-occurrence (one-vs-rest)");
                    OperationLogger.getInstance().logOperation(
                            "SPATIAL STATS COOC ONE-VS-REST",
                            OperationLogger.spatialStatsParams(
                                    "Co-occurrence (one-vs-rest)",
                                    result.getSpatialGraphType(),
                                    spatialPermsUsed > 0 ? spatialPermsUsed
                                            : coOcc.getNPermutations(),
                                    nCells),
                            coOcc.cellCount() + " clusters",
                            System.currentTimeMillis() - spatialStartTs);
                } catch (Exception e) {
                    logger.warn("Failed to parse co-occurrence one-vs-rest: {}",
                            e.getMessage());
                }
            }

            if (result.hasAnySpatialStats()
                    && task.outputs.containsKey("spatial_graph_type")) {
                OperationLogger.getInstance().logOperation(
                        "SPATIAL GRAPH",
                        OperationLogger.graphConstructorParams(
                                result.getSpatialGraphType(),
                                config.getSpatialGraphK(),
                                config.getSpatialGraphRadius(),
                                config.getSpatialGraphDelaunayMaxEdge(),
                                nCells),
                        "Spatial neighbor graph built for " + nCells + " cells",
                        -1);
            }

            // ---- Spatial graph overlay (v0.3) outputs ----
            ClusteringResult.SpatialGraphPayload payload = retrieveSpatialGraphPayload(
                    task, nCells);
            if (payload != null) {
                result.setSpatialGraphPayload(payload);
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
                    config.getEmbeddingMethod().getId(), embeddingName(config));
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
        if (detections == null || detections.isEmpty()) return null;
        // Try UMAP, PCA, tSNE in order.
        String[][] prefixes = {{"UMAP1", "UMAP2"}, {"PCA1", "PCA2"}, {"tSNE1", "tSNE2"}};

        for (String[] pair : prefixes) {
            // Decide presence by scanning for ANY detection that carries the pair,
            // not just detections.get(0) -- a heterogeneous set (e.g. first cell
            // lacks the columns) would otherwise be misdetected either way.
            boolean anyPresent = false;
            for (PathObject det : detections) {
                var ml = det.getMeasurements();
                if (ml.containsKey(pair[0]) && ml.containsKey(pair[1])) {
                    anyPresent = true;
                    break;
                }
            }
            if (!anyPresent) continue;

            double[][] emb = new double[detections.size()][2];
            int missing = 0;
            for (int i = 0; i < detections.size(); i++) {
                var ml = detections.get(i).getMeasurements();
                if (!ml.containsKey(pair[0]) || !ml.containsKey(pair[1])) missing++;
                emb[i][0] = ml.getOrDefault(pair[0], 0.0).doubleValue();
                emb[i][1] = ml.getOrDefault(pair[1], 0.0).doubleValue();
            }
            if (missing > 0) {
                // Zero-filled cells would sit at the origin of the scatter and skew
                // any embedding-space distance -- surface it instead of hiding it.
                logger.warn("Existing embedding {}/{} is missing on {} of {} cells; "
                        + "those cells were zero-filled (embedding may be unreliable).",
                        pair[0], pair[1], missing, detections.size());
            } else {
                logger.info("Found existing embedding: {}/{}", pair[0], pair[1]);
            }
            return emb;
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

        forEachTile(nCells, progressCallback, i -> {
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
        });

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
    /**
     * @param downsample downsample factor (1 = full resolution, 2 = half, etc.)
     *                   The tileSize is in full-resolution pixels; the output
     *                   array dimensions are tileSize/downsample per side.
     */
    private float[] readMultiChannelTilesAroundCentroids(
            ImageServer<BufferedImage> server,
            List<PathObject> detections,
            int tileSize,
            double downsample,
            boolean includeMask,
            Consumer<String> progressCallback) {

        int nCells = detections.size();
        int imageChannels = server.nChannels();
        int totalChannels = imageChannels + (includeMask ? 1 : 0);
        int halfTile = tileSize / 2;
        // Output dimensions after downsample
        int outSize = (int) Math.round(tileSize / downsample);
        if (outSize < 2) outSize = 2;

        long tileArraySize = (long) nCells * totalChannels * outSize * outSize;
        if (tileArraySize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Tile data too large: " + nCells + " cells * " + totalChannels
                    + " channels * " + outSize + "x" + outSize
                    + " = " + tileArraySize + " floats. Increase downsample or use measurement mode.");
        }
        float[] tileData = new float[(int) tileArraySize];

        final int outSz = outSize;  // effectively-final copy for the parallel body
        forEachTile(nCells, progressCallback, i -> {
            PathObject det = detections.get(i);
            double cx = det.getROI().getCentroidX();
            double cy = det.getROI().getCentroidY();

            // Request region in full-resolution coordinates
            int x = Math.max(0, (int) cx - halfTile);
            int y = Math.max(0, (int) cy - halfTile);
            x = Math.min(x, Math.max(0, server.getWidth() - tileSize));
            y = Math.min(y, Math.max(0, server.getHeight() - tileSize));

            int readW = Math.min(tileSize, server.getWidth() - x);
            int readH = Math.min(tileSize, server.getHeight() - y);

            try {
                // Server returns image at the downsampled resolution
                RegionRequest request = RegionRequest.createInstance(
                        server.getPath(), downsample, x, y, readW, readH);
                BufferedImage tile = server.readRegion(request);
                var raster = tile.getRaster();

                int tileW = Math.min(outSz, tile.getWidth());
                int tileH = Math.min(outSz, tile.getHeight());

                // Pack image channels as NCHW: [cell_idx][channel][y][x]
                int cellOffset = i * totalChannels * outSz * outSz;
                for (int c = 0; c < imageChannels; c++) {
                    int channelOffset = cellOffset + c * outSz * outSz;
                    for (int ty = 0; ty < tileH; ty++) {
                        for (int tx = 0; tx < tileW; tx++) {
                            tileData[channelOffset + ty * outSz + tx] =
                                    raster.getSampleFloat(tx, ty, c);
                        }
                    }
                }

                // Add binary cell mask channel (last channel)
                if (includeMask) {
                    java.awt.Shape roiShape = det.getROI().getShape();
                    int maskOffset = cellOffset + imageChannels * outSz * outSz;
                    for (int ty = 0; ty < outSz; ty++) {
                        for (int tx = 0; tx < outSz; tx++) {
                            // Convert downsampled pixel coords to full-resolution image coords
                            double imgX = x + tx * downsample;
                            double imgY = y + ty * downsample;
                            if (roiShape.contains(imgX, imgY)) {
                                tileData[maskOffset + ty * outSz + tx] = 1.0f;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to read tile for detection {}: {}", i, e.getMessage());
            }
        });

        return tileData;
    }

    /**
     * Runs a per-cell tile-read task over {@code [0, nCells)}, in parallel when
     * the job is large enough to be worth it. Each task writes to disjoint output
     * offsets (index-derived), so no locking is needed.
     * <p>
     * Concurrent {@code readRegion} is safe for the common large-image readers
     * (verified against QuPath 0.7 source): BioFormats hands each call its own
     * reader from a pooled {@code ReaderPool} and reads inside
     * {@code synchronized(reader)}; OpenSlide relies on the native library's
     * documented thread-safety on a single handle; and
     * {@code AbstractTileableImageServer} coalesces tile requests through a
     * concurrent cache. (QuPath's own viewer/tile-prefetch read concurrently.)
     * <p>
     * The pool is capped small (a few threads) to bound the number of extra
     * BioFormats readers created and to avoid thrashing tiled readers. Progress
     * is reported via an atomic counter (tasks finish out of order). Per-cell
     * exceptions are handled inside {@code body} (zero-fill), as the sequential
     * path did.
     */
    private void forEachTile(int nCells, Consumer<String> progressCallback,
            java.util.function.IntConsumer body) {
        int nThreads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        if (nThreads <= 1 || nCells < 256) {
            for (int i = 0; i < nCells; i++) {
                body.accept(i);
                if ((i + 1) % 500 == 0) {
                    report(progressCallback, "Read " + (i + 1) + "/" + nCells + " tiles...");
                }
            }
            return;
        }
        java.util.concurrent.atomic.AtomicInteger done =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ForkJoinPool pool = new java.util.concurrent.ForkJoinPool(nThreads);
        try {
            pool.submit(() -> java.util.stream.IntStream.range(0, nCells).parallel().forEach(i -> {
                body.accept(i);
                int c = done.incrementAndGet();
                if (c % 500 == 0) {
                    report(progressCallback, "Read " + c + "/" + nCells + " tiles...");
                }
            })).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Tile reading interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            throw new RuntimeException("Tile reading failed: "
                    + (cause == null ? e.getMessage() : cause.getMessage()), cause);
        } finally {
            pool.shutdown();
        }
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
                closeQuietly(tilesNd, "tilesNd");
                closeQuietly(featuresNd, "featuresNd");

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
                        closeQuietly(simNd, "simNd");
                    }
                }

                closeQuietly(tilesNd, "tilesNd");
                closeQuietly(labelsNd, "labelsNd");
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

    // ==================== Autoencoder Training & Inference ====================

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
                    double matchDist = QpcatPreferences.getAePointMatchDistance();
                    double bestDist = matchDist * matchDist;
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
        // specific type" looks like. The user explicitly opted in to existing
        // detection classifications by checking the option, so honour ALL
        // classes including Cluster N from a prior QP-CAT run. Parity with
        // the same fix in AutoencoderDialog.refreshClassDistribution in v0.2.10.
        if (useDetections) {
            int detLabeled = 0;
            for (int i = 0; i < n; i++) {
                PathClass pc = detections.get(i).getPathClass();
                String name;
                if (pc == null || pc == PathClass.getNullClass()) {
                    name = "Unclassified";
                } else {
                    name = pc.toString();
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
     * Trains a VAE classifier on detections from the current image.
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
            String inputMode, int tileSize, double downsample, boolean includeCellMask,
            double validationSplit, int earlyStoppingPatience,
            boolean enableClassWeights, Map<String, Double> manualClassWeights,
            boolean enableAugmentation,
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
        try {

        // Capture each image's filtered detection list ONCE so the labels, the tiles,
        // and the applied results all index the SAME objects in the SAME order.
        // getDetectionObjects() has no guaranteed stable iteration order across calls, so
        // re-reading it later (for tile writing) could misalign tile rows with labels.
        Map<ImageData<BufferedImage>, List<PathObject>> perImageDets = new LinkedHashMap<>();

        // Collect detections and labels from each image
        for (ImageData<BufferedImage> imageData : imageDatas) {
            PathObjectHierarchy hierarchy = imageData.getHierarchy();
            List<PathObject> dets = new ArrayList<>(hierarchy.getDetectionObjects());
            if (cellsOnly) dets.removeIf(d -> !d.isCell());
            perImageDets.put(imageData, dets);
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

        // Extract measurements (for measurement mode, or hybrid tile+measurements mode)
        MeasurementExtractor.ExtractionResult extraction = null;
        boolean hybridMode = useTiles && selectedMeasurements != null && !selectedMeasurements.isEmpty();
        if (!useTiles || hybridMode) {
            report(progressCallback, "Extracting measurements from "
                    + imageDatas.size() + " images...");
            MeasurementExtractor extractor = new MeasurementExtractor();
            extraction = extractor.extractMultiImage(groups, selectedMeasurements);
            if (hybridMode) {
                logger.info("Hybrid tile+measurement mode: {} measurements alongside tiles",
                        extraction.getNMeasurements());
            }
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
                    // Reuse the exact list captured above (do NOT re-read the hierarchy --
                    // that could reorder rows relative to the labels).
                    List<PathObject> dets = perImageDets.getOrDefault(
                            imageData, java.util.Collections.emptyList());
                    if (dets.isEmpty()) continue;

                    report(progressCallback, "Writing tiles from "
                            + server.getMetadata().getName()
                            + " (" + dets.size() + " cells)...");

                    // Process in batches of 500 to limit per-batch memory
                    int tileBatchSize = QpcatPreferences.getAeTileBatchSize();
                    for (int batchStart = 0; batchStart < dets.size(); batchStart += tileBatchSize) {
                        int batchEnd = Math.min(batchStart + tileBatchSize, dets.size());
                        List<PathObject> batch = dets.subList(batchStart, batchEnd);
                        float[] batchTiles = readMultiChannelTilesAroundCentroids(
                                server, batch, tileSize, downsample, includeCellMask, null);

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
                if (manualClassWeights != null && !manualClassWeights.isEmpty()) {
                    inputs.put("manual_weight_names",
                            new ArrayList<>(manualClassWeights.keySet()));
                    inputs.put("manual_weight_values",
                            new ArrayList<>(manualClassWeights.values()));
                }
                inputs.put("enable_augmentation", enableAugmentation);

                // Advanced VAE parameters from Preferences
                inputs.put("kl_beta_max", QpcatPreferences.getAeKlBetaMax());
                inputs.put("kl_cycles", QpcatPreferences.getAeKlCycles());
                inputs.put("kl_ramp_fraction", QpcatPreferences.getAeKlRampFraction());
                inputs.put("free_bits", QpcatPreferences.getAeFreeBits());
                inputs.put("pretrain_fraction", QpcatPreferences.getAePretrainFraction());
                // Measurement-mode augmentation
                inputs.put("aug_noise_std", QpcatPreferences.getAeAugNoise());
                inputs.put("aug_scale_range", QpcatPreferences.getAeAugScale());
                inputs.put("aug_dropout_p", QpcatPreferences.getAeAugDropout());
                // Tile-mode augmentation
                inputs.put("aug_flip_h", QpcatPreferences.isAeAugFlipH());
                inputs.put("aug_flip_v", QpcatPreferences.isAeAugFlipV());
                inputs.put("aug_rotation_90", QpcatPreferences.isAeAugRotation90());
                inputs.put("aug_elastic", QpcatPreferences.isAeAugElastic());
                inputs.put("aug_elastic_alpha", QpcatPreferences.getAeAugElasticAlpha());
                inputs.put("aug_intensity_mode", QpcatPreferences.getAeAugIntensityMode());
                inputs.put("aug_intensity_amount", QpcatPreferences.getAeAugIntensityAmount());
                inputs.put("aug_gauss_noise", QpcatPreferences.getAeAugGaussNoise());

                inputs.put("grad_clip_norm", QpcatPreferences.getAeGradClipNorm());
                inputs.put("lr_scheduler_factor", QpcatPreferences.getAeLrSchedulerFactor());
                inputs.put("lr_scheduler_patience", QpcatPreferences.getAeLrSchedulerPatience());

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
                    inputs.put("tile_size", Math.max(2, (int) Math.round(tileSize / downsample)));

                    // Hybrid mode: also pass measurements alongside tiles
                    if (finalExtraction != null && finalExtraction.getNMeasurements() > 0) {
                        int nMeasurements = finalExtraction.getNMeasurements();
                        NDArray.Shape mShape = new NDArray.Shape(
                                NDArray.Shape.Order.C_ORDER, nCells, nMeasurements);
                        NDArray tileMeasNd = new NDArray(NDArray.DType.FLOAT64, mShape);
                        var mBuf = tileMeasNd.buffer().asDoubleBuffer();
                        for (double[] row : finalExtraction.getData()) mBuf.put(row);
                        inputs.put("tile_measurements", tileMeasNd);
                    }
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
                resultMap.put("best_val_accuracy", task.outputs.get("best_val_accuracy"));
                resultMap.put("best_epoch", task.outputs.get("best_epoch"));
                resultMap.put("n_classes", task.outputs.get("n_classes"));
                resultMap.put("active_units", task.outputs.get("active_units"));

                closeQuietly(latentNd, "latentNd");
                closeQuietly(predNd, "predNd");
                closeQuietly(confNd, "confNd");

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
        } finally {
            // imageDatas mixes the live open image with detached reads; close the reads.
            closeReadImageDatas(imageDatas);
        }
    }

    /**
     * [TEST FEATURE] Evaluates a trained autoencoder against existing labels.
     * Runs inference on checked images and compares predictions to ground truth
     * WITHOUT modifying any object classifications.
     *
     * @return map with confusion_matrix, correct, total_labeled, total_cells, class_names
     */
    public Map<String, Object> evaluateAutoencoder(
            List<ProjectImageEntry<BufferedImage>> imageEntries,
            List<String> measurements, String modelStateBase64,
            String[] classNames, String inputMode, int tileSize, double downsample,
            boolean includeCellMask, boolean cellsOnly,
            boolean labelFromLocked, boolean labelFromPoints, boolean labelFromDetections,
            Consumer<String> progressCallback) throws IOException {

        boolean useTiles = "tiles".equals(inputMode);
        int totalCells = 0;
        int totalCorrect = 0;
        int totalLabeled = 0;
        List<Map<String, Object>> misclassifications = new ArrayList<>();

        // confusion_matrix[actual][predicted] = count
        Map<String, Map<String, Integer>> confusionMatrix = new LinkedHashMap<>();
        for (String cn : classNames) {
            confusionMatrix.put(cn, new LinkedHashMap<>());
            for (String cn2 : classNames) {
                confusionMatrix.get(cn).put(cn2, 0);
            }
        }

        for (int idx = 0; idx < imageEntries.size(); idx++) {
            ProjectImageEntry<BufferedImage> entry = imageEntries.get(idx);
            report(progressCallback, "Evaluating image " + (idx + 1) + "/"
                    + imageEntries.size() + ": " + entry.getImageName());

            ImageData<BufferedImage> imageData;
            try {
                var currentData = qupath.getImageData();
                var currentEntry = (qupath.getProject() != null && currentData != null)
                        ? qupath.getProject().getEntry(currentData) : null;
                if (currentEntry != null && currentEntry.equals(entry)) {
                    imageData = currentData;
                } else {
                    imageData = entry.readImageData();
                }
            } catch (Exception e) {
                logger.warn("Failed to read {}: {}", entry.getImageName(), e.getMessage());
                continue;
            }
            // Close this image's reader at the end of the iteration unless it is the
            // live open image (which the GUI owns).
            ImageData<BufferedImage> iterImageData = imageData;
            try {

            List<PathObject> detections = new ArrayList<>(
                    imageData.getHierarchy().getDetectionObjects());
            if (cellsOnly) detections.removeIf(d -> !d.isCell());
            if (detections.isEmpty()) continue;

            // Get ground truth labels
            List<String> imgClassNames = new ArrayList<>();
            int[] groundTruth = extractClassLabels(imageData.getHierarchy(), detections,
                    imgClassNames, labelFromLocked, labelFromPoints, labelFromDetections);

            // Run inference (same as apply, but don't write results back)
            // For measurement mode, filter to objects with all measurements
            List<PathObject> validDetections = detections;
            if (!useTiles && measurements != null && !measurements.isEmpty()) {
                List<PathObject> filtered = new ArrayList<>();
                List<Integer> filteredGT = new ArrayList<>();
                for (int i = 0; i < detections.size(); i++) {
                    boolean hasMissing = false;
                    for (String m : measurements) {
                        if (detections.get(i).getMeasurements().get(m) == null) {
                            hasMissing = true;
                            break;
                        }
                    }
                    if (!hasMissing) {
                        filtered.add(detections.get(i));
                        filteredGT.add(groundTruth[i]);
                    }
                }
                validDetections = filtered;
                groundTruth = filteredGT.stream().mapToInt(Integer::intValue).toArray();
            }

            if (validDetections.isEmpty()) continue;

            // Run inference via Appose
            MeasurementExtractor.ExtractionResult extraction = null;
            Path inferTileFile = null;
            int nChannels = 0;

            if (useTiles) {
                ImageServer<BufferedImage> server = imageData.getServer();
                nChannels = server.nChannels();
                if (includeCellMask) nChannels++;
                inferTileFile = Files.createTempFile(getProjectTempDir(), "qpcat_eval_", ".bin");
                try (var raf = new java.io.RandomAccessFile(inferTileFile.toFile(), "rw")) {
                    int batchSz = QpcatPreferences.getAeTileBatchSize();
                    for (int bs = 0; bs < validDetections.size(); bs += batchSz) {
                        int be = Math.min(bs + batchSz, validDetections.size());
                        float[] batch = readMultiChannelTilesAroundCentroids(
                                server, validDetections.subList(bs, be), tileSize, downsample, includeCellMask, null);
                        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(batch.length * 4);
                        bb.order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        bb.asFloatBuffer().put(batch);
                        raf.write(bb.array());
                    }
                }
            } else {
                MeasurementExtractor extractor = new MeasurementExtractor();
                extraction = extractor.extract(validDetections, measurements);
            }

            final MeasurementExtractor.ExtractionResult fExtraction = extraction;
            final Path fInferTileFile = inferTileFile;
            final int fNChannels = nChannels;
            final int nCells = validDetections.size();

            int[] predictions;
            try {
                predictions = ApposeClusteringService.withExtensionClassLoader(() -> {
                    Map<String, Object> inputs = new HashMap<>();
                    inputs.put("model_state_base64", modelStateBase64);

                    if (useTiles) {
                        inputs.put("tile_file_path", fInferTileFile.toAbsolutePath().toString());
                        inputs.put("n_cells", nCells);
                        inputs.put("n_channels", fNChannels);
                        inputs.put("tile_size", Math.max(2, (int) Math.round(tileSize / downsample)));
                    } else {
                        int nMeasurements = fExtraction.getNMeasurements();
                        NDArray.Shape shape = new NDArray.Shape(
                                NDArray.Shape.Order.C_ORDER, nCells, nMeasurements);
                        NDArray measurementsNd = new NDArray(NDArray.DType.FLOAT64, shape);
                        var buf = measurementsNd.buffer().asDoubleBuffer();
                        for (double[] row : fExtraction.getData()) buf.put(row);
                        inputs.put("measurements", measurementsNd);
                        inputs.put("marker_names", List.of(fExtraction.getMeasurementNames()));
                    }

                    ApposeClusteringService service = ApposeClusteringService.getInstance();
                    Task task = service.runTask("infer_autoencoder", inputs);

                    NDArray predNd = (NDArray) task.outputs.get("predicted_labels");
                    int[] preds = new int[nCells];
                    predNd.buffer().asIntBuffer().get(preds);
                    closeQuietly(predNd, "predNd");
                    return preds;
                });
            } catch (Exception e) {
                logger.error("Inference failed for {}: {}", entry.getImageName(), e.getMessage());
                deleteTempFile(inferTileFile);
                continue;
            }

            deleteTempFile(inferTileFile);
            totalCells += validDetections.size();

            // Compare predictions to ground truth
            final int[] gt = groundTruth;
            for (int i = 0; i < nCells; i++) {
                if (gt[i] < 0) continue; // unlabeled
                totalLabeled++;

                // Map ground truth index to class name (using merged order)
                String actualClass = gt[i] < imgClassNames.size()
                        ? imgClassNames.get(gt[i]) : "Unknown";
                String predictedClass = predictions[i] >= 0 && predictions[i] < classNames.length
                        ? classNames[predictions[i]] : "Unknown";

                if (actualClass.equals(predictedClass)) {
                    totalCorrect++;
                } else {
                    // Collect misclassification for navigation
                    var det = validDetections.get(i);
                    Map<String, Object> mis = new LinkedHashMap<>();
                    mis.put("image", entry.getImageName());
                    mis.put("imageId", entry.getID());
                    mis.put("x", det.getROI().getCentroidX());
                    mis.put("y", det.getROI().getCentroidY());
                    mis.put("actual", actualClass);
                    mis.put("predicted", predictedClass);
                    misclassifications.add(mis);
                }

                // Update confusion matrix
                Map<String, Integer> row = confusionMatrix.get(actualClass);
                if (row != null) {
                    row.merge(predictedClass, 1, Integer::sum);
                }
            }
            } finally {
                if (iterImageData != qupath.getImageData()) closeReadImageData(iterImageData);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("confusion_matrix", confusionMatrix);
        result.put("correct", totalCorrect);
        result.put("total_labeled", totalLabeled);
        result.put("total_cells", totalCells);
        result.put("class_names", classNames);
        result.put("misclassifications", misclassifications);

        double accuracy = totalLabeled > 0 ? (double) totalCorrect / totalLabeled * 100 : 0;
        logger.info("[TEST] Evaluation: {}/{} correct ({} labeled cells across {} images)",
                totalCorrect, totalLabeled, totalLabeled, imageEntries.size());

        return result;
    }

    /**
     * [TEST FEATURE] Applies a trained autoencoder to selected images.
     *
     * @param imageEntries     project images to apply to
     * @param measurements     measurement names (must match training)
     * @param modelStateBase64 base64-encoded model checkpoint
     * @param classNames       class names from training
     * @param progressCallback optional progress callback
     * @throws IOException if application fails
     */
    /**
     * @return true if the currently open image was among those applied to
     *         (caller should prompt user to reload)
     */
    public boolean applyAutoencoderToProject(
            List<ProjectImageEntry<BufferedImage>> imageEntries,
            List<String> measurements,
            String modelStateBase64,
            String[] classNames,
            String inputMode, int tileSize, double downsample, boolean includeCellMask,
            boolean cellsOnly,
            Consumer<String> progressCallback) throws IOException {

        long startTime = System.currentTimeMillis();
        boolean currentImageApplied = false;
        int totalApplied = 0;

        for (int idx = 0; idx < imageEntries.size(); idx++) {
            ProjectImageEntry<BufferedImage> entry = imageEntries.get(idx);
            report(progressCallback, "Processing image " + (idx + 1) + "/"
                    + imageEntries.size() + ": " + entry.getImageName());

            // Always read from qpdata file (not live in-memory data).
            // This ensures we modify and save the persistent version.
            // If the current image is affected, we'll prompt the user to reload.
            ImageData<BufferedImage> imageData;
            boolean isCurrentImage = false;
            try {
                var currentData = qupath.getImageData();
                var currentEntry = (qupath.getProject() != null && currentData != null)
                        ? qupath.getProject().getEntry(currentData) : null;
                isCurrentImage = (currentEntry != null && currentEntry.equals(entry));
                imageData = entry.readImageData();
            } catch (Exception e) {
                logger.warn("Failed to read {}: {}", entry.getImageName(), e.getMessage());
                continue;
            }
            // This is always a detached read (even for the current image); close its
            // reader when the iteration ends.
            ImageData<BufferedImage> iterImageData = imageData;
            try {

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
                    int batchSz = QpcatPreferences.getAeTileBatchSize();
                    for (int bs = 0; bs < detections.size(); bs += batchSz) {
                        int be = Math.min(bs + batchSz, detections.size());
                        float[] batch = readMultiChannelTilesAroundCentroids(
                                server, detections.subList(bs, be), tileSize, downsample, includeCellMask, null);
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
                        inputs.put("tile_size", Math.max(2, (int) Math.round(tileSize / downsample)));
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

                    closeQuietly(latentNd, "latentNd");
                    closeQuietly(predNd, "predNd");
                    closeQuietly(confNd, "confNd");

                    return null;
                });
            } catch (Exception e) {
                logger.error("Failed to apply autoencoder to {}: {}",
                        entry.getImageName(), e.getMessage());
                continue;
            }

            // Always save to qpdata file (consistent for all images)
            try {
                entry.saveImageData(imageData);
                totalApplied++;
                if (isCurrentImage) currentImageApplied = true;
                logger.info("Saved autoencoder results for {} ({} detections)",
                        entry.getImageName(), detections.size());
            } catch (Exception e) {
                logger.error("Failed to save {}: {}", entry.getImageName(), e.getMessage());
            }

            // Clean up per-image tile temp file
            if (useTiles) deleteTempFile(inferTileFile);
            } finally {
                if (iterImageData != qupath.getImageData()) closeReadImageData(iterImageData);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        String msg = "[TEST] Autoencoder applied to " + totalApplied + "/"
                + imageEntries.size() + " images";
        report(progressCallback, msg);
        OperationLogger.getInstance().logOperation("AUTOENCODER_PROJECT_APPLY",
                Map.of("Images", String.valueOf(imageEntries.size()),
                       "Applied", String.valueOf(totalApplied)),
                msg, elapsed);

        return currentImageApplied;
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

    /** Deletes a temp file if it exists. Retries once after a delay for Windows file locking. */
    private static void deleteTempFile(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // Windows: Python memmap may still hold file handle briefly after task returns.
            // Wait for GC/close to release, then retry.
            logger.debug("Temp file locked, retrying after 500ms: {}", file);
            try {
                Thread.sleep(500);
                Files.deleteIfExists(file);
            } catch (Exception e2) {
                // Mark for deletion on JVM exit as last resort
                file.toFile().deleteOnExit();
                logger.warn("Temp file still locked, will delete on exit: {}", file);
            }
        }
    }

    private static void report(Consumer<String> callback, String message) {
        if (callback != null) callback.accept(message);
    }

    /**
     * Report a phase boundary: the message is prefixed "{@code token|}" so a
     * dialog can advance a phase checklist (it strips the token before display).
     * Used only on the clustering paths, whose dialog parses the token; other
     * workflows keep plain messages.
     */
    private void reportPhase(Consumer<String> callback, String token, String message) {
        if (phaseCallback != null) phaseCallback.accept(token);
        if (callback != null) callback.accept(message);
    }

    /**
     * Close an Appose NDArray (or any AutoCloseable), logging at debug if it
     * fails. Used during best-effort cleanup of shared-memory buffers; we
     * cannot meaningfully recover from a close failure here, but silent
     * swallowing hides real bugs.
     */
    private static void closeQuietly(AutoCloseable resource, String name) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception e) {
            logger.debug("Failed to close {}: {}", name, e.getMessage());
        }
    }

    // ==================== Spatial Stats Expansion (v1) parsers ====================

    static qupath.ext.qpcat.model.RipleyResult parseRipley(String json, Gson gson) {
        Map<String, Object> raw = gson.fromJson(json,
                new TypeToken<Map<String, Object>>(){}.getType());
        qupath.ext.qpcat.model.RipleyResult out = new qupath.ext.qpcat.model.RipleyResult();
        out.setClusterNames(asStringList(raw.get("cluster_names")));
        out.setRadii(asDoubleArray(raw.get("radii")));
        out.setKValues(asDouble2D(raw.get("k_values")));
        out.setLValues(asDouble2D(raw.get("l_values")));
        out.setPoissonK(asDoubleArray(raw.get("poisson_k")));
        out.setPoissonL(asDoubleArray(raw.get("poisson_l")));
        Object pv = raw.get("p_values");
        if (pv instanceof Map<?, ?> pvm) {
            Map<String, Double> pMap = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : pvm.entrySet()) {
                if (e.getKey() != null && e.getValue() instanceof Number n) {
                    pMap.put(e.getKey().toString(), n.doubleValue());
                }
            }
            out.setPValues(pMap);
        }
        Object n = raw.get("n_permutations");
        if (n instanceof Number num) out.setNPermutations(num.intValue());
        if (raw.get("graph_type") != null) out.setGraphType(raw.get("graph_type").toString());
        return out;
    }

    static qupath.ext.qpcat.model.GearyCResult parseGeary(String json, Gson gson) {
        Map<String, Object> raw = gson.fromJson(json,
                new TypeToken<Map<String, Object>>(){}.getType());
        qupath.ext.qpcat.model.GearyCResult out = new qupath.ext.qpcat.model.GearyCResult();
        Object stats = raw.get("marker_stats");
        if (stats instanceof Map<?, ?> mstats) {
            for (Map.Entry<?, ?> e : mstats.entrySet()) {
                if (e.getKey() == null) continue;
                String marker = e.getKey().toString();
                if (e.getValue() instanceof Map<?, ?> entry) {
                    double c = readNumber(entry.get("c"), Double.NaN);
                    double p = readNumber(entry.get("p_value"), Double.NaN);
                    out.putMarker(marker, c, p);
                }
            }
        }
        Object n = raw.get("n_permutations");
        if (n instanceof Number num) out.setNPermutations(num.intValue());
        if (raw.get("graph_type") != null) out.setGraphType(raw.get("graph_type").toString());
        return out;
    }

    static qupath.ext.qpcat.model.CoOccurrenceResult parseCoOccurrence(String json, Gson gson) {
        Map<String, Object> raw = gson.fromJson(json,
                new TypeToken<Map<String, Object>>(){}.getType());
        qupath.ext.qpcat.model.CoOccurrenceResult out =
                new qupath.ext.qpcat.model.CoOccurrenceResult();
        if (raw.get("mode") != null) out.setMode(raw.get("mode").toString());
        out.setClusterNames(asStringList(raw.get("cluster_names")));
        out.setIntervals(asDoubleArray(raw.get("intervals")));
        out.setData(asDouble3D(raw.get("data")));
        Object n = raw.get("n_permutations");
        if (n instanceof Number num) out.setNPermutations(num.intValue());
        if (raw.get("graph_type") != null) out.setGraphType(raw.get("graph_type").toString());
        return out;
    }

    private static List<String> asStringList(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                out.add(o == null ? "" : o.toString());
            }
            return out;
        }
        return new ArrayList<>();
    }

    private static double[] asDoubleArray(Object raw) {
        if (raw instanceof List<?> list) {
            double[] out = new double[list.size()];
            for (int i = 0; i < list.size(); i++) {
                out[i] = readNumber(list.get(i), 0.0);
            }
            return out;
        }
        return new double[0];
    }

    private static double[][] asDouble2D(Object raw) {
        if (raw instanceof List<?> list) {
            double[][] out = new double[list.size()][];
            for (int i = 0; i < list.size(); i++) {
                out[i] = asDoubleArray(list.get(i));
            }
            return out;
        }
        return new double[0][];
    }

    private static double[][][] asDouble3D(Object raw) {
        if (raw instanceof List<?> list) {
            double[][][] out = new double[list.size()][][];
            for (int i = 0; i < list.size(); i++) {
                out[i] = asDouble2D(list.get(i));
            }
            return out;
        }
        return new double[0][][];
    }

    private static double readNumber(Object raw, double fallback) {
        if (raw instanceof Number n) return n.doubleValue();
        if (raw == null) return fallback;
        try {
            return Double.parseDouble(raw.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ==================== Spatial Graph Overlay (v0.3) ====================

    /**
     * Resolve the Delaunay max-edge threshold to send to Python in pixel
     * units. Prefers the canonical micron value
     * ({@link ClusteringConfig#getDelaunayMaxEdgeUm}) when the current
     * image has a pixel calibration; otherwise falls back to the pixel
     * preference. Negative values (no-pruning sentinel) pass through.
     */
    /**
     * Resolve the pixel size in microns for the run. Used to scale
     * per-cell distance + triangle-area columns from pixel units into
     * microns. Returns 1.0 (no scaling) when no calibration is available,
     * which keeps the unit treatment consistent with the v0.2.7
     * spatial-stats expansion. For multi-image runs, prefers the first
     * segment's calibration; otherwise falls back to the current viewer's
     * image data and finally to 1.0.
     */
    private double resolveSpatialPixelSizeUm(
            MeasurementExtractor.ExtractionResult extraction) {
        try {
            if (extraction != null && extraction.getImageSegments() != null
                    && !extraction.getImageSegments().isEmpty()) {
                for (MeasurementExtractor.ImageSegment seg : extraction.getImageSegments()) {
                    @SuppressWarnings("unchecked")
                    ImageData<BufferedImage> segData =
                            (ImageData<BufferedImage>) seg.getImageData();
                    Double px = readPixelSizeMicrons(segData);
                    if (px != null) {
                        return px;
                    }
                }
            }
            if (qupath != null) {
                ImageData<BufferedImage> imageData = qupath.getImageData();
                Double px = readPixelSizeMicrons(imageData);
                if (px != null) {
                    return px;
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to resolve pixel size in microns: {}", e.getMessage());
        }
        return 1.0;
    }

    private static Double readPixelSizeMicrons(ImageData<BufferedImage> imageData) {
        if (imageData == null || imageData.getServer() == null) {
            return null;
        }
        PixelCalibration cal = imageData.getServer().getPixelCalibration();
        if (cal != null && cal.hasPixelSizeMicrons()) {
            double px = cal.getAveragedPixelSizeMicrons();
            if (px > 0 && Double.isFinite(px)) {
                return px;
            }
        }
        return null;
    }

    private double resolveDelaunayMaxEdgePixels(ClusteringConfig config) {
        double micronValue = config.getDelaunayMaxEdgeUm();
        if (micronValue > 0 && qupath != null) {
            try {
                ImageData<BufferedImage> imageData = qupath.getImageData();
                if (imageData != null && imageData.getServer() != null) {
                    PixelCalibration cal = imageData.getServer().getPixelCalibration();
                    if (cal != null && cal.hasPixelSizeMicrons()) {
                        double pxSize = cal.getAveragedPixelSizeMicrons();
                        if (pxSize > 0) {
                            return micronValue / pxSize;
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to resolve Delaunay max-edge microns to pixels: {}",
                        e.getMessage());
            }
        }
        return config.getSpatialGraphDelaunayMaxEdge();
    }

    /**
     * Pull the v0.3 spatial-graph payload from {@code task.outputs}.
     * Returns {@code null} when no edge COO is present. Each output is
     * optional; missing arrays are left null on the payload.
     */
    private ClusteringResult.SpatialGraphPayload retrieveSpatialGraphPayload(
            Task task, int nCells) {
        if (!task.outputs.containsKey("spatial_graph_row")
                && !task.outputs.containsKey("spatial_num_neighbors")
                && !task.outputs.containsKey("component_labels")) {
            return null;
        }

        ClusteringResult.SpatialGraphPayload payload = new ClusteringResult.SpatialGraphPayload();
        List<NDArray> opened = new ArrayList<>();
        try {
            if (task.outputs.containsKey("spatial_graph_row")
                    && task.outputs.containsKey("spatial_graph_col")) {
                NDArray rowNd = (NDArray) task.outputs.get("spatial_graph_row");
                NDArray colNd = (NDArray) task.outputs.get("spatial_graph_col");
                opened.add(rowNd);
                opened.add(colNd);
                int nEdges = (int) Math.min(rowNd.shape().numElements(),
                        Integer.MAX_VALUE);
                long[] rowArr = new long[nEdges];
                long[] colArr = new long[nEdges];
                rowNd.buffer().asLongBuffer().get(rowArr);
                colNd.buffer().asLongBuffer().get(colArr);
                payload.setEdgeRow(rowArr);
                payload.setEdgeCol(colArr);
                logger.info("Received spatial graph edge COO ({} undirected edges)", nEdges);
            }

            if (task.outputs.containsKey("spatial_num_neighbors")) {
                NDArray nd = (NDArray) task.outputs.get("spatial_num_neighbors");
                opened.add(nd);
                int[] arr = new int[nCells];
                nd.buffer().asIntBuffer().get(arr);
                payload.setNumNeighbors(arr);
            }

            payload.setMeanDistance(readDoubleArray(task, "spatial_mean_distance", nCells, opened));
            payload.setMedianDistance(readDoubleArray(task, "spatial_median_distance", nCells, opened));
            payload.setMaxDistance(readDoubleArray(task, "spatial_max_distance", nCells, opened));
            payload.setMinDistance(readDoubleArray(task, "spatial_min_distance", nCells, opened));

            if (task.outputs.containsKey("spatial_triangle_areas")) {
                NDArray nd = (NDArray) task.outputs.get("spatial_triangle_areas");
                opened.add(nd);
                double[] flat = new double[nCells * 2];
                nd.buffer().asDoubleBuffer().get(flat);
                double[] mean = new double[nCells];
                double[] max = new double[nCells];
                for (int i = 0; i < nCells; i++) {
                    mean[i] = flat[i * 2];
                    max[i] = flat[i * 2 + 1];
                }
                payload.setMeanTriangleArea(mean);
                payload.setMaxTriangleArea(max);
                logger.info("Received Delaunay triangle areas for {} cells", nCells);
            }

            if (task.outputs.containsKey("component_labels")) {
                NDArray nd = (NDArray) task.outputs.get("component_labels");
                opened.add(nd);
                int[] arr = new int[nCells];
                nd.buffer().asIntBuffer().get(arr);
                payload.setComponentLabels(arr);
                logger.info("Received connected-component labels for {} cells", nCells);
            }

            return payload;
        } finally {
            for (NDArray nd : opened) {
                try {
                    nd.close();
                } catch (Exception e) {
                    logger.trace("Failed to close NDArray (best-effort): {}", e.getMessage());
                }
            }
        }
    }

    private static double[] readDoubleArray(Task task, String key, int n, List<NDArray> opened) {
        if (!task.outputs.containsKey(key)) return null;
        NDArray nd = (NDArray) task.outputs.get(key);
        opened.add(nd);
        double[] arr = new double[n];
        nd.buffer().asDoubleBuffer().get(arr);
        return arr;
    }

    /**
     * Apply a v0.3 spatial-graph payload to the given image data: build
     * {@link PathObjectConnections}, write {@code QPCAT spatial:} per-cell
     * measurements, and (when {@code writeComponentMeasurements} is true)
     * write per-component {@code QPCAT component:} aggregates. Fires a
     * hierarchy event on the FX thread when an FX runtime is available.
     *
     * <p>Called once per image (single-image runs, per-segment in
     * project runs). The order is deterministic against
     * {@code detections.get(i)}; the i-th cell maps to the i-th element
     * of each per-cell array on the payload.</p>
     */
    public void applySpatialGraphPayload(
            ImageData<BufferedImage> imageData,
            List<PathObject> detections,
            ClusteringConfig config,
            ClusteringResult.SpatialGraphPayload payload) {
        // Single-image path: prompt (once) when the overlay is dense/large.
        applySpatialGraphPayload(imageData, detections, config, payload, null);
    }

    /**
     * @param overlayProceedOverride when non-null, use this pre-decided answer for a
     *   dense/large overlay INSTEAD of prompting. Multi-image runs decide once for the
     *   whole set (one prompt, not one per image) and pass the result here. Null keeps
     *   the single-image prompt behaviour.
     */
    @SuppressWarnings("deprecation")
    public void applySpatialGraphPayload(
            ImageData<BufferedImage> imageData,
            List<PathObject> detections,
            ClusteringConfig config,
            ClusteringResult.SpatialGraphPayload payload,
            Boolean overlayProceedOverride) {

        if (imageData == null || detections == null || payload == null) return;
        int n = detections.size();

        // D1 (v0.3.4): when the payload came from SpatialGraphPayload.slice
        // (project / multi-image path), per-cell aggregates were
        // intentionally left null because the global values include
        // phantom cross-image neighbours. Recompute them from the sliced
        // edge COO + this image's centroids + this image's pixel
        // calibration. Triangle areas stay null on multi-image (would
        // require per-image Delaunay re-triangulation).
        if (config.isWriteNodeMeasurements()
                && payload.hasEdgeCoo()
                && !payload.hasNodeMeasurements()) {
            recomputeNodeAggregatesFromEdges(imageData, detections, payload);
        }

        // 1. Per-cell node measurements
        if (config.isWriteNodeMeasurements() && payload.hasNodeMeasurements()) {
            writeNodeMeasurements(detections, payload);
        }

        // 2. Per-component aggregates (Java-side groupby)
        if (config.isWriteComponentMeasurements() && payload.hasComponentLabels()) {
            writeComponentMeasurements(detections, payload.getComponentLabels());
        }

        // 3. PathObjectConnections overlay
        if (config.isPushConnectionsToViewer() && payload.hasEdgeCoo()) {
            int nEdges = payload.getEdgeRow().length;
            int threshold = config.getConnectionsPromptThreshold();
            // Average connections per cell = degree (each undirected edge touches
            // two cells). Above ~4 the overlay tends to render as an unhelpful
            // white mass -- the edges fill the screen and we cannot yet
            // colour-code edges or nodes to make it readable.
            double avgDegree = n > 0 ? (2.0 * nEdges) / n : 0.0;
            boolean dense = avgDegree > OVERLAY_DENSITY_WARN;
            boolean large = threshold > 0 && nEdges > threshold;
            boolean proceed = true;
            if (dense || large) {
                // F2: prompt the user instead of silently skipping. Workflow
                // runs on a background thread; use the CountDownLatch +
                // Platform.runLater pattern documented in
                // claude-reports/2025-01-30_dialog-fixes-session.md. For multi-image
                // runs the decision is made ONCE up front and passed in via
                // overlayProceedOverride, so we do not prompt once per image.
                proceed = (overlayProceedOverride != null)
                        ? overlayProceedOverride
                        : confirmOverlayPush(nEdges, threshold, avgDegree, dense, large);
                if (!proceed) {
                    logger.info("Spatial graph viewer push declined by user"
                                    + " (edges={}, avgDegree={}, threshold={})",
                            nEdges, String.format("%.1f", avgDegree), threshold);
                }
            }
            if (proceed) {
                DefaultPathObjectConnectionGroup group = buildConnectionGroup(
                        detections, payload.getEdgeRow(), payload.getEdgeCol(), n);
                attachConnections(imageData, group);
                fireHierarchyChangedFx(imageData);
            }
        }
    }

    /**
     * F2: confirmation dialog for pushing a large spatial-graph overlay to
     * the viewer. Called from a background thread; blocks until the user
     * answers Yes or No (default No). Returns true to proceed with the
     * push, false to skip. If the FX thread is unavailable (headless run,
     * tests) the prompt defaults to false (skip), matching the
     * intake-specified "default to safer" behaviour.
     */
    private boolean confirmOverlayPush(int nEdges, int threshold, double avgDegree,
                                       boolean dense, boolean large) {
        if (Platform.isFxApplicationThread()) {
            return showOverlayPushDialog(nEdges, threshold, avgDegree, dense, large);
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean answer = new AtomicBoolean(false);
        try {
            Platform.runLater(() -> {
                try {
                    answer.set(showOverlayPushDialog(nEdges, threshold, avgDegree, dense, large));
                } finally {
                    latch.countDown();
                }
            });
            latch.await();
            return answer.get();
        } catch (IllegalStateException e) {
            // FX toolkit not initialised (e.g. headless test run).
            logger.warn("Cannot prompt for overlay push (FX not running);"
                            + " defaulting to skip (edges={}, avgDegree={})",
                    nEdges, String.format("%.1f", avgDegree));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean showOverlayPushDialog(int nEdges, int threshold, double avgDegree,
                                          boolean dense, boolean large) {
        StringBuilder body = new StringBuilder();
        if (dense) {
            body.append(String.format(
                    "This spatial graph averages %.1f connections per cell (%d edges).%n%n"
                            + "At this density the overlay usually renders as a solid white "
                            + "mass that fills the viewer and is not informative -- QP-CAT "
                            + "cannot yet colour-code edges or nodes to make it readable.%n%n",
                    avgDegree, nEdges));
        } else {
            body.append(String.format(
                    "The spatial graph has %d edges (threshold: %d).%n%n", nEdges, threshold));
        }
        body.append("Push it to the viewer anyway? ")
            .append("(The graph and its measurements are still computed and saved either way; "
                    + "this only controls the on-screen overlay. Large graphs can also slow "
                    + "pan and zoom.)");
        // Dialogs.showYesNoDialog returns true for Yes, false for No.
        return Dialogs.showYesNoDialog("QP-CAT - spatial graph overlay", body.toString());
    }

    /**
     * One-prompt confirmation for a MULTI-IMAGE run: decides the overlay push for the
     * whole set at once (instead of once per image). Same background-thread-safe latch
     * pattern as {@link #confirmOverlayPush}. Defaults to skip if FX is unavailable.
     */
    private boolean confirmOverlayPushBatch(int nImages, double maxAvgDegree, int maxEdges,
                                            int threshold, boolean dense, boolean large) {
        if (Platform.isFxApplicationThread()) {
            return showOverlayPushDialogBatch(nImages, maxAvgDegree, maxEdges, threshold, dense, large);
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean answer = new AtomicBoolean(false);
        try {
            Platform.runLater(() -> {
                try {
                    answer.set(showOverlayPushDialogBatch(
                            nImages, maxAvgDegree, maxEdges, threshold, dense, large));
                } finally {
                    latch.countDown();
                }
            });
            latch.await();
            return answer.get();
        } catch (IllegalStateException e) {
            logger.warn("Cannot prompt for overlay push (FX not running); defaulting to skip"
                    + " (images={}, maxAvgDegree={})", nImages, String.format("%.1f", maxAvgDegree));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean showOverlayPushDialogBatch(int nImages, double maxAvgDegree, int maxEdges,
                                               int threshold, boolean dense, boolean large) {
        StringBuilder body = new StringBuilder();
        body.append(String.format("Applying the spatial-graph overlay across %d images.%n%n",
                nImages));
        if (dense) {
            body.append(String.format(
                    "The densest image averages %.1f connections per cell (up to %d edges).%n%n"
                            + "At this density the overlay usually renders as a solid white mass "
                            + "that fills the viewer and is not informative -- QP-CAT cannot yet "
                            + "colour-code edges or nodes to make it readable.%n%n",
                    maxAvgDegree, maxEdges));
        } else {
            body.append(String.format(
                    "The largest graph has %d edges (threshold: %d).%n%n", maxEdges, threshold));
        }
        body.append("Push the overlay to ALL of these images? ")
            .append("(The graph and its measurements are still computed and saved either way; "
                    + "this only controls the on-screen overlay. This choice is applied to every "
                    + "image in the run -- you are asked once, not per image.)");
        return Dialogs.showYesNoDialog("QP-CAT - spatial graph overlay", body.toString());
    }

    /** Best available image name for a segment, for the per-image spatial-graph summary. */
    private static String segmentImageName(MeasurementExtractor.ImageSegment seg) {
        Object e = seg.getImageEntry();
        if (e instanceof ProjectImageEntry<?> pe && pe.getImageName() != null) {
            return pe.getImageName();
        }
        return "image";
    }

    /** Show ONE spatial-graph summary (per-image averages + run mean) on the FX thread. */
    private void reportSpatialGraphSummary(List<String> perImage, double meanAvg) {
        if (qupath == null) return;   // headless run: the log line already recorded it
        int shown = Math.min(perImage.size(), 30);
        StringBuilder sb = new StringBuilder("Spatial graph -- average connections per cell:\n\n");
        for (int i = 0; i < shown; i++) sb.append("  ").append(perImage.get(i)).append('\n');
        if (perImage.size() > shown) {
            sb.append("  ... and ").append(perImage.size() - shown).append(" more\n");
        }
        sb.append(String.format(java.util.Locale.US,
                "%nMean across %d images: %.1f connections/cell.", perImage.size(), meanAvg));
        String msg = sb.toString();
        Platform.runLater(() -> Dialogs.showPlainMessage("QP-CAT - spatial graph overlay", msg));
    }

    private void writeNodeMeasurements(List<PathObject> detections,
                                        ClusteringResult.SpatialGraphPayload payload) {
        int n = detections.size();
        int[] numNeighbors = payload.getNumNeighbors();
        double[] mean = payload.getMeanDistance();
        double[] median = payload.getMedianDistance();
        double[] max = payload.getMaxDistance();
        double[] min = payload.getMinDistance();
        double[] triMean = payload.getMeanTriangleArea();
        double[] triMax = payload.getMaxTriangleArea();

        int limit = Math.min(n, numNeighbors == null ? n : numNeighbors.length);
        for (int i = 0; i < limit; i++) {
            MeasurementList ml = detections.get(i).getMeasurementList();
            if (numNeighbors != null) {
                ml.put("QPCAT spatial: Num neighbors", numNeighbors[i]);
            }
            putIfFinite(ml, "QPCAT spatial: Mean distance", mean, i);
            putIfFinite(ml, "QPCAT spatial: Median distance", median, i);
            putIfFinite(ml, "QPCAT spatial: Max distance", max, i);
            putIfFinite(ml, "QPCAT spatial: Min distance", min, i);
            putIfFinite(ml, "QPCAT spatial: Mean triangle area", triMean, i);
            putIfFinite(ml, "QPCAT spatial: Max triangle area", triMax, i);
            ml.close();
        }
        logger.info("Wrote QPCAT spatial: measurements to {} cells", limit);
    }

    private static void putIfFinite(MeasurementList ml, String name, double[] arr, int idx) {
        if (arr == null || idx >= arr.length) return;
        double v = arr[idx];
        if (Double.isFinite(v)) {
            ml.put(name, v);
        }
    }

    /**
     * D1 fix (v0.3.4): recompute per-cell aggregates from the sliced
     * edge COO of a multi-image-clustering payload, using this image's
     * own centroids and pixel calibration. Mutates the payload in place
     * (writes numNeighbors + meanDistance/medianDistance/maxDistance/
     * minDistance). Triangle areas remain null on the multi-image
     * path -- recomputing them would require re-running the Delaunay
     * triangulation per-image, which is deferred to a future release.
     *
     * <p>Distance scaling matches the global path: microns when the
     * image has pixel calibration, pixels otherwise.</p>
     */
    private void recomputeNodeAggregatesFromEdges(
            ImageData<BufferedImage> imageData,
            List<PathObject> detections,
            ClusteringResult.SpatialGraphPayload payload) {
        int n = detections.size();
        if (n == 0) return;
        long[] er = payload.getEdgeRow();
        long[] ec = payload.getEdgeCol();
        if (er == null || ec == null) return;

        double pxSizeUm = 1.0;
        Double cal = readPixelSizeMicrons(imageData);
        if (cal != null) pxSizeUm = cal;

        // Cache centroids once.
        double[] cx = new double[n];
        double[] cy = new double[n];
        for (int i = 0; i < n; i++) {
            cx[i] = detections.get(i).getROI().getCentroidX();
            cy[i] = detections.get(i).getROI().getCentroidY();
        }

        // Per-cell neighbour distance lists.
        List<List<Double>> neighbours = new ArrayList<>(n);
        for (int i = 0; i < n; i++) neighbours.add(new ArrayList<>());
        int nEdges = er.length;
        for (int e = 0; e < nEdges; e++) {
            int a = (int) er[e];
            int b = (int) ec[e];
            if (a < 0 || a >= n || b < 0 || b >= n) continue;
            double dx = (cx[a] - cx[b]) * pxSizeUm;
            double dy = (cy[a] - cy[b]) * pxSizeUm;
            double d = Math.sqrt(dx * dx + dy * dy);
            neighbours.get(a).add(d);
            neighbours.get(b).add(d);
        }

        int[] numNeighbors = new int[n];
        double[] meanD = new double[n];
        double[] medianD = new double[n];
        double[] maxD = new double[n];
        double[] minD = new double[n];
        for (int i = 0; i < n; i++) {
            List<Double> dl = neighbours.get(i);
            int k = dl.size();
            numNeighbors[i] = k;
            if (k == 0) {
                meanD[i] = Double.NaN;
                medianD[i] = Double.NaN;
                maxD[i] = Double.NaN;
                minD[i] = Double.NaN;
                continue;
            }
            double sum = 0;
            double mn = Double.POSITIVE_INFINITY;
            double mx = Double.NEGATIVE_INFINITY;
            for (double v : dl) {
                sum += v;
                if (v < mn) mn = v;
                if (v > mx) mx = v;
            }
            meanD[i] = sum / k;
            maxD[i] = mx;
            minD[i] = mn;
            // Median: sort the (small) per-cell list.
            Collections.sort(dl);
            if (k % 2 == 1) {
                medianD[i] = dl.get(k / 2);
            } else {
                medianD[i] = (dl.get(k / 2 - 1) + dl.get(k / 2)) / 2.0;
            }
        }
        payload.setNumNeighbors(numNeighbors);
        payload.setMeanDistance(meanD);
        payload.setMedianDistance(medianD);
        payload.setMaxDistance(maxD);
        payload.setMinDistance(minD);
        logger.info("D1 recompute: per-cell aggregates rebuilt for {} cells from {} sliced edges",
                n, nEdges);
    }

    /**
     * Per-component Java-side fan-out. Mirrors
     * {@code DelaunayTriangulation.addClusterMeasurements}: scan once
     * for the existing numeric measurement names (excluding any name
     * starting with {@code QPCAT spatial: } or {@code QPCAT component: }
     * to prevent feedback-loop columns on rerun), groupby on the labels,
     * compute the mean, write back as {@code QPCAT component: mean: <X>}
     * to every cell in the component, and write {@code QPCAT component:
     * size} = component cell count.
     */
    private void writeComponentMeasurements(List<PathObject> detections, int[] labels) {
        int n = detections.size();
        if (labels == null || labels.length < n) return;

        // Single scan for the existing measurement-name set BEFORE writing
        // any new column so the feedback-loop guard sees only the original
        // columns. F3: the guard now skips QP-CAT's other writeback
        // prefixes (UMAP*, tSNE*, PCA*, Cluster*, FM_*, ZS_*, AE_*) in
        // addition to QPCAT spatial: / QPCAT component:, via
        // isFeedbackProneMeasurementName().
        java.util.LinkedHashSet<String> namesSet = new java.util.LinkedHashSet<>();
        for (PathObject p : detections) {
            for (String key : p.getMeasurementList().getNames()) {
                if (isFeedbackProneMeasurementName(key)) {
                    continue;
                }
                namesSet.add(key);
            }
        }
        List<String> names = new ArrayList<>(namesSet);

        // Buckets per component label.
        Map<Integer, List<Integer>> buckets = new HashMap<>();
        for (int i = 0; i < n; i++) {
            buckets.computeIfAbsent(labels[i], k -> new ArrayList<>()).add(i);
        }

        for (Map.Entry<Integer, List<Integer>> entry : buckets.entrySet()) {
            List<Integer> members = entry.getValue();
            int size = members.size();
            double[] sums = new double[names.size()];
            int[] counts = new int[names.size()];
            for (int idx : members) {
                MeasurementList ml = detections.get(idx).getMeasurementList();
                for (int m = 0; m < names.size(); m++) {
                    double v = ml.get(names.get(m));
                    if (Double.isFinite(v)) {
                        sums[m] += v;
                        counts[m]++;
                    }
                }
            }
            double[] means = new double[names.size()];
            for (int m = 0; m < names.size(); m++) {
                means[m] = counts[m] > 0 ? sums[m] / counts[m] : Double.NaN;
            }
            for (int idx : members) {
                MeasurementList ml = detections.get(idx).getMeasurementList();
                for (int m = 0; m < names.size(); m++) {
                    if (Double.isFinite(means[m])) {
                        ml.put("QPCAT component: mean: " + names.get(m), means[m]);
                    }
                }
                ml.put("QPCAT component: size", size);
                ml.close();
            }
        }
        logger.info("Wrote QPCAT component: measurements ({} components, {} cells)",
                buckets.size(), n);
    }

    @SuppressWarnings("deprecation")
    private DefaultPathObjectConnectionGroup buildConnectionGroup(
            List<PathObject> detections, long[] rows, long[] cols, int nCells) {
        // Build adjacency lists from the COO triplet so we can hand the
        // copy-constructor an anonymous PathObjectConnectionGroup that
        // exposes per-cell neighbors directly. The undirected edge (i, j)
        // contributes to both i and j's neighbor list.
        List<List<PathObject>> neighbors = new ArrayList<>(nCells);
        for (int i = 0; i < nCells; i++) {
            neighbors.add(new ArrayList<>());
        }
        int nEdges = rows.length;
        for (int e = 0; e < nEdges; e++) {
            int i = (int) rows[e];
            int j = (int) cols[e];
            if (i < 0 || i >= nCells || j < 0 || j >= nCells) continue;
            neighbors.get(i).add(detections.get(j));
            neighbors.get(j).add(detections.get(i));
        }

        final Map<PathObject, List<PathObject>> map = new HashMap<>(nCells);
        for (int i = 0; i < nCells; i++) {
            map.put(detections.get(i), neighbors.get(i));
        }

        PathObjectConnectionGroup view = new PathObjectConnectionGroup() {
            @Override
            public boolean containsObject(PathObject pathObject) {
                return map.containsKey(pathObject);
            }

            @Override
            public java.util.Collection<PathObject> getPathObjects() {
                return map.keySet();
            }

            @Override
            public List<PathObject> getConnectedObjects(PathObject pathObject) {
                List<PathObject> conn = map.get(pathObject);
                return conn == null ? java.util.Collections.emptyList() : conn;
            }
        };
        return new DefaultPathObjectConnectionGroup(view);
    }

    @SuppressWarnings("deprecation")
    private static void attachConnections(ImageData<BufferedImage> imageData,
                                           DefaultPathObjectConnectionGroup group) {
        synchronized (imageData) {
            Object o = imageData.getProperty(DefaultPathObjectConnectionGroup.KEY_OBJECT_CONNECTIONS);
            PathObjectConnections connections;
            if (o instanceof PathObjectConnections existing) {
                connections = existing;
            } else {
                connections = new PathObjectConnections();
                imageData.setProperty(DefaultPathObjectConnectionGroup.KEY_OBJECT_CONNECTIONS, connections);
            }
            // Drop a stale same-class-filter stash from a prior run.
            imageData.setProperty(
                    SpatialConnectionsScripts.KEY_OVERLAY_SOURCE_GROUP,
                    null);
            for (PathObjectConnectionGroup g : new ArrayList<>(connections.getConnectionGroups())) {
                connections.removeGroup(g);
            }
            connections.addGroup(group);
        }
    }

    private static void fireHierarchyChangedFx(ImageData<BufferedImage> imageData) {
        Platform.runLater(() -> {
            if (imageData.getHierarchy() != null) {
                imageData.getHierarchy().fireHierarchyChangedEvent(ClusteringWorkflow.class);
            }
        });
    }

    /** Optional user-supplied embedding measurement name from the config params. */
    private static String embeddingName(ClusteringConfig config) {
        Map<String, Object> p = config.getEmbeddingParams();
        Object name = (p == null) ? null : p.get("name");
        return name == null ? null : name.toString();
    }
}
