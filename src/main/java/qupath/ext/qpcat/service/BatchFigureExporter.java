package qupath.ext.qpcat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.ExportOptions;
import qupath.ext.qpcat.model.ExportResult;
import qupath.ext.qpcat.model.OutputFormat;
import qupath.ext.qpcat.model.PlotKind;
import qupath.ext.qpcat.model.SavedClusteringResult;
import qupath.lib.common.GeneralTools;
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
import java.util.List;
import java.util.Map;
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
 * <strong>Headless plot scope.</strong> Only matplotlib-backed plots
 * are exportable from a headless call -- JavaFX plots
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

        for (ProjectImageEntry<?> entry : entries) {
            if (isCancelled(progressCallback, processed, totalWork, "Scanning " + entry.getImageName())) {
                result.setCancelled(true);
                return result;
            }

            // Load the saved result for this image (best effort -- if
            // none exists, record a failure and skip).
            SavedClusteringResult saved = loadSavedResultForImage(project, entry, options, result);

            for (PlotKind plot : options.getPlotKinds()) {
                for (OutputFormat fmt : options.getOutputFormats()) {
                    processed++;
                    String msg = entry.getImageName() + " - " + plot.getDisplayName()
                            + " (" + fmt.getExtension() + ")";
                    if (isCancelled(progressCallback, processed, totalWork, msg)) {
                        result.setCancelled(true);
                        return result;
                    }
                    exportSingle(project, entry, saved, plot, fmt, options, result);
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
                    String currentName = qupath.getImageData().getServer().getMetadata().getName();
                    for (ProjectImageEntry<?> entry : project.getImageList()) {
                        if (entry.getImageName().equals(currentName)) {
                            out.add(entry);
                            break;
                        }
                    }
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
     * Locate the saved clustering result for this image. The convention
     * is one saved-result directory per image (named after the image).
     * If options.resultName is set, prefer that; otherwise look for any
     * result whose JSON references this image. If none found, fall back
     * to "most-recent saved result" since the existing
     * {@link ClusteringResultManager} keeps results listed most-recent
     * first.
     */
    private static SavedClusteringResult loadSavedResultForImage(
            Project<?> project, ProjectImageEntry<?> entry,
            ExportOptions options, ExportResult result) {
        try {
            String explicit = options.getResultName();
            if (explicit != null && !explicit.isEmpty()) {
                try {
                    return ClusteringResultManager.loadSavedResult(project, explicit);
                } catch (IOException e) {
                    // fall through to most-recent
                    logger.debug("Explicit result '{}' not found: {}", explicit, e.getMessage());
                }
            }
            List<String> names = ClusteringResultManager.listResults(project);
            // Prefer a result whose name matches the image
            String imgSafe = GeneralTools.stripInvalidFilenameChars(entry.getImageName());
            for (String n : names) {
                if (n.equals(imgSafe) || n.startsWith(imgSafe + "_")
                        || n.contains(entry.getImageName())) {
                    return ClusteringResultManager.loadSavedResult(project, n);
                }
            }
            if (!names.isEmpty()) {
                return ClusteringResultManager.loadSavedResult(project, names.get(0));
            }
        } catch (IOException e) {
            result.addFailure(entry.getImageName()
                    + ": failed to load saved clustering result (" + e.getMessage() + ")");
        }
        return null;
    }

    /**
     * Export a single (image, plot, format) tuple. Records exactly one
     * outcome: a success on {@code result.filesWritten++} or a failure
     * row on {@code result.failures}.
     */
    private static void exportSingle(Project<?> project, ProjectImageEntry<?> entry,
                                      SavedClusteringResult saved,
                                      PlotKind plot, OutputFormat fmt,
                                      ExportOptions options, ExportResult result) {
        String imageName = entry.getImageName();
        String filename = FilenameSanitizer.expand(
                options.getFilenamePattern(),
                imageName,
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
                case JAVAFX -> {
                    if (!options.isSkipMissingPlots()) {
                        result.addFailure(imageName + " - " + plot.getSlug()
                                + ": JavaFX plots are not exportable from the headless path");
                    } else {
                        result.addFailure(imageName + " - " + plot.getSlug()
                                + ": skipped (JavaFX-only plot; open the results dialog and use the GUI exporter)");
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
            } catch (Exception ignore) {}
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
        for (String imageName : imageNames) {
            Map<String, Boolean> per = new LinkedHashMap<>();
            // Pre-fill every kind as false
            for (PlotKind kind : PlotKind.values()) {
                per.put(kind.getSlug(), false);
            }
            try {
                String pick = pickSavedResultForImage(savedNames, imageName, resultName);
                if (pick != null) {
                    SavedClusteringResult saved =
                            ClusteringResultManager.loadSavedResult(project, pick);
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

    private static String pickSavedResultForImage(List<String> savedNames,
                                                   String imageName, String preferred) {
        if (savedNames == null || savedNames.isEmpty()) return null;
        if (preferred != null && savedNames.contains(preferred)) return preferred;
        String imgSafe = GeneralTools.stripInvalidFilenameChars(imageName);
        for (String n : savedNames) {
            if (n.equals(imgSafe) || n.startsWith(imgSafe + "_")
                    || n.contains(imageName)) {
                return n;
            }
        }
        return savedNames.get(0);  // fall back to most-recent
    }

    // No instances; placate spotbugs.
    @SuppressWarnings("unused")
    private static Stream<?> __keepImports() { return null; }
}
