package qupath.ext.qpcat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.ExportOptions;
import qupath.ext.qpcat.model.ExportResult;
import qupath.ext.qpcat.model.OutputFormat;
import qupath.ext.qpcat.model.PlotKind;
import qupath.ext.qpcat.model.SavedClusteringResult;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 * Headless figure-export logic for QP-CAT.
 * <p>
 * Iterates over a user-selected subset of project images, resolves the
 * saved clustering result for each, and writes one file per requested
 * {@link PlotKind} x {@link OutputFormat} combination.
 * <p>
 * <strong>FX-free.</strong> This class never touches a JavaFX scene
 * graph, never calls {@code Platform.runLater}, and is safe to invoke
 * from any thread. The dialog wraps it in a daemon thread; Feature C's
 * YAML batch invokes it directly under {@code QuPath script}.
 * <p>
 * <strong>Headless plot scope.</strong> Matplotlib-backed plots
 * ({@link PlotKind.Source#MATPLOTLIB}, copied from the run's saved PNGs) and
 * the Java-rendered cluster-composition figures and tables
 * ({@link PlotKind.Source#COMPUTED}, drawn by {@link CompositionFigure} from
 * the saved result) are both exportable from a headless call. JavaFX plots
 * ({@link PlotKind.Source#JAVAFX}) require an interactive snapshot path
 * which the GUI dialog provides as a separate code path. Plot kinds the
 * exporter cannot fulfil are recorded as failures and otherwise skipped.
 */
public final class BatchFigureExporter {

    private static final Logger logger = LoggerFactory.getLogger(BatchFigureExporter.class);

    private BatchFigureExporter() {}

    /**
     * Headless project-wide figure export. The contract entry point
     * called by both the GUI dialog (via a daemon thread) and Feature
     * C's YAML batch.
     *
     * @param options          export options (image subset, plot kinds,
     *                         formats, DPI, output dir, pattern)
     * @param progressCallback receives {@code (message, fractionStr)};
     *                         return {@code false} to request cancel.
     *                         May be {@code null}.
     * @return a populated {@link ExportResult}
     * @throws IOException on unrecoverable filesystem error (the output
     *                     directory cannot be created, write fails on
     *                     every file, etc.)
     */
    public static ExportResult exportProject(
            ExportOptions options,
            BiFunction<String, String, Boolean> progressCallback) throws IOException {

        if (options == null) {
            throw new IllegalArgumentException("ExportOptions must not be null");
        }
        if (options.getOutputDir() == null) {
            throw new IOException("Output directory is not set");
        }
        String patternErr = FilenameSanitizer.validatePattern(options.getFilenamePattern());
        if (patternErr != null) {
            throw new IOException("Invalid filename pattern: " + patternErr);
        }
        if (options.getPlotKinds() == null || options.getPlotKinds().isEmpty()) {
            throw new IOException("No plot kinds selected");
        }
        if (options.getOutputFormats() == null || options.getOutputFormats().isEmpty()) {
            throw new IOException("No output formats selected");
        }

        Files.createDirectories(options.getOutputDir());

        ExportResult result = new ExportResult();

        // Resolve image entries by scope
        Project<?> project = resolveProject();
        List<ProjectImageEntry<?>> entries = resolveImageEntries(project, options, result);
        if (entries.isEmpty()) {
            result.addFailure("No images matched the requested scope");
            return result;
        }

        int totalWork = entries.size() * options.getPlotKinds().size() * options.getOutputFormats().size();
        int processed = 0;

        // Composition figures are result-level; this records which have already
        // been written so the per-image loop does not repeat them.
        Set<String> emittedComposition = new LinkedHashSet<>();
        // Results read once for the whole export, newest first.
        Map<String, SavedClusteringResult> loaded = new LinkedHashMap<>();
        Map<String, Integer> dupNames = InputChecks.duplicateNames(
                entries.stream().map(e -> e.getImageName()).toList());

        for (ProjectImageEntry<?> entry : entries) {
            if (isCancelled(progressCallback, processed, totalWork, "Scanning " + entry.getImageName())) {
                result.setCancelled(true);
                return result;
            }

            // Load the saved result for this image (best effort -- if
            // none exists, record a failure and skip).
            SavedClusteringResult saved = loadSavedResultForImage(project, entry, options, result,
                    loaded);
            // Two images sharing a name would write one file; the second gets its entry id.
            String fileLabel = InputChecks.imageLabel(entry.getImageName(), entry.getID(), dupNames);

            for (PlotKind plot : options.getPlotKinds()) {
                for (OutputFormat fmt : options.getOutputFormats()) {
                    processed++;
                    String msg = entry.getImageName() + " - " + plot.getDisplayName()
                            + " (" + fmt.getExtension() + ")";
                    if (isCancelled(progressCallback, processed, totalWork, msg)) {
                        result.setCancelled(true);
                        return result;
                    }
                    exportSingle(project, entry, fileLabel, saved, plot, fmt, options, result,
                            emittedComposition);
                }
            }
        }

        if (progressCallback != null) {
            progressCallback.apply(
                    "Export complete: " + result.summary(),
                    fraction(totalWork, totalWork));
        }
        return result;
    }

    // ---------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------

    private static Project<?> resolveProject() {
        QuPathGUI qupath = QuPathGUI.getInstance();
        return qupath == null ? null : qupath.getProject();
    }

    private static List<ProjectImageEntry<?>> resolveImageEntries(
            Project<?> project, ExportOptions options, ExportResult result) {
        List<ProjectImageEntry<?>> out = new ArrayList<>();
        if (project == null) {
            // CURRENT scope can still proceed with the open image even
            // when no project is open, but only as a virtual single
            // entry; we cannot resolve project-relative saved results
            // without a project, so record a failure.
            result.addFailure(
                    "No project is open; project-scoped figure export needs an open project");
            return out;
        }
        switch (options.getScope()) {
            case CURRENT -> {
                QuPathGUI qupath = QuPathGUI.getInstance();
                if (qupath != null && qupath.getImageData() != null) {
                    // The open image's own entry: its server name can differ from the
                    // entry name, and two entries can share a name.
                    @SuppressWarnings("unchecked")
                    ProjectImageEntry<?> open = ((Project<java.awt.image.BufferedImage>) project)
                            .getEntry(qupath.getImageData());
                    if (open != null) out.add(open);
                }
                if (out.isEmpty()) {
                    result.addFailure(
                            "No image is currently open in the viewer");
                }
            }
            case ALL -> out.addAll(project.getImageList());
            case SUBSET -> {
                List<String> wanted = options.getImageSubset();
                if (wanted == null || wanted.isEmpty()) {
                    result.addFailure(
                            "Subset scope requested but image subset list is empty");
                    return out;
                }
                for (ProjectImageEntry<?> entry : project.getImageList()) {
                    if (wanted.contains(entry.getImageName())) {
                        out.add(entry);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Locate the saved clustering result for this image: the one named in the
     * options, else the newest result that covers this image, preferring one named
     * after it. A result covers an image when its scope or its cells are that
     * project entry ({@link #covers}). An image no result covers is a failure
     * for that image; another image's result is never used in its place.
     *
     * @param loaded results already read in this export, by name; filled here
     */
    private static SavedClusteringResult loadSavedResultForImage(
            Project<?> project, ProjectImageEntry<?> entry,
            ExportOptions options, ExportResult result,
            Map<String, SavedClusteringResult> loaded) {
        String explicit = options.getResultName();
        try {
            if (explicit != null && !explicit.isEmpty()) {
                SavedClusteringResult saved = load(project, explicit, loaded);
                if (covers(saved, entry.getID(), entry.getImageName())) return saved;
                result.addFailure(entry.getImageName() + ": saved result '" + explicit
                        + "' has no cells on this image");
                return null;
            }
            List<String> names = ClusteringResultManager.listResults(project);
            List<String> order = new ArrayList<>();
            for (String n : names) if (namedAfterImage(n, entry.getImageName())) order.add(n);
            for (String n : names) if (!order.contains(n)) order.add(n);
            for (String n : order) {
                SavedClusteringResult saved = load(project, n, loaded);
                if (covers(saved, entry.getID(), entry.getImageName())) return saved;
            }
            result.addFailure(entry.getImageName() + ": no saved clustering result has cells "
                    + "on this image");
        } catch (IOException e) {
            result.addFailure(entry.getImageName()
                    + ": failed to load saved clustering result (" + e.getMessage() + ")");
        }
        return null;
    }

    private static SavedClusteringResult load(Project<?> project, String name,
                                              Map<String, SavedClusteringResult> loaded)
            throws IOException {
        SavedClusteringResult saved = loaded.get(name);
        if (saved == null) {
            saved = ClusteringResultManager.loadSavedResult(project, name);
            loaded.put(name, saved);
        }
        return saved;
    }

    /**
     * Whether a saved result is about an image: its scope key is the entry id, or a
     * cell of it lies on that entry. A result recorded without image ids (before
     * 0.20) is matched by its scope key or cell image names.
     *
     * @param saved     the result; may be null
     * @param entryId   the image's entry id, or null
     * @param imageName the image's name
     * @return whether the result covers the image
     */
    static boolean covers(SavedClusteringResult saved, String entryId, String imageName) {
        if (saved == null) return false;
        String[] ids = saved.getCellImageIds();
        if (entryId != null && (entryId.equals(saved.getScopeKey())
                || (ids != null && java.util.Arrays.asList(ids).contains(entryId)))) {
            return true;
        }
        if (ids != null || imageName == null) return false;
        String[] names = saved.getCellImageNames();
        return imageName.equals(saved.getScopeKey())
                || (names != null && java.util.Arrays.asList(names).contains(imageName));
    }

    /**
     * Export a single (image, plot, format) tuple. Records exactly one
     * outcome: a success on {@code result.filesWritten++} or a failure
     * row on {@code result.failures}.
     */
    private static void exportSingle(Project<?> project, ProjectImageEntry<?> entry,
                                      String fileLabel, SavedClusteringResult saved,
                                      PlotKind plot, OutputFormat fmt,
                                      ExportOptions options, ExportResult result,
                                      Set<String> emittedComposition) {
        String imageName = entry.getImageName();

        // Composition figures describe the WHOLE result (how its clusters split
        // across every image / annotation), not one image, so they are emitted
        // once per result rather than once per image in the loop.
        if (plot.getSource() == PlotKind.Source.COMPUTED) {
            try {
                exportCompositionPlot(project, saved, plot, fmt, options, result,
                        emittedComposition);
            } catch (IOException ioe) {
                result.addFailure(plot.getSlug() + ": write failed (" + ioe.getMessage() + ")");
                logger.warn("Composition export failed for {}: {}",
                        plot.getSlug(), ioe.getMessage());
            }
            return;
        }

        if (saved == null) {
            return; // loadSavedResultForImage recorded this image's failure
        }
        String filename = FilenameSanitizer.expand(
                options.getFilenamePattern(),
                fileLabel,
                plot.getSlug(),
                saved != null ? saved.getName() : options.getResultName(),
                fmt.getExtension());
        Path target = options.getOutputDir().resolve(filename);

        try {
            if (!options.isOverwriteExisting() && Files.exists(target)) {
                result.addFailure(imageName + " - " + plot.getSlug()
                        + ": file exists (" + target.getFileName() + "); enable overwrite to replace");
                return;
            }

            switch (plot.getSource()) {
                case MATPLOTLIB -> exportMatplotlibPlot(project, saved, plot, fmt, target, result);
                case COMPUTED -> { /* handled above -- result-level, not per-image */ }
                case JAVAFX -> {
                    if (!options.isSkipMissingPlots()) {
                        result.addFailure(imageName + " - " + plot.getSlug()
                                + ": interactive plots are not exportable here; save it from the "
                                + "Results window with Save plot...");
                    } else {
                        result.addFailure(imageName + " - " + plot.getSlug()
                                + ": skipped (interactive plot; save it from the Results window "
                                + "with Save plot...)");
                    }
                }
                case TEXT_ONLY -> result.addFailure(imageName + " - " + plot.getSlug()
                        + ": skipped (text-only plot; not yet renderable to image)");
            }
        } catch (IOException ioe) {
            result.addFailure(imageName + " - " + plot.getSlug()
                    + ": write failed (" + ioe.getMessage() + ")");
            logger.warn("Figure export failed for {} - {}: {}",
                    imageName, plot.getSlug(), ioe.getMessage());
        }
    }

    /**
     * Render and write one cluster-composition output (pie figure or CSV table)
     * for the saved result as a whole.
     *
     * <p>Composition is a property of the result, not of any one image, so the
     * per-image loop would otherwise write N identical files. The
     * {@code emittedComposition} set makes the first write win and the rest
     * no-ops -- silently, because a repeat is expected bookkeeping, not a
     * failure the user needs to read about N-1 times.</p>
     *
     * <p>Table kinds always write {@code .csv} regardless of the requested
     * raster format, and are likewise emitted once even when both PNG and TIFF
     * are ticked.</p>
     *
     * <p>Public so the YAML batch orchestrator, which cannot call
     * {@link #exportProject} (it avoids {@code QuPathGUI.getInstance()}), reuses
     * this exact code rather than reimplementing it.</p>
     *
     * @param emittedComposition run-scoped set of already-written composition
     *                           keys; pass the same instance for every image of
     *                           a run. May be null to disable deduplication.
     */
    public static void exportCompositionPlot(Project<?> project, SavedClusteringResult saved,
                                               PlotKind plot, OutputFormat fmt,
                                               ExportOptions options, ExportResult result,
                                               Set<String> emittedComposition)
            throws IOException {
        boolean table = plot == PlotKind.COMPOSITION_TABLE_IMAGE
                || plot == PlotKind.COMPOSITION_TABLE_ANNOTATION
                || plot == PlotKind.COMPOSITION_TABLE_AREA
                || plot == PlotKind.COMPOSITION_TABLE_CLASS;
        String ext = table ? "csv" : fmt.getExtension();

        String resultName = saved != null ? saved.getName() : options.getResultName();
        String filename = FilenameSanitizer.expand(
                options.getFilenamePattern(),
                // {image} is meaningless for a result-level figure; name the
                // scope instead so the file is not mistaken for a per-image one.
                "all-images",
                plot.getSlug(),
                resultName,
                ext);
        Path target = options.getOutputDir().resolve(filename);

        String dedupeKey = String.valueOf(resultName) + '/' + plot.getSlug() + '.' + ext;
        if (emittedComposition != null && !emittedComposition.add(dedupeKey)) {
            return;  // already written for this result in this run
        }

        if (saved == null) {
            result.addFailure(plot.getSlug() + ": no saved clustering result to compose from");
            return;
        }
        if (!options.isOverwriteExisting() && Files.exists(target)) {
            result.addFailure(plot.getSlug() + ": file exists ("
                    + target.getFileName() + "); enable overwrite to replace");
            return;
        }

        CompositionFigure figure = buildComposition(project, saved, plot, result);
        if (figure == null) return;   // reason already recorded

        if (table) {
            CsvText.writeCsvFile(target, figure.toCsv());
        } else {
            BufferedImage img = figure.render(
                    CompositionFigure.scaleForDpi(options.getDpi()), null);
            if (fmt == OutputFormat.PNG) {
                if (!ImageIO.write(img, "PNG", target.toFile())) {
                    throw new IOException("No ImageIO writer for PNG");
                }
            } else if (!writeWithTiffWriter(img, target)) {
                if (!ImageIO.write(img, fmt.getImageIoFormatName(), target.toFile())) {
                    throw new IOException("No ImageIO writer for format " + fmt.name());
                }
            }
        }
        result.incrementFilesWritten();
        result.addBytes(Files.size(target));
        logger.debug("Wrote composition {} ({} bytes)", target, Files.size(target));
    }

    /**
     * Build the composition tally for one plot kind from a saved result, or
     * {@code null} (with a recorded reason) when the saved result lacks the
     * per-cell references the tally needs.
     */
    private static CompositionFigure buildComposition(Project<?> project,
                                                       SavedClusteringResult saved,
                                                       PlotKind plot, ExportResult result) {
        var clustering = saved.toClusteringResult();
        int[] labels = clustering.getClusterLabels();
        if (labels == null || labels.length == 0) {
            result.addFailure(plot.getSlug() + ": saved result has no cluster labels");
            return null;
        }

        // Each grouping axis is conditional: a result that cannot support it
        // records a FAILURE naming the reason rather than writing an empty or
        // misleading figure.
        String[] groups;
        String dimension;
        if (plot == PlotKind.COMPOSITION_PIE_ANNOTATION
                || plot == PlotKind.COMPOSITION_TABLE_ANNOTATION) {
            if (!clustering.hasCellParentNames()) {
                result.addFailure(plot.getSlug()
                        + ": this result has no per-cell annotation names (it was not run on "
                        + "annotation input)");
                return null;
            }
            groups = clustering.getCellParentNames();
            dimension = "Annotation";
        } else if (plot == PlotKind.COMPOSITION_PIE_CLASS
                || plot == PlotKind.COMPOSITION_TABLE_CLASS) {
            if (!clustering.hasCellParentClasses()) {
                result.addFailure(plot.getSlug()
                        + ": this result has fewer than two annotation classes, so a "
                        + "by-class split would be one row restating the whole result");
                return null;
            }
            groups = clustering.getCellParentClasses();
            dimension = "Class";
        } else if (plot == PlotKind.COMPOSITION_PIE_AREA
                || plot == PlotKind.COMPOSITION_TABLE_AREA) {
            if (clustering.getAreaCount() < 2) {
                result.addFailure(plot.getSlug()
                        + ": this result was not split into independent areas");
                return null;
            }
            groups = clustering.getCellAreaNames();
            dimension = "Area";
        } else {
            if (!clustering.hasCellRefs()) {
                result.addFailure(plot.getSlug()
                        + ": this result has no per-cell image references (saved by an older version)");
                return null;
            }
            groups = imageGroupsFor(project, clustering);
            dimension = "Image";
        }
        // Custom names from a rename / merge, so an exported figure says "Tumor"
        // wherever the results window does.
        return CompositionFigure.tally(labels, clustering.getNClusters(),
                        clustering.getNoiseRowIndex(), groups, dimension)
                .withClusterNames(clustering::clusterName);
    }

    /** Per-cell source-image names, resolving any missing name by project id. */
    private static String[] imageGroupsFor(Project<?> project,
                                            qupath.ext.qpcat.model.ClusteringResult clustering) {
        Map<String, String> idToName = new LinkedHashMap<>();
        if (project != null) {
            for (ProjectImageEntry<?> e : project.getImageList()) {
                idToName.put(e.getID(), e.getImageName());
            }
        }
        var refs = clustering.getCellRefs();
        String[] out = new String[refs.length];
        for (int i = 0; i < refs.length; i++) {
            var r = refs[i];
            String name = r != null ? r.getImageName() : null;
            if ((name == null || name.isBlank()) && r != null) {
                name = idToName.get(r.getImageId());
            }
            out[i] = (name == null || name.isBlank()) ? "(unknown image)" : name;
        }
        return out;
    }

    private static void exportMatplotlibPlot(Project<?> project, SavedClusteringResult saved,
                                              PlotKind plot, OutputFormat fmt, Path target,
                                              ExportResult result) throws IOException {
        if (saved == null) {
            result.addFailure(target.getFileName() + ": no saved clustering result for this image");
            return;
        }
        Map<String, String> paths = saved.getPlotPaths();
        if (paths == null || paths.isEmpty()) {
            result.addFailure(target.getFileName() + ": saved result has no persisted plots");
            return;
        }
        String key = plot.getSavedPlotKey();
        String relPath = paths.get(key);
        if (relPath == null) {
            result.addFailure(target.getFileName()
                    + ": plot '" + plot.getSlug() + "' not available in saved result");
            return;
        }

        // Resolve relative paths against the results directory
        Path src = Path.of(relPath);
        if (!src.isAbsolute() && project != null) {
            Path resultsDir = ClusteringResultManager.getResultsDirectory(project);
            src = resultsDir.resolve(relPath);
        }
        if (!Files.exists(src)) {
            result.addFailure(target.getFileName()
                    + ": source plot file missing (" + src + ")");
            return;
        }

        writeWithFormat(src, target, fmt);
        long bytes = Files.size(target);
        result.incrementFilesWritten();
        result.addBytes(bytes);
        logger.debug("Wrote {} ({} bytes)", target, bytes);
    }

    /**
     * Copy the source PNG to the target, transcoding via ImageIO when
     * the target format differs. The DPI metadata is best-effort; some
     * JDK writers ignore custom metadata for TIFF/PNG.
     */
    static void writeWithFormat(Path src, Path target, OutputFormat fmt) throws IOException {
        if (fmt == OutputFormat.PNG) {
            // Source is already PNG (matplotlib savefig output); a copy
            // preserves all the matplotlib-side metadata. DPI is already
            // baked in by the Python side at savefig time.
            Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        // Transcode to TIFF via ImageIO. JDK 9+ ships a built-in TIFF
        // writer (uncompressed); twelvemonkeys-imageio-tiff is not
        // required but if present will be picked up automatically.
        BufferedImage img = ImageIO.read(src.toFile());
        if (img == null) {
            throw new IOException("Cannot decode source image: " + src);
        }
        boolean ok = writeWithTiffWriter(img, target);
        if (!ok) {
            // Fallback: write whatever format ImageIO offers (will throw
            // if even the basic writer is missing).
            if (!ImageIO.write(img, fmt.getImageIoFormatName(), target.toFile())) {
                throw new IOException("No ImageIO writer for format " + fmt.name());
            }
        }
    }

    /**
     * Write a TIFF using the first available writer. Returns true on
     * success, false to let the caller fall back to a generic writer.
     */
    private static boolean writeWithTiffWriter(BufferedImage img, Path target) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("TIFF");
        if (!writers.hasNext()) {
            writers = ImageIO.getImageWritersByFormatName("tif");
        }
        if (!writers.hasNext()) return false;
        ImageWriter writer = writers.next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            IIOMetadata meta = writer.getDefaultImageMetadata(
                    new javax.imageio.ImageTypeSpecifier(img), param);
            // DPI metadata is best-effort; ignore failures
            try {
                setStandardDpi(meta, 300);
            } catch (Exception e) {
                logger.trace("Could not set TIFF DPI metadata (best-effort): {}", e.getMessage());
            }
            writer.write(null, new IIOImage(img, null, meta), param);
            return true;
        } finally {
            writer.dispose();
        }
    }

    private static void setStandardDpi(IIOMetadata meta, int dpi) {
        // DPI in millimetres for the standard tree (dpi -> mm per pixel)
        double mmPerPixel = 25.4 / dpi;
        IIOMetadataNode horiz = new IIOMetadataNode("HorizontalPixelSize");
        horiz.setAttribute("value", Double.toString(mmPerPixel));
        IIOMetadataNode vert = new IIOMetadataNode("VerticalPixelSize");
        vert.setAttribute("value", Double.toString(mmPerPixel));
        IIOMetadataNode dim = new IIOMetadataNode("Dimension");
        dim.appendChild(horiz);
        dim.appendChild(vert);
        IIOMetadataNode root = new IIOMetadataNode(
                javax.imageio.metadata.IIOMetadataFormatImpl.standardMetadataFormatName);
        root.appendChild(dim);
        try {
            meta.mergeTree(
                    javax.imageio.metadata.IIOMetadataFormatImpl.standardMetadataFormatName, root);
        } catch (Exception ignore) {
            // Some writers don't accept the standard tree; non-fatal.
        }
    }

    private static boolean isCancelled(BiFunction<String, String, Boolean> cb,
                                        int processed, int total, String msg) {
        if (cb == null) return false;
        Boolean keepGoing = cb.apply(msg, fraction(processed, total));
        return keepGoing != null && !keepGoing;
    }

    private static String fraction(int n, int total) {
        if (total <= 0) return "0";
        double f = (double) n / (double) total;
        if (f < 0) f = 0;
        if (f > 1) f = 1;
        return Double.toString(f);
    }

    /**
     * Scan the saved-result directory of every requested image to
     * determine which plot kinds are available. Used by the GUI dialog
     * to populate per-row availability captions. Returns
     * {@code Map<imageName, Map<plotSlug, available>>}.
     * <p>
     * Best-effort: exceptions on individual images become an empty map
     * for that image and are logged.
     */
    public static Map<String, Map<String, Boolean>> scanAvailability(
            Project<?> project, List<String> imageNames, String resultName) {
        Map<String, Map<String, Boolean>> out = new LinkedHashMap<>();
        if (project == null || imageNames == null) return out;
        List<String> savedNames;
        try {
            savedNames = ClusteringResultManager.listResults(project);
        } catch (IOException e) {
            logger.warn("Plot-availability scan: could not list saved results: {}", e.getMessage());
            return out;
        }
        Map<String, SavedClusteringResult> loaded = new LinkedHashMap<>();
        for (String imageName : imageNames) {
            Map<String, Boolean> per = new LinkedHashMap<>();
            // Pre-fill every kind as false
            for (PlotKind kind : PlotKind.values()) {
                per.put(kind.getSlug(), false);
            }
            try {
                SavedClusteringResult saved = pickSavedResultForImage(project, savedNames,
                        imageName, resultName, loaded);
                if (saved != null) {
                    Map<String, String> paths = saved.getPlotPaths();
                    if (paths != null) {
                        for (PlotKind kind : PlotKind.values()) {
                            String key = kind.getSavedPlotKey();
                            if (key != null && paths.containsKey(key)) {
                                per.put(kind.getSlug(), true);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                logger.debug("Availability scan failed for {}: {}", imageName, e.getMessage());
            }
            out.put(imageName, per);
        }
        return out;
    }

    /** The result the export would use for an image of this name, or null; see {@link #covers}. */
    private static SavedClusteringResult pickSavedResultForImage(
            Project<?> project, List<String> savedNames, String imageName, String preferred,
            Map<String, SavedClusteringResult> loaded) throws IOException {
        if (savedNames == null || savedNames.isEmpty()) return null;
        List<String> order = new ArrayList<>();
        if (preferred != null && savedNames.contains(preferred)) {
            order.add(preferred);
        } else {
            for (String n : savedNames) if (namedAfterImage(n, imageName)) order.add(n);
            for (String n : savedNames) if (!order.contains(n)) order.add(n);
        }
        for (String n : order) {
            SavedClusteringResult saved = load(project, n, loaded);
            if (coversByName(saved, imageName)) return saved;
        }
        return null;
    }

    /** {@link #covers} for a caller that knows only the image's name. */
    private static boolean coversByName(SavedClusteringResult saved, String imageName) {
        if (saved == null || imageName == null) return false;
        String[] names = saved.getCellImageNames();
        return imageName.equals(saved.getScopeKey())
                || (names != null && java.util.Arrays.asList(names).contains(imageName));
    }

    /** True when a saved result's file name is the image's name, under either file-name rule. */
    static boolean namedAfterImage(String savedName, String imageName) {
        if (imageName == null) return false;
        if (savedName.contains(imageName)) return true;
        for (String s : FilenameSanitizer.spellingsOnDisk(imageName, FilenameSanitizer.EMPTY_FALLBACK)) {
            if (savedName.equals(s) || savedName.startsWith(s + "_")) return true;
        }
        return false;
    }

    // No instances; placate spotbugs.
    @SuppressWarnings("unused")
    private static Stream<?> __keepImports() { return null; }
}
