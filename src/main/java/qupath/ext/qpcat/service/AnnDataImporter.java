package qupath.ext.qpcat.service;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javafx.application.Platform;
import org.apposed.appose.NDArray;
import org.apposed.appose.Service.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.CellRef;
import qupath.lib.common.ColorTools;
import qupath.lib.images.ImageData;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathObject;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Imports labels and measurements from an AnnData (.h5ad) file onto the QuPath
 * cells it describes, matched by object id.
 *
 * <p>QP-CAT's export names every cell ({@code obs_names}) with its object id and
 * records the image in {@code obs["qupath_image_id"]}. A cell whose name matches
 * no detection is reported and skipped; nothing is matched by position.
 */
public final class AnnDataImporter {

    private static final Logger logger = LoggerFactory.getLogger(AnnDataImporter.class);

    /** Prefix of every measurement an import writes. */
    public static final String MEASUREMENT_PREFIX = "AnnData: ";

    private AnnDataImporter() {}

    /** An obs column and how it can be imported. */
    public static final class Column {
        private String name;
        @SerializedName("as_class") private boolean asClass;
        @SerializedName("as_number") private boolean asNumber;
        @SerializedName("n_values") private int nValues;
        @SerializedName("n_missing") private int nMissing;

        public String getName() { return name; }
        public boolean isAsClass() { return asClass; }
        public boolean isAsNumber() { return asNumber; }
        public int getNValues() { return nValues; }
        public int getNMissing() { return nMissing; }
    }

    /** An obsm array that can be imported as one measurement per axis. */
    public static final class Embedding {
        private String key;
        @SerializedName("n_dims") private int nDims;

        public String getKey() { return key; }
        public int getNDims() { return nDims; }
    }

    /** An image named in the file. */
    public static final class FileImage {
        private String id;
        private String name;
        @SerializedName("n_cells") private int nCells;

        public String getId() { return id; }
        public String getName() { return name; }
        public int getNCells() { return nCells; }
    }

    /** What a file holds. */
    public static final class Summary {
        @SerializedName("n_cells") private int nCells;
        private List<Column> columns;
        private List<Embedding> embeddings;
        private List<FileImage> images;
        @SerializedName("has_image_ids") private boolean hasImageIds;

        public int getNCells() { return nCells; }
        public List<Column> getColumns() { return columns == null ? List.of() : columns; }
        public List<Embedding> getEmbeddings() {
            return embeddings == null ? List.of() : embeddings;
        }
        public List<FileImage> getImages() { return images == null ? List.of() : images; }
        public boolean hasImageIds() { return hasImageIds; }
    }

    /**
     * The values chosen from a file, one row per cell in file order.
     *
     * @param names       cell names (object ids)
     * @param imageIds    project image id per cell, or null when the file has none
     * @param classCodes  index into {@code classNames} per cell; -1 for no value
     * @param classNames  class names; empty when no class column was chosen
     * @param classColors {@code #rrggbb} per class name, or null
     * @param values      n_cells x valueNames.size()
     * @param valueNames  measurement names without {@link #MEASUREMENT_PREFIX}
     */
    public record Contents(String[] names, String[] imageIds, int[] classCodes,
                           List<String> classNames, List<String> classColors,
                           double[][] values, List<String> valueNames) {
        public int nCells() { return names.length; }
        public boolean hasClasses() { return !classNames.isEmpty(); }
    }

    /** One image's matches. */
    public static final class ImageMatch {
        public String imageName;
        public int fileCells;
        public int matched;
        /** Matched cells whose classification the import changes. */
        public int reclassified;
        public String error;
    }

    /** What an import matched, or did. */
    public static final class Report {
        public final List<ImageMatch> images = new ArrayList<>();
        /** Cells naming an image that is not in this project. */
        public int cellsInOtherImages;
        public int otherImages;

        public int fileCells() {
            return images.stream().mapToInt(m -> m.fileCells).sum() + cellsInOtherImages;
        }
        public int matched() { return images.stream().mapToInt(m -> m.matched).sum(); }
        public int reclassified() { return images.stream().mapToInt(m -> m.reclassified).sum(); }

        /** One line per image, then the totals. */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            for (ImageMatch m : images) {
                sb.append(m.imageName).append(": ");
                if (m.error != null) {
                    sb.append("ERROR ").append(m.error);
                } else {
                    sb.append(String.format("%,d of %,d cells found", m.matched, m.fileCells));
                    if (m.reclassified > 0) {
                        sb.append(String.format(", %,d change classification", m.reclassified));
                    }
                }
                sb.append('\n');
            }
            if (otherImages > 0) {
                sb.append(String.format("%,d cells in %d image(s) not in this project%n",
                        cellsInOtherImages, otherImages));
            }
            sb.append(String.format("Total: %,d of %,d cells found", matched(), fileCells()));
            return sb.toString();
        }
    }

    /**
     * Read what a file holds. Must be called with the extension class loader set.
     *
     * @param file the .h5ad file
     * @return the summary
     * @throws IOException when the file cannot be read
     */
    public static Summary inspect(Path file) throws IOException {
        Task task = ApposeClusteringService.getInstance().runTask("read_anndata",
                Map.of("path", file.toString(), "mode", "inspect"));
        return new Gson().fromJson((String) task.outputs.get("summary_json"), Summary.class);
    }

    /**
     * Read the chosen columns. Must be called with the extension class loader set.
     *
     * @param file           the .h5ad file
     * @param classColumn    obs column to import as the classification, or null
     * @param numberColumns  numeric obs columns to import as measurements
     * @param embeddingKeys  obsm keys to import, one measurement per axis
     * @return the values, in file order
     * @throws IOException when the file cannot be read or its cell names repeat
     */
    @SuppressWarnings("unchecked")
    public static Contents read(Path file, String classColumn, List<String> numberColumns,
                                List<String> embeddingKeys) throws IOException {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("path", file.toString());
        inputs.put("mode", "read");
        inputs.put("class_column", classColumn == null ? "" : classColumn);
        inputs.put("number_columns", numberColumns);
        inputs.put("embedding_keys", embeddingKeys);
        Task task = ApposeClusteringService.getInstance().runTask("read_anndata", inputs);
        NDArray codesNd = (NDArray) task.outputs.get("class_codes");
        NDArray valuesNd = (NDArray) task.outputs.get("values");
        try {
            List<String> names = (List<String>) task.outputs.get("obs_names");
            List<String> imageIds = (List<String>) task.outputs.get("image_ids");
            int n = names.size();
            int[] codes = new int[n];
            codesNd.buffer().asIntBuffer().get(codes);
            List<String> valueNames = (List<String>) task.outputs.get("value_names");
            double[][] values = new double[n][valueNames.size()];
            var buf = valuesNd.buffer().asDoubleBuffer();
            int stride = Math.max(1, valueNames.size());
            for (int i = 0; i < n; i++) {
                buf.position(i * stride);
                buf.get(values[i]);
            }
            return new Contents(names.toArray(String[]::new),
                    imageIds == null ? null : imageIds.toArray(String[]::new), codes,
                    (List<String>) task.outputs.get("class_names"),
                    (List<String>) task.outputs.get("class_colors"), values, valueNames);
        } finally {
            ApposeArrays.closeQuietly(codesNd, valuesNd);
        }
    }

    /**
     * File rows per project image id. A file without image ids, and rows with a
     * blank one (exported outside a project), belong to the open image.
     *
     * @param imageIds image id per row, or null
     * @param nRows    row count
     * @param openId   the open image's id, or null
     * @return image id to row indices, in first-seen order; a null key holds rows
     *         with no image to go to
     */
    static Map<String, List<Integer>> rowsByImage(String[] imageIds, int nRows, String openId) {
        Map<String, List<Integer>> rows = new LinkedHashMap<>();
        for (int i = 0; i < nRows; i++) {
            String id = imageIds == null || imageIds[i] == null || imageIds[i].isBlank()
                    ? openId : imageIds[i];
            rows.computeIfAbsent(id, k -> new ArrayList<>()).add(i);
        }
        return rows;
    }

    /**
     * The detection each row names, by object id.
     *
     * @param detections the image's detections
     * @param names      cell names, all rows
     * @param rows       the rows to look up
     * @return detection per entry of {@code rows}; null where none matches
     */
    static PathObject[] match(Iterable<PathObject> detections, String[] names, List<Integer> rows) {
        Map<String, PathObject> byId = new HashMap<>();
        for (PathObject d : detections) {
            String id = CellRef.idOf(d);
            if (id != null) byId.put(id, d);
        }
        PathObject[] out = new PathObject[rows.size()];
        for (int r = 0; r < out.length; r++) {
            out[r] = byId.get(names[rows.get(r)]);
        }
        return out;
    }

    /**
     * Match the file's cells and, when {@code write} is true, write the values.
     * Must be called off the JavaFX thread: it reads, and writes, project images.
     *
     * @param project  the open project, or null for the open image only
     * @param openData the open image, or null
     * @param contents what to import
     * @param write    false to count matches only
     * @return what was matched (and written)
     */
    @SuppressWarnings("unchecked")
    public static Report run(Project<BufferedImage> project, ImageData<BufferedImage> openData,
                             Contents contents, boolean write) {
        Report report = new Report();
        String openId = null;
        if (project != null && openData != null) {
            ProjectImageEntry<BufferedImage> e = project.getEntry(openData);
            openId = e != null ? e.getID() : null;
        }
        Map<String, List<Integer>> byImage =
                rowsByImage(contents.imageIds(), contents.nCells(), openId);
        for (var group : byImage.entrySet()) {
            String id = group.getKey();
            List<Integer> rows = group.getValue();
            boolean isOpen = openData != null && Objects.equals(id, openId);
            ImageData<BufferedImage> data = null;
            ImageData<BufferedImage> toClose = null;
            ImageMatch m = new ImageMatch();
            m.fileCells = rows.size();
            try {
                if (isOpen) {
                    data = openData;
                    m.imageName = openData.getServer().getMetadata().getName();
                } else if (project != null && id != null) {
                    data = SavedResultApplier.readById(project, id);
                    toClose = data;
                    m.imageName = SavedResultApplier.shortName(project, id);
                }
                if (data == null) {
                    report.otherImages++;
                    report.cellsInOtherImages += rows.size();
                    continue;
                }
                PathObject[] cells = match(data.getHierarchy().getDetectionObjects(),
                        contents.names(), rows);
                List<PathObject> classed = new ArrayList<>();
                List<Integer> codes = new ArrayList<>();
                for (int r = 0; r < cells.length; r++) {
                    PathObject cell = cells[r];
                    if (cell == null) continue;
                    m.matched++;
                    int code = contents.classCodes()[rows.get(r)];
                    if (contents.hasClasses() && code >= 0) {
                        String now = cell.getPathClass() == null
                                ? null : cell.getPathClass().toString();
                        if (!contents.classNames().get(code).equals(now)) m.reclassified++;
                        classed.add(cell);
                        codes.add(code);
                    }
                    if (write) writeValues(cell, contents, rows.get(r));
                }
                if (write && m.matched > 0) {
                    if (!classed.isEmpty()) {
                        new ResultApplier().applyClusterLabelsNamed(classed,
                                codes.stream().mapToInt(Integer::intValue).toArray(),
                                code -> contents.classNames().get(code));
                        applyColors(contents);
                    }
                    recordStep(data, contents, m);
                    if (project != null) {
                        ProjectImageEntry<BufferedImage> entry = project.getEntry(data);
                        if (entry != null) entry.saveImageData(data);
                    }
                    if (isOpen) {
                        var hierarchy = data.getHierarchy();
                        Platform.runLater(() ->
                                hierarchy.fireHierarchyChangedEvent(AnnDataImporter.class));
                    }
                }
            } catch (Exception ex) {
                logger.error("AnnData import failed on image {}", m.imageName, ex);
                m.error = ex.getMessage();
            } finally {
                ImageDataResources.closeQuietly(toClose);
            }
            if (m.imageName != null) report.images.add(m);
        }
        return report;
    }

    private static void writeValues(PathObject cell, Contents contents, int row) {
        if (contents.valueNames().isEmpty()) return;
        MeasurementList ml = cell.getMeasurementList();
        double[] values = contents.values()[row];
        for (int c = 0; c < values.length; c++) {
            String name = MEASUREMENT_PREFIX + contents.valueNames().get(c);
            if (Double.isFinite(values[c])) {
                ml.put(name, values[c]);
            } else {
                ml.remove(name);
            }
        }
    }

    /** scanpy's colors for the classes, when the file has them. */
    private static void applyColors(Contents contents) {
        if (contents.classColors() == null) return;
        Map<String, Integer> colors = new HashMap<>();
        for (int i = 0; i < contents.classNames().size(); i++) {
            String hex = contents.classColors().get(i);
            if (hex != null && hex.matches("#[0-9a-fA-F]{6}")) {
                int rgb = Integer.parseInt(hex.substring(1), 16);
                colors.put(contents.classNames().get(i),
                        ColorTools.packRGB((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff));
            }
        }
        new ResultApplier().applyClusterColors(colors);
    }

    private static void recordStep(ImageData<BufferedImage> data, Contents contents,
                                   ImageMatch m) {
        String what = (contents.hasClasses() ? "classification" : "")
                + (contents.hasClasses() && !contents.valueNames().isEmpty() ? " and " : "")
                + (contents.valueNames().isEmpty() ? ""
                        : contents.valueNames().size() + " measurement(s)");
        data.getHistoryWorkflow().addStep(new DefaultScriptableWorkflowStep(
                "QP-CAT: import from AnnData (" + m.matched + " cells)",
                "// QP-CAT: imported " + what + " from an AnnData file onto " + m.matched
                        + " of " + m.fileCells + " cells, matched by object id."));
    }
}
