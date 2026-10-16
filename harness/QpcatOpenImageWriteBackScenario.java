import qupath.ext.qpcat.controller.ClusteringWorkflow;
import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.ext.qpcat.service.ApposeClusteringService;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.ProjectImageEntry;

import javafx.application.Platform;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * A multi-image clustering run with one of its images open in the viewer: the viewer,
 * the saved file and the "unsaved changes" flag must all agree afterwards.
 *
 * <p>Found re-walking the BINA guide (2026-10-15): the run wrote labels to a detached
 * copy of the open image, so the viewer kept the old labels, was marked changed, and
 * answering Yes to "Save changes?" wrote the old labels back over the new ones.
 *
 * <p>args: projectDir. The project is MODIFIED (labels are written); run on a copy.
 */
public final class QpcatOpenImageWriteBackScenario {

    private static int failures = 0;

    private static void ok(String s) { System.out.println("[PASS] " + s); }
    private static void bad(String s) { System.out.println("[FAIL] " + s); failures++; }
    private static void note(String s) { System.out.println("[....] " + s); }

    public static void main(String[] args) throws Exception {
        Path projectDir = Path.of(args[0]);

        startFx();
        QuPathGUI qupath = onFx(QuPathGUI::createHiddenInstance);
        Project<BufferedImage> project = ProjectIO.loadProject(
                projectDir.resolve("project.qpproj").toFile(), BufferedImage.class);
        onFx(() -> { qupath.setProject(project); return null; });

        List<ProjectImageEntry<BufferedImage>> entries =
                new ArrayList<>(project.getImageList().subList(0, 2));
        ProjectImageEntry<BufferedImage> openEntry = entries.get(0);
        ProjectImageEntry<BufferedImage> closedEntry = entries.get(1);
        onFx(() -> qupath.openImageEntry(openEntry));
        ImageData<BufferedImage> live = onFx(qupath::getImageData);
        onFx(() -> { live.setChanged(false); return null; });
        note("open in the viewer: " + openEntry.getImageName() + ", also in the run: "
                + closedEntry.getImageName());

        ApposeClusteringService service = ApposeClusteringService.getInstance();
        if (!service.isAvailable()) {
            note("starting the QPCAT Python service...");
            service.initialize(QpcatOpenImageWriteBackScenario::note);
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
        config.setAlgorithmParams(Map.of("n_clusters", 4));
        config.setPushConnectionsToViewer(false);

        ClusteringResult result = new ClusteringWorkflow(qupath)
                .runProjectClustering(entries, config, QpcatOpenImageWriteBackScenario::note);
        note("run finished: " + result.getNClusters() + " clusters, " + result.getNCells() + " cells");
        // Let the run's FX-thread refresh land before reading the flag.
        onFx(() -> null);

        Map<String, Integer> inViewer = onFx(() -> classCounts(live));
        Map<String, Integer> onDisk = classCounts(openEntry.readImageData());
        Map<String, Integer> closedOnDisk = classCounts(closedEntry.readImageData());
        note("viewer:          " + inViewer);
        note("open image file: " + onDisk);
        note("closed image file: " + closedOnDisk);

        if (onFx(qupath::getImageData) == live) {
            ok("the viewer still holds the same ImageData instance");
        } else {
            bad("the viewer's ImageData was replaced during the run");
        }
        if (inViewer.keySet().stream().allMatch(c -> c.startsWith("Cluster "))) {
            ok("every cell in the viewer carries a new cluster label");
        } else {
            bad("the viewer shows labels that are not this run's: " + inViewer.keySet());
        }
        if (inViewer.equals(onDisk)) {
            ok("viewer and saved file agree");
        } else {
            bad("viewer and saved file DISAGREE -- a save from the viewer would overwrite the run");
        }
        if (closedOnDisk.keySet().stream().allMatch(c -> c.startsWith("Cluster "))) {
            ok("the image that was not open is labelled on disk");
        } else {
            bad("the image that was not open is not labelled on disk: " + closedOnDisk.keySet());
        }
        if (!onFx(live::isChanged)) {
            ok("the open image is not marked as having unsaved changes");
        } else {
            bad("the open image is marked changed, so closing it asks to save");
        }
        finish();
    }

    private static Map<String, Integer> classCounts(ImageData<BufferedImage> data) {
        Map<String, Integer> counts = new TreeMap<>();
        for (PathObject det : data.getHierarchy().getDetectionObjects()) {
            String c = det.getPathClass() == null ? "(none)" : det.getPathClass().toString();
            counts.merge(c, 1, Integer::sum);
        }
        return counts;
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
