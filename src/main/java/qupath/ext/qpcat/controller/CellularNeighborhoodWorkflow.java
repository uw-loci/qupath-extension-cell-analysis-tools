package qupath.ext.qpcat.controller;

import org.apposed.appose.NDArray;
import org.apposed.appose.Service.ResponseType;
import org.apposed.appose.Service.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.service.ApposeClusteringService;
import qupath.ext.qpcat.service.MeasurementExtractor;
import qupath.ext.qpcat.service.OperationLogger;
import qupath.ext.qpcat.service.ResultApplier;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;
import qupath.lib.projects.ProjectImageEntry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javafx.application.Platform;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Cellular-neighborhood (CN) analysis: groups cells by the COMPOSITION of their
 * local spatial window rather than by their own measurements. For each cell it
 * takes the existing categorical label (a cluster or phenotype), builds a window
 * of the k nearest neighbors, turns the window into a cell-type fraction vector,
 * and k-means-clusters those vectors into N neighborhoods. Each cell's
 * neighborhood id is written as the measurement {@code "QPCAT CN"}, leaving the
 * cell-type classification (the analysis input) intact.
 *
 * <p>This is the scalable "default-A" spatial niche analysis -- O(n*k) with a
 * KD/Ball-tree neighbor search plus a small k-means, single-process, no
 * permutation machinery. It mirrors the neighborhood method of Goltsev et al.
 * (Cell 2018) and Schurch et al. (Cell 2020); see the Python script header and
 * REFERENCES.md. It is distinct from BANKSY (expression-space kernel), which
 * QP-CAT offers as a clustering algorithm.</p>
 *
 * <p>The single-image entry point ({@link #run}) must be called off the JavaFX
 * application thread; it pushes the hierarchy-change event back onto FX.</p>
 */
public class CellularNeighborhoodWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(CellularNeighborhoodWorkflow.class);


    private final QuPathGUI qupath;

    private volatile Task currentTask;
    private volatile boolean cancelled;

    public CellularNeighborhoodWorkflow(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /** Request cancellation of an in-flight run; no labels are applied if it
     *  is cancelled before the apply step. Best-effort. */
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

    /** Immutable summary returned to the dialog. */
    public static final class CnResult {
        private final boolean applied;
        private final int nNeighborhoods;
        private final int nCells;
        private final String countsJson;
        private final String heatmapPath;
        private final String runId;
        private final String paramsHash;

        CnResult(boolean applied, int nNeighborhoods, int nCells, String countsJson,
                 String heatmapPath, String runId, String paramsHash) {
            this.applied = applied;
            this.nNeighborhoods = nNeighborhoods;
            this.nCells = nCells;
            this.countsJson = countsJson;
            this.heatmapPath = heatmapPath;
            this.runId = runId;
            this.paramsHash = paramsHash;
        }

        public boolean isApplied() { return applied; }
        public int getNNeighborhoods() { return nNeighborhoods; }
        public int getNCells() { return nCells; }
        public String getCountsJson() { return countsJson; }
        public String getHeatmapPath() { return heatmapPath; }
        public String getRunId() { return runId; }
        public String getParamsHash() { return paramsHash; }
    }

    /** Immutable summary returned for a joint multi-image (cohort) run. */
    public static final class CnProjectResult {
        private final boolean applied;
        private final int nNeighborhoods;
        private final int totalCells;
        private final int nImages;
        private final String resultsDir;
        private final String enrichmentHeatmapPath;
        private final String perSampleHeatmapPath;
        private final String groupHeatmapPath;
        private final String regionAdjacencyHeatmapPath;
        private final String perSampleJson;
        private final String groupJson;
        private final String regionAdjacencyJson;
        private final String divergenceWarning;
        private final String runId;
        private final String paramsHash;

        CnProjectResult(boolean applied, int nNeighborhoods, int totalCells, int nImages,
                        String resultsDir, String enrichmentHeatmapPath,
                        String perSampleHeatmapPath, String groupHeatmapPath,
                        String regionAdjacencyHeatmapPath,
                        String perSampleJson, String groupJson, String regionAdjacencyJson,
                        String divergenceWarning, String runId, String paramsHash) {
            this.applied = applied;
            this.nNeighborhoods = nNeighborhoods;
            this.totalCells = totalCells;
            this.nImages = nImages;
            this.resultsDir = resultsDir;
            this.enrichmentHeatmapPath = enrichmentHeatmapPath;
            this.perSampleHeatmapPath = perSampleHeatmapPath;
            this.groupHeatmapPath = groupHeatmapPath;
            this.regionAdjacencyHeatmapPath = regionAdjacencyHeatmapPath;
            this.perSampleJson = perSampleJson;
            this.groupJson = groupJson;
            this.regionAdjacencyJson = regionAdjacencyJson;
            this.divergenceWarning = divergenceWarning;
            this.runId = runId;
            this.paramsHash = paramsHash;
        }

        public boolean isApplied() { return applied; }
        public int getNNeighborhoods() { return nNeighborhoods; }
        public int getTotalCells() { return totalCells; }
        public int getNImages() { return nImages; }
        public String getResultsDir() { return resultsDir; }
        public String getEnrichmentHeatmapPath() { return enrichmentHeatmapPath; }
        public String getPerSampleHeatmapPath() { return perSampleHeatmapPath; }
        public String getGroupHeatmapPath() { return groupHeatmapPath; }
        public String getRegionAdjacencyHeatmapPath() { return regionAdjacencyHeatmapPath; }
        public String getPerSampleJson() { return perSampleJson; }
        public String getGroupJson() { return groupJson; }
        public String getRegionAdjacencyJson() { return regionAdjacencyJson; }
        /** Non-null when scope images do not share the same cell-type class set. */
        public String getDivergenceWarning() { return divergenceWarning; }
        public String getRunId() { return runId; }
        public String getParamsHash() { return paramsHash; }
    }

    /**
     * Run cellular-neighborhood analysis on the current image's detections.
     *
     * @param kNeighbors      window size (number of nearest neighbors, includes self)
     * @param nNeighborhoods  number of neighborhoods for k-means
     * @param seed            random seed for k-means reproducibility
     * @param generateHeatmap render the CN x cell-type enrichment heatmap PNG
     * @param progress        optional progress callback (may be null)
     * @return summary of the run; {@link CnResult#isApplied()} is false if cancelled
     * @throws IOException on extraction / Python failure
     */
    public CnResult run(int kNeighbors, int nNeighborhoods, int seed,
                        boolean generateHeatmap, boolean radiusMode, double radiusMicrons,
                        Consumer<String> progress) throws IOException {
        long startTime = System.currentTimeMillis();
        cancelled = false;

        ImageData<BufferedImage> imageData = qupath.getImageData();
        if (imageData == null) {
            throw new IOException("No image is open");
        }
        PathObjectHierarchy hierarchy = imageData.getHierarchy();
        List<PathObject> detections = new ArrayList<>(hierarchy.getDetectionObjects());
        if (detections.isEmpty()) {
            throw new IOException("No detection objects found. Run cell detection first.");
        }

        // Read the existing categorical column from detection classifications.
        report(progress, "Reading cell types...");
        List<String> classNames = new ArrayList<>();
        int[] typeLabels = readCategoricalLabels(detections, classNames);
        long distinctRealClasses = classNames.stream()
                .filter(n -> !"Unclassified".equals(n)).count();
        if (distinctRealClasses < 2) {
            throw new IOException(
                    "Cellular neighborhoods need an existing cell-type column with at "
                    + "least two classes.\nFound " + distinctRealClasses + " class(es). "
                    + "Run clustering or phenotyping first, then run this.");
        }

        int nCells = detections.size();
        double pxUm = pixelSizeMicrons(imageData);
        logger.info("Cellular neighborhoods: {} cells, {} cell-type classes",
                nCells, classNames.size());

        // Provenance.
        String runId = UUID.randomUUID().toString();
        String paramsHash = Integer.toHexString(
                java.util.Objects.hash(kNeighbors, nNeighborhoods, seed, classNames,
                        radiusMode, radiusMicrons));

        if (cancelled) {
            return new CnResult(false, 0, nCells, "{}", "", runId, paramsHash);
        }

        // Build inputs and run the Python task.
        report(progress, "Sending " + nCells + " cells to Python...");
        Path heatmapDir = null;
        if (generateHeatmap) {
            try {
                heatmapDir = Files.createTempDirectory("qpcat-cn-");
            } catch (IOException e) {
                logger.warn("Could not create heatmap directory: {}", e.getMessage());
            }
        }
        final Path heatmapDirFinal = heatmapDir;

        Map<String, Object> resultMap;
        try {
            resultMap = ApposeClusteringService.withExtensionClassLoader(() ->
                    executeTask(detections, typeLabels, classNames, kNeighbors,
                            nNeighborhoods, seed, generateHeatmap, heatmapDirFinal,
                            radiusMode, radiusMicrons, new double[]{pxUm}, progress));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cellular-neighborhood analysis failed: " + e.getMessage(), e);
        }

        int[] cnLabels = (int[]) resultMap.get("labels");
        int actualCn = (Integer) resultMap.get("n_neighborhoods");
        String countsJson = (String) resultMap.get("counts");
        String heatmapPath = (String) resultMap.getOrDefault("heatmap_path", "");

        // Honour a cancel that arrived during the Python run: write nothing.
        if (cancelled) {
            report(progress, "Cancelled -- no labels were applied.");
            return new CnResult(false, actualCn, nCells, countsJson, "", runId, paramsHash);
        }

        // Store the neighborhood id as a MEASUREMENT, leaving each cell's
        // classification (the cell-type input to the analysis) intact. Color by
        // neighborhood with QuPath's measurement maps ("QPCAT CN").
        report(progress, "Applying neighborhood measurement...");
        new ResultApplier().applyNeighborhoodMeasurement(detections, cnLabels,
                ResultApplier.NEIGHBORHOOD_MEASUREMENT);

        Platform.runLater(() -> {
            ImageData<BufferedImage> current = qupath.getImageData();
            if (current != null) {
                current.getHierarchy().fireHierarchyChangedEvent(this);
            }
        });

        recordWorkflowStep(imageData, kNeighbors, nNeighborhoods, actualCn, nCells,
                classNames.size(), runId, paramsHash);

        long elapsed = System.currentTimeMillis() - startTime;
        String completeMsg = "Cellular neighborhoods complete: " + actualCn
                + " neighborhoods over " + nCells + " cells.";
        report(progress, completeMsg);
        OperationLogger.getInstance().logOperation("CELLULAR NEIGHBORHOODS",
                Map.of("k_neighbors", String.valueOf(kNeighbors),
                       "n_neighborhoods", String.valueOf(nNeighborhoods),
                       "cell_type_classes", String.valueOf(classNames.size()),
                       "cells", String.valueOf(nCells),
                       "run_id", runId,
                       "params_hash", paramsHash),
                completeMsg, elapsed);

        return new CnResult(true, actualCn, nCells, countsJson, heatmapPath, runId, paramsHash);
    }

    /** A project image loaded for a joint CN run: entry, data, detections. */
    private static final class LoadedImage {
        final ProjectImageEntry<BufferedImage> entry;
        final ImageData<BufferedImage> imageData;
        final List<PathObject> detections;
        LoadedImage(ProjectImageEntry<BufferedImage> entry,
                    ImageData<BufferedImage> imageData, List<PathObject> detections) {
            this.entry = entry;
            this.imageData = imageData;
            this.detections = detections;
        }
    }

    /**
     * Run JOINT cellular-neighborhood analysis across several project images.
     *
     * <p>Spatial windows are built independently within each image (cells in
     * different slides are never neighbors), the composition vectors are pooled,
     * and ONE k-means defines the neighborhoods so a {@code QPCAT CN} measurement value
     * means the same cell-type mixture in every image -- the prerequisite for
     * comparing per-sample CN proportions across the cohort (Goltsev 2018 /
     * Schurch 2020 / imcRtools). Labels are written back and saved per image, a
     * Workflow record is added to each, and the per-sample (and, when
     * {@code groupMetadataKey} is set, per-group) proportion tables + heatmaps
     * are written to a results folder under the project.</p>
     *
     * <p>The cell-type column is unioned across the scope images; if some images
     * carry classes others do not, the run still proceeds (the union keeps the
     * vectors comparable) but {@link CnProjectResult#getDivergenceWarning()} is
     * set so the dialog can flag the inconsistent panel.</p>
     *
     * @param imageEntries     project images to include
     * @param kNeighbors       window size (nearest neighbors, includes self)
     * @param nNeighborhoods   number of neighborhoods for the joint k-means
     * @param seed             random seed for k-means reproducibility
     * @param generateHeatmap  render the enrichment + proportion heatmaps
     * @param groupMetadataKey image-metadata key to group images by (null = none)
     * @param progress         optional progress callback
     */
    public CnProjectResult runProject(List<ProjectImageEntry<BufferedImage>> imageEntries,
                                      int kNeighbors, int nNeighborhoods, int seed,
                                      boolean generateHeatmap, String groupMetadataKey,
                                      boolean radiusMode, double radiusMicrons,
                                      Consumer<String> progress) throws IOException {
        long startTime = System.currentTimeMillis();
        cancelled = false;

        if (imageEntries == null || imageEntries.isEmpty()) {
            throw new IOException("No project images selected.");
        }

        // 1. Load detections per image; skip images that fail or have none.
        report(progress, "Loading detections from " + imageEntries.size() + " images...");
        List<LoadedImage> loaded = new ArrayList<>();
        // Reuse the LIVE ImageData for the currently-open image so applied CN
        // labels and region annotations appear in the viewer immediately. A
        // detached readImageData() copy is written to disk only, which forces the
        // user to "Reload data" to see anything (the bug this guards against).
        ImageData<BufferedImage> openData = qupath.getImageData();
        ProjectImageEntry<BufferedImage> openEntry =
                (qupath.getProject() != null && openData != null)
                        ? qupath.getProject().getEntry(openData) : null;
        for (int idx = 0; idx < imageEntries.size(); idx++) {
            ProjectImageEntry<BufferedImage> entry = imageEntries.get(idx);
            report(progress, "Loading image " + (idx + 1) + "/" + imageEntries.size()
                    + ": " + entry.getImageName());
            boolean isOpenImage = openEntry != null && openEntry.equals(entry);
            ImageData<BufferedImage> imageData;
            try {
                imageData = isOpenImage ? openData : entry.readImageData();
            } catch (Exception e) {
                logger.warn("Failed to read image data for {}: {}",
                        entry.getImageName(), e.getMessage());
                continue;
            }
            List<PathObject> dets = new ArrayList<>(imageData.getHierarchy().getDetectionObjects());
            if (dets.isEmpty()) {
                logger.info("Skipping {} - no detections", entry.getImageName());
                continue;
            }
            loaded.add(new LoadedImage(entry, imageData, dets));
            logger.info("Loaded {} detections from {}", dets.size(), entry.getImageName());
        }
        if (loaded.isEmpty()) {
            throw new IOException("No detection objects found in any selected image. "
                    + "Run cell detection (and clustering/phenotyping) first.");
        }

        // 2. Union the cell-type class set across all images; flag divergence.
        report(progress, "Unifying cell-type classes across images...");
        LinkedHashSet<String> globalClasses = new LinkedHashSet<>();
        Map<String, LinkedHashSet<String>> perImageClasses = new LinkedHashMap<>();
        for (LoadedImage li : loaded) {
            LinkedHashSet<String> here = new LinkedHashSet<>();
            for (PathObject det : li.detections) {
                PathClass pc = det.getPathClass();
                here.add((pc == null || pc == PathClass.getNullClass())
                        ? "Unclassified" : pc.toString());
            }
            perImageClasses.put(li.entry.getImageName(), here);
            globalClasses.addAll(here);
        }
        List<String> classNames = new ArrayList<>(globalClasses);
        long realClasses = classNames.stream().filter(n -> !"Unclassified".equals(n)).count();
        if (realClasses < 2) {
            throw new IOException("Cellular neighborhoods need an existing cell-type column "
                    + "with at least two classes across the selected images.\nFound "
                    + realClasses + ". Run clustering or phenotyping on these images first.");
        }
        String divergenceWarning = buildDivergenceWarning(perImageClasses, globalClasses);

        // 3. Build combined arrays with per-image segment ids.
        int totalCells = loaded.stream().mapToInt(li -> li.detections.size()).sum();
        double[][] coords = new double[totalCells][2];
        int[] typeLabels = new int[totalCells];
        int[] imageIds = new int[totalCells];
        List<String> imageNames = new ArrayList<>(loaded.size());
        double[] pixelSizes = new double[loaded.size()];
        int pos = 0;
        for (int imgIdx = 0; imgIdx < loaded.size(); imgIdx++) {
            LoadedImage li = loaded.get(imgIdx);
            imageNames.add(li.entry.getImageName());
            pixelSizes[imgIdx] = pixelSizeMicrons(li.imageData);
            for (PathObject det : li.detections) {
                var roi = det.getROI();
                coords[pos][0] = roi.getCentroidX();
                coords[pos][1] = roi.getCentroidY();
                PathClass pc = det.getPathClass();
                String name = (pc == null || pc == PathClass.getNullClass())
                        ? "Unclassified" : pc.toString();
                typeLabels[pos] = classNames.indexOf(name);
                imageIds[pos] = imgIdx;
                pos++;
            }
        }

        // 4. Optional grouping by an image-metadata key (work "B").
        int[] groupLabels = null;
        List<String> groupNames = null;
        if (groupMetadataKey != null && !groupMetadataKey.isBlank()) {
            LinkedHashMap<String, Integer> valueToIdx = new LinkedHashMap<>();
            groupLabels = new int[loaded.size()];
            for (int imgIdx = 0; imgIdx < loaded.size(); imgIdx++) {
                String val = readMetadata(loaded.get(imgIdx).entry, groupMetadataKey);
                if (val == null || val.isBlank()) val = "(unset)";
                groupLabels[imgIdx] = valueToIdx.computeIfAbsent(val, v -> valueToIdx.size());
            }
            groupNames = new ArrayList<>(valueToIdx.keySet());
        }

        String runId = UUID.randomUUID().toString();
        String paramsHash = Integer.toHexString(java.util.Objects.hash(
                kNeighbors, nNeighborhoods, seed, classNames, imageNames, groupMetadataKey,
                radiusMode, radiusMicrons));

        if (cancelled) {
            return new CnProjectResult(false, 0, totalCells, loaded.size(), null,
                    "", "", "", "", "", "", "", divergenceWarning, runId, paramsHash);
        }

        // 5. Results folder under the project (heatmaps + tables land here).
        Path resultsDir = createResultsDir(runId);

        // 6. Run the joint Python task.
        report(progress, "Sending " + totalCells + " cells from " + loaded.size()
                + " images to Python...");
        final int[] groupLabelsF = groupLabels;
        final List<String> groupNamesF = groupNames;
        Map<String, Object> resultMap;
        try {
            resultMap = ApposeClusteringService.withExtensionClassLoader(() ->
                    runCnTask(coords, typeLabels, classNames, kNeighbors, nNeighborhoods,
                            seed, generateHeatmap, resultsDir, imageIds, imageNames,
                            groupLabelsF, groupNamesF, radiusMode, radiusMicrons, pixelSizes,
                            progress));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cellular-neighborhood analysis failed: " + e.getMessage(), e);
        }

        int[] cnLabels = (int[]) resultMap.get("labels");
        int actualCn = (Integer) resultMap.get("n_neighborhoods");
        String enrichmentHeatmap = (String) resultMap.getOrDefault("heatmap_path", "");
        String perSampleHeatmap = (String) resultMap.getOrDefault("per_sample_heatmap_path", "");
        String groupHeatmap = (String) resultMap.getOrDefault("group_heatmap_path", "");
        String perSampleJson = (String) resultMap.getOrDefault("per_sample_json", "");
        String groupJson = (String) resultMap.getOrDefault("group_json", "");
        String adjacencyJson = (String) resultMap.getOrDefault("region_adjacency_json", "");
        String adjacencyHeatmap = (String) resultMap.getOrDefault("region_adjacency_heatmap_path", "");

        if (cancelled) {
            report(progress, "Cancelled -- no labels were applied.");
            return new CnProjectResult(false, actualCn, totalCells, loaded.size(),
                    safePath(resultsDir), "", "", "", "", "", "", "", divergenceWarning,
                    runId, paramsHash);
        }

        // 7. Store the neighborhood id as a MEASUREMENT per image (leaving the
        //    cell-type classification intact), save, and record a Workflow step.
        report(progress, "Applying neighborhood measurement to " + loaded.size() + " images...");
        ResultApplier applier = new ResultApplier();
        int start = 0;
        for (LoadedImage li : loaded) {
            int end = start + li.detections.size();
            int[] segLabels = new int[end - start];
            System.arraycopy(cnLabels, start, segLabels, 0, end - start);
            applier.applyNeighborhoodMeasurement(li.detections, segLabels,
                    ResultApplier.NEIGHBORHOOD_MEASUREMENT);
            recordProjectWorkflowStep(li.imageData, kNeighbors, nNeighborhoods, actualCn,
                    li.detections.size(), totalCells, loaded.size(), classNames.size(),
                    runId, paramsHash);
            try {
                li.entry.saveImageData(li.imageData);
                logger.info("Saved CN labels for {} ({} cells)",
                        li.entry.getImageName(), li.detections.size());
            } catch (Exception e) {
                logger.error("Failed to save image data for {}: {}",
                        li.entry.getImageName(), e.getMessage());
            }
            report(progress, "Saved results for " + li.entry.getImageName());
            start = end;
        }

        // Refresh the currently open image if it was one of the processed ones.
        Platform.runLater(() -> {
            ImageData<BufferedImage> current = qupath.getImageData();
            if (current != null) {
                current.getHierarchy().fireHierarchyChangedEvent(this);
            }
        });

        // 8. Write the cohort tables + a run-info file next to the heatmaps.
        writeCohortTables(resultsDir, perSampleJson, groupJson, adjacencyJson, runId, paramsHash,
                kNeighbors, nNeighborhoods, actualCn, totalCells, loaded.size(),
                classNames, groupMetadataKey, divergenceWarning);

        long elapsed = System.currentTimeMillis() - startTime;
        String completeMsg = "Joint cellular neighborhoods complete: " + actualCn
                + " neighborhoods over " + totalCells + " cells across " + loaded.size()
                + " images.";
        report(progress, completeMsg);
        OperationLogger.getInstance().logOperation("CELLULAR NEIGHBORHOODS (project)",
                Map.of("k_neighbors", String.valueOf(kNeighbors),
                       "n_neighborhoods", String.valueOf(nNeighborhoods),
                       "cell_type_classes", String.valueOf(classNames.size()),
                       "images", String.valueOf(loaded.size()),
                       "cells", String.valueOf(totalCells),
                       "group_by", groupMetadataKey == null ? "(none)" : groupMetadataKey,
                       "run_id", runId,
                       "params_hash", paramsHash),
                completeMsg, elapsed);

        return new CnProjectResult(true, actualCn, totalCells, loaded.size(),
                safePath(resultsDir), enrichmentHeatmap, perSampleHeatmap, groupHeatmap,
                adjacencyHeatmap, perSampleJson, groupJson, adjacencyJson,
                divergenceWarning, runId, paramsHash);
    }

    /** Build a human-readable warning if images do not share the same class set. */
    private static String buildDivergenceWarning(
            Map<String, LinkedHashSet<String>> perImageClasses, LinkedHashSet<String> global) {
        List<String> diverging = new ArrayList<>();
        for (Map.Entry<String, LinkedHashSet<String>> e : perImageClasses.entrySet()) {
            if (!e.getValue().containsAll(global)) {
                LinkedHashSet<String> missing = new LinkedHashSet<>(global);
                missing.removeAll(e.getValue());
                diverging.add(e.getKey() + " (missing: " + String.join(", ", missing) + ")");
            }
        }
        if (diverging.isEmpty()) return null;
        return "Cell-type panels differ across images. The union of "
                + global.size() + " classes is used so composition vectors stay comparable, "
                + "but proportions for absent classes will read as zero in those images:\n  "
                + String.join("\n  ", diverging.stream().limit(8).toList())
                + (diverging.size() > 8 ? "\n  ... (" + (diverging.size() - 8) + " more)" : "");
    }

    private static String readMetadata(ProjectImageEntry<BufferedImage> entry, String key) {
        try {
            Map<String, String> meta = entry.getMetadata();
            return meta == null ? null : meta.get(key);
        } catch (Exception e) {
            return null;
        }
    }

    private Path createResultsDir(String runId) {
        try {
            if (qupath != null && qupath.getProject() != null
                    && qupath.getProject().getPath() != null) {
                Path base = qupath.getProject().getPath().getParent()
                        .resolve("qpcat-cellular-neighborhoods")
                        .resolve(runId.substring(0, Math.min(8, runId.length())));
                Files.createDirectories(base);
                return base;
            }
        } catch (Exception e) {
            logger.warn("Could not create project results dir: {}", e.getMessage());
        }
        try {
            return Files.createTempDirectory("qpcat-cn-");
        } catch (IOException e) {
            logger.warn("Could not create temp results dir: {}", e.getMessage());
            return null;
        }
    }

    private static String safePath(Path p) {
        return p == null ? null : p.toString();
    }

    /**
     * Read a categorical label index per detection from its PathClass.
     * Unclassified cells (null / null-class) become a trailing "Unclassified"
     * class so every cell contributes to a window. Populates {@code classNames}
     * with the discovered class names in first-seen order.
     */
    private static int[] readCategoricalLabels(List<PathObject> detections,
                                               List<String> classNames) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        String[] assigned = new String[detections.size()];
        for (int i = 0; i < detections.size(); i++) {
            PathClass pc = detections.get(i).getPathClass();
            String name = (pc == null || pc == PathClass.getNullClass())
                    ? "Unclassified" : pc.toString();
            assigned[i] = name;
            unique.add(name);
        }
        classNames.addAll(unique);
        List<String> nameList = new ArrayList<>(classNames);
        int[] labels = new int[detections.size()];
        for (int i = 0; i < detections.size(); i++) {
            labels[i] = nameList.indexOf(assigned[i]);
        }
        return labels;
    }

    private Map<String, Object> executeTask(List<PathObject> detections,
                                            int[] typeLabels, List<String> classNames,
                                            int kNeighbors, int nNeighborhoods, int seed,
                                            boolean generateHeatmap, Path heatmapDir,
                                            boolean radiusMode, double radiusMicrons,
                                            double[] pixelSizesUm,
                                            Consumer<String> progress) throws IOException {
        double[][] centroids = MeasurementExtractor.extractCentroids(detections);
        return runCnTask(centroids, typeLabels, classNames, kNeighbors, nNeighborhoods,
                seed, generateHeatmap, heatmapDir, null, null, null, null,
                radiusMode, radiusMicrons, pixelSizesUm, progress);
    }

    /** Averaged pixel size in microns, or 1.0 if the image is uncalibrated. */
    private static double pixelSizeMicrons(ImageData<BufferedImage> imageData) {
        try {
            double um = imageData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
            return (um > 0 && !Double.isNaN(um)) ? um : 1.0;
        } catch (Exception e) {
            return 1.0;
        }
    }

    /**
     * Run the {@code cellular_neighborhoods} Python task on prebuilt arrays.
     * The {@code imageIds}/{@code imageNames}/{@code groupLabels}/{@code groupNames}
     * inputs are optional: pass them for joint multi-image (cohort) runs so the
     * windows are built per-image, neighborhoods are clustered jointly, and the
     * per-sample / per-group proportion summaries are produced. Pass nulls for a
     * single-image run.
     *
     * @param coords      cell XY centroids, {@code [nCells][2]}
     * @param typeLabels  per-cell cell-type index into {@code classNames}
     * @param imageIds    per-cell source-image index (null = single image)
     * @param imageNames  display name per image index (null when single image)
     * @param groupLabels per-image group index (null = no grouping)
     * @param groupNames  display name per group index (null when no grouping)
     */
    private Map<String, Object> runCnTask(double[][] coords, int[] typeLabels,
                                          List<String> classNames, int kNeighbors,
                                          int nNeighborhoods, int seed, boolean generateHeatmap,
                                          Path outputDir, int[] imageIds, List<String> imageNames,
                                          int[] groupLabels, List<String> groupNames,
                                          boolean radiusMode, double radiusMicrons,
                                          double[] pixelSizesUm,
                                          Consumer<String> progress) throws IOException {
        int nCells = coords.length;

        NDArray.Shape spatialShape = new NDArray.Shape(NDArray.Shape.Order.C_ORDER, nCells, 2);
        NDArray spatialNd = new NDArray(NDArray.DType.FLOAT64, spatialShape);
        var sbuf = spatialNd.buffer().asDoubleBuffer();
        for (int i = 0; i < nCells; i++) {
            sbuf.put(coords[i]);
        }

        List<Integer> labelsList = new ArrayList<>(nCells);
        for (int v : typeLabels) labelsList.add(v);

        Map<String, Object> inputs = new java.util.HashMap<>();
        inputs.put("spatial_coords", spatialNd);
        inputs.put("cell_type_labels", labelsList);
        inputs.put("class_names", classNames);
        inputs.put("k_neighbors", kNeighbors);
        inputs.put("n_neighborhoods", nNeighborhoods);
        inputs.put("seed", seed);
        inputs.put("generate_heatmap", generateHeatmap && outputDir != null);
        if (outputDir != null) {
            inputs.put("output_dir", outputDir.toString());
        }
        if (imageIds != null) {
            List<Integer> idList = new ArrayList<>(imageIds.length);
            for (int v : imageIds) idList.add(v);
            inputs.put("image_ids", idList);
        }
        if (imageNames != null) {
            inputs.put("image_names", imageNames);
        }
        if (groupLabels != null) {
            List<Integer> gList = new ArrayList<>(groupLabels.length);
            for (int v : groupLabels) gList.add(v);
            inputs.put("group_labels", gList);
        }
        if (groupNames != null) {
            inputs.put("group_names", groupNames);
        }
        // Window definition: kNN (default) or physical radius in microns. The
        // per-image pixel sizes let Python convert microns -> pixels per block.
        inputs.put("window_mode", radiusMode ? "radius" : "knn");
        if (radiusMode) {
            inputs.put("radius_microns", radiusMicrons);
        }
        if (pixelSizesUm != null) {
            List<Double> px = new ArrayList<>(pixelSizesUm.length);
            for (double v : pixelSizesUm) px.add(v);
            inputs.put("pixel_sizes_um", px);
        }

        NDArray labelsNd = null;
        try {
            Task task = ApposeClusteringService.getInstance().runTaskWithListener(
                    "cellular_neighborhoods", inputs,
                    event -> {
                        if (event.responseType == ResponseType.UPDATE && event.message != null) {
                            report(progress, event.message);
                        }
                    },
                    t -> currentTask = t);

            labelsNd = (NDArray) task.outputs.get("neighborhood_labels");
            int[] labels = new int[nCells];
            labelsNd.buffer().asIntBuffer().get(labels);

            Map<String, Object> result = new java.util.HashMap<>();
            result.put("labels", labels);
            result.put("n_neighborhoods", ((Number) task.outputs.get("n_neighborhoods")).intValue());
            result.put("counts", String.valueOf(task.outputs.get("neighborhood_counts")));
            result.put("heatmap_path", String.valueOf(task.outputs.getOrDefault("heatmap_path", "")));
            result.put("per_sample_json", String.valueOf(task.outputs.getOrDefault("per_sample_proportions_json", "")));
            result.put("per_sample_heatmap_path", String.valueOf(task.outputs.getOrDefault("per_sample_heatmap_path", "")));
            result.put("group_json", String.valueOf(task.outputs.getOrDefault("group_proportions_json", "")));
            result.put("group_heatmap_path", String.valueOf(task.outputs.getOrDefault("group_heatmap_path", "")));
            result.put("region_adjacency_json", String.valueOf(task.outputs.getOrDefault("region_adjacency_json", "")));
            result.put("region_adjacency_heatmap_path", String.valueOf(task.outputs.getOrDefault("region_adjacency_heatmap_path", "")));
            return result;
        } finally {
            spatialNd.close();
            if (labelsNd != null) labelsNd.close();
            currentTask = null;
        }
    }

    private void recordWorkflowStep(ImageData<BufferedImage> imageData, int kNeighbors,
                                    int nNeighborhoods, int actualCn, int nCells,
                                    int nClasses, String runId, String paramsHash) {
        if (imageData == null) return;
        try {
            String script = String.join("\n",
                    "// QP-CAT cellular neighborhoods",
                    "// Window k=" + kNeighbors + " nearest neighbors; k-means into "
                            + nNeighborhoods + " neighborhoods (" + actualCn + " produced)",
                    "// Cell-type classes used: " + nClasses,
                    "// Result: " + actualCn + " neighborhoods over " + nCells + " cells",
                    "// Neighborhood id written as measurement 'QPCAT CN'",
                    "// run_id: " + runId + "  params_hash: " + paramsHash);
            String stepName = "QP-CAT: cellular neighborhoods (" + actualCn
                    + " neighborhoods, k=" + kNeighbors + ")";
            imageData.getHistoryWorkflow().addStep(
                    new DefaultScriptableWorkflowStep(stepName, script));
        } catch (Exception e) {
            logger.warn("Failed to record cellular-neighborhood workflow step: {}", e.getMessage());
        }
    }

    /**
     * Record the joint run on EVERY processed image's command-history Workflow,
     * scope-explicit, so each image carries an audit trail noting its CN labels
     * came from a cross-image run. Informational by design (not runnable): a
     * naive single-image re-run would recompute different neighborhoods.
     */
    private void recordProjectWorkflowStep(ImageData<BufferedImage> imageData, int kNeighbors,
                                           int nNeighborhoods, int actualCn, int imageCells,
                                           int totalCells, int nImages, int nClasses,
                                           String runId, String paramsHash) {
        if (imageData == null) return;
        try {
            String script = String.join("\n",
                    "// QP-CAT cellular neighborhoods (JOINT, " + nImages + " images)",
                    "// Window k=" + kNeighbors + " nearest neighbors WITHIN each image; one",
                    "//   k-means over the pooled composition vectors into " + nNeighborhoods
                            + " neighborhoods (" + actualCn + " produced).",
                    "// Cell-type classes (union across images): " + nClasses,
                    "// This image contributed " + imageCells + " of " + totalCells
                            + " cells to the joint run.",
                    "// Neighborhood id written as measurement 'QPCAT CN'.",
                    "// WARNING: neighborhoods were defined jointly across " + nImages
                            + " images; re-running cellular neighborhoods on this image",
                    "//   alone would produce DIFFERENT labels (a CN id would no longer mean",
                    "//   the same mixture across the cohort). To reproduce, re-run with the",
                    "//   same image scope. run_id: " + runId + "  params_hash: " + paramsHash);
            String stepName = "QP-CAT: cellular neighborhoods (joint, " + nImages
                    + " images, " + actualCn + " neighborhoods, k=" + kNeighbors + ")";
            imageData.getHistoryWorkflow().addStep(
                    new DefaultScriptableWorkflowStep(stepName, script));
        } catch (Exception e) {
            logger.warn("Failed to record cellular-neighborhood workflow step: {}", e.getMessage());
        }
    }

    /**
     * Write the cohort summary tables (per-sample, and per-group when grouping
     * is on) as CSV plus a run-info text file into {@code resultsDir}. The JSON
     * payloads come straight from the Python task. Best-effort; a write failure
     * is logged but never fails the run.
     */
    private void writeCohortTables(Path resultsDir, String perSampleJson, String groupJson,
                                   String adjacencyJson, String runId, String paramsHash,
                                   int kNeighbors, int nNeighborhoods, int actualCn,
                                   int totalCells, int nImages, List<String> classNames,
                                   String groupMetadataKey, String divergenceWarning) {
        if (resultsDir == null) return;
        try {
            String perSampleCsv = proportionsJsonToCsv(perSampleJson, "image", "image_names");
            if (perSampleCsv != null) {
                Files.writeString(resultsDir.resolve("cn_per_sample_proportions.csv"),
                        perSampleCsv, StandardCharsets.UTF_8);
            }
            String groupCsv = proportionsJsonToCsv(groupJson, "group", "group_names");
            if (groupCsv != null) {
                Files.writeString(resultsDir.resolve("cn_per_group_proportions.csv"),
                        groupCsv, StandardCharsets.UTF_8);
            }
            String adjCsv = adjacencyJsonToCsv(adjacencyJson);
            if (adjCsv != null) {
                Files.writeString(resultsDir.resolve("cn_neighborhood_adjacency.csv"),
                        adjCsv, StandardCharsets.UTF_8);
            }
            StringBuilder info = new StringBuilder();
            info.append("QP-CAT joint cellular-neighborhood run\n");
            info.append("run_id: ").append(runId).append('\n');
            info.append("params_hash: ").append(paramsHash).append('\n');
            info.append("k_neighbors (window): ").append(kNeighbors).append('\n');
            info.append("n_neighborhoods requested: ").append(nNeighborhoods)
                    .append(" (produced ").append(actualCn).append(")\n");
            info.append("images: ").append(nImages).append('\n');
            info.append("total cells: ").append(totalCells).append('\n');
            info.append("cell-type classes (union): ").append(classNames.size())
                    .append(" -> ").append(String.join(", ", classNames)).append('\n');
            info.append("grouped by metadata key: ")
                    .append(groupMetadataKey == null || groupMetadataKey.isBlank()
                            ? "(none)" : groupMetadataKey).append('\n');
            if (divergenceWarning != null) {
                info.append("\nNOTE: ").append(divergenceWarning).append('\n');
            }
            info.append("\nNeighborhoods were defined JOINTLY across all images (windows are\n");
            info.append("within-image; one k-means over the pooled composition vectors), so a\n");
            info.append("'QPCAT CN' measurement value means the same cell-type mixture in every image\n");
            info.append("and the per-sample / per-group proportions are directly comparable.\n");
            Files.writeString(resultsDir.resolve("cn_RUN_INFO.txt"),
                    info.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.warn("Failed to write cohort tables: {}", e.getMessage());
        }
    }

    /**
     * Convert a {row x CN} proportions JSON payload into CSV. {@code rowHeader}
     * names the first column; {@code labelsKey} is the JSON array of row labels
     * ({@code image_names} or {@code group_names}). Returns null on empty input.
     */
    private static String proportionsJsonToCsv(String json, String rowHeader, String labelsKey) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            JsonArray labels = obj.getAsJsonArray(labelsKey);
            JsonArray props = obj.getAsJsonArray("proportions");
            if (labels == null || props == null || props.size() == 0) return null;
            int nCn = props.get(0).getAsJsonArray().size();
            StringBuilder sb = new StringBuilder();
            sb.append(rowHeader);
            for (int cn = 0; cn < nCn; cn++) sb.append(",CN_").append(cn);
            sb.append('\n');
            for (int r = 0; r < props.size(); r++) {
                String label = r < labels.size() ? labels.get(r).getAsString() : (rowHeader + "_" + r);
                sb.append('"').append(label.replace("\"", "\"\"")).append('"');
                JsonArray row = props.get(r).getAsJsonArray();
                for (int cn = 0; cn < row.size(); cn++) {
                    sb.append(',').append(String.format(java.util.Locale.US, "%.6f",
                            row.get(cn).getAsDouble()));
                }
                sb.append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            logger.warn("Could not convert proportions JSON to CSV: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Convert a region-adjacency JSON payload ({@code {n_cn, matrix[CN][CN]}})
     * into a CN x CN CSV. Returns null on empty input.
     */
    private static String adjacencyJsonToCsv(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            JsonArray matrix = obj.getAsJsonArray("matrix");
            if (matrix == null || matrix.size() == 0) return null;
            int n = matrix.size();
            StringBuilder sb = new StringBuilder();
            sb.append("CN");
            for (int j = 0; j < n; j++) sb.append(",CN_").append(j);
            sb.append('\n');
            for (int i = 0; i < n; i++) {
                sb.append("CN_").append(i);
                JsonArray row = matrix.get(i).getAsJsonArray();
                for (int j = 0; j < row.size(); j++) {
                    var cell = row.get(j);
                    sb.append(',').append(cell.isJsonNull() ? ""
                            : String.format(java.util.Locale.US, "%.6f", cell.getAsDouble()));
                }
                sb.append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            logger.warn("Could not convert adjacency JSON to CSV: {}", e.getMessage());
            return null;
        }
    }

    private static int maxLabel(int[] labels) {
        int m = 0;
        for (int v : labels) if (v > m) m = v;
        return m;
    }

    private void report(Consumer<String> progress, String msg) {
        if (progress != null) progress.accept(msg);
        logger.info(msg);
    }
}
