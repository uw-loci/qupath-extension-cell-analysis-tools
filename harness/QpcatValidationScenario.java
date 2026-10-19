import qupath.ext.qpcat.controller.ClusteringWorkflow;
import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.ext.qpcat.model.SavedClusteringResult;
import qupath.ext.qpcat.model.ValidationReport;
import qupath.ext.qpcat.service.ApposeClusteringService;
import qupath.ext.qpcat.service.ClusteringResultManager;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.ProjectImageEntry;

import javafx.application.Platform;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Stability across seeds and agreement with known classes, end to end on the
 * ground-truthed synthetic dataset.
 *
 * <p>Each cell is given its true type as its classification (matched to the
 * dataset's all_groundtruth.csv by centroid, within 10 px; the tumour type is
 * split by its Ki67_positive flag, giving seven classes), then the project is
 * clustered with both checks on. The true types are written again before every
 * run, because a run replaces them with its own "Cluster N" classes.
 *
 * <p>Usage: {@code qp-gui QpcatValidationScenario.java <repo> <project> <all_groundtruth.csv>}
 */
public final class QpcatValidationScenario {

    private static int failures = 0;

    private static void ok(String s) { System.out.println("[PASS] " + s); }
    private static void bad(String s) { System.out.println("[FAIL] " + s); failures++; }
    private static void note(String s) { System.out.println("[....] " + s); }

    /** One ground-truth cell. */
    private record Truth(double x, double y, String type) {}

    public static void main(String[] args) throws Exception {
        Path projectDir = Path.of(args[0]);
        Path truthCsv = Path.of(args[1]);

        startFx();
        QuPathGUI qupath = onFx(QuPathGUI::createHiddenInstance);
        Project<BufferedImage> project = ProjectIO.loadProject(
                projectDir.resolve("project.qpproj").toFile(), BufferedImage.class);
        onFx(() -> { qupath.setProject(project); return null; });
        List<ProjectImageEntry<BufferedImage>> entries = new ArrayList<>(project.getImageList());
        note(entries.size() + " images");

        Map<String, List<Truth>> truth = readTruth(truthCsv);

        ApposeClusteringService service = ApposeClusteringService.getInstance();
        if (!service.isAvailable()) {
            note("starting the QPCAT Python service...");
            service.initialize(QpcatValidationScenario::note);
        }
        if (!service.isAvailable()) {
            bad("QPCAT service did not start: " + service.getInitError());
            finish();
        }

        List<String> measurements = meanMeasurements(entries.get(0).readImageData());
        note(measurements.size() + " mean measurements: " + measurements);

        int[] totals = writeTruth(entries, truth);
        note("ground truth written: " + totals[0] + " cells matched, " + totals[1]
                + " with no ground-truth cell within 10 px (left unclassified)");

        Map<String, Object> km = new HashMap<>();
        km.put("n_clusters", 7);
        km.put("random_state", 42);
        runAndCheck(qupath, project, entries, truth, measurements,
                ClusteringConfig.Algorithm.KMEANS, km, "KMeans k=7", totals);

        Map<String, Object> leiden = new HashMap<>();
        leiden.put("n_neighbors", 50);
        leiden.put("resolution", 1.0);
        leiden.put("random_state", 42);
        runAndCheck(qupath, project, entries, truth, measurements,
                ClusteringConfig.Algorithm.LEIDEN, leiden, "Leiden n_neighbors=50 resolution=1.0",
                totals);

        finish();
    }

    private static void runAndCheck(QuPathGUI qupath, Project<BufferedImage> project,
                                    List<ProjectImageEntry<BufferedImage>> entries,
                                    Map<String, List<Truth>> truth, List<String> measurements,
                                    ClusteringConfig.Algorithm algorithm,
                                    Map<String, Object> params, String label, int[] totals)
            throws Exception {
        writeTruth(entries, truth);
        ClusteringConfig config = new ClusteringConfig();
        config.setSelectedMeasurements(measurements);
        config.setAlgorithm(algorithm);
        config.setAlgorithmParams(params);
        config.setEmbeddingMethod(ClusteringConfig.EmbeddingMethod.NONE);
        config.setGeneratePlots(false);
        config.setComputePaga(false);
        config.setPushConnectionsToViewer(false);
        config.setStabilityRepeats(5);
        config.setCompareWithClasses(true);

        long t0 = System.nanoTime();
        ClusteringResult result = new ClusteringWorkflow(qupath)
                .runProjectClustering(entries, config, s -> { });
        double secs = (System.nanoTime() - t0) / 1e9;
        note(String.format(Locale.US, "%s: %s over %d cells in %.0f s (5 extra runs included)",
                label, result.clusterCountText(), result.getNCells(), secs));

        ValidationReport report = ValidationReport.parse(result.getValidationJson());
        if (report == null || report.stability == null || report.agreement == null) {
            bad(label + ": the result carries both validation checks");
            return;
        }
        ok(label + ": the result carries both validation checks");
        note(label + " stability: " + report.stabilitySummary());
        for (String[] r : report.stabilityRows(c -> "Cluster " + c)) {
            note("    " + String.join(" | ", r));
        }
        note(label + " agreement: " + report.agreementSummary());
        note("    clusters x classes " + report.agreement.classes);
        for (String[] r : report.contingencyRows(c -> c < 0 ? "Noise" : "Cluster " + c)) {
            note("    " + String.join(" | ", r));
        }

        if (report.stability.n_runs == 6) {
            ok(label + ": six runs compared (the reported one plus five seeds)");
        } else {
            bad(label + ": expected 6 runs, got " + report.stability.n_runs);
        }
        if (report.agreement.n_cells + report.agreement.n_unclassified_excluded
                == result.getNCells()) {
            ok(label + ": every cell is either compared or counted as unclassified");
        } else {
            bad(label + ": compared " + report.agreement.n_cells + " + excluded "
                    + report.agreement.n_unclassified_excluded + " != " + result.getNCells());
        }
        if (report.agreement.n_unclassified_excluded == totals[1]) {
            ok(label + ": the excluded count is exactly the cells with no ground truth");
        } else {
            bad(label + ": excluded " + report.agreement.n_unclassified_excluded
                    + " but " + totals[1] + " cells had no ground truth");
        }
        // The classes compared must be the true types, not a previous run's clusters.
        if (report.agreement.classes.stream().noneMatch(c -> c.startsWith("Cluster "))) {
            ok(label + ": compared against the ground-truth classes");
        } else {
            bad(label + ": compared against cluster classes " + report.agreement.classes);
        }

        String saved = result.getSavedName();
        SavedClusteringResult back = saved == null ? null
                : ClusteringResultManager.loadSavedResult(project, saved);
        if (back != null && result.getValidationJson().equals(back.getValidationJson())) {
            ok(label + ": the checks are saved with the result (" + saved + ")");
        } else {
            bad(label + ": the saved result lacks the validation checks (" + saved + ")");
        }
    }

    /** Every "mean" measurement that is not one QP-CAT wrote, as 'Select Mean only'. */
    private static List<String> meanMeasurements(ImageData<BufferedImage> data) {
        TreeSet<String> names = new TreeSet<>();
        for (PathObject det : data.getHierarchy().getDetectionObjects()) {
            for (String n : det.getMeasurementList().getNames()) {
                if (n.toLowerCase(Locale.ROOT).contains("mean") && !n.startsWith("QPCAT")) {
                    names.add(n);
                }
            }
            break;
        }
        return new ArrayList<>(names);
    }

    /** Classifies every detection with its true type and saves; returns {matched, unmatched}. */
    private static int[] writeTruth(List<ProjectImageEntry<BufferedImage>> entries,
                                    Map<String, List<Truth>> truth) throws Exception {
        int matched = 0;
        int unmatched = 0;
        Map<String, Integer> byType = new TreeMap<>();
        for (ProjectImageEntry<BufferedImage> e : entries) {
            String image = e.getImageName().replaceAll("\\.tiff?$", "");
            List<Truth> cells = truth.getOrDefault(image, List.of());
            ImageData<BufferedImage> data = e.readImageData();
            for (PathObject det : data.getHierarchy().getDetectionObjects()) {
                double x = det.getROI().getCentroidX();
                double y = det.getROI().getCentroidY();
                Truth best = null;
                double bestD = 10.0 * 10.0;
                for (Truth t : cells) {
                    double d = (t.x - x) * (t.x - x) + (t.y - y) * (t.y - y);
                    if (d <= bestD) {
                        bestD = d;
                        best = t;
                    }
                }
                if (best == null) {
                    det.setPathClass(null);
                    unmatched++;
                } else {
                    det.setPathClass(PathClass.fromString(best.type));
                    byType.merge(best.type, 1, Integer::sum);
                    matched++;
                }
            }
            e.saveImageData(data);
        }
        note("true types on the cells: " + byType);
        return new int[] {matched, unmatched};
    }

    /** image -> its ground-truth cells; tumour split by Ki67_positive. */
    private static Map<String, List<Truth>> readTruth(Path csv) throws Exception {
        List<String> lines = Files.readAllLines(csv);
        String[] h = lines.get(0).split(",");
        Map<String, Integer> col = new LinkedHashMap<>();
        for (int i = 0; i < h.length; i++) col.put(h[i], i);
        Map<String, List<Truth>> out = new HashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",");
            String type = f[col.get("cell_type")];
            if ("tumor".equals(type)) {
                type = "True".equals(f[col.get("Ki67_positive")]) ? "tumor Ki67+" : "tumor Ki67-";
            }
            out.computeIfAbsent(f[col.get("image_name")], k -> new ArrayList<>())
                    .add(new Truth(Double.parseDouble(f[col.get("centroid_x_px")]),
                            Double.parseDouble(f[col.get("centroid_y_px")]), type));
        }
        return out;
    }

    private static void finish() {
        System.out.println();
        System.out.println(failures == 0 ? "SCENARIO PASSED" : "SCENARIO FAILED (" + failures + ")");
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void startFx() throws Exception {
        CountDownLatch up = new CountDownLatch(1);
        try {
            Platform.startup(up::countDown);
        } catch (IllegalStateException alreadyRunning) {
            up.countDown();
        }
        if (!up.await(60, TimeUnit.SECONDS)) {
            throw new IllegalStateException("JavaFX did not start");
        }
        Platform.setImplicitExit(false);
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(1800, TimeUnit.SECONDS);
    }
}
