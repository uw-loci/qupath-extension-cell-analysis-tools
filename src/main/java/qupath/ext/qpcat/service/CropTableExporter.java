package qupath.ext.qpcat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.CellRef;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.roi.interfaces.ROI;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Writes one image crop per cell plus a feature table over the same cells.
 *
 * <p>The table's shape is TraitHorizon's input contract
 * (<a href="https://traithorizon.readthedocs.io/en/latest/usage.html">usage docs</a>,
 * Janowczyk lab): a folder of per-object images and a tab-separated file whose
 * first column is {@code filename} and whose remaining columns are numeric
 * features, one row per object. QP-CAT writes that file so its cells can be
 * browsed in a parallel-coordinates plot beside their images -- the question
 * "is this cluster real, or is it a cluster of bad segmentations" -- which
 * QP-CAT's own representative-cell gallery does not answer, because medoids
 * show what is typical rather than what is broken.
 *
 * <p>The same table is written as CSV on request. It is the identical table;
 * only the delimiter, the quoting and the byte-order mark differ.
 *
 * <p>QP-CAT reads TraitHorizon's file format and nothing else -- no code is
 * taken from it, so its license does not reach us. (Its own license statement
 * is inconsistent: the README says The Clear BSD License, the documentation
 * index says MIT.)
 *
 * <p>{@link #export} touches disk for every cell and MUST run off the JavaFX
 * application thread.
 */
public final class CropTableExporter {

    private static final Logger logger = LoggerFactory.getLogger(CropTableExporter.class);

    /** The column TraitHorizon requires first, holding the image file name. */
    public static final String FILENAME_COLUMN = "filename";

    /** Numeric column holding the index of a cell's classification. */
    public static final String CLASS_INDEX_COLUMN = "class_index";

    /** String column holding the classification name; not an axis. */
    public static final String CLASSIFICATION_COLUMN = "classification";

    /** String column holding the source image name; not an axis. */
    public static final String IMAGE_COLUMN = "image";

    /** Columns TraitHorizon already hides, so we never pass them to --hide_axes. */
    private static final List<String> ALWAYS_HIDDEN = List.of("filename", "img", "url");

    /** Appended to a measurement whose name collides with a column we add. */
    private static final String COLLISION_SUFFIX = "_measurement";

    /**
     * UTF-8 byte-order mark, written as an escape because this source stays ASCII.
     * Excel needs it to read a CSV as UTF-8; TraitHorizon's TSV must NOT have it.
     */
    private static final char BOM = '\uFEFF';

    /** Delimiter and quoting of the written table. */
    public enum Format {

        /**
         * Tab-separated, UTF-8, no byte-order mark. TraitHorizon parses the
         * header literally, so a BOM would turn the required first column into
         * an unrecognised one and the file would be rejected.
         */
        TSV("tsv", '\t', false),

        /**
         * Comma-separated, UTF-8 with a byte-order mark. Excel assumes the
         * local 8-bit code page without one, which mangles the non-ASCII
         * characters ordinary QuPath measurement names carry ("um", "^2").
         */
        CSV("csv", ',', true);

        private final String extension;
        private final char delimiter;
        private final boolean bom;

        Format(String extension, char delimiter, boolean bom) {
            this.extension = extension;
            this.delimiter = delimiter;
            this.bom = bom;
        }

        public String getExtension() { return extension; }

        public char getDelimiter() { return delimiter; }

        /** True when the file is written with a UTF-8 byte-order mark. */
        public boolean hasBom() { return bom; }
    }

    /** What to export. */
    public static final class Options {
        /** Bundle root; receives {@code images/}, the table(s) and a README. */
        public Path outputDir;
        /** Measurement names to write as feature columns, in order. */
        public List<String> measurements = List.of();
        /** Formats to write; TSV is what TraitHorizon reads. */
        public List<Format> formats = List.of(Format.TSV);
        /** Write the per-cell PNGs. Off writes the table alone. */
        public boolean writeCrops = true;
        /** Crop side as a multiple of the cell's bounding box. */
        public double cropScale = CellCropService.DEFAULT_CROP_SCALE;
        /** Total cells across all classifications. */
        public int globalCap = 2000;
        /** Floor per classification, so a rare population is not sampled away. */
        public int minPerClass = 30;
        /** Sampling seed, so the same settings write the same file twice. */
        public int seed = 42;
        /** Skip cells with no classification. */
        public boolean classifiedOnly = false;
        /** Write the {@code image} string column. */
        public boolean includeImageName = true;
        /** Write the {@code classification} string column. */
        public boolean includeClassification = true;
    }

    /** One table row: a crop plus the numbers that describe it. */
    public record Row(String filename, double[] values, int classIndex,
                      String classification, String imageName) {}

    /** What an export produced, and what it had to leave out. */
    public static final class Result {
        public final int cellsWritten;
        public final int cropsWritten;
        public final int cellsSkippedMissingValues;
        public final int cellsSkippedNotSampled;
        public final int imagesUsed;
        public final List<String> classNames;
        public final Path outputDir;
        public final List<Path> tables;

        Result(int cellsWritten, int cropsWritten, int cellsSkippedMissingValues,
               int cellsSkippedNotSampled, int imagesUsed, List<String> classNames,
               Path outputDir, List<Path> tables) {
            this.cellsWritten = cellsWritten;
            this.cropsWritten = cropsWritten;
            this.cellsSkippedMissingValues = cellsSkippedMissingValues;
            this.cellsSkippedNotSampled = cellsSkippedNotSampled;
            this.imagesUsed = imagesUsed;
            this.classNames = classNames;
            this.outputDir = outputDir;
            this.tables = tables;
        }
    }

    private CropTableExporter() {}

    // ==================== table shape ====================

    /**
     * The table's columns, and which of them hold text rather than numbers.
     *
     * @param columns     every column, {@code filename} first
     * @param textColumns the columns TraitHorizon cannot plot as an axis
     */
    public record Header(List<String> columns, List<String> textColumns) {

        /** Number of columns. */
        public int size() { return columns.size(); }

        /**
         * The text columns to pass to {@code --hide_axes}.
         *
         * <p>TraitHorizon already hides {@code filename}, {@code img} and
         * {@code url} itself, so those are never repeated.
         */
        public List<String> hideAxes() {
            List<String> out = new ArrayList<>();
            for (String c : textColumns) {
                if (!ALWAYS_HIDDEN.contains(c)) out.add(c);
            }
            return out;
        }
    }

    /**
     * Column names for the table, {@code filename} first.
     *
     * <p>TraitHorizon requires every feature name to be unique, so a
     * measurement whose name collides with a column we add is suffixed rather
     * than silently shadowing it.
     *
     * @param measurements          measurement names, in export order
     * @param includeClassification add the {@code classification} text column
     * @param includeImageName      add the {@code image} text column
     * @return the header
     */
    public static Header buildHeader(List<String> measurements,
                                     boolean includeClassification,
                                     boolean includeImageName) {
        List<String> ours = new ArrayList<>();
        ours.add(CLASS_INDEX_COLUMN);
        if (includeClassification) ours.add(CLASSIFICATION_COLUMN);
        if (includeImageName) ours.add(IMAGE_COLUMN);

        List<String> columns = new ArrayList<>();
        columns.add(FILENAME_COLUMN);
        LinkedHashSet<String> seen = new LinkedHashSet<>(columns);
        for (String m : measurements) {
            String name = (m == null || m.isBlank()) ? "measurement" : m.trim();
            // Our own columns are fixed by the contract or by the README's
            // mapping, so a colliding measurement is the one that moves.
            if (FILENAME_COLUMN.equals(name) || ours.contains(name)) {
                name = name + COLLISION_SUFFIX;
            }
            while (!seen.add(name)) {
                name = name + "_2";
            }
            columns.add(name);
        }
        List<String> text = new ArrayList<>();
        for (String o : ours) {
            String name = o;
            while (!seen.add(name)) {
                name = name + "_2";
            }
            columns.add(name);
            if (!CLASS_INDEX_COLUMN.equals(o)) text.add(name);
        }
        return new Header(List.copyOf(columns), List.copyOf(text));
    }

    /**
     * The TraitHorizon command line for a written bundle.
     *
     * @param assetsDir the crops folder
     * @param tsv       the written TSV
     * @param header    the table's header, for the hidden-axis names
     * @return a copy-pasteable command
     */
    public static String traitHorizonCommand(Path assetsDir, Path tsv, Header header) {
        StringBuilder sb = new StringBuilder("traithorizon ");
        sb.append(quoteArg(assetsDir.toString())).append(' ').append(quoteArg(tsv.toString()));
        List<String> hide = header.hideAxes();
        if (!hide.isEmpty()) {
            sb.append(" --hide_axes");
            for (String h : hide) {
                sb.append(' ').append(quoteArg(h));
            }
        }
        return sb.toString();
    }

    private static String quoteArg(String s) {
        return s.indexOf(' ') >= 0 ? "\"" + s + "\"" : s;
    }

    // ==================== cell values ====================

    /**
     * How many of {@code detections} carry a finite value for each measurement.
     *
     * <p>Shown before an export runs because TraitHorizon requires no missing
     * values, so QP-CAT drops any cell missing one -- which means a measurement
     * present on a few percent of cells silently deletes nearly the whole
     * export. Knowing that beforehand turns it into a choice.
     *
     * @param detections cells to examine
     * @param measurements measurement names
     * @return count of cells with a finite value, index-aligned with {@code measurements}
     */
    public static int[] completeness(List<PathObject> detections, List<String> measurements) {
        int[] out = new int[measurements.size()];
        for (PathObject det : detections) {
            var ml = det.getMeasurements();
            for (int i = 0; i < measurements.size(); i++) {
                Number v = ml.get(measurements.get(i));
                if (v != null && Double.isFinite(v.doubleValue())) out[i]++;
            }
        }
        return out;
    }

    /**
     * Name the measurements that cost the export its cells, worst first.
     *
     * <p>Reported worst-first rather than in the order chosen, because this
     * message exists only when something went wrong and the reader needs the
     * culprit. Measured against the real synthetic dataset: a list led by five
     * measurements at 100% coverage, truncated before reaching the one at 0%,
     * says "no cell carried every chosen measurement" and names nothing that
     * explains it.
     *
     * @param names    measurement names
     * @param present  count of cells carrying a finite value, index-aligned
     * @param examined cells scanned
     * @return a one-line summary, or "" when there is nothing to say
     */
    static String coverageSummary(List<String> names, int[] present, int examined) {
        if (names == null || present == null || examined == 0
                || present.length != names.size()) {
            return "";
        }
        // A measurement on NO cell is a different mistake from a sparse one --
        // almost always a name that does not exist on these detections.
        List<String> absent = new ArrayList<>();
        Integer[] order = new Integer[names.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
            if (present[i] == 0) absent.add(names.get(i));
        }
        if (!absent.isEmpty()) {
            return "Scanned " + examined + " cell(s), and "
                    + (absent.size() == 1 ? "this measurement is" : "these measurements are")
                    + " on none of them: " + String.join(", ", absent)
                    + ". Check the name against the measurements on these detections.";
        }
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(present[a], present[b]));
        StringBuilder sb = new StringBuilder("Scanned ").append(examined)
                .append(" cell(s); lowest coverage: ");
        int shown = Math.min(5, order.length);
        for (int k = 0; k < shown; k++) {
            if (k > 0) sb.append(", ");
            int i = order[k];
            sb.append(names.get(i)).append(' ')
                    .append(Math.round(100.0 * present[i] / examined)).append('%');
        }
        if (order.length > shown) sb.append(", ...");
        return sb.toString();
    }

    /**
     * A cell's measurement values, or null when any is missing or non-finite.
     *
     * @param det          the cell
     * @param measurements measurement names, in order
     * @return the values, or null when the cell cannot be exported
     */
    static double[] valuesOf(PathObject det, List<String> measurements) {
        double[] out = new double[measurements.size()];
        var ml = det.getMeasurements();
        for (int i = 0; i < measurements.size(); i++) {
            Number v = ml.get(measurements.get(i));
            if (v == null) return null;
            double d = v.doubleValue();
            if (!Double.isFinite(d)) return null;
            out[i] = d;
        }
        return out;
    }

    // ==================== writing ====================

    /**
     * Write one table.
     *
     * @param file   destination
     * @param format delimiter, quoting and byte-order mark
     * @param header the header row
     * @param rows   the rows, in order
     * @throws IOException on a write failure
     */
    public static void writeTable(Path file, Format format, Header header,
                                  List<Row> rows) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            if (format.hasBom()) {
                w.write(BOM);
            }
            writeLine(w, format, header.columns());
            for (Row row : rows) {
                List<String> cells = new ArrayList<>(header.size());
                cells.add(row.filename());
                for (double v : row.values()) {
                    cells.add(formatNumber(v));
                }
                cells.add(Integer.toString(row.classIndex()));
                if (row.classification() != null) cells.add(row.classification());
                if (row.imageName() != null) cells.add(row.imageName());
                writeLine(w, format, cells);
            }
        }
    }

    private static void writeLine(BufferedWriter w, Format format, List<String> cells)
            throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) w.write(format.getDelimiter());
            w.write(escape(cells.get(i), format));
        }
        w.write('\n');
    }

    /**
     * Make one cell safe for the format.
     *
     * <p>TSV has no quoting, so a tab or newline inside a value would create a
     * column or a row: they are replaced with a space. CSV quotes instead, so
     * nothing is lost.
     */
    static String escape(String raw, Format format) {
        String s = raw == null ? "" : raw;
        if (format == Format.TSV) {
            return s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
        }
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0
                && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    /**
     * Format a measurement for a table TraitHorizon parses with
     * {@code d3.autoType}: plain decimal or scientific notation, no grouping
     * separators, and a locale-independent decimal point (a comma would split
     * the CSV row and is not a number to d3).
     */
    static String formatNumber(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return String.format(Locale.US, "%.6g", v);
    }

    // ==================== the export ====================

    /**
     * Write the bundle. Must run off the JavaFX application thread.
     *
     * @param qupath    the GUI, for the project and the image servers
     * @param entries   images to export from, or null for the open image alone
     * @param opts      what to export
     * @param progress  receives status lines; may be null
     * @param cancelled polled between cells; may be null
     * @return what was written
     * @throws IOException when nothing could be written
     */
    public static Result export(QuPathGUI qupath,
                                List<ProjectImageEntry<BufferedImage>> entries,
                                Options opts,
                                Consumer<String> progress,
                                BooleanSupplier cancelled) throws IOException {
        if (opts.outputDir == null) {
            throw new IOException("No output folder was chosen.");
        }
        if (opts.measurements.isEmpty()) {
            throw new IOException("No measurements were chosen for the feature table.");
        }
        if (opts.formats.isEmpty()) {
            throw new IOException("No output format was chosen (TSV and/or CSV).");
        }

        report(progress, "Reading cells...");
        Gathered gathered = gather(qupath, entries, opts, progress);
        if (gathered.cells.isEmpty()) {
            throw new IOException("No cell carried every chosen measurement. "
                    + gathered.missingSummary());
        }

        // Group by classification so a rare population keeps its floor, then
        // draw each group's share. One group when nothing is classified.
        Map<String, List<Candidate>> byClass = new TreeMap<>();
        for (Candidate c : gathered.cells) {
            byClass.computeIfAbsent(c.className, k -> new ArrayList<>()).add(c);
        }
        List<String> classNames = new ArrayList<>(byClass.keySet());
        int[] sizes = new int[classNames.size()];
        for (int i = 0; i < classNames.size(); i++) {
            sizes[i] = byClass.get(classNames.get(i)).size();
        }
        int[] targets = StratifiedSample.allocateCounts(sizes, opts.globalCap, opts.minPerClass);

        List<Candidate> selected = new ArrayList<>();
        for (int i = 0; i < classNames.size(); i++) {
            selected.addAll(StratifiedSample.draw(
                    byClass.get(classNames.get(i)), targets[i], opts.seed, i));
        }
        int notSampled = gathered.cells.size() - selected.size();

        Header header = buildHeader(opts.measurements,
                opts.includeClassification, opts.includeImageName);
        Path imagesDir = opts.outputDir.resolve("images");
        if (opts.writeCrops) {
            Files.createDirectories(imagesDir);
        } else {
            Files.createDirectories(opts.outputDir);
        }

        List<Row> rows = new ArrayList<>(selected.size());
        int crops = 0;
        report(progress, "Writing " + selected.size() + " cell(s) to "
                + opts.outputDir + " ...");
        try (CellCropService cropService = new CellCropService(qupath)) {
            for (int i = 0; i < selected.size(); i++) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    report(progress, "Cancelled after " + i + " cell(s).");
                    break;
                }
                Candidate c = selected.get(i);
                String fname = String.format("cell_%06d.png", i);
                if (opts.writeCrops) {
                    try {
                        BufferedImage crop = cropService.readCrop(c.ref, opts.cropScale);
                        if (crop != null) {
                            ImageIO.write(crop, "png", imagesDir.resolve(fname).toFile());
                            crops++;
                        } else {
                            logger.warn("Crop unavailable for cell {} in '{}'", i, c.imageName);
                        }
                    } catch (Exception ex) {
                        logger.warn("Crop failed for cell {} in '{}': {}",
                                i, c.imageName, ex.getMessage());
                    }
                }
                rows.add(new Row(fname, c.values, classNames.indexOf(c.className),
                        opts.includeClassification ? c.className : null,
                        opts.includeImageName ? c.imageName : null));
                if ((i & 0xff) == 0 && i > 0) {
                    report(progress, "Wrote " + i + "/" + selected.size() + " cell(s)...");
                }
            }
        }

        // A crop that could not be read leaves a row pointing at a file that is
        // not there, and TraitHorizon's filename column must match a real file.
        if (opts.writeCrops && crops < rows.size()) {
            List<Row> present = new ArrayList<>(crops);
            for (Row r : rows) {
                if (Files.exists(imagesDir.resolve(r.filename()))) present.add(r);
            }
            logger.warn("Dropped {} row(s) whose crop could not be read",
                    rows.size() - present.size());
            rows = present;
        }

        List<Path> tables = new ArrayList<>();
        for (Format format : opts.formats) {
            Path file = opts.outputDir.resolve("features." + format.getExtension());
            writeTable(file, format, header, rows);
            tables.add(file);
        }

        Path readme = opts.outputDir.resolve("README.txt");
        writeReadme(readme, opts, header, classNames, sizes, rows.size(), crops,
                gathered, notSampled, imagesDir, tables);

        logger.info("Crop/table export: {} cells ({} crops) across {} image(s), "
                        + "{} classification(s) -> {}",
                rows.size(), crops, gathered.imagesUsed, classNames.size(), opts.outputDir);
        OperationLogger.getInstance().logEvent("CROP TABLE EXPORT",
                "Exported " + rows.size() + " cell(s) (" + crops + " crops, "
                + opts.measurements.size() + " measurements) across " + gathered.imagesUsed
                + " image(s) to " + opts.outputDir);

        return new Result(rows.size(), crops, gathered.skippedMissing, notSampled,
                gathered.imagesUsed, classNames, opts.outputDir, tables);
    }

    // ==================== gathering ====================

    /** A cell that has every chosen measurement, with what it takes to crop it. */
    private static final class Candidate {
        final CellRef ref;
        final double[] values;
        final String className;
        final String imageName;

        Candidate(CellRef ref, double[] values, String className, String imageName) {
            this.ref = ref;
            this.values = values;
            this.className = className;
            this.imageName = imageName;
        }
    }

    /** Exportable cells plus why the rest were left out. */
    private static final class Gathered {
        final List<Candidate> cells = new ArrayList<>();
        int examined = 0;
        int skippedMissing = 0;
        int skippedUnclassified = 0;
        int skippedNoRoi = 0;
        int imagesUsed = 0;
        /** Cells carrying a finite value, per measurement, summed over the scope. */
        int[] present;
        List<String> measurements = List.of();

        String missingSummary() {
            return coverageSummary(measurements, present, examined);
        }
    }

    private static Gathered gather(QuPathGUI qupath,
                                   List<ProjectImageEntry<BufferedImage>> entries,
                                   Options opts, Consumer<String> progress) throws IOException {
        Gathered g = new Gathered();
        g.measurements = opts.measurements;
        g.present = new int[opts.measurements.size()];

        Project<BufferedImage> project = qupath.getProject();
        ImageData<BufferedImage> openData = qupath.getImageData();
        ProjectImageEntry<BufferedImage> openEntry =
                (project != null && openData != null) ? project.getEntry(openData) : null;

        if (entries == null) {
            if (openData == null) {
                throw new IOException("No image is open and no images were chosen.");
            }
            String name = openData.getServer().getMetadata().getName();
            collect(openData, openEntry != null ? openEntry.getID() : null, name, opts, g);
            g.imagesUsed = 1;
            return g;
        }

        for (ProjectImageEntry<BufferedImage> entry : entries) {
            boolean isOpen = openEntry != null && openEntry.getID().equals(entry.getID());
            ImageData<BufferedImage> data;
            if (isOpen) {
                data = openData;
            } else {
                try {
                    data = entry.readImageData();
                } catch (Exception e) {
                    logger.warn("Crop/table export: could not read '{}': {}",
                            entry.getImageName(), e.getMessage());
                    continue;
                }
            }
            int before = g.cells.size();
            report(progress, "Reading " + entry.getImageName() + "...");
            collect(data, entry.getID(), entry.getImageName(), opts, g);
            if (g.cells.size() > before) g.imagesUsed++;
            if (!isOpen) ImageDataResources.closeQuietly(data);
        }
        return g;
    }

    private static void collect(ImageData<BufferedImage> data, String imageId, String imageName,
                                Options opts, Gathered g) {
        for (PathObject det : DetectionSelector.filterToCellsWhenPresent(
                data.getHierarchy().getDetectionObjects(), imageName)) {
            g.examined++;
            var ml = det.getMeasurements();
            for (int i = 0; i < opts.measurements.size(); i++) {
                Number v = ml.get(opts.measurements.get(i));
                if (v != null && Double.isFinite(v.doubleValue())) g.present[i]++;
            }
            ROI roi = det.getROI();
            if (roi == null) {
                g.skippedNoRoi++;
                continue;
            }
            boolean unclassified = CellClasses.isUnclassified(det);
            if (opts.classifiedOnly && unclassified) {
                g.skippedUnclassified++;
                continue;
            }
            double[] values = valuesOf(det, opts.measurements);
            if (values == null) {
                g.skippedMissing++;
                continue;
            }
            double half = 0.5 * Math.max(roi.getBoundsWidth(), roi.getBoundsHeight());
            g.cells.add(new Candidate(
                    new CellRef(imageId, imageName, CellRef.idOf(det),
                            roi.getCentroidX(), roi.getCentroidY(), half),
                    values, CellClasses.displayNameOf(det), imageName));
        }
    }

    // ==================== README ====================

    private static void writeReadme(Path file, Options opts, Header header,
                                    List<String> classNames, int[] sizes, int rowsWritten,
                                    int crops, Gathered g, int notSampled, Path imagesDir,
                                    List<Path> tables) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("QP-CAT: cell crops + feature table\n");
        sb.append("==================================\n\n");
        sb.append("Written ").append(java.time.LocalDateTime.now()).append("\n\n");

        sb.append("Contents\n--------\n");
        if (opts.writeCrops) {
            sb.append("  images/      ").append(crops).append(" PNG crop(s), one per cell\n");
        }
        for (Path t : tables) {
            sb.append("  ").append(t.getFileName()).append("      ")
                    .append(rowsWritten).append(" row(s), ").append(header.size())
                    .append(" column(s)\n");
        }
        sb.append("\n");

        sb.append("Opening this in TraitHorizon\n----------------------------\n");
        sb.append("TraitHorizon plots every column as one axis of a parallel-coordinates\n");
        sb.append("plot and shows each cell's crop beside its feature vector, which is how\n");
        sb.append("you tell a real population from a cluster of segmentation artifacts.\n");
        sb.append("Install it, then run:\n\n");
        Path tsv = tables.stream()
                .filter(p -> p.getFileName().toString().endsWith(".tsv"))
                .findFirst().orElse(null);
        if (tsv != null) {
            sb.append("  ").append(traitHorizonCommand(imagesDir, tsv, header)).append("\n\n");
        } else {
            sb.append("  (no TSV was written -- TraitHorizon reads the TSV, not the CSV)\n\n");
        }
        sb.append("Docs: https://traithorizon.readthedocs.io/en/latest/usage.html\n");
        sb.append("Code: https://github.com/choosehappy/TraitHorizon\n\n");
        sb.append("QP-CAT writes TraitHorizon's file format and uses none of its code.\n");
        sb.append("TraitHorizon is a separate project by a different group; QP-CAT does not\n");
        sb.append("support it, and nothing here has been verified against its output.\n\n");

        sb.append("What the columns mean\n---------------------\n");
        sb.append("  filename      the crop in images/; TraitHorizon requires this first\n");
        sb.append("  <measurement> the QuPath measurement of that name, unchanged\n");
        sb.append("  class_index   index into the classification list below; -1 is unused\n");
        if (opts.includeClassification) {
            sb.append("  classification  the classification name (text, not an axis)\n");
        }
        if (opts.includeImageName) {
            sb.append("  image         the source image name (text, not an axis)\n");
        }
        sb.append("\nClassifications (class_index -> name, cells found in scope):\n");
        for (int i = 0; i < classNames.size(); i++) {
            sb.append("  ").append(i).append("  ").append(classNames.get(i))
                    .append("  (").append(sizes[i]).append(" cell(s))\n");
        }
        sb.append("\n");

        sb.append("What was left out, and why\n--------------------------\n");
        sb.append("  cells scanned in scope:            ").append(g.examined).append("\n");
        sb.append("  rows written:                      ").append(rowsWritten).append("\n");
        sb.append("  dropped, a measurement missing:    ").append(g.skippedMissing).append("\n");
        if (opts.classifiedOnly) {
            sb.append("  dropped, no classification:        ")
                    .append(g.skippedUnclassified).append("\n");
        }
        if (g.skippedNoRoi > 0) {
            sb.append("  dropped, no ROI to crop:           ")
                    .append(g.skippedNoRoi).append("\n");
        }
        sb.append("  not sampled (cap ").append(opts.globalCap)
                .append(", floor ").append(opts.minPerClass).append("):  ")
                .append(notSampled).append("\n\n");
        sb.append("TraitHorizon requires no missing values, so a cell missing ANY chosen\n");
        sb.append("measurement is dropped rather than written with a blank. Per-measurement\n");
        sb.append("coverage over the scope:\n");
        for (int i = 0; i < g.present.length; i++) {
            int pct = g.examined == 0 ? 0 : (int) Math.round(100.0 * g.present[i] / g.examined);
            sb.append("  ").append(pct).append("%  ").append(g.measurements.get(i)).append("\n");
        }
        sb.append("\nThe sample is seeded (seed ").append(opts.seed)
                .append("), size-proportional with a floor of ").append(opts.minPerClass)
                .append(" per\nclassification, so the same settings write the same file twice ")
                .append("and a rare\npopulation does not sample away. It is NOT the whole ")
                .append("dataset: counts read off\nthis table are counts of the sample.\n\n");

        sb.append("Reading it back\n---------------\n");
        sb.append("The crops are rendered with the viewer's current brightness, contrast and\n");
        sb.append("channel selection, at ").append(opts.cropScale)
                .append("x the cell bounding box. They are a picture of\n");
        sb.append("the cell, not the pixel data: do not measure them.\n");

        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void report(Consumer<String> progress, String message) {
        if (progress != null) progress.accept(message);
        logger.debug(message);
    }
}
