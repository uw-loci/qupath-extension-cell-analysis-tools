package qupath.ext.qpcat.controller;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apposed.appose.NDArray;
import org.apposed.appose.Service.ResponseType;
import org.apposed.appose.Service.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.ext.qpcat.model.GearyCResult;
import qupath.ext.qpcat.model.SavedClusteringResult;
import qupath.ext.qpcat.service.ApposeClusteringService;
import qupath.ext.qpcat.service.MeasurementExtractor;
import qupath.ext.qpcat.service.OperationLogger;
import qupath.ext.qpcat.service.SavedResultApplier;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Post-hoc spatial statistics over cells that already carry a categorical
 * classification (an existing cluster or phenotype), WITHOUT re-running
 * clustering or embedding.
 *
 * <p>Each analysis <b>window</b> -- a whole image, an annotation class merged per
 * image, or a single annotation -- is analyzed independently with its OWN spatial
 * graph. This is what makes multi-image correct: cells from different images (or
 * different annotation regions) are never joined into one graph. Windows can be
 * built across the current image, all project images, or a selected subset, so a
 * project's regions can be compared per-image / per-annotation.</p>
 *
 * <p>Annotations are analysis windows only -- detections are never reparented,
 * and nothing is written to the object hierarchy (read-only). Reuses the same
 * {@code spatial_stats} Python helpers as the clustering flow.</p>
 */
public class PostHocSpatialWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(PostHocSpatialWorkflow.class);

    private final QuPathGUI qupath;
    private volatile Task currentTask;
    private volatile boolean cancelled;
    // imageId -> (quantized centroid key -> cluster label), built from a saved
    // result when it is used as the label source (see Options.savedLabelSource).
    private Map<String, Map<Long, Integer>> savedLabelMap;
    // Absolute path of the persisted results folder from the last run, or null.
    private String lastSavedPath;

    public PostHocSpatialWorkflow(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /** User-selected options for a post-hoc spatial-stats run. */
    public static final class Options {
        // Scope: null = current image only; otherwise the images to process.
        public List<ProjectImageEntry<BufferedImage>> entries;

        // Label source: null = each cell's current PathClass; else the cluster
        // labels from this saved result (matched by image id + centroid, in memory,
        // WITHOUT writing PathClasses so the hierarchy is untouched).
        public SavedClusteringResult savedLabelSource;

        // Region / window definition.
        public boolean useSelectedAnnotations;   // current image only: use the selected annotations
        public String windowClass;               // annotation class defining windows (null/blank = whole image)
        public boolean perAnnotation;            // each annotation separately (else merge per image)
        public Set<String> excludeClasses = new LinkedHashSet<>();  // annotation classes to exclude

        // Graph.
        public String graphType = "knn";
        public int graphK = 15;
        public double graphRadius = -1.0;
        public double graphDelaunayMaxEdge = -1.0;
        public int permutations = 0;             // 0 = adaptive

        // Statistics.
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

    /** One analysis window's cells (an image, a merged class, or one annotation). */
    private static final class Window {
        final String imageName;
        final String imageId;       // source image entry id (for saved-label matching), or null
        final double pixelSizeUm;   // um per pixel for this image; NaN if uncalibrated
        final String regionLabel;   // "whole image" | annotation name | "class: X" | "selected annotations"
        final String className;     // annotation class, or null
        final List<PathObject> detections;

        Window(String imageName, String imageId, double pixelSizeUm, String regionLabel,
               String className, List<PathObject> detections) {
            this.imageName = imageName;
            this.imageId = imageId;
            this.pixelSizeUm = pixelSizeUm;
            this.regionLabel = regionLabel;
            this.className = className;
            this.detections = detections;
        }
    }

    /** Result for one window, for the summary table + drill-in. */
    public static final class WindowResult {
        public String imageName;
        public String regionLabel;
        public String className;
        public int nCells;
        public int nClasses;
        public String unit = "px";          // distance unit for this window ("px" or "um")
        public String statsRun = "";
        public String skipReason;          // non-null when the window was skipped
        public ClusteringResult result;    // full result for drill-in (null when skipped)

        public boolean isSkipped() { return skipReason != null; }
    }

    /**
     * Build the windows for the given options and run spatial statistics on each,
     * independently. Must be called off the JavaFX application thread. Returns one
     * {@link WindowResult} per window (including skipped windows, with a reason).
     */
    public List<WindowResult> runWindows(Options opts, Consumer<String> progress) throws IOException {
        if (opts.savedLabelSource != null) {
            savedLabelMap = buildSavedLabelMap(opts.savedLabelSource);
            if (savedLabelMap == null) {
                throw new IOException("The chosen saved result lacks the per-cell references "
                        + "(image id + centroid) needed to match labels; use a newer result "
                        + "or the current classifications.");
            }
        }
        List<Target> targets = resolveTargets(opts);
        if (targets.isEmpty()) {
            throw new IOException("No images to analyze.");
        }
        try {
            List<Window> windows = new ArrayList<>();
            for (Target t : targets) {
                windows.addAll(buildWindows(t, opts));
            }
            if (windows.isEmpty()) {
                throw new IOException("No analysis windows matched the chosen region settings "
                        + "(no annotations of that class, or no cells inside them).");
            }

            List<WindowResult> results = new ArrayList<>();
            int idx = 0;
            for (Window w : windows) {
                if (cancelled) break;
                idx++;
                report(progress, String.format("Window %d/%d: %s / %s (%d cells)...",
                        idx, windows.size(), w.imageName, w.regionLabel, w.detections.size()));
                results.add(runWindow(w, opts, progress));
            }

            // Persist the run linked to its source + per-window ROI identity (Alex's
            // "save the results linked to the selected ROI and the clustering result").
            lastSavedPath = persistToProject(opts, results);

            OperationLogger.getInstance().logEvent("POST-HOC SPATIAL STATS",
                    "Ran spatial stats on " + windows.size() + " window(s) across "
                    + targets.size() + " image(s); graph=" + opts.graphType
                    + (lastSavedPath != null ? "; saved to " + lastSavedPath : ""));
            return results;
        } finally {
            // Close every ImageData we read ourselves; leave the live open image alone.
            closeTargets(targets);
        }
    }

    /** Close the native reader behind each Target we opened via readImageData(). */
    private static void closeTargets(List<Target> targets) {
        for (Target t : targets) {
            if (t != null && t.owned && t.imageData != null) {
                try {
                    t.imageData.getServer().close();
                } catch (Exception e) {
                    logger.warn("Failed to close image reader for {}: {}", t.imageName, e.getMessage());
                }
            }
        }
    }

    /** Folder the last run's CSV + JSON were written to, or null. */
    public String getLastSavedPath() {
        return lastSavedPath;
    }

    /**
     * Write the run's combined CSV + a metadata JSON (source result, graph params,
     * per-window ROI identity + stats) under {@code <project>/qpcat/spatial_stats/}.
     * Returns the folder path, or null if there is no project to write into.
     */
    @SuppressWarnings("unchecked")
    private String persistToProject(Options opts, List<WindowResult> results) {
        Project<BufferedImage> project = (Project<BufferedImage>) qupath.getProject();
        if (project == null || project.getPath() == null) return null;
        try {
            Path dir = project.getPath().getParent().resolve("qpcat/spatial_stats");
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String base = "spatial_" + stamp;

            Files.writeString(dir.resolve(base + ".csv"), buildCsv(results));

            Map<String, Object> meta = new java.util.LinkedHashMap<>();
            meta.put("timestamp", LocalDateTime.now().toString());
            meta.put("labelSource", opts.savedLabelSource != null
                    ? opts.savedLabelSource.getName() : "current classifications");
            meta.put("graphType", opts.graphType);
            meta.put("graphK", opts.graphK);
            meta.put("graphRadius", opts.graphRadius);
            meta.put("graphDelaunayMaxEdge", opts.graphDelaunayMaxEdge);
            meta.put("permutations", opts.permutations);
            List<Map<String, Object>> windows = new ArrayList<>();
            for (WindowResult wr : results) {
                Map<String, Object> w = new java.util.LinkedHashMap<>();
                w.put("image", wr.imageName);
                w.put("region", wr.regionLabel);
                w.put("class", wr.className);
                w.put("nCells", wr.nCells);
                w.put("nClasses", wr.nClasses);
                w.put("unit", wr.unit);
                w.put("statsRun", wr.statsRun);
                w.put("skipReason", wr.skipReason);
                windows.add(w);
            }
            meta.put("windows", windows);
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(dir.resolve(base + ".json"), gson.toJson(meta));
            return dir.toString();
        } catch (Exception e) {
            logger.warn("Could not persist post-hoc spatial results: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Long-format CSV of the per-window statistics for cross-window comparison
     * (one row per window x statistic x key). Curve statistics (Ripley,
     * co-occurrence) are noted as available for drill-in but not tabulated.
     */
    public static String buildCsv(List<WindowResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("image,region,class,n_cells,n_classes,unit,statistic,key,value,p_value\n");
        for (WindowResult wr : results) {
            String base = csv(wr.imageName) + "," + csv(wr.regionLabel) + ","
                    + csv(wr.className == null ? "" : wr.className) + ","
                    + wr.nCells + "," + wr.nClasses + "," + csv(wr.unit) + ",";
            if (wr.isSkipped()) {
                sb.append(base).append("skipped,").append(csv(wr.skipReason)).append(",,\n");
                continue;
            }
            ClusteringResult r = wr.result;
            boolean any = false;
            if (r.hasGeary() && r.getGeary().getMarkerStats() != null) {
                for (var e : r.getGeary().getMarkerStats().entrySet()) {
                    GearyCResult.Entry en = e.getValue();
                    sb.append(base).append("geary_c,").append(csv(e.getKey())).append(",")
                            .append(fmt(en.getC())).append(",").append(fmt(en.getPValue())).append("\n");
                    any = true;
                }
            }
            if (r.hasSpatialAutocorr()) any |= appendMoran(sb, base, r.getSpatialAutocorrJson());
            if (r.hasNhoodEnrichment()) {
                any |= appendNhood(sb, base, r.getNhoodEnrichment(), r.getNhoodClusterNames());
            }
            for (String s : new String[]{
                    r.hasRipley() ? "ripley" : null,
                    r.hasCoOccurrencePairwise() ? "cooc_pairwise" : null,
                    r.hasCoOccurrenceOneVsRest() ? "cooc_one_vs_rest" : null}) {
                if (s != null) { sb.append(base).append(s).append(",(see full result),,\n"); any = true; }
            }
            if (!any) sb.append(base).append("none,,,\n");
        }
        return sb.toString();
    }

    private static boolean appendMoran(StringBuilder sb, String base, String json) {
        boolean any = false;
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            for (var e : obj.entrySet()) {
                JsonObject m = e.getValue().getAsJsonObject();
                double i = m.has("I") ? m.get("I").getAsDouble() : Double.NaN;
                double p = m.has("pval") ? m.get("pval").getAsDouble() : Double.NaN;
                sb.append(base).append("moran_i,").append(csv(e.getKey())).append(",")
                        .append(fmt(i)).append(",").append(fmt(p)).append("\n");
                any = true;
            }
        } catch (Exception ex) {
            LoggerFactory.getLogger(PostHocSpatialWorkflow.class)
                    .debug("Moran JSON parse for CSV failed: {}", ex.getMessage());
        }
        return any;
    }

    private static boolean appendNhood(StringBuilder sb, String base, double[][] m, String[] names) {
        if (m == null) return false;
        boolean any = false;
        for (int i = 0; i < m.length; i++) {
            for (int j = i + 1; j < m[i].length; j++) {
                String a = names != null && i < names.length ? names[i] : String.valueOf(i);
                String b = names != null && j < names.length ? names[j] : String.valueOf(j);
                sb.append(base).append("nhood_z,").append(csv(a + " | " + b)).append(",")
                        .append(fmt(m[i][j])).append(",\n");
                any = true;
            }
        }
        return any;
    }

    private static String fmt(double v) {
        return Double.isFinite(v) ? String.format("%.6g", v) : "";
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    /** Cancel the running Appose task and stop the window loop. */
    public void cancel() {
        cancelled = true;
        Task t = currentTask;
        if (t != null) {
            try { t.cancel(); } catch (Exception ignore) { /* best effort */ }
        }
    }

    // ---- window construction ----

    private static final class Target {
        final String imageName;
        final String entryId;    // project entry id (for saved-label matching), or null
        final ImageData<BufferedImage> imageData;
        // true when we opened this ImageData via readImageData() (so we must close it);
        // false for the live open image, which the GUI owns.
        final boolean owned;
        Target(String imageName, String entryId, ImageData<BufferedImage> imageData, boolean owned) {
            this.imageName = imageName;
            this.entryId = entryId;
            this.imageData = imageData;
            this.owned = owned;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Target> resolveTargets(Options opts) throws IOException {
        List<Target> targets = new ArrayList<>();
        Project<BufferedImage> project = (Project<BufferedImage>) qupath.getProject();
        ImageData<BufferedImage> openData = qupath.getImageData();
        String openId = null;
        if (project != null && openData != null) {
            ProjectImageEntry<BufferedImage> e = project.getEntry(openData);
            if (e != null) openId = e.getID();
        }
        if (opts.entries == null) {
            if (openData == null) throw new IOException("No image is open.");
            targets.add(new Target(imageName(openData), openId, openData, false));
            return targets;
        }
        for (ProjectImageEntry<BufferedImage> entry : opts.entries) {
            if (cancelled) break;
            try {
                if (openId != null && openId.equals(entry.getID())) {
                    targets.add(new Target(entry.getImageName(), entry.getID(), openData, false));
                } else {
                    targets.add(new Target(entry.getImageName(), entry.getID(), entry.readImageData(), true));
                }
            } catch (Exception e) {
                logger.warn("Could not read image {}: {}", entry.getImageName(), e.getMessage());
            }
        }
        return targets;
    }

    private List<Window> buildWindows(Target t, Options opts) {
        PathObjectHierarchy hierarchy = t.imageData.getHierarchy();
        double pxUm = pixelSizeMicrons(t.imageData);

        // Cells to drop: those inside annotations of an excluded class (per image).
        Set<PathObject> excluded = new LinkedHashSet<>();
        if (opts.excludeClasses != null && !opts.excludeClasses.isEmpty()) {
            for (PathObject a : hierarchy.getAnnotationObjects()) {
                PathClass pc = a.getPathClass();
                if (pc != null && opts.excludeClasses.contains(pc.toString())) {
                    excluded.addAll(hierarchy.getAllDetectionsForROI(a.getROI()));
                }
            }
        }

        // Source annotations that define the windows (null = whole image).
        List<PathObject> sourceAnnos = null;
        if (opts.useSelectedAnnotations) {
            sourceAnnos = hierarchy.getSelectionModel().getSelectedObjects().stream()
                    .filter(PathObject::isAnnotation).toList();
            if (sourceAnnos.isEmpty()) sourceAnnos = null;  // fall back to whole image
        } else if (opts.windowClass != null && !opts.windowClass.isBlank()) {
            sourceAnnos = hierarchy.getAnnotationObjects().stream()
                    .filter(a -> a.getPathClass() != null
                            && opts.windowClass.equals(a.getPathClass().toString()))
                    .toList();
        }

        List<Window> windows = new ArrayList<>();
        if (sourceAnnos == null) {
            List<PathObject> cells = new ArrayList<>(hierarchy.getDetectionObjects());
            cells.removeAll(excluded);
            windows.add(new Window(t.imageName, t.entryId, pxUm, "whole image", null, cells));
        } else if (opts.perAnnotation) {
            int n = 0;
            for (PathObject a : sourceAnnos) {
                n++;
                List<PathObject> cells = new ArrayList<>(hierarchy.getAllDetectionsForROI(a.getROI()));
                cells.removeAll(excluded);
                String label = a.getName() != null && !a.getName().isBlank()
                        ? a.getName()
                        : (a.getPathClass() != null ? a.getPathClass().toString() : "region") + " #" + n;
                String cls = a.getPathClass() != null ? a.getPathClass().toString() : null;
                windows.add(new Window(t.imageName, t.entryId, pxUm, label, cls, cells));
            }
        } else {
            LinkedHashSet<PathObject> cells = new LinkedHashSet<>();
            for (PathObject a : sourceAnnos) {
                cells.addAll(hierarchy.getAllDetectionsForROI(a.getROI()));
            }
            cells.removeAll(excluded);
            String label = opts.useSelectedAnnotations ? "selected annotations"
                    : "class: " + opts.windowClass;
            windows.add(new Window(t.imageName, t.entryId, pxUm, label, opts.windowClass,
                    new ArrayList<>(cells)));
        }
        return windows;
    }

    // ---- per-window execution ----

    private WindowResult runWindow(Window w, Options opts, Consumer<String> progress) {
        WindowResult wr = new WindowResult();
        wr.imageName = w.imageName;
        wr.regionLabel = w.regionLabel;
        wr.className = w.className;

        // Assign each cell a label key from the chosen source. Unlabeled cells are
        // dropped: from the PathClass source, a null-class "Unclassified" bucket is
        // a heterogeneous non-phenotype that would pollute enrichment / co-occurrence;
        // from a saved-result source, a cell with no matching saved cell has no label.
        boolean fromSaved = opts.savedLabelSource != null && savedLabelMap != null;
        Map<Long, Integer> imgMap = fromSaved ? savedLabelMap.get(w.imageId) : null;
        List<PathObject> cells = new ArrayList<>();
        List<String> cellKeys = new ArrayList<>();
        for (PathObject d : w.detections) {
            if (fromSaved) {
                if (imgMap == null) break;   // saved result has no cells for this image
                var roi = d.getROI();
                if (roi == null) continue;
                Integer id = imgMap.get(SavedResultApplier.centroidKey(
                        roi.getCentroidX(), roi.getCentroidY()));
                if (id == null || id < 0) continue;
                cells.add(d);
                cellKeys.add("Cluster " + id);
            } else {
                PathClass pc = d.getPathClass();
                if (pc == null || pc == PathClass.getNullClass()) continue;
                cells.add(d);
                cellKeys.add(pc.toString());
            }
        }
        wr.nCells = cells.size();

        if (cells.size() < 3) {
            wr.skipReason = fromSaved ? "fewer than 3 cells matched the saved result"
                    : "fewer than 3 classified cells";
            return wr;
        }
        List<String> classNames = new ArrayList<>(new LinkedHashSet<>(cellKeys));
        int[] labels = new int[cells.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = classNames.indexOf(cellKeys.get(i));
        wr.nClasses = classNames.size();
        if (classNames.size() < 2) {
            wr.skipReason = "only one cell class";
            return wr;
        }

        // Report distances in microns when the image is calibrated: scale the
        // pixel centroids by um/px so the graph radius, Ripley radii, co-occurrence
        // intervals and distance measurements all come out in microns -- and become
        // comparable across images. Uncalibrated images stay in pixels.
        double[][] coords = MeasurementExtractor.extractCentroids(cells);
        boolean microns = Double.isFinite(w.pixelSizeUm) && w.pixelSizeUm > 0;
        if (microns) {
            for (double[] c : coords) {
                c[0] *= w.pixelSizeUm;
                c[1] *= w.pixelSizeUm;
            }
        }
        wr.unit = microns ? "um" : "px";

        double[][] featureMatrix = null;
        String[] markerNames = null;
        if (opts.needsFeatures()) {
            // Geary's C / Moran's I autocorrelate marker expression -- select only
            // plausible marker measurements and drop coordinate / geometry / derived
            // columns (autocorrelating X/Y or a prior spatial output is meaningless).
            List<String> measNames = selectAutocorrMeasurements(
                    MeasurementExtractor.getAllMeasurements(cells));
            if (!measNames.isEmpty()) {
                MeasurementExtractor.ExtractionResult ex =
                        new MeasurementExtractor().extract(cells, measNames);
                featureMatrix = ex.getData();
                markerNames = ex.getMeasurementNames();
            } else {
                logger.warn("Geary/Moran requested but no suitable marker measurements "
                        + "remained after filtering coordinate/derived columns.");
            }
        }

        final double[][] fFeat = featureMatrix;
        final String[] fMarkers = markerNames;
        try {
            final String coordUnit = wr.unit;
            Map<String, Object> outputs = ApposeClusteringService.withExtensionClassLoader(() ->
                    runTask(coords, labels, classNames, fFeat, fMarkers, opts, coordUnit, progress));
            wr.result = buildResult(labels, classNames, outputs);
            wr.result.setSpatialUnit(wr.unit);
            wr.statsRun = statsRunLabel(wr.result);
        } catch (Exception e) {
            logger.warn("Spatial stats failed for {} / {}: {}",
                    w.imageName, w.regionLabel, e.getMessage());
            wr.skipReason = "failed: " + e.getMessage();
        }
        return wr;
    }

    private static String statsRunLabel(ClusteringResult r) {
        List<String> s = new ArrayList<>();
        if (r.hasRipley()) s.add("Ripley");
        if (r.hasCoOccurrencePairwise()) s.add("Cooc-pair");
        if (r.hasCoOccurrenceOneVsRest()) s.add("Cooc-1vR");
        if (r.hasNhoodEnrichment()) s.add("Nhood");
        if (r.hasGeary()) s.add("Geary");
        if (r.hasSpatialAutocorr()) s.add("Moran");
        return String.join(", ", s);
    }

    /**
     * Keep only measurement columns that plausibly represent marker expression for
     * Geary's C / Moran's I. Drops coordinate columns (autocorrelating X/Y is
     * trivially maximal), geometry that duplicates area, and previously-derived
     * QP-CAT / embedding / spatial columns (autocorrelating them is circular).
     */
    static List<String> selectAutocorrMeasurements(List<String> all) {
        List<String> keep = new ArrayList<>();
        for (String name : all) {
            if (name == null) continue;
            String n = name.toLowerCase();
            boolean drop = n.contains("centroid")
                    || n.equals("x") || n.equals("y")
                    || n.startsWith("qpcat")
                    || n.contains("spatial")
                    || n.contains("cluster")
                    || n.contains("component")
                    || n.contains("neighbor")
                    || n.contains("umap") || n.contains("tsne") || n.contains("t-sne")
                    || n.contains("pca")
                    || n.contains("distance to");
            if (!drop) keep.add(name);
        }
        return keep;
    }

    /**
     * Build imageId -&gt; (quantized centroid key -&gt; cluster label) from a saved result,
     * for using it as an in-memory label source (no PathClass writes). Returns null
     * if the result lacks the per-cell references needed to match.
     */
    private static Map<String, Map<Long, Integer>> buildSavedLabelMap(SavedClusteringResult saved) {
        String[] ids = saved.getCellImageIds();
        double[] cx = saved.getCellX();
        double[] cy = saved.getCellY();
        int[] labels = saved.getClusterLabels();
        if (ids == null || cx == null || cy == null || labels == null) return null;
        int n = Math.min(ids.length, Math.min(cx.length, Math.min(cy.length, labels.length)));
        Map<String, Map<Long, Integer>> map = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            if (ids[i] == null) continue;
            map.computeIfAbsent(ids[i], k -> new java.util.HashMap<>())
                    .putIfAbsent(SavedResultApplier.centroidKey(cx[i], cy[i]), labels[i]);
        }
        return map;
    }

    private Map<String, Object> runTask(double[][] coords, int[] labels, List<String> classNames,
                                        double[][] featureMatrix, String[] markerNames,
                                        Options opts, String coordUnit, Consumer<String> progress)
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
        inputs.put("coord_unit", coordUnit != null ? coordUnit : "px");
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
        } else if (outputs.containsKey("ripley_error")) {
            // Extraction failed; Python deliberately did not emit zero-filled curves.
            logger.error("Ripley K/L failed: {}", String.valueOf(outputs.get("ripley_error")));
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

    /** Averaged pixel size in microns for an image, or NaN if uncalibrated. */
    private static double pixelSizeMicrons(ImageData<BufferedImage> data) {
        try {
            var cal = data.getServer().getPixelCalibration();
            if (cal != null && cal.hasPixelSizeMicrons()) {
                double um = cal.getAveragedPixelSizeMicrons();
                if (Double.isFinite(um) && um > 0) return um;
            }
        } catch (Exception e) {
            logger.debug("Pixel calibration unavailable: {}", e.getMessage());
        }
        return Double.NaN;
    }

    private static String imageName(ImageData<BufferedImage> data) {
        try {
            return data.getServer().getMetadata().getName();
        } catch (Exception e) {
            return "image";
        }
    }

    private static void report(Consumer<String> progress, String msg) {
        if (progress != null) {
            try { progress.accept(msg); } catch (Exception ignore) { /* UI sink */ }
        }
        logger.info(msg);
    }
}
