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

import javafx.application.Platform;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * and k-means-clusters those vectors into N neighborhoods. Each cell is then
 * classified as {@code "QPCAT CN: <id>"}.
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

    /** Classification prefix for cellular-neighborhood labels. */
    public static final String CN_PREFIX = "QPCAT CN: ";

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
                        boolean generateHeatmap, Consumer<String> progress) throws IOException {
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
        logger.info("Cellular neighborhoods: {} cells, {} cell-type classes",
                nCells, classNames.size());

        // Provenance.
        String runId = UUID.randomUUID().toString();
        String paramsHash = Integer.toHexString(
                java.util.Objects.hash(kNeighbors, nNeighborhoods, seed, classNames));

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
                            nNeighborhoods, seed, generateHeatmap, heatmapDirFinal, progress));
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

        // Apply "QPCAT CN: <id>" classifications.
        report(progress, "Applying neighborhood labels...");
        String[] cnNames = new String[Math.max(actualCn, maxLabel(cnLabels) + 1)];
        for (int i = 0; i < cnNames.length; i++) {
            cnNames[i] = CN_PREFIX + i;
        }
        new ResultApplier().applyPhenotypeLabels(detections, cnLabels, cnNames);

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
                                            Consumer<String> progress) throws IOException {
        int nCells = detections.size();
        double[][] centroids = MeasurementExtractor.extractCentroids(detections);

        NDArray.Shape spatialShape = new NDArray.Shape(NDArray.Shape.Order.C_ORDER, nCells, 2);
        NDArray spatialNd = new NDArray(NDArray.DType.FLOAT64, spatialShape);
        var sbuf = spatialNd.buffer().asDoubleBuffer();
        for (int i = 0; i < nCells; i++) {
            sbuf.put(centroids[i]);
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
        inputs.put("generate_heatmap", generateHeatmap && heatmapDir != null);
        if (heatmapDir != null) {
            inputs.put("output_dir", heatmapDir.toString());
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
                    "// Labels written as detection class 'QPCAT CN: <id>'",
                    "// run_id: " + runId + "  params_hash: " + paramsHash);
            String stepName = "QP-CAT: cellular neighborhoods (" + actualCn
                    + " neighborhoods, k=" + kNeighbors + ")";
            imageData.getHistoryWorkflow().addStep(
                    new DefaultScriptableWorkflowStep(stepName, script));
        } catch (Exception e) {
            logger.warn("Failed to record cellular-neighborhood workflow step: {}", e.getMessage());
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
