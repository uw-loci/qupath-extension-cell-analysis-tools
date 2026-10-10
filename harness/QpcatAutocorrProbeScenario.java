import qupath.ext.qpcat.controller.ClusteringWorkflow;
import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.ext.qpcat.service.ApposeClusteringService;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.ProjectImageEntry;

import javafx.application.Platform;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Probe which per-marker spatial statistics a real clustering run actually
 * produces, through {@link ClusteringWorkflow#runProjectClustering}.
 *
 * <p>Written to settle a question reading could not: Moran's I and Geary's C
 * are requested by the same tick-box and computed a few lines apart in
 * {@code run_clustering.py}, and both are wrapped in a bare
 * {@code except Exception} that only logs. So a statistic that never arrives is
 * indistinguishable, in the results window, from one the user did not ask for.
 * This asserts on the payloads rather than on the log.
 *
 * <p>args: projectDir [imageCount] [nClusters]
 */
public final class QpcatAutocorrProbeScenario {

    private static int failures = 0;

    private static void ok(String s) { System.out.println("[PASS] " + s); }
    private static void bad(String s) { System.out.println("[FAIL] " + s); failures++; }
    private static void note(String s) { System.out.println("[....] " + s); }

    public static void main(String[] args) throws Exception {
        Path projectDir = Path.of(args[0]);
        int imageCount = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        int nClusters = args.length > 2 ? Integer.parseInt(args[2]) : 6;
        String normalization = args.length > 3 ? args[3] : "zscore";

        startFx();
        QuPathGUI qupath = onFx(QuPathGUI::createHiddenInstance);
        Project<BufferedImage> project = ProjectIO.loadProject(
                projectDir.resolve("project.qpproj").toFile(), BufferedImage.class);
        onFx(() -> { qupath.setProject(project); return null; });

        List<ProjectImageEntry<BufferedImage>> all = new ArrayList<>(project.getImageList());
        List<ProjectImageEntry<BufferedImage>> entries =
                new ArrayList<>(all.subList(0, Math.min(imageCount, all.size())));
        note("project " + projectDir + " -- using " + entries.size() + " of " + all.size() + " image(s)");

        // A hidden QuPathGUI loads the extension but never runs its installation
        // hook, so the Appose worker is not started for us.
        ApposeClusteringService service = ApposeClusteringService.getInstance();
        if (!service.isAvailable()) {
            note("starting the QPCAT Python service...");
            service.initialize(QpcatAutocorrProbeScenario::note);
        }
        if (!service.isAvailable()) {
            bad("QPCAT service did not start: " + service.getInitError());
            finish();
        }

        ClusteringConfig config = new ClusteringConfig();
        config.setSelectedMeasurements(Arrays.asList(
                "Nucleus: DAPI mean", "Nucleus: PanCK mean", "Nucleus: Ki67 mean",
                "Nucleus: aSMA mean", "Nucleus: CD3 mean", "Nucleus: CD8 mean",
                "Nucleus: CD20 mean", "Nucleus: CD68 mean"));
        config.setAlgorithm(ClusteringConfig.Algorithm.KMEANS);
        config.setNormalization(ClusteringConfig.Normalization.valueOf(
                normalization.toUpperCase(java.util.Locale.ROOT)));
        config.setAlgorithmParams(java.util.Map.of("n_clusters", nClusters));
        config.setGeneratePlots(true);
        // The tick-box that gates BOTH neighbourhood enrichment and Moran's I.
        config.setEnableSpatialAnalysis(true);
        config.setEnableGeary(true);
        // A dense graph triggers a modal "push the overlay anyway?" prompt
        // (ClusteringWorkflow.confirmOverlayPushBatch), which a hidden GUI has
        // nobody to answer -- the run parks on its latch forever. The overlay
        // is a viewer convenience and nothing here reads it.
        config.setPushConnectionsToViewer(false);

        note("running KMeans k=" + nClusters + ", normalization=" + normalization
                + ", with spatial analysis + Geary's C...");
        long t0 = System.currentTimeMillis();
        ClusteringResult result = new ClusteringWorkflow(qupath)
                .runProjectClustering(entries, config, QpcatAutocorrProbeScenario::note);
        note("run finished in " + ((System.currentTimeMillis() - t0) / 1000) + "s: "
                + result.getNClusters() + " clusters, " + result.getNCells() + " cells");

        // --- the three statistics the same tick-box asks for ---

        if (result.getNhoodEnrichment() != null) {
            ok("neighbourhood enrichment: " + result.getNhoodEnrichment().length
                    + "x" + result.getNhoodEnrichment()[0].length + " z-score matrix");
        } else {
            bad("neighbourhood enrichment: ABSENT");
        }

        if (result.hasSpatialAutocorr()) {
            ok("Moran's I: present");
            note("payload: " + abbreviate(result.getSpatialAutocorrJson()));
        } else {
            bad("Moran's I: ABSENT -- requested by the same tick-box that produced "
                    + "neighbourhood enrichment above");
        }

        if (result.hasGeary()) {
            ok("Geary's C: present, " + result.getGeary().getMarkerStats().size() + " markers, "
                    + result.getGeary().getNPermutations() + " permutations");
            result.getGeary().getMarkerStats().forEach((m, s) ->
                    note(String.format("   %-24s C=%.4f  p=%.4g", m, s.getC(), s.getPValue())));
        } else {
            bad("Geary's C: ABSENT");
        }

        boolean dotplotDrawn = result.getPlotPaths() != null
                && result.getPlotPaths().containsKey("dotplot");
        note("dotplot drawn: " + dotplotDrawn);

        if (result.getQualityWarnings() != null && !result.getQualityWarnings().isEmpty()) {
            note("quality warnings carried to the user:");
            for (String w : result.getQualityWarnings()) note("   " + w);
        } else {
            note("no quality warnings were carried to the user");
        }

        finish();
    }

    private static String abbreviate(String s) {
        if (s == null) return "null";
        return s.length() <= 400 ? s : s.substring(0, 400) + "... (" + s.length() + " chars)";
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
