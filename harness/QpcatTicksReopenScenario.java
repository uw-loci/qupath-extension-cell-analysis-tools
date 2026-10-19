import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.ext.qpcat.service.ClusteringConfigManager;
import qupath.ext.qpcat.ui.ClusteringDialog;
import qupath.ext.qpcat.ui.MeasurementSelectionPane;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import javafx.application.Platform;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;

/**
 * Reopens the Clustering dialog after a stored "All project images" run and
 * checks that its measurement ticks came back. The list for a project scope is
 * read off the FX thread after the ticks are restored, and used to replace them.
 *
 * <p>Usage: {@code qp-gui QpcatTicksReopenScenario.java <repo> <project> [open|closed]}
 * (whether an image is open in the viewer). Overwrites the project's stored
 * last-run settings for the normal clustering mode.
 */
public final class QpcatTicksReopenScenario {
    public static void main(String[] args) throws Exception {
        CountDownLatch up = new CountDownLatch(1);
        Platform.startup(up::countDown);
        up.await(60, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
        QuPathGUI qupath = onFx(QuPathGUI::createHiddenInstance);
        Project<BufferedImage> project = ProjectIO.loadProject(
                Path.of(args[0]).resolve("project.qpproj").toFile(), BufferedImage.class);
        onFx(() -> { qupath.setProject(project); return null; });
        boolean openImage = args.length > 1 && "open".equals(args[1]);
        if (openImage) onFx(() -> qupath.openImageEntry(project.getImageList().get(0)));

        List<String> wanted = List.of("Cell: CD3 mean", "Cell: CD8 mean", "Nucleus: PanCK mean");
        ClusteringConfig last = new ClusteringConfig();
        last.setAlgorithm(ClusteringConfig.Algorithm.KMEANS);
        last.setSelectedMeasurements(wanted);
        last.setClusterEntireProject(true);           // "All project images"
        last.setScopeImageNames(List.of());
        ClusteringConfigManager.saveLastRun(project, "cluster", last);

        ClusteringDialog dlg = onFx(() -> new ClusteringDialog(qupath));
        onFx(() -> { dlg.show(); return null; });
        Thread.sleep(15000);   // the scope read is off the FX thread
        Field f = ClusteringDialog.class.getDeclaredField("measurementPane");
        f.setAccessible(true);
        MeasurementSelectionPane pane = (MeasurementSelectionPane) f.get(dlg);
        int rows = onFx(() -> pane.getAllMeasurements().size());
        List<String> ticked = onFx(pane::getSelected);
        System.out.println("[....] image open in viewer: " + openImage + "; rows " + rows + ", ticked " + ticked);
        boolean ok = ticked.containsAll(wanted) && ticked.size() == wanted.size();
        System.out.println(ok
                ? "[PASS] reopened dialog restored the last run's ticks"
                : "[FAIL] reopened dialog lost the last run's ticks");
        System.out.println();
        System.out.println(ok ? "SCENARIO PASSED" : "SCENARIO FAILED (1)");
        Platform.exit();
        System.exit(ok ? 0 : 1);
    }
    private static <T> T onFx(Callable<T> c) throws Exception {
        FutureTask<T> t = new FutureTask<>(c); Platform.runLater(t); return t.get(300, TimeUnit.SECONDS);
    }
}
