import qupath.ext.qpcat.batch.BatchYamlParser;
import qupath.ext.qpcat.batch.ProgressEmitter;
import qupath.ext.qpcat.batch.ValidationIssue;
import qupath.ext.qpcat.batch.YamlBatchOrchestrator;
import qupath.ext.qpcat.controller.ClusteringWorkflow;
import qupath.ext.qpcat.model.CellRef;
import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.ext.qpcat.model.MeasurementNames;
import qupath.ext.qpcat.model.SavedClusteringResult;
import qupath.ext.qpcat.service.CellMatcher;
import qupath.ext.qpcat.service.InputAudit;
import qupath.ext.qpcat.service.SavedResultApplier;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.ProjectImageEntry;

import javafx.application.Platform;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Imported data that repeats identities, end to end on a COPY of a real project:
 * one image's detections exported to GeoJSON and imported again (QuPath keeps the
 * ids, so every id repeats), and a second project entry with the same name as
 * another image.
 *
 * <p>Checks that the input audit refuses clustering on the repeated image with the
 * count, that the clustering workflow itself refuses before any work, that applying a
 * saved result leaves the image unchanged and says why, that the batch dry run
 * reports E027, and that two same-named entries are told apart by entry id.
 *
 * <p>Usage: {@code qp-gui QpcatImportedIdentityScenario.java <repo> <project dir> <work dir>}
 * (the project is copied into the work dir; only the copy is changed).
 */
public final class QpcatImportedIdentityScenario {

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
        Path source = Path.of(args[0]);
        Path work = Path.of(args[1]).resolve("imported-identity");
        copyProject(source, work);
        Path projectFile = work.resolve("project.qpproj");

        startFx();
        QuPathGUI qupath = onFx(QuPathGUI::createHiddenInstance);
        Project<BufferedImage> project = ProjectIO.loadProject(projectFile.toFile(),
                BufferedImage.class);
        onFx(() -> { qupath.setProject(project); return null; });

        List<ProjectImageEntry<BufferedImage>> withCells = new ArrayList<>();
        for (ProjectImageEntry<BufferedImage> e : project.getImageList()) {
            if (!e.readHierarchy().getDetectionObjects().isEmpty()) withCells.add(e);
            if (withCells.size() == 2) break;
        }
        if (withCells.size() < 2) {
            bad("project has two images with cells");
            finish();
        }
        ProjectImageEntry<BufferedImage> a = withCells.get(0);
        ProjectImageEntry<BufferedImage> b = withCells.get(1);

        // ---- 1. the same objects imported twice ----
        ImageData<BufferedImage> dataA = a.readImageData();
        List<PathObject> originals = new ArrayList<>(dataA.getHierarchy().getDetectionObjects());
        Path geojson = work.resolve("a_detections.geojson");
        PathIO.exportObjectsAsGeoJSON(geojson, originals);
        List<PathObject> imported = PathIO.readObjects(geojson);
        Set<String> originalIds = new HashSet<>();
        for (PathObject p : originals) originalIds.add(CellRef.idOf(p));
        check(!imported.isEmpty() && originalIds.contains(CellRef.idOf(imported.get(0))),
                "QuPath's GeoJSON import keeps the object ids (the premise)");
        dataA.getHierarchy().addObjects(imported);
        a.saveImageData(dataA);
        int n = originals.size();
        note(a.getImageName() + ": " + n + " detections, then the same " + imported.size()
                + " imported again");

        var liveIndex = CellMatcher.liveIndex(
                a.readHierarchy().getDetectionObjects(), true);
        check(liveIndex.repeats() == imported.size(),
                "every imported copy is counted as a repeated id (" + liveIndex.repeats() + ")");

        InputAudit audit = InputAudit.of(InputAudit.Workflow.CLUSTERING);
        audit.addImage(a.getID(), a.getImageName(), a.readHierarchy().getDetectionObjects(), null);
        String refusal = audit.report().refusalMessage();
        check(refusal != null && refusal.contains(String.format(Locale.ROOT, "%,d detection(s) repeat",
                imported.size())), "the input audit refuses clustering and names the count");

        // ---- 2. the clustering workflow itself refuses before any work ----
        String meanName = firstMean(a);
        ClusteringConfig config = new ClusteringConfig();
        config.setSelectedMeasurements(List.of(meanName));
        try {
            new ClusteringWorkflow(qupath).runProjectClustering(List.of(a, b), config, null);
            bad("project clustering over the repeated image was refused");
        } catch (IOException e) {
            check(e.getMessage() != null && e.getMessage().contains("repeat another detection's object id"),
                    "project clustering over the repeated image is refused before any work: "
                            + firstLine(e.getMessage()));
        }

        // ---- 3. applying a saved result leaves the image unchanged ----
        CellRef[] refs = new CellRef[n];
        for (int i = 0; i < n; i++) refs[i] = CellRef.of(a.getID(), a.getImageName(), originals.get(i));
        ClusteringResult r = new ClusteringResult(new int[n], 1, new double[n][2],
                new double[1][1], new String[]{meanName});
        r.setCellRefs(refs);
        SavedClusteringResult saved = SavedClusteringResult.fromResult(r, "identity_check",
                "kmeans", "zscore", "umap");
        SavedResultApplier.ApplyReport report = SavedResultApplier.apply(qupath, saved, true, false);
        check(report.imagesRefused == 1 && report.cellsMatched == 0,
                "applying a saved result to the repeated image changes nothing ("
                        + report.imagesRefused + " refused, " + report.cellsMatched + " labeled)");
        check(report.perImage.stream().anyMatch(l -> l.contains("not changed")),
                "the apply report says why: " + (report.perImage.isEmpty() ? "" : report.perImage.get(0)));
        boolean anyApplied = a.readHierarchy().getDetectionObjects().stream()
                .anyMatch(p -> p.getPathClass() != null
                        && p.getPathClass().toString().startsWith("identity_check"));
        check(!anyApplied, "no cell on the repeated image carries the saved result's class");

        // ---- 4. the batch dry run reports E027 ----
        Capture dry = dryRun("scope:\n  projects: ['" + projectFile + "']\n  images: ['"
                + a.getImageName() + "']\nclustering:\n  type: kmeans\n  measurements: ['"
                + meanName + "']\n");
        check(dry.any("ERROR", "E027"), "the batch dry run reports E027 for the repeated image");

        // ---- 5. two entries with one name ----
        ProjectImageEntry<BufferedImage> twin = project.addImage(b.getServerBuilder());
        twin.setImageName(b.getImageName());
        twin.saveImageData(b.readImageData());
        project.syncChanges();
        note("added entry " + twin.getID() + " named '" + twin.getImageName() + "' beside entry "
                + b.getID());
        InputAudit pair = InputAudit.of(InputAudit.Workflow.CROP_TABLE_EXPORT);
        pair.addImage(b.getID(), b.getImageName(), b.readHierarchy().getDetectionObjects(), null);
        pair.addImage(twin.getID(), twin.getImageName(), twin.readHierarchy().getDetectionObjects(), null);
        check(pair.report().notices().stream().anyMatch(s -> s.contains("share a name")),
                "a run over two same-named images says so");

        ClusteringResult two = new ClusteringResult(new int[2], 1, new double[2][2],
                new double[1][1], new String[]{meanName});
        PathObject cellB = b.readHierarchy().getDetectionObjects().iterator().next();
        PathObject cellTwin = twin.readHierarchy().getDetectionObjects().iterator().next();
        two.setCellRefs(new CellRef[]{CellRef.of(b.getID(), b.getImageName(), cellB),
                CellRef.of(twin.getID(), twin.getImageName(), cellTwin)});
        check(two.cellsPerImage().size() == 2,
                "cells per image keeps the two same-named entries apart: " + two.cellsPerImage());

        Capture dupNames = dryRun("scope:\n  projects: ['" + projectFile + "']\n  images: ['"
                + b.getImageName() + "']\n");
        check(dupNames.any("ERROR", "E024"), "the batch refuses a scope whose images share a name (E024)");

        finish();
    }

    private static String firstMean(ProjectImageEntry<BufferedImage> e) throws IOException {
        PathObject first = e.readHierarchy().getDetectionObjects().iterator().next();
        for (String m : first.getMeasurements().keySet()) {
            if (m.toLowerCase(Locale.ROOT).contains("mean") && !m.contains("QPCAT")
                    && !MeasurementNames.split(m).marker().isEmpty()) {
                return m;
            }
        }
        return first.getMeasurements().keySet().iterator().next();
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        String head = i < 0 ? s : s.substring(0, i);
        int j = s.indexOf("\n\n");
        return j < 0 ? head : head + " / " + s.substring(j + 2, Math.min(s.length(), j + 90));
    }

    private static Capture dryRun(String body) throws Exception {
        String yaml = "version: '1.0'\n" + body;
        BatchYamlParser.ParseOutcome po = BatchYamlParser.parseString(yaml);
        Capture c = new Capture();
        for (ValidationIssue i : po.getIssues()) c.lines.add("ERROR " + i.format());
        if (po.getSchema() != null) {
            YamlBatchOrchestrator.runBatch(po.getSchema(),
                    new YamlBatchOrchestrator.BatchRunOptions().setDryRun(true).setYamlContent(yaml),
                    c);
        }
        for (String l : c.lines) {
            if (l.startsWith("ERROR") || l.startsWith("WARN")) note("dry run: " + firstLine(l));
        }
        return c;
    }

    /** Copy project.qpproj and data/ (image URIs are absolute, so the images stay where they are). */
    private static void copyProject(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            try (Stream<Path> s = Files.walk(target)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(target);
        Files.copy(source.resolve("project.qpproj"), target.resolve("project.qpproj"));
        Path data = source.resolve("data");
        try (Stream<Path> s = Files.walk(data)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path to = target.resolve("data").resolve(data.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(p, to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
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
