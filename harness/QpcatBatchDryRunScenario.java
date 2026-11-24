import qupath.ext.qpcat.batch.BatchYamlParser;
import qupath.ext.qpcat.batch.BatchYamlValidator;
import qupath.ext.qpcat.batch.ProgressEmitter;
import qupath.ext.qpcat.batch.ValidationIssue;
import qupath.ext.qpcat.batch.YamlBatchOrchestrator;
import qupath.ext.qpcat.model.MeasurementNames;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.ProjectImageEntry;

import javafx.application.Platform;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Batch dry runs against a real project (read only): a rule written with a bare
 * marker is refused with E010 and its measurements listed, an omitted
 * clustering.measurements prints the default list, and a bare image-name
 * string selects that image only.
 *
 * <p>Usage: {@code qp-gui QpcatBatchDryRunScenario.java <repo> <project dir>}
 */
public final class QpcatBatchDryRunScenario {

    private static int failures = 0;

    private static void ok(String s) { System.out.println("[PASS] " + s); }
    private static void bad(String s) { System.out.println("[FAIL] " + s); failures++; }
    private static void note(String s) { System.out.println("[....] " + s); }
    private static void check(boolean c, String s) { if (c) ok(s); else bad(s); }

    /** Collects every emitted line. */
    private static final class Capture implements ProgressEmitter {
        final List<String> lines = new ArrayList<>();

        @Override
        public void emit(Level level, String message) {
            lines.add(level + " " + message);
        }

        @Override
        public void emitRow(Level level, int index, int total, Map<String, Object> fields,
                            String tail) {
            lines.add(level + " " + fields + " " + tail);
        }

        boolean any(String level, String text) {
            return lines.stream().anyMatch(l -> l.startsWith(level + " ") && l.contains(text));
        }
    }

    public static void main(String[] args) throws Exception {
        Path projectFile = Path.of(args[0]).resolve("project.qpproj");
        startFx();
        onFx(QuPathGUI::createHiddenInstance);
        Project<BufferedImage> project = ProjectIO.loadProject(projectFile.toFile(),
                BufferedImage.class);
        List<ProjectImageEntry<BufferedImage>> withCells = new ArrayList<>();
        String meanName = null;
        for (ProjectImageEntry<BufferedImage> e : project.getImageList()) {
            var dets = e.readHierarchy().getDetectionObjects();
            if (dets.isEmpty()) continue;
            withCells.add(e);
            if (meanName == null) {
                PathObject first = dets.iterator().next();
                for (String n : first.getMeasurements().keySet()) {
                    if (n.toLowerCase(Locale.ROOT).contains("mean") && !n.contains("QPCAT")
                            && !MeasurementNames.split(n).marker().isEmpty()) {
                        meanName = n;
                        break;
                    }
                }
            }
            if (withCells.size() == 2) break;
        }
        if (withCells.size() < 2 || meanName == null) {
            bad("project has two images with cells and a 'mean' measurement");
            finish();
        }
        String marker = MeasurementNames.split(meanName).marker();
        String images = "['" + withCells.get(0).getImageName() + "', '"
                + withCells.get(1).getImageName() + "']";
        note("images " + images + "; measurement '" + meanName + "', marker '" + marker + "'");

        Run bare = dryRun("scope:\n  projects: ['" + projectFile + "']\n  images: " + images
                + "\nphenotyping:\n  rules:\n    - name: T\n      require_markers: ['" + marker
                + "']\n");
        check(bare.exit == 2, "a rule naming the bare marker stops the dry run (exit " + bare.exit
                + ")");
        check(bare.capture.any("ERROR", "E010") && bare.capture.any("ERROR", meanName),
                "the E010 message lists the measurement '" + meanName + "'");

        Capture defaults = dryRun("scope:\n  projects: ['" + projectFile + "']\n  images: "
                + images + "\nclustering: { type: leiden }\nphenotyping:\n  rules:\n"
                + "    - name: T\n      require_markers: ['" + meanName + "']\n").capture;
        check(defaults.any("INFO", "clustering.measurements not set"),
                "the dry run prints the default clustering measurements");
        check(!defaults.any("ERROR", ""), "full measurement names pass the dry run");
        check(defaults.lines.stream().noneMatch(l -> l.contains("not set") && l.contains("QPCAT")),
                "the default list leaves QP-CAT's own measurements out");

        Capture one = dryRun("scope:\n  projects: ['" + projectFile + "']\n  images: '"
                + withCells.get(0).getImageName() + "'\n").capture;
        check(one.any("INFO", "1 project(s), 1 image(s)"),
                "a bare image-name string selects that one image");

        for (String l : defaults.lines) note(l);
        finish();
    }

    private record Run(Capture capture, int exit) {}

    private static Run dryRun(String body) throws Exception {
        String yaml = "version: '1.0'\n" + body;
        BatchYamlParser.ParseOutcome po = BatchYamlParser.parseString(yaml);
        Capture c = new Capture();
        for (ValidationIssue i : po.getIssues()) c.lines.add("ERROR " + i.format());
        for (ValidationIssue i : BatchYamlValidator.validate(po.getSchema()).getIssues()) {
            c.lines.add((i.getSeverity() == ValidationIssue.Severity.ERROR ? "ERROR " : "WARN ")
                    + i.format());
        }
        var outcome = YamlBatchOrchestrator.runBatch(po.getSchema(),
                new YamlBatchOrchestrator.BatchRunOptions().setDryRun(true).setYamlContent(yaml),
                c);
        return new Run(c, outcome.getExitCode());
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
        return task.get(600, TimeUnit.SECONDS);
    }
}
