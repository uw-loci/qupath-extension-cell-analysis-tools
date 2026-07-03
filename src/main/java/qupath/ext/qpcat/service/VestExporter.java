package qupath.ext.qpcat.service;

import org.apposed.appose.NDArray;
import org.apposed.appose.Service.ResponseType;
import org.apposed.appose.Service.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.CellRef;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.roi.interfaces.ROI;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exports the currently-clustered cells as a bundle for the VEST 3D viewer
 * (<a href="https://github.com/scads/vest">scads/vest</a>): an {@code embedding.csv}
 * ({@code filename,x,y,z,cluster}) plus an {@code images/} folder of per-cell crop PNGs.
 *
 * <p>The 3D layout is computed by the {@code embed_3d} Appose task (UMAP/PCA/t-SNE with
 * {@code n_components=3}) from the cells' marker measurements. Cells are taken from the
 * open image's detections that carry a {@code "Cluster N"} class, subsampled per cluster
 * so the export stays bounded. This is a ONE-WAY export -- VEST runs standalone in a
 * browser and does not navigate back into QuPath. (A separate in-QuPath 3D navigator with
 * click-to-cell is planned; see the extension-team intake.)</p>
 */
public final class VestExporter {

    private static final Logger logger = LoggerFactory.getLogger(VestExporter.class);
    private static final Pattern CLUSTER = Pattern.compile("^Cluster (\\d+)$");

    /** Measurement-name substrings that are NOT markers (coords / embeddings / labels). */
    private static final String[] NON_MARKER_TOKENS = {
            "centroid", "cluster", "umap", "pca", "tsne", "t-sne", "component",
            "neighbor", "neighbour", "distance", "spatial", "embedding"
    };

    private VestExporter() {}

    /** Options for an export run. All defaults are overridable from the dialog. */
    public static final class Options {
        public String method = "umap";        // umap | pca | tsne
        public String normalization = "zscore";
        // "stratified" (default): global budget, abundance-weighted, seeded uniform-random
        // within each cluster. "geosketch": density-aware geometric sketch (preserves rare
        // structure within clusters too) + the same per-class floor.
        public String samplingMode = "stratified";
        // GLOBAL cell budget (total across all clusters) with a per-class floor, so the
        // cloud reflects relative abundance but no cluster vanishes to class imbalance.
        public int globalCap = 1000;
        public int minPerClass = 30;
        public double cropScale = CellCropService.DEFAULT_CROP_SCALE;
        public int seed = 42;                  // embedding random seed (reproducibility)
        // UMAP
        public int umapNeighbors = 15;
        public double umapMinDist = 0.1;
        // t-SNE (perplexity <= 0 => auto from cell count)
        public double tsnePerplexity = 0.0;
        // Percentile-normalization clip bounds (fractions in 0..1)
        public double percentileLow = 0.01;
        public double percentileHigh = 0.99;
        public Path outputDir;                 // bundle root (images/ + embedding.csv)
    }

    /** Summary of a completed export. */
    public static final class Result {
        public final int cells;
        public final int clusters;
        public final Path outputDir;
        public Result(int cells, int clusters, Path outputDir) {
            this.cells = cells;
            this.clusters = clusters;
            this.outputDir = outputDir;
        }
    }

    /**
     * Run the export. Must be called off the JavaFX application thread (it does an
     * Appose round-trip + disk I/O).
     */
    public static Result export(QuPathGUI qupath, Options opts, Consumer<String> progress)
            throws IOException {
        if (opts.outputDir == null) {
            throw new IOException("No output directory chosen.");
        }
        ImageData<BufferedImage> imageData = qupath.getImageData();
        if (imageData == null) {
            throw new IOException("No image is open.");
        }

        // 1. Gather clustered cells on the open image.
        report(progress, "Collecting clustered cells...");
        Project<?> project = qupath.getProject();
        String imageId = null;
        String imageName = null;
        if (project != null) {
            @SuppressWarnings("unchecked")
            ProjectImageEntry<BufferedImage> entry =
                    ((Project<BufferedImage>) project).getEntry(imageData);
            if (entry != null) {
                imageId = entry.getID();
                imageName = entry.getImageName();
            }
        }

        Map<Integer, List<PathObject>> byCluster = new LinkedHashMap<>();
        for (PathObject det : imageData.getHierarchy().getDetectionObjects()) {
            PathClass pc = det.getPathClass();
            if (pc == null) continue;
            Matcher m = CLUSTER.matcher(pc.toString());
            if (!m.matches()) continue;
            if (det.getROI() == null) continue;
            int cid = Integer.parseInt(m.group(1));
            byCluster.computeIfAbsent(cid, k -> new ArrayList<>()).add(det);
        }
        if (byCluster.isEmpty()) {
            throw new IOException("No cells with a \"Cluster N\" class were found on the "
                    + "open image. Run (or apply) clustering first.");
        }

        // 2. Choose which cells to export + their 3D coordinates, per sampling mode.
        List<Integer> clusterIds = new ArrayList<>(byCluster.keySet());
        MeasurementExtractor extractor = new MeasurementExtractor();

        List<PathObject> selected;
        List<Integer> selectedClusters;
        double[][] coords3d;

        if ("geosketch".equalsIgnoreCase(opts.samplingMode)) {
            // Density-aware geometric sketch over ALL clustered cells (preserves rare
            // structure within clusters, not just rare whole-clusters) + per-class floor.
            List<PathObject> allDets = new ArrayList<>();
            List<Integer> allCids = new ArrayList<>();
            for (int cid : clusterIds) {
                for (PathObject d : byCluster.get(cid)) { allDets.add(d); allCids.add(cid); }
            }
            List<String> markerNames = markerMeasurements(allDets);
            if (markerNames.isEmpty()) {
                throw new IOException("No marker measurements found on these cells to embed.");
            }
            Aligned a = extractAligned(extractor, allDets, allCids, markerNames);
            report(progress, "Representative sketch of " + a.dets.size() + " clustered cells...");
            int[] labels = a.clusters.stream().mapToInt(Integer::intValue).toArray();
            int[] sel = geosketchSelect(a.data, markerNames, labels, opts, progress);

            selected = new ArrayList<>(sel.length);
            selectedClusters = new ArrayList<>(sel.length);
            double[][] selData = new double[sel.length][];
            for (int i = 0; i < sel.length; i++) {
                selected.add(a.dets.get(sel[i]));
                selectedClusters.add(a.clusters.get(sel[i]));
                selData[i] = a.data[sel[i]];
            }
            report(progress, "Computing 3D embedding (" + opts.method + ", "
                    + selected.size() + " cells)...");
            coords3d = compute3dEmbedding(selData, markerNames.toArray(new String[0]), opts, progress);
        } else {
            // Stratified: GLOBAL budget (abundance-weighted) + per-class floor, then draw
            // each cluster's share by SEEDED UNIFORM RANDOM sampling (reproducible; not an
            // index stride, whose representativeness would depend on detection order).
            int[] sizes = new int[clusterIds.size()];
            for (int i = 0; i < clusterIds.size(); i++) sizes[i] = byCluster.get(clusterIds.get(i)).size();
            int[] targets = allocateCounts(sizes, opts.globalCap, opts.minPerClass);

            List<PathObject> pick = new ArrayList<>();
            List<Integer> pickCids = new ArrayList<>();
            for (int i = 0; i < clusterIds.size(); i++) {
                int cid = clusterIds.get(i);
                List<PathObject> group = byCluster.get(cid);
                int take = Math.min(targets[i], group.size());
                List<PathObject> chosen;
                if (take >= group.size()) {
                    chosen = group;
                } else {
                    chosen = new ArrayList<>(group);
                    java.util.Collections.shuffle(chosen,
                            new java.util.Random(opts.seed * 1000003L + cid));
                    chosen = chosen.subList(0, take);
                }
                for (PathObject d : chosen) { pick.add(d); pickCids.add(cid); }
            }
            List<String> markerNames = markerMeasurements(pick);
            if (markerNames.isEmpty()) {
                throw new IOException("No marker measurements found on these cells to embed.");
            }
            Aligned a = extractAligned(extractor, pick, pickCids, markerNames);
            selected = a.dets;
            selectedClusters = a.clusters;
            report(progress, "Selected " + selected.size() + " cells across "
                    + byCluster.size() + " clusters.");
            report(progress, "Computing 3D embedding (" + opts.method + ", "
                    + selected.size() + " cells)...");
            coords3d = compute3dEmbedding(a.data, markerNames.toArray(new String[0]), opts, progress);
        }
        int n = selected.size();

        // 5. Write the bundle: images/ + embedding.csv + README.
        Path imagesDir = opts.outputDir.resolve("images");
        Files.createDirectories(imagesDir);
        report(progress, "Writing crops + CSV to " + opts.outputDir + " ...");

        List<String> csvRows = new ArrayList<>(n);
        int written = 0;
        try (CellCropService crops = new CellCropService(qupath)) {
            for (int i = 0; i < n; i++) {
                PathObject det = selected.get(i);
                ROI roi = det.getROI();
                double half = 0.5 * Math.max(roi.getBoundsWidth(), roi.getBoundsHeight());
                CellRef ref = new CellRef(imageId, imageName,
                        roi.getCentroidX(), roi.getCentroidY(), half);
                String fname = String.format("cell_%05d.png", i);
                try {
                    BufferedImage crop = crops.readCrop(ref, opts.cropScale);
                    if (crop != null) {
                        ImageIO.write(crop, "png", imagesDir.resolve(fname).toFile());
                        written++;
                    }
                } catch (Exception ex) {
                    logger.warn("VEST export: crop failed for cell {}: {}", i, ex.getMessage());
                }
                csvRows.add(String.format(Locale.US, "%s,%.6f,%.6f,%.6f,%d",
                        fname, coords3d[i][0], coords3d[i][1], coords3d[i][2],
                        selectedClusters.get(i)));
                if ((i & 0x3f) == 0) {
                    report(progress, "Wrote " + i + "/" + n + " crops...");
                }
            }
        }

        writeCsv(opts.outputDir.resolve("embedding.csv"), csvRows);
        writeReadme(opts.outputDir, n, byCluster.size());

        logger.info("VEST export: {} cells ({} crops) across {} clusters -> {}",
                n, written, byCluster.size(), opts.outputDir);
        OperationLogger.getInstance().logEvent("VEST EXPORT",
                "Exported " + n + " cells (" + written + " crops) across " + byCluster.size()
                + " clusters to " + opts.outputDir);
        return new Result(n, byCluster.size(), opts.outputDir);
    }

    /**
     * Per-cluster export counts under a GLOBAL cell budget with a per-class floor.
     *
     * <p>Each cluster gets at least {@code min(minPerClass, size)} cells -- so severe
     * class imbalance (e.g. one cluster with a million cells) never hides a smaller
     * cluster -- plus a size-proportional share of {@code globalCap}, capped at the
     * cluster's actual size. The per-class floor takes priority: when there are so many
     * clusters that the floors alone exceed the budget, the total exceeds
     * {@code globalCap} (keeping every cluster visible wins over the nominal cap). Pure
     * function of its inputs so it can be unit-tested and previewed in the dialog.</p>
     */
    public static int[] allocateCounts(int[] sizes, int globalCap, int minPerClass) {
        int k = sizes.length;
        int[] out = new int[k];
        long total = 0;
        for (int s : sizes) total += Math.max(0, s);
        if (total == 0) return out;
        int cap = Math.max(0, globalCap);
        int floor = Math.max(0, minPerClass);
        for (int i = 0; i < k; i++) {
            int s = Math.max(0, sizes[i]);
            int floorI = Math.min(s, floor);
            int prop = (int) Math.round((double) cap * s / total);
            out[i] = Math.min(s, Math.max(floorI, prop));
        }
        return out;
    }

    /** Total cells {@link #allocateCounts} would export for the given cluster sizes. */
    public static int totalAllocated(int[] sizes, int globalCap, int minPerClass) {
        int sum = 0;
        for (int c : allocateCounts(sizes, globalCap, minPerClass)) sum += c;
        return sum;
    }

    /**
     * Sizes of the {@code "Cluster N"} classes on the open image, for the dialog's live
     * export-size estimate. Returns an empty array if no image / no clustered cells.
     */
    public static int[] clusterSizes(QuPathGUI qupath) {
        ImageData<BufferedImage> imageData = qupath.getImageData();
        if (imageData == null) return new int[0];
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (PathObject det : imageData.getHierarchy().getDetectionObjects()) {
            PathClass pc = det.getPathClass();
            if (pc == null) continue;
            Matcher m = CLUSTER.matcher(pc.toString());
            if (!m.matches() || det.getROI() == null) continue;
            counts.merge(Integer.parseInt(m.group(1)), 1, Integer::sum);
        }
        int[] out = new int[counts.size()];
        int i = 0;
        for (int v : counts.values()) out[i++] = v;
        return out;
    }

    /** All numeric marker measurements present on the cells (coords/embeddings excluded). */
    private static List<String> markerMeasurements(List<PathObject> dets) {
        List<String> all = MeasurementExtractor.getAllMeasurements(dets);
        List<String> markers = new ArrayList<>();
        for (String name : all) {
            String lower = name.toLowerCase(Locale.US);
            boolean skip = false;
            for (String tok : NON_MARKER_TOKENS) {
                if (lower.contains(tok)) { skip = true; break; }
            }
            if (!skip) markers.add(name);
        }
        return markers;
    }

    private static double[][] compute3dEmbedding(double[][] data, String[] markerNames,
                                                 Options opts, Consumer<String> progress)
            throws IOException {
        final int n = data.length;
        final int m = markerNames.length;
        try {
            return ApposeClusteringService.withExtensionClassLoader(() -> {
                NDArray measNd = new NDArray(NDArray.DType.FLOAT64,
                        new NDArray.Shape(NDArray.Shape.Order.C_ORDER, n, m));
                var buf = measNd.buffer().asDoubleBuffer();
                for (double[] row : data) buf.put(row);

                Map<String, Object> inputs = new HashMap<>();
                inputs.put("measurements", measNd);
                inputs.put("marker_names", new ArrayList<>(List.of(markerNames)));
                inputs.put("normalization", opts.normalization);
                inputs.put("method", opts.method);
                inputs.put("n_neighbors", opts.umapNeighbors);
                inputs.put("min_dist", opts.umapMinDist);
                inputs.put("tsne_perplexity", opts.tsnePerplexity);
                inputs.put("percentile_low", opts.percentileLow);
                inputs.put("percentile_high", opts.percentileHigh);
                inputs.put("seed", opts.seed);

                try {
                    Task task = ApposeClusteringService.getInstance().runTaskWithListener(
                            "embed_3d", inputs,
                            event -> {
                                if (event.responseType == ResponseType.UPDATE && event.message != null) {
                                    report(progress, event.message);
                                }
                            });
                    NDArray out = (NDArray) task.outputs.get("embedding3d");
                    if (out == null) {
                        throw new IOException("embed_3d returned no embedding.");
                    }
                    double[][] coords = new double[n][3];
                    var obuf = out.buffer().asDoubleBuffer();
                    for (int i = 0; i < n; i++) obuf.get(coords[i]);
                    out.close();
                    return coords;
                } finally {
                    measNd.close();
                }
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("3D embedding failed: " + e.getMessage(), e);
        }
    }

    /** Detections + their cluster ids aligned to an extracted feature matrix. */
    private static final class Aligned {
        final List<PathObject> dets;
        final List<Integer> clusters;
        final double[][] data;
        Aligned(List<PathObject> dets, List<Integer> clusters, double[][] data) {
            this.dets = dets;
            this.clusters = clusters;
            this.data = data;
        }
    }

    /**
     * Extract marker features and keep the detection + cluster-id lists aligned to the
     * matrix rows -- {@code extract()} can drop rows lacking all measurements.
     */
    private static Aligned extractAligned(MeasurementExtractor extractor, List<PathObject> dets,
                                          List<Integer> cids, List<String> markerNames) {
        MeasurementExtractor.ExtractionResult ex = extractor.extract(dets, markerNames);
        List<PathObject> kept = ex.getDetections();
        if (kept.size() == dets.size()) {
            return new Aligned(kept, cids, ex.getData());
        }
        Map<PathObject, Integer> cidOf = new HashMap<>();
        for (int i = 0; i < dets.size(); i++) cidOf.put(dets.get(i), cids.get(i));
        List<Integer> keptCids = new ArrayList<>(kept.size());
        for (PathObject d : kept) keptCids.add(cidOf.getOrDefault(d, -1));
        return new Aligned(kept, keptCids, ex.getData());
    }

    /**
     * Run the vendored geosketch selection (density-aware + per-class floor) in Python.
     * Returns row indices into {@code data} to keep.
     */
    private static int[] geosketchSelect(double[][] data, List<String> markerNames,
                                         int[] labels, Options opts, Consumer<String> progress)
            throws IOException {
        final int nn = data.length;
        final int mm = markerNames.size();
        try {
            return ApposeClusteringService.withExtensionClassLoader(() -> {
                NDArray measNd = new NDArray(NDArray.DType.FLOAT64,
                        new NDArray.Shape(NDArray.Shape.Order.C_ORDER, nn, mm));
                var buf = measNd.buffer().asDoubleBuffer();
                for (double[] row : data) buf.put(row);
                NDArray labelsNd = new NDArray(NDArray.DType.INT32,
                        new NDArray.Shape(NDArray.Shape.Order.C_ORDER, nn));
                labelsNd.buffer().asIntBuffer().put(labels);

                Map<String, Object> inputs = new HashMap<>();
                inputs.put("measurements", measNd);
                inputs.put("marker_names", new ArrayList<>(markerNames));
                inputs.put("cluster_labels", labelsNd);
                inputs.put("normalization", opts.normalization);
                inputs.put("global_cap", opts.globalCap);
                inputs.put("min_per_class", opts.minPerClass);
                inputs.put("percentile_low", opts.percentileLow);
                inputs.put("percentile_high", opts.percentileHigh);
                inputs.put("seed", opts.seed);

                try {
                    Task task = ApposeClusteringService.getInstance().runTaskWithListener(
                            "geosketch_select", inputs,
                            event -> {
                                if (event.responseType == ResponseType.UPDATE && event.message != null) {
                                    report(progress, event.message);
                                }
                            });
                    NDArray out = (NDArray) task.outputs.get("selected_indices");
                    if (out == null) {
                        throw new IOException("geosketch_select returned no indices.");
                    }
                    java.nio.IntBuffer ib = out.buffer().asIntBuffer();
                    int[] idx = new int[ib.remaining()];
                    ib.get(idx);
                    out.close();
                    return idx;
                } finally {
                    measNd.close();
                    labelsNd.close();
                }
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Representative sketch failed: " + e.getMessage(), e);
        }
    }

    private static void writeCsv(Path file, List<String> rows) throws IOException {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("filename,x,y,z,cluster\n");
            for (String r : rows) { w.write(r); w.write("\n"); }
        }
    }

    private static void writeReadme(Path dir, int cells, int clusters) throws IOException {
        String txt = "QP-CAT export for the VEST 3D viewer (https://github.com/scads/vest)\n\n"
                + cells + " cells across " + clusters + " clusters.\n\n"
                + "This folder contains:\n"
                + "  embedding.csv  -- filename,x,y,z,cluster (cluster is a numeric color key)\n"
                + "  images/        -- one PNG crop per cell, matching the CSV filename column\n\n"
                + "To view it in 3D:\n"
                + "  1. Install VEST (once):   pip install vision-embedding-space-travelling\n"
                + "  2. From this folder:      vest embedding.csv --image-path ./images\n"
                + "  3. Open the URL it prints in a browser.\n\n"
                + "Color-map by cluster using the 'cluster' column in the VEST controls.\n"
                + "Note: VEST runs standalone in the browser; it does not navigate back into\n"
                + "QuPath. An in-QuPath 3D navigator with click-to-cell is a separate tool.\n";
        Files.writeString(dir.resolve("README.txt"), txt);
    }

    private static void report(Consumer<String> progress, String msg) {
        if (progress != null) {
            try { progress.accept(msg); } catch (Exception ignore) { /* UI sink */ }
        }
    }
}
