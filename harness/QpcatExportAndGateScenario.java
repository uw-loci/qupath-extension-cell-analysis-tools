import javafx.application.Platform;
import qupath.ext.qpcat.model.GateSet;
import qupath.ext.qpcat.service.CropTableExporter;
import qupath.ext.qpcat.service.GateApplier;
import qupath.ext.qpcat.service.GateStore;
import qupath.ext.qpcat.model.CellRef;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.ProjectImageEntry;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Real-QuPath scenario: the crop/table export and the gate save/replay path,
 * driven against a project with real detections and real measurements.
 *
 * <p>Needs a QuPathGUI, which is why no unit test reaches it: CellCropService
 * resolves image servers and display settings through the GUI.
 * QuPathGUI.createHiddenInstance() provides one with no visible window.
 *
 * <p>args: projectDir outputDir
 */
public final class QpcatExportAndGateScenario {

    private static int failures = 0;

    private static void ok(String what) {
        System.out.println("[PASS] " + what);
    }

    private static void bad(String what) {
        System.out.println("[FAIL] " + what);
        failures++;
    }

    private static void check(boolean condition, String what) {
        if (condition) ok(what);
        else bad(what);
    }

    private static void note(String what) {
        System.out.println("[....] " + what);
    }

    public static void main(String[] args) throws Exception {
        Path projectDir = Path.of(args[0]);
        Path outDir = Path.of(args[1]);

        startFx();
        QuPathGUI qupath = onFx(QuPathGUI::createHiddenInstance);
        note("hidden QuPathGUI up, QuPath " + QuPathGUI.getVersion());

        Project<BufferedImage> project = ProjectIO.loadProject(
                projectDir.resolve("project.qpproj").toFile(), BufferedImage.class);
        onFx(() -> {
            qupath.setProject(project);
            return null;
        });
        List<ProjectImageEntry<BufferedImage>> entries =
                new ArrayList<>(project.getImageList());
        note("project loaded, " + entries.size() + " image(s)");

        onFx(() -> qupath.openImageEntry(entries.get(0)));
        // The viewer needs a beat to actually attach the image data.
        for (int i = 0; i < 100 && onFx(qupath::getImageData) == null; i++) {
            Thread.sleep(100);
        }
        check(onFx(qupath::getImageData) != null,
                "an image is open in the hidden viewer");

        int classified = classifyByBrightestMarker(project);
        note("classified " + classified + " cell(s) by brightest lineage marker");

        List<String> measurements = List.of(
                "Cell: PanCK mean", "Cell: Ki67 mean", "Cell: aSMA mean",
                "Cell: CD3 mean", "Cell: CD8 mean", "Cell: CD20 mean",
                "Cell: CD68 mean", "Nucleus: Area");

        exportScenario(qupath, entries, outDir, measurements);
        gateScenario(qupath, project, entries, outDir, measurements);

        System.out.println();
        System.out.println(failures == 0 ? "SCENARIO PASSED" : "SCENARIO FAILED (" + failures + ")");
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }

    // ==================== the crop + table export ====================

    private static void exportScenario(QuPathGUI qupath,
                                       List<ProjectImageEntry<BufferedImage>> entries,
                                       Path outDir, List<String> measurements)
            throws Exception {
        System.out.println();
        System.out.println("--- crop + feature table export ---");

        CropTableExporter.Options opts = new CropTableExporter.Options();
        opts.outputDir = outDir.resolve("crop_table");
        opts.measurements = measurements;
        opts.formats = List.of(CropTableExporter.Format.TSV, CropTableExporter.Format.CSV);
        opts.writeCrops = true;
        opts.globalCap = 180;
        opts.minPerClass = 10;
        opts.seed = 42;

        CropTableExporter.Result result = CropTableExporter.export(
                qupath, entries, opts, m -> {}, () -> false);
        note("exported " + result.cellsWritten + " row(s), " + result.cropsWritten
                + " crop(s), " + result.classNames.size() + " class(es), "
                + result.imagesUsed + " image(s)");

        check(result.cellsWritten > 0, "the export wrote rows");
        check(result.imagesUsed == entries.size(),
                "every image in scope contributed (" + result.imagesUsed + "/"
                        + entries.size() + ")");

        // ---- the TSV, against TraitHorizon's documented contract ----
        Path tsv = opts.outputDir.resolve("features.tsv");
        check(Files.exists(tsv), "features.tsv exists");
        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
        check(lines.size() == result.cellsWritten + 1,
                "TSV has one header + one row per exported cell ("
                        + (lines.size() - 1) + " vs " + result.cellsWritten + ")");

        byte[] head = Files.readAllBytes(tsv);
        check(!(head.length > 2 && (head[0] & 0xFF) == 0xEF),
                "TSV carries no byte-order mark");

        String[] header = lines.get(0).split("\t", -1);
        check("filename".equals(header[0]), "first column is 'filename'");
        check(new LinkedHashSet<>(List.of(header)).size() == header.length,
                "every column name is unique");

        Set<String> names = new LinkedHashSet<>();
        int numericBad = 0;
        int missing = 0;
        int ragged = 0;
        int cropMissing = 0;
        int classIdxBad = 0;
        int classIdxCol = indexOf(header, "class_index");
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split("\t", -1);
            if (cells.length != header.length) ragged++;
            if (!names.add(cells[0])) {
                bad("duplicate filename in the table: " + cells[0]);
            }
            if (!Files.exists(opts.outputDir.resolve("images").resolve(cells[0]))) {
                cropMissing++;
            }
            for (int c = 0; c < cells.length; c++) {
                if (cells[c].isEmpty()) missing++;
            }
            // Every measurement column must parse as a number for d3.autoType.
            for (int c = 1; c <= measurements.size(); c++) {
                try {
                    Double.parseDouble(cells[c]);
                } catch (NumberFormatException e) {
                    numericBad++;
                }
            }
            if (classIdxCol >= 0) {
                int idx = Integer.parseInt(cells[classIdxCol]);
                if (idx < 0 || idx >= result.classNames.size()) classIdxBad++;
            }
        }
        check(ragged == 0, "no ragged row (all have " + header.length + " cells)");
        check(names.size() == result.cellsWritten, "every filename is unique");
        check(missing == 0, "no empty cell anywhere (" + missing + " found)");
        check(numericBad == 0, "every measurement value parses as a number");
        check(cropMissing == 0,
                "every row's crop is on disk (" + cropMissing + " missing)");
        check(classIdxBad == 0, "every class_index is in range");

        // ---- the crops are real images, not black squares ----
        int decoded = 0;
        int blank = 0;
        List<String> sample = new ArrayList<>(names).subList(0, Math.min(25, names.size()));
        for (String name : sample) {
            BufferedImage img = ImageIO.read(
                    opts.outputDir.resolve("images").resolve(name).toFile());
            if (img == null) continue;
            decoded++;
            if (isUniform(img)) blank++;
        }
        check(decoded == sample.size(),
                "every sampled crop decodes as a PNG (" + decoded + "/" + sample.size() + ")");
        check(blank == 0, "no sampled crop is a uniform blank (" + blank + " blank)");

        // ---- the CSV ----
        Path csv = opts.outputDir.resolve("features.csv");
        check(Files.exists(csv), "features.csv exists");
        byte[] csvBytes = Files.readAllBytes(csv);
        check(csvBytes.length > 3 && (csvBytes[0] & 0xFF) == 0xEF
                        && (csvBytes[1] & 0xFF) == 0xBB && (csvBytes[2] & 0xFF) == 0xBF,
                "CSV carries a UTF-8 byte-order mark");
        String csvText = new String(csvBytes, StandardCharsets.UTF_8);
        check(csvText.contains("Nucleus: Area"),
                "the measurement names survived into the CSV");
        check(Files.readAllLines(csv, StandardCharsets.UTF_8).size() == lines.size(),
                "CSV and TSV have the same number of rows");

        // ---- the README and the command it hands the user ----
        Path readme = opts.outputDir.resolve("README.txt");
        check(Files.exists(readme), "README.txt exists");
        String text = Files.readString(readme, StandardCharsets.UTF_8);
        check(text.contains("traithorizon "), "README names the command to run");
        check(text.contains("--hide_axes"), "README's command hides the text columns");
        check(text.contains("coverage"), "README reports per-measurement coverage");
        for (String cls : result.classNames) {
            if (!text.contains(cls)) {
                bad("README does not list the classification '" + cls + "'");
            }
        }
        ok("README lists all " + result.classNames.size() + " classification(s)");

        // ---- the budget was honoured ----
        check(result.cellsWritten <= opts.globalCap + opts.minPerClass * result.classNames.size(),
                "the cell budget was honoured");

        // ---- a measurement that is not on these cells must name itself ----
        CropTableExporter.Options bogus = new CropTableExporter.Options();
        bogus.outputDir = outDir.resolve("crop_table_bogus");
        bogus.measurements = new ArrayList<>(measurements);
        bogus.measurements = List.of("Cell: CD3 mean", "Nucleus: Area \u00b5m^2");
        bogus.formats = List.of(CropTableExporter.Format.TSV);
        bogus.writeCrops = false;
        try {
            CropTableExporter.export(qupath, entries, bogus, m -> {}, () -> false);
            bad("a measurement absent from every cell should have failed the export");
        } catch (java.io.IOException expected) {
            String msg = expected.getMessage();
            check(msg.contains("Nucleus: Area \u00b5m^2"),
                    "the failure names the measurement that is on no cell");
            check(!msg.contains("Cell: CD3 mean"),
                    "the failure does not lead with the measurements that were fine");
            note("message: " + msg);
        }

        // ---- reproducibility: the same seed writes the same table ----
        CropTableExporter.Options again = new CropTableExporter.Options();
        again.outputDir = outDir.resolve("crop_table_again");
        again.measurements = measurements;
        again.formats = List.of(CropTableExporter.Format.TSV);
        again.writeCrops = false;
        again.globalCap = opts.globalCap;
        again.minPerClass = opts.minPerClass;
        again.seed = 42;
        CropTableExporter.export(qupath, entries, again, m -> {}, () -> false);
        List<String> rerun = Files.readAllLines(
                again.outputDir.resolve("features.tsv"), StandardCharsets.UTF_8);
        check(rerun.equals(lines), "the same seed and settings wrote the same table");
    }

    // ==================== gate save / replay / apply ====================

    private static void gateScenario(QuPathGUI qupath, Project<BufferedImage> project,
                                     List<ProjectImageEntry<BufferedImage>> entries,
                                     Path outDir, List<String> measurements)
            throws Exception {
        System.out.println();
        System.out.println("--- gate save, replay, and apply ---");

        // A biaxial plot over two real markers, pooled across every image, with
        // the CellRefs the gate bar would have built.
        String colX = "Cell: CD3 mean";
        String colY = "Cell: CD8 mean";
        List<double[]> xy = new ArrayList<>();
        List<CellRef> refs = new ArrayList<>();
        for (ProjectImageEntry<BufferedImage> entry : entries) {
            ImageData<BufferedImage> data = entry.readImageData();
            for (PathObject det : data.getHierarchy().getDetectionObjects()) {
                Number vx = det.getMeasurements().get(colX);
                Number vy = det.getMeasurements().get(colY);
                if (vx == null || vy == null || det.getROI() == null) continue;
                xy.add(new double[] {vx.doubleValue(), vy.doubleValue()});
                double half = 0.5 * Math.max(det.getROI().getBoundsWidth(),
                        det.getROI().getBoundsHeight());
                refs.add(new CellRef(entry.getID(), entry.getImageName(),
                        det.getROI().getCentroidX(), det.getROI().getCentroidY(), half));
            }
            data.getServer().close();
        }
        double[][] plot = xy.toArray(new double[0][]);
        note("pooled " + plot.length + " cell(s) onto '" + colX + "' x '" + colY + "'");

        // A gate over the CD3-high, CD8-high quadrant -- the cytotoxic T cells.
        double[] maxes = {0, 0};
        for (double[] p : plot) {
            maxes[0] = Math.max(maxes[0], p[0]);
            maxes[1] = Math.max(maxes[1], p[1]);
        }
        double tx = 0.35 * maxes[0];
        double ty = 0.35 * maxes[1];
        double[][] poly = {{tx, ty}, {maxes[0] * 2, ty},
                {maxes[0] * 2, maxes[1] * 2}, {tx, maxes[1] * 2}};

        GateSet.Axes axes = new GateSet.Axes(GateSet.MODE_BIAXIAL, "biaxial",
                colX, colY, entries.size() + " image(s)");
        String fingerprint = GateSet.fingerprintOf(plot);
        GateSet.Gate gate = new GateSet.Gate("CD3+CD8+", poly, 0);
        int[] inGate = GateStore.indicesIn(gate, plot);
        note("the gate holds " + inGate.length + " cell(s)");
        check(inGate.length > 50, "the gate holds a real population");

        GateSet saved = new GateSet(axes, plot.length, fingerprint, "harness",
                java.time.LocalDateTime.now().toString(),
                List.of(new GateSet.Gate("CD3+CD8+", poly, inGate.length)));
        Path gateFile = outDir.resolve("gates").resolve("cd8" + GateStore.EXTENSION);
        GateStore.write(gateFile, saved);
        check(Files.exists(gateFile), "the gate file was written");

        GateSet back = GateStore.read(gateFile);
        check(back.getGates().size() == 1, "the gate file reads back");
        check(GateStore.indicesIn(back.getGates().get(0), plot).length == inGate.length,
                "the reloaded gate selects the same cells");

        GateStore.Compatibility exact = GateStore.check(back, axes, fingerprint, plot.length);
        check(exact.match() == GateStore.Match.EXACT,
                "same axes and coordinates reads as an exact match");

        // The other axes: refused, not warned.
        GateSet.Axes other = new GateSet.Axes(GateSet.MODE_BIAXIAL, "biaxial",
                colX, "Cell: CD20 mean", "3 image(s)");
        check(!GateStore.check(back, other, fingerprint, plot.length).replayable(),
                "a different Y measurement is refused");

        // A real re-pool in another image order must still match: a gate is geometry.
        double[][] reordered = new double[plot.length][];
        for (int i = 0; i < plot.length; i++) {
            reordered[i] = plot[plot.length - 1 - i];
        }
        check(GateStore.check(back, axes, GateSet.fingerprintOf(reordered), plot.length)
                        .match() == GateStore.Match.EXACT,
                "re-pooling the same cells in another order still matches");

        // ---- apply it to real cells, across images, and check it stuck ----
        String className = "HarnessGate";
        GateApplier.Result applied = GateApplier.assignClass(
                qupath, refs.toArray(new CellRef[0]), inGate, className);
        note("assigned '" + className + "' to " + applied.cellsClassified
                + " cell(s) across " + applied.imagesTouched + " image(s), "
                + applied.unmatched + " unmatched");
        check(applied.cellsClassified > 0, "the gate classified real cells");
        check(applied.imagesTouched == entries.size(),
                "the gate wrote to every image it covered ("
                        + applied.imagesTouched + "/" + entries.size() + ")");
        check(applied.unmatched == 0,
                "every gated cell resolved back to its detection ("
                        + applied.unmatched + " unmatched)");

        // Re-read from disk: this is the part a unit test cannot do.
        Project<BufferedImage> reloaded = ProjectIO.loadProject(
                Path.of(project.getPath().getParent().toString(), "project.qpproj").toFile(),
                BufferedImage.class);
        int onDisk = 0;
        for (ProjectImageEntry<BufferedImage> entry : reloaded.getImageList()) {
            ImageData<BufferedImage> data = entry.readImageData();
            for (PathObject det : data.getHierarchy().getDetectionObjects()) {
                PathClass pc = det.getPathClass();
                if (pc != null && className.equals(pc.toString())) onDisk++;
            }
            data.getServer().close();
        }
        check(onDisk == applied.cellsClassified,
                "the classification survived a save and reload (" + onDisk + " on disk vs "
                        + applied.cellsClassified + " reported)");
    }

    // ==================== helpers ====================

    /** Give the cells a realistic set of classes: the brightest lineage marker. */
    private static int classifyByBrightestMarker(Project<BufferedImage> project)
            throws Exception {
        String[] markers = {"Cell: PanCK mean", "Cell: aSMA mean", "Cell: CD3 mean",
                "Cell: CD20 mean", "Cell: CD68 mean"};
        String[] labels = {"Tumor", "Fibroblast", "T cell", "B cell", "Macrophage"};
        int total = 0;
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            ImageData<BufferedImage> data = entry.readImageData();
            for (PathObject det : data.getHierarchy().getDetectionObjects()) {
                int best = -1;
                double bestV = 12.0;   // below this, call it unclassified
                for (int i = 0; i < markers.length; i++) {
                    Number v = det.getMeasurements().get(markers[i]);
                    if (v != null && v.doubleValue() > bestV) {
                        bestV = v.doubleValue();
                        best = i;
                    }
                }
                if (best >= 0) {
                    det.setPathClass(PathClass.fromString(labels[best]));
                    total++;
                }
            }
            entry.saveImageData(data);
            data.getServer().close();
        }
        project.syncChanges();
        return total;
    }

    private static int indexOf(String[] array, String want) {
        for (int i = 0; i < array.length; i++) {
            if (want.equals(array[i])) return i;
        }
        return -1;
    }

    /** True when every pixel is the same colour -- a crop that shows nothing. */
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
