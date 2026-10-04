import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.embed.swing.SwingFXUtils;
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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Render QP-CAT dialogs to PNG from a hidden QuPathGUI, for documentation.
 *
 * <p>A dialog screenshot taken by hand goes stale the moment a control moves,
 * and nothing says so. Rendering it from the real dialog, against a real
 * project, means the figure is as current as the build that produced it.
 *
 * <p>The dialogs are real {@code Stage}s, so they do flash on screen under
 * WSLg. There is no way around that: a JavaFX node has to be in a shown scene
 * before it has a layout to snapshot.
 *
 * <p>args: projectDir outputDir [dialogName ...]
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

    public static void main(String[] args) throws Exception {
        Path projectDir = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        List<String> wanted = args.length > 2
                ? List.of(args).subList(2, args.length)
                : KNOWN;
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
        if (onFx(qupath::getImageData) == null) {
            bad("no image opened -- every dialog would render empty");
            System.exit(1);
        }
        ensureClassifications(project);
        note("project ready: " + entries.size() + " image(s), image "
                + entries.get(0).getImageName() + " open");

        for (String name : wanted) {
            switch (name) {
                case "crop-table" -> shoot(qupath, outDir, name,
                        "QP-CAT - Export Cell Crops + Feature Table",
                        () -> CropTableExportDialog.show(qupath));
                default -> bad("unknown dialog '" + name + "'; known: " + KNOWN);
            }
        }

        System.out.println();
        System.out.println(failures == 0 ? "SCENARIO PASSED" : "SCENARIO FAILED (" + failures + ")");
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * Open a dialog, wait for its window, snapshot it, close it.
     *
     * <p>The dialog is found by its title rather than returned by the opener,
     * because QP-CAT's dialogs are static {@code show()} methods that hand
     * nothing back. Titles are unique by project convention, which is what
     * makes this work at all.
     */
    private static void shoot(QuPathGUI qupath, Path outDir, String name,
                              String titlePrefix, Runnable opener) throws Exception {
        Platform.runLater(opener);

        Stage stage = null;
        for (int i = 0; i < 150 && stage == null; i++) {
            Thread.sleep(100);
            stage = onFx(() -> findStage(titlePrefix));
        }
        if (stage == null) {
            bad(name + ": no window titled '" + titlePrefix + "' appeared");
            return;
        }
        final Stage found = stage;

        // Let the layout settle: a dialog snapshotted on the pulse it opened on
        // is a half-laid-out dialog, and the figure silently shows that.
        Thread.sleep(1200);
        onFx(() -> {
            found.sizeToScene();
            found.getScene().getRoot().applyCss();
            found.getScene().getRoot().layout();
            return null;
        });
        Thread.sleep(400);

        WritableImage shot = onFx(() -> {
            Scene scene = found.getScene();
            return scene.snapshot(null);
        });
        onFx(() -> {
            found.close();
            return null;
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
                    + ", too small to be the dialog");
            return;
        }
        if (isUniform(img)) {
            bad(name + ": snapshot is a uniform blank");
            return;
        }
        ok(name + ": " + img.getWidth() + "x" + img.getHeight() + " -> " + file);
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

    /** The export dialog reads classifications, so give the cells some. */
    private static void ensureClassifications(Project<BufferedImage> project) throws Exception {
        String[] markers = {"Cell: PanCK mean", "Cell: aSMA mean", "Cell: CD3 mean",
                "Cell: CD20 mean", "Cell: CD68 mean"};
        String[] labels = {"Tumor", "Fibroblast", "T cell", "B cell", "Macrophage"};
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            ImageData<BufferedImage> data = entry.readImageData();
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
        return task.get(120, TimeUnit.SECONDS);
    }
}
