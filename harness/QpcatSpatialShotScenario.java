import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.embed.swing.SwingFXUtils;
import qupath.ext.qpcat.controller.PostHocSpatialWorkflow;
import qupath.ext.qpcat.model.SavedClusteringResult;
import qupath.ext.qpcat.service.ApposeClusteringService;
import qupath.ext.qpcat.service.ClusteringResultManager;
import qupath.ext.qpcat.ui.ClusteringDialog;
import qupath.ext.qpcat.ui.SpatialStatsSummaryDialog;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.ProjectImageEntry;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Run QP-CAT's post-hoc spatial statistics over a project and render the
 * summary window and one image's Ripley L tab to PNG, for documentation.
 *
 * <p>Unlike a saved clustering result, a spatial run has nothing on disk to
 * reopen: {@code qpcat/spatial_stats/} records what was run, not a session.
 * So the figures can only be as current as a real run, which is what this
 * does -- through the same {@link PostHocSpatialWorkflow} the dialog drives,
 * and against a saved result's labels rather than whatever classifications the
 * project currently carries.
 *
 * <p>args: projectDir outDir savedResultName tickClusters [imageName]
 *          [summaryFigure] [ripleyFigure]
 *
 * <p>{@code tickClusters} is a comma-separated list of the cluster class names
 * to leave ticked in the Ripley L tab, e.g. {@code "Cluster 1,Cluster 6"}.
 */
public final class QpcatSpatialShotScenario {

    private static int failures = 0;

    private static void ok(String s) {
        System.out.println("[PASS] " + s);
    }

    private static void bad(String s) {
        System.out.println("[FAIL] " + s);
        failures++;
    }

    private static void note(String s) {
        System.out.println("[....] " + s);
    }

    public static void main(String[] args) throws Exception {
        Path projectDir = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        String resultName = args[2];
        Set<String> tick = new HashSet<>(Arrays.asList(args[3].split(",")));
        String imageName = args.length > 4 ? args[4] : null;
        String summaryFigure = args.length > 5 ? args[5] : "spatial-summary";
        String ripleyFigure = args.length > 6 ? args[6] : "spatial-ripley-l";
        Files.createDirectories(outDir);

        startFx();
        QuPathGUI qupath = onFx(QuPathGUI::createHiddenInstance);
        Project<BufferedImage> project = ProjectIO.loadProject(
                projectDir.resolve("project.qpproj").toFile(), BufferedImage.class);
        onFx(() -> {
            qupath.setProject(project);
            return null;
        });
        List<ProjectImageEntry<BufferedImage>> entries =
                new ArrayList<>(project.getImageList());
        onFx(() -> qupath.openImageEntry(entries.get(0)));
        for (int i = 0; i < 100 && onFx(qupath::getImageData) == null; i++) {
            Thread.sleep(100);
        }

        // A hidden QuPathGUI loads the extension but never starts its Appose
        // worker -- that happens in the extension's own installation hook. Every
        // statistic here is computed in Python, so without this the whole run
        // reports "QPCAT service is not available" and skips every image.
        ApposeClusteringService service = ApposeClusteringService.getInstance();
        if (!service.isAvailable()) {
            note("starting the QPCAT Python service...");
            service.initialize(QpcatSpatialShotScenario::note);
        }
        if (!service.isAvailable()) {
            bad("QPCAT service did not start: " + service.getInitError());
            finish();
        }

        SavedClusteringResult saved =
                ClusteringResultManager.loadSavedResult(project, resultName);
        note("labels from '" + resultName + "'");

        // The parameters of the run this figure documents, taken from the
        // RUN_INFO the original left in the project: a radius graph with the
        // automatic radius, adaptive permutations, and every statistic the
        // summary table reports.
        PostHocSpatialWorkflow.Options opts = new PostHocSpatialWorkflow.Options();
        opts.entries = entries;
        opts.savedLabelSource = saved;
        opts.graphType = "radius";
        opts.graphK = 15;
        opts.graphRadius = -1.0;
        opts.permutations = 0;
        opts.ripley = true;
        opts.gearyC = true;
        opts.coocPairwise = true;
        opts.coocOneVsRest = true;
        opts.nhood = true;

        PostHocSpatialWorkflow workflow = new PostHocSpatialWorkflow(qupath);
        long t0 = System.currentTimeMillis();
        List<PostHocSpatialWorkflow.WindowResult> results =
                workflow.runWindows(opts, QpcatSpatialShotScenario::note);
        note("ran " + results.size() + " window(s) in "
                + ((System.currentTimeMillis() - t0) / 1000) + "s");
        int analyzed = 0;
        for (PostHocSpatialWorkflow.WindowResult wr : results) {
            if (wr.isSkipped()) {
                bad(wr.imageName + ": skipped -- " + wr.skipReason);
            } else {
                analyzed++;
                note(wr.imageName + " / " + wr.regionLabel + ": " + wr.nCells
                        + " cells, " + wr.nClasses + " classes, " + wr.unit
                        + ", " + wr.statsRun);
            }
        }
        if (analyzed == 0) {
            bad("no window produced a result; nothing to shoot");
            finish();
        }

        Platform.runLater(() -> SpatialStatsSummaryDialog.show(qupath, results));
        Stage summary = awaitStage("QP-CAT - Spatial statistics summary", 300);
        if (summary == null) {
            bad("the spatial summary window never appeared");
        } else {
            Thread.sleep(1500);
            onFx(() -> {
                summary.sizeToScene();
                return null;
            });
            snapshot(summary, outDir, summaryFigure);
            onFx(() -> {
                summary.close();
                return null;
            });
        }

        PostHocSpatialWorkflow.WindowResult chosen = null;
        for (PostHocSpatialWorkflow.WindowResult wr : results) {
            if (wr.isSkipped()) continue;
            if (imageName == null || imageName.equals(wr.imageName)) {
                chosen = wr;
                break;
            }
        }
        if (chosen == null) {
            bad("no analyzed window for image '" + imageName + "'");
            finish();
        }
        final PostHocSpatialWorkflow.WindowResult target = chosen;
        Platform.runLater(() -> ClusteringDialog.showResultsDialog(target.result,
                "Embedding", "Post-hoc spatial: " + target.imageName
                        + " / " + target.regionLabel, null,
                target.imageName + " / " + target.regionLabel));
        Stage ripley = awaitStage("QPCAT - Results", 600);
        if (ripley == null) {
            bad("no results window appeared for " + target.imageName);
            finish();
        }
        note("window: " + onFx(ripley::getTitle));

        String tabs = onFx(() -> selectTab(ripley, "Ripley L"));
        if (tabs != null) {
            bad("no 'Ripley L' tab. Tabs: " + tabs);
        } else {
            Thread.sleep(3000);
            String shown = onFx(() -> tickClusters(ripley, tick));
            note("ticked: " + shown);
            onFx(() -> {
                ripley.setWidth(1100);
                ripley.setHeight(900);
                return null;
            });
            Thread.sleep(2500);
            snapshot(ripley, outDir, ripleyFigure);
        }
        onFx(() -> {
            ripley.close();
            return null;
        });
        finish();
    }

    /**
     * Leave only the named cluster checkboxes ticked, and make sure
     * "Relative to random" stays on.
     *
     * @return what ended up ticked, so the caller can report it rather than
     *         assume the names matched
     */
    private static String tickClusters(Stage stage, Set<String> wanted) {
        List<CheckBox> boxes = new ArrayList<>();
        collect(stage.getScene().getRoot(), CheckBox.class, boxes);
        List<String> on = new ArrayList<>();
        for (CheckBox cb : boxes) {
            String text = cb.getText();
            if (text == null) continue;
            if ("Relative to random".equals(text)) {
                cb.setSelected(true);
                on.add(text);
            } else if (text.startsWith("Cluster ")) {
                boolean want = wanted.contains(text);
                cb.setSelected(want);
                if (want) on.add(text);
            }
        }
        return on.isEmpty() ? "(nothing matched)" : String.join(", ", on);
    }

    private static String selectTab(Stage stage, String tabName) {
        TabPane pane = (TabPane) find(stage.getScene().getRoot(), TabPane.class);
        if (pane == null) return "(no tab pane in this window)";
        List<String> names = new ArrayList<>();
        for (Tab t : pane.getTabs()) {
            names.add(t.getText());
            if (tabName.equals(t.getText())) {
                pane.getSelectionModel().select(t);
                return null;
            }
        }
        return String.join(", ", names);
    }

    private static Node find(Node from, Class<?> type) {
        if (type.isInstance(from)) return from;
        if (from instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                Node hit = find(child, type);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> void collect(Node from, Class<T> type, List<T> into) {
        if (type.isInstance(from)) into.add((T) from);
        if (from instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) collect(child, type, into);
        }
    }

    private static void snapshot(Stage stage, Path outDir, String name) throws Exception {
        onFx(() -> {
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            return null;
        });
        Thread.sleep(600);
        WritableImage shot = onFx(() -> {
            Scene scene = stage.getScene();
            return scene.snapshot(null);
        });
        BufferedImage img = SwingFXUtils.fromFXImage(shot, null);
        if (img == null) {
            bad(name + ": snapshot produced no image");
            return;
        }
        Path file = outDir.resolve(name + ".png");
        ImageIO.write(img, "png", file.toFile());
        if (img.getWidth() < 200 || img.getHeight() < 200) {
            bad(name + ": snapshot is " + img.getWidth() + "x" + img.getHeight()
                    + ", too small to be the window");
            return;
        }
        if (isUniform(img)) {
            bad(name + ": snapshot is a uniform blank");
            return;
        }
        ok(name + ": " + img.getWidth() + "x" + img.getHeight() + " -> " + file);
    }

    private static Stage awaitStage(String titlePrefix, int tenths) throws Exception {
        for (int i = 0; i < tenths; i++) {
            Stage s = onFx(() -> findStage(titlePrefix));
            if (s != null) return s;
            Thread.sleep(100);
        }
        return null;
    }

    private static Stage findStage(String titlePrefix) {
        for (Window w : Window.getWindows()) {
            if (w instanceof Stage s && s.isShowing()
                    && s.getTitle() != null && s.getTitle().startsWith(titlePrefix)) {
                return s;
            }
        }
        return null;
    }

    private static boolean isUniform(BufferedImage img) {
        int first = img.getRGB(0, 0);
        for (int y = 0; y < img.getHeight(); y += 2) {
            for (int x = 0; x < img.getWidth(); x += 2) {
                if (img.getRGB(x, y) != first) return false;
            }
        }
        return true;
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
        return task.get(900, TimeUnit.SECONDS);
    }
}
