package qupath.ext.qpcat.controller;

import com.google.gson.Gson;
import org.apposed.appose.NDArray;
import org.apposed.appose.Service.ResponseType;
import org.apposed.appose.Service.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.ext.qpcat.service.ApposeClusteringService;
import qupath.ext.qpcat.service.MeasurementExtractor;
import qupath.ext.qpcat.service.OperationLogger;
import qupath.ext.qpcat.ui.ClusteringDialog;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

import javafx.application.Platform;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Post-hoc spatial statistics over cells that already carry a categorical
 * classification (an existing cluster or phenotype), WITHOUT re-running
 * clustering or embedding. Cells can be restricted to selected annotation ROIs
 * ("analysis windows") and cells inside excluded-class annotations (e.g.
 * Ignore / Necrosis) can be dropped -- neither modifies the object hierarchy.
 *
 * <p>Operates on the currently open image. It reads each detection's PathClass
 * as the label, extracts centroids, optionally builds a measurement matrix for
 * Geary's C / Moran's I, and runs the {@code spatial_stats_standalone} Appose
 * task, which delegates to the same {@code spatial_stats} helpers the clustering
 * flow uses. Results are shown in the standard QP-CAT results window (spatial
 * tabs only), read-only.</p>
 */
public class PostHocSpatialWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(PostHocSpatialWorkflow.class);

    private final QuPathGUI qupath;
    private volatile Task currentTask;

    public PostHocSpatialWorkflow(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /** User-selected options for a post-hoc spatial-stats run. */
    public static final class Options {
        public boolean useSelectedAnnotations;   // restrict to selected annotation ROIs
        public Set<String> excludeClasses = new LinkedHashSet<>();  // annotation classes to exclude
        public String graphType = "knn";
        public int graphK = 15;
        public double graphRadius = -1.0;
        public double graphDelaunayMaxEdge = -1.0;
        public int permutations = 0;              // 0 = adaptive
        public boolean ripley;
        public boolean gearyC;
        public boolean coocPairwise;
        public boolean coocOneVsRest;
        public boolean nhood;
        public boolean moran;

        public boolean anyStat() {
            return ripley || gearyC || coocPairwise || coocOneVsRest || nhood || moran;
        }

        public boolean needsFeatures() {
            return gearyC || moran;
        }
    }

    /**
     * Run the analysis. Must be called off the JavaFX application thread.
     * Shows the results window on the FX thread when complete.
     */
    public void run(Options opts, Consumer<String> progress) throws IOException {
        ImageData<BufferedImage> imageData = qupath.getImageData();
        if (imageData == null) {
            throw new IOException("No image is open.");
        }
        PathObjectHierarchy hierarchy = imageData.getHierarchy();

        List<PathObject> detections = gatherDetections(hierarchy, opts);
        if (detections.size() < 3) {
            throw new IOException("Need at least 3 cells inside the selected region(s); found "
                    + detections.size() + ".");
        }

        List<String> classNames = new ArrayList<>();
        int[] labels = readCategoricalLabels(detections, classNames);
        if (classNames.size() < 2) {
            throw new IOException("Spatial statistics need at least two cell classes; the "
                    + "selected cells all share one class. Classify or cluster cells first.");
        }

        double[][] coords = MeasurementExtractor.extractCentroids(detections);

        // Feature matrix for Geary's C / Moran's I (all numeric measurements).
        double[][] featureMatrix = null;
        String[] markerNames = null;
        if (opts.needsFeatures()) {
            List<String> measNames = MeasurementExtractor.getAllMeasurements(detections);
            if (measNames.isEmpty()) {
                logger.warn("Geary/Moran requested but no numeric measurements found; "
                        + "those statistics will be skipped.");
            } else {
                MeasurementExtractor.ExtractionResult ex =
                        new MeasurementExtractor().extract(detections, measNames);
                featureMatrix = ex.getData();
                markerNames = ex.getMeasurementNames();
            }
        }

        report(progress, "Running spatial statistics on " + detections.size()
                + " cells (" + classNames.size() + " classes)...");

        final double[][] fFeat = featureMatrix;
        final String[] fMarkers = markerNames;
        Map<String, Object> outputs;
        try {
            outputs = ApposeClusteringService.withExtensionClassLoader(() ->
                    runTask(coords, labels, classNames, fFeat, fMarkers, opts, progress));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Spatial statistics failed: " + e.getMessage(), e);
        }

        ClusteringResult result = buildResult(labels, classNames, outputs);
        OperationLogger.getInstance().logEvent("POST-HOC SPATIAL STATS",
                "Ran spatial stats on " + detections.size() + " cells, "
                + classNames.size() + " classes, graph=" + opts.graphType);

        Platform.runLater(() -> ClusteringDialog.showResultsDialog(
                result, "Embedding", "Post-hoc spatial", null));
        report(progress, "Spatial statistics complete.");
    }

    /** Cancel the running Appose task, if any. */
    public void cancel() {
        Task t = currentTask;
        if (t != null) {
            try { t.cancel(); } catch (Exception ignore) { /* best effort */ }
        }
    }

    // ---- helpers ----

    private List<PathObject> gatherDetections(PathObjectHierarchy hierarchy, Options opts) {
        LinkedHashSet<PathObject> keep = new LinkedHashSet<>();
        if (opts.useSelectedAnnotations) {
            List<PathObject> annos = hierarchy.getSelectionModel().getSelectedObjects().stream()
                    .filter(PathObject::isAnnotation).toList();
            if (annos.isEmpty()) {
                // Fall back to all detections when the user forgot to select an ROI.
                keep.addAll(hierarchy.getDetectionObjects());
            } else {
                for (PathObject a : annos) {
                    keep.addAll(hierarchy.getAllDetectionsForROI(a.getROI()));
                }
            }
        } else {
            keep.addAll(hierarchy.getDetectionObjects());
        }

        // Exclude cells inside annotations of the excluded classes.
        if (opts.excludeClasses != null && !opts.excludeClasses.isEmpty()) {
            for (PathObject a : hierarchy.getAnnotationObjects()) {
                PathClass pc = a.getPathClass();
                if (pc != null && opts.excludeClasses.contains(pc.toString())) {
                    keep.removeAll(hierarchy.getAllDetectionsForROI(a.getROI()));
                }
            }
        }
        return new ArrayList<>(keep);
    }

    /** Read a categorical label index per detection from its PathClass. */
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

    private Map<String, Object> runTask(double[][] coords, int[] labels, List<String> classNames,
                                        double[][] featureMatrix, String[] markerNames,
                                        Options opts, Consumer<String> progress)
            throws IOException {
        int nCells = coords.length;

        NDArray coordsNd = new NDArray(NDArray.DType.FLOAT64,
                new NDArray.Shape(NDArray.Shape.Order.C_ORDER, nCells, 2));
        var cbuf = coordsNd.buffer().asDoubleBuffer();
        for (double[] c : coords) cbuf.put(c);

        NDArray featNd = null;
        if (featureMatrix != null && featureMatrix.length == nCells && markerNames != null) {
            int m = markerNames.length;
            featNd = new NDArray(NDArray.DType.FLOAT64,
                    new NDArray.Shape(NDArray.Shape.Order.C_ORDER, nCells, m));
            var fbuf = featNd.buffer().asDoubleBuffer();
            for (double[] row : featureMatrix) fbuf.put(row);
        }

        List<Integer> labelList = new ArrayList<>(nCells);
        for (int v : labels) labelList.add(v);

        Map<String, Object> inputs = new java.util.HashMap<>();
        inputs.put("spatial_coords", coordsNd);
        inputs.put("cell_type_labels", labelList);
        inputs.put("class_names", classNames);
        inputs.put("graph_type", opts.graphType);
        inputs.put("graph_k", opts.graphK);
        inputs.put("graph_radius", opts.graphRadius);
        inputs.put("graph_delaunay_max_edge", opts.graphDelaunayMaxEdge);
        inputs.put("permutations", opts.permutations);
        inputs.put("enable_ripley", opts.ripley);
        inputs.put("enable_geary", opts.gearyC);
        inputs.put("enable_co_occurrence_pairwise", opts.coocPairwise);
        inputs.put("enable_co_occurrence_one_vs_rest", opts.coocOneVsRest);
        inputs.put("enable_nhood_enrichment", opts.nhood);
        inputs.put("enable_moran", opts.moran);
        if (featNd != null) {
            inputs.put("feature_matrix", featNd);
            inputs.put("marker_names", new ArrayList<>(List.of(markerNames)));
        }

        try {
            Task task = ApposeClusteringService.getInstance().runTaskWithListener(
                    "spatial_stats_standalone", inputs,
                    event -> {
                        if (event.responseType == ResponseType.UPDATE && event.message != null) {
                            report(progress, event.message);
                        }
                    },
                    t -> currentTask = t);
            return new java.util.HashMap<>(task.outputs);
        } finally {
            coordsNd.close();
            if (featNd != null) featNd.close();
            currentTask = null;
        }
    }

    /** Populate a ClusteringResult's spatial fields from the task outputs. */
    private ClusteringResult buildResult(int[] labels, List<String> classNames,
                                         Map<String, Object> outputs) {
        int nClasses = classNames.size();
        ClusteringResult result = new ClusteringResult(labels, nClasses, null, null,
                classNames.toArray(new String[0]));

        Gson gson = new Gson();
        if (outputs.containsKey("spatial_graph_type")) {
            result.setSpatialGraphType(String.valueOf(outputs.get("spatial_graph_type")));
        }
        if (outputs.containsKey("ripley")) {
            try {
                result.setRipley(ClusteringWorkflow.parseRipley(
                        (String) outputs.get("ripley"), gson));
            } catch (Exception e) { logger.warn("Parse Ripley failed: {}", e.getMessage()); }
        }
        if (outputs.containsKey("geary_c")) {
            try {
                result.setGeary(ClusteringWorkflow.parseGeary(
                        (String) outputs.get("geary_c"), gson));
            } catch (Exception e) { logger.warn("Parse Geary failed: {}", e.getMessage()); }
        }
        if (outputs.containsKey("co_occurrence_pairwise")) {
            try {
                result.setCoOccurrencePairwise(ClusteringWorkflow.parseCoOccurrence(
                        (String) outputs.get("co_occurrence_pairwise"), gson));
            } catch (Exception e) { logger.warn("Parse co-occurrence pairwise failed: {}", e.getMessage()); }
        }
        if (outputs.containsKey("co_occurrence_one_vs_rest")) {
            try {
                result.setCoOccurrenceOneVsRest(ClusteringWorkflow.parseCoOccurrence(
                        (String) outputs.get("co_occurrence_one_vs_rest"), gson));
            } catch (Exception e) { logger.warn("Parse co-occurrence one-vs-rest failed: {}", e.getMessage()); }
        }
        if (outputs.containsKey("nhood_enrichment")) {
            try {
                NDArray nd = (NDArray) outputs.get("nhood_enrichment");
                double[][] m = new double[nClasses][nClasses];
                var buf = nd.buffer().asDoubleBuffer();
                for (int i = 0; i < nClasses; i++) buf.get(m[i]);
                result.setNhoodEnrichment(m);
                if (outputs.containsKey("nhood_cluster_names")) {
                    List<String> namesList = gson.fromJson(
                            (String) outputs.get("nhood_cluster_names"),
                            new com.google.gson.reflect.TypeToken<List<String>>(){}.getType());
                    result.setNhoodClusterNames(namesList.toArray(new String[0]));
                }
            } catch (Exception e) { logger.warn("Parse nhood enrichment failed: {}", e.getMessage()); }
        }
        if (outputs.containsKey("spatial_autocorr")) {
            result.setSpatialAutocorrJson(String.valueOf(outputs.get("spatial_autocorr")));
        }
        return result;
    }

    private static void report(Consumer<String> progress, String msg) {
        if (progress != null) {
            try { progress.accept(msg); } catch (Exception ignore) { /* UI sink */ }
        }
        logger.info(msg);
    }
}
