import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.embed.swing.SwingFXUtils;
import qupath.ext.qpcat.model.SavedClusteringResult;
import qupath.ext.qpcat.service.ClusteringResultManager;
import qupath.ext.qpcat.service.SavedResultApplier;
import qupath.ext.qpcat.ui.ClusteringDialog;
import qupath.ext.qpcat.ui.CropTableExportDialog;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.ProjectImageEntry;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Render QP-CAT dialogs and saved-result tabs to PNG from a hidden QuPathGUI,
 * for documentation.
 *
 * <p>A dialog screenshot taken by hand goes stale the moment a control moves,
 * and nothing says so. Rendering it from the real dialog, against a real
 * project, means the figure is as current as the build that produced it.
 *
 * <p>The dialogs are real {@code Stage}s, so they do flash on screen under
 * WSLg. There is no way around that: a JavaFX node has to be in a shown scene
 * before it has a layout to snapshot.
 *
 * <p>args: projectDir outputDir [spec ...]
 *
 * <p>A spec is one of:
 * <ul>
 * <li>{@code crop-table} -- a dialog this scenario knows how to open;
 * <li>{@code apply:<savedResultName>} -- write that saved result's cluster
 *     labels onto the detections before anything is shot. Tabs that read the
 *     project rather than the result (the 3D View) otherwise show whatever
 *     classifications the project happens to carry now, which after any later
 *     run is not the result named in the title bar. Applies run first, in the
 *     order given;
 * <li>{@code <figure>=<savedResultName>=<tab name>[=<width>x<height>]} -- a tab
 *     of a saved clustering result.
 * </ul>
 * Result names are not hard-coded here because the mapping from a figure to
 * the run behind it belongs to the document, not to this repo.
 */
public final class QpcatDialogShotScenario {

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

    /** Dialogs this scenario knows how to open, by the name used on the command line. */
    private static final List<String> KNOWN = List.of("crop-table");

    /** A tab of a saved clustering result, and the figure file it becomes. */
    private record TabShot(String figure, String tab, int width, int height) {}

    public static void main(String[] args) throws Exception {
        Path projectDir = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        List<String> specs = args.length > 2
                ? List.of(args).subList(2, args.length)
                : KNOWN;
        Files.createDirectories(outDir);

        // Group the result tabs by result, so a result is loaded once however
        // many of its tabs are wanted. Loading one re-reads every cell.
        List<String> dialogs = new ArrayList<>();
        List<String> applies = new ArrayList<>();
        Map<String, List<TabShot>> byResult = new LinkedHashMap<>();
        for (String spec : specs) {
            if (spec.startsWith("apply:")) {
                applies.add(spec.substring("apply:".length()));
                continue;
            }
            String[] parts = spec.split("=");
            if (parts.length < 3) {
                dialogs.add(spec);
                continue;
            }
            int w = 1600;
            int h = 1150;
            if (parts.length >= 4) {
                String[] wh = parts[3].split("x");
                w = Integer.parseInt(wh[0]);
                h = Integer.parseInt(wh[1]);
            }
            byResult.computeIfAbsent(parts[1], k -> new ArrayList<>())
                    .add(new TabShot(parts[0], parts[2], w, h));
        }

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
        if (onFx(qupath::getImageData) == null) {
            bad("no image opened -- every dialog would render empty");
            System.exit(1);
        }
        for (String name : applies) {
            applySavedResult(qupath, project, name);
        }
        if (!dialogs.isEmpty()) ensureClassifications(project);
        note("project ready: " + entries.size() + " image(s), image "
                + entries.get(0).getImageName() + " open");

        for (String name : dialogs) {
            switch (name) {
                case "crop-table" -> shootDialog(qupath, outDir, name,
                        "QP-CAT - Export Cell Crops + Feature Table",
                        () -> CropTableExportDialog.show(qupath));
                default -> bad("unknown dialog '" + name + "'; known: " + KNOWN);
            }
        }
        for (Map.Entry<String, List<TabShot>> e : byResult.entrySet()) {
            shootResultTabs(qupath, outDir, e.getKey(), e.getValue());
        }

        System.out.println();
        System.out.println(failures == 0 ? "SCENARIO PASSED" : "SCENARIO FAILED (" + failures + ")");
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * Write a saved result's cluster labels back onto the detections, which is
     * what the "Apply saved QP-CAT result to detections" menu item does.
     *
     * <p>The labels are written as bare "Cluster N" classes, which is the state
     * a finished run leaves behind and therefore the state these figures
     * document. The menu item's own apply namespaces every class with the
     * result name instead, so that labels from two results can coexist; in the
     * 3D View's narrow CLASSES column that renders as seven identical
     * truncated rows with the counts pushed out of sight.
     *
     * <p>Must run off the JavaFX thread: it reads and writes every image's
     * data and blocks while it does.
     */
    private static void applySavedResult(QuPathGUI qupath, Project<BufferedImage> project,
                                         String resultName) {
        try {
            SavedClusteringResult saved =
                    ClusteringResultManager.loadSavedResult(project, resultName);
            SavedResultApplier.ApplyReport report =
                    SavedResultApplier.applyRenamed(qupath, saved, Map.of());
            if (report.isError()) {
                bad("apply " + resultName + ": " + report.error);
                return;
            }
            if (report.cellsUnmatched > 0) {
                bad("apply " + resultName + ": " + report.cellsUnmatched
                        + " saved cell(s) did not match a detection");
                return;
            }
            ok("applied " + resultName + ": " + report.cellsMatched + " cell(s) over "
                    + report.imagesProcessed + " image(s)");
        } catch (Exception e) {
            bad("apply " + resultName + ": " + e);
        }
    }

    /**
     * Reopen a saved clustering result and snapshot each wanted tab.
     *
     * <p>This drives the real "View Past Results" menu action rather than
     * calling the results window directly, because only that path rebuilds the
     * set of images the result was clustered over. The public entry point that
     * takes a result object passes no scope, and the 3D tab then shows the open
     * image's cells alone.
     */
    private static void shootResultTabs(QuPathGUI qupath, Path outDir,
                                        String resultName, List<TabShot> shots) throws Exception {
        Platform.runLater(() -> ClusteringDialog.showPastResultsChooser(qupath));
        Stage chooser = awaitStage("QPCAT - View Past Results", 300);
        if (chooser == null) {
            bad(resultName + ": the View Past Results chooser never appeared");
            return;
        }
        String picked = onFx(() -> pickResult(chooser, resultName));
        if (picked == null) {
            bad(resultName + ": not offered by the chooser in this project");
            onFx(() -> {
                chooser.close();
                return null;
            });
            return;
        }
        note("chose '" + picked + "'");

        // The chooser's OK handler loads the result on the FX thread, so every
        // call below queues behind it. A big result takes a while to come back.
        Stage results = awaitStage("QPCAT - Results", 1800);
        if (results == null) {
            bad(resultName + ": no results window appeared");
            return;
        }
        note("window: " + onFx(results::getTitle));

        for (TabShot shot : shots) {
            String tabs = onFx(() -> selectTab(results, shot.tab()));
            if (tabs != null) {
                bad(shot.figure() + ": no tab named '" + shot.tab() + "'. Tabs: " + tabs);
                continue;
            }
            onFx(() -> {
                results.setWidth(shot.width());
                results.setHeight(shot.height());
                return null;
            });
            // A tab builds its content when it is first selected, and several
            // of these build a chart or a montage, so give it real time.
            Thread.sleep(4000);
            snapshot(results, outDir, shot.figure());
        }
        onFx(() -> {
            results.close();
            return null;
        });
    }

    /**
     * Select the result in the chooser's combo box and press OK.
     *
     * @return the item chosen, or null when the result is not in the list
     */
    private static String pickResult(Stage chooser, String resultName) {
        Parent root = chooser.getScene().getRoot();
        DialogPane pane = (DialogPane) find(root, DialogPane.class);
        ComboBox<?> combo = (ComboBox<?>) find(root, ComboBox.class);
        if (pane == null || combo == null) return null;
        String match = null;
        for (Object item : combo.getItems()) {
            if (item != null && item.toString().startsWith(resultName)) {
                match = item.toString();
                break;
            }
        }
        if (match == null) return null;
        @SuppressWarnings("unchecked")
        ComboBox<Object> typed = (ComboBox<Object>) combo;
        typed.setValue(match);
        Node okButton = pane.lookupButton(ButtonType.OK);
        if (!(okButton instanceof Button b)) return null;
        b.fire();
        return match;
    }

    /**
     * Select a tab by its text.
     *
     * @return null on success, or a comma-separated list of the tabs that do
     *         exist. Naming what is there turns a failed run into a fixed spec
     *         rather than a guess.
     */
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

    /** Depth-first search for the first node of a type. */
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

    /**
     * Open a dialog, wait for its window, snapshot it, close it.
     *
     * <p>The dialog is found by its title rather than returned by the opener,
     * because QP-CAT's dialogs are static {@code show()} methods that hand
     * nothing back. Titles are unique by project convention, which is what
     * makes this work at all.
     */
    private static void shootDialog(QuPathGUI qupath, Path outDir, String name,
                                    String titlePrefix, Runnable opener) throws Exception {
        Platform.runLater(opener);
        Stage stage = awaitStage(titlePrefix, 150);
        if (stage == null) {
            bad(name + ": no window titled '" + titlePrefix + "' appeared");
            return;
        }
        Thread.sleep(1200);
        onFx(() -> {
            stage.sizeToScene();
            return null;
        });
        snapshot(stage, outDir, name);
        onFx(() -> {
            stage.close();
            return null;
        });
    }

    /** Lay the window out, snapshot its scene, and check the result is a picture. */
    private static void snapshot(Stage stage, Path outDir, String name) throws Exception {
        // A dialog snapshotted on the pulse it opened on is a half-laid-out
        // dialog, and the figure silently shows that.
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

    /**
     * The export dialog reads classifications, so give the cells some -- but
     * only when the project has none. A project that arrives already
     * classified is carrying the state its figures were made from, and
     * overwriting it would quietly change what the figures show.
     */
    private static void ensureClassifications(Project<BufferedImage> project) throws Exception {
        String[] markers = {"Cell: PanCK mean", "Cell: aSMA mean", "Cell: CD3 mean",
                "Cell: CD20 mean", "Cell: CD68 mean"};
        String[] labels = {"Tumor", "Fibroblast", "T cell", "B cell", "Macrophage"};
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            ImageData<BufferedImage> data = entry.readImageData();
            boolean preclassified = false;
            for (PathObject det : data.getHierarchy().getDetectionObjects()) {
                if (det.getPathClass() != null) {
                    preclassified = true;
                    break;
                }
            }
            if (preclassified) {
                note("leaving existing classifications alone on " + entry.getImageName());
                data.getServer().close();
                continue;
            }
            boolean changed = false;
            for (PathObject det : data.getHierarchy().getDetectionObjects()) {
                if (det.getPathClass() != null) continue;
                int best = -1;
                double bestV = 12.0;
                for (int i = 0; i < markers.length; i++) {
                    Number v = det.getMeasurements().get(markers[i]);
                    if (v != null && v.doubleValue() > bestV) {
                        bestV = v.doubleValue();
                        best = i;
                    }
                }
                if (best >= 0) {
                    det.setPathClass(PathClass.fromString(labels[best]));
                    changed = true;
                }
            }
            if (changed) entry.saveImageData(data);
            data.getServer().close();
        }
        project.syncChanges();
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
