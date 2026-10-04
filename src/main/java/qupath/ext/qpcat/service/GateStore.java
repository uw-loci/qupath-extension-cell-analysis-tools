package qupath.ext.qpcat.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.GateSet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes {@link GateSet} files, and decides whether a saved gate may
 * be replayed on the plot currently open.
 *
 * <p>The write is the easy half. The read has to answer a question the user
 * cannot answer by looking: a polygon dropped onto a plot built from different
 * numbers draws in exactly the same place and selects different cells. So a
 * load is never silent -- it reports {@link Match}, and the caller says so.
 */
public final class GateStore {

    private static final Logger logger = LoggerFactory.getLogger(GateStore.class);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Extension of a saved gate file. */
    public static final String EXTENSION = ".gates.json";

    private GateStore() {}

    /** How well a saved gate set fits the plot it is being loaded onto. */
    public enum Match {

        /** Same axis columns and the same coordinates: replay reproduces the selection. */
        EXACT,

        /**
         * Same axis columns, different coordinates.
         *
         * <p>For a biaxial plot this is the ordinary, useful case: the axes are
         * measurements, so the gate means the same thing on other cells. For an
         * embedding it is the dangerous one -- the layout is a property of that
         * run, so the same polygon encloses a different population.
         */
        SAME_COLUMNS,

        /** Different axis columns: the numbers under the polygon are not comparable. */
        DIFFERENT_COLUMNS
    }

    /** A classified load attempt: what fits, what does not, and what to tell the user. */
    public record Compatibility(Match match, boolean replayable, String message) {}

    /**
     * Write a gate set.
     *
     * @param file destination; its folder is created
     * @param set  the gates
     * @throws IOException on a write failure
     */
    public static void write(Path file, GateSet set) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(file, GSON.toJson(set), StandardCharsets.UTF_8);
        logger.info("Wrote {} gate(s) to {}", set.getGates().size(), file);
    }

    /**
     * Read a gate set.
     *
     * @param file the file
     * @return the gates
     * @throws IOException when the file cannot be read or holds no usable gate
     */
    public static GateSet read(Path file) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        GateSet set;
        try {
            set = GSON.fromJson(json, GateSet.class);
        } catch (JsonSyntaxException e) {
            throw new IOException("Not a QP-CAT gate file: " + e.getMessage(), e);
        }
        if (set == null || set.getGates().isEmpty()) {
            throw new IOException("No gates in " + file.getFileName());
        }
        if (set.getFormatVersion() > GateSet.FORMAT_VERSION) {
            throw new IOException("This file was written by a newer QP-CAT "
                    + "(gate format " + set.getFormatVersion() + ", this build reads "
                    + GateSet.FORMAT_VERSION + "). Update QP-CAT to load it.");
        }
        if (set.usableGates().isEmpty()) {
            throw new IOException("Every gate in " + file.getFileName()
                    + " has fewer than three vertices, so none encloses anything.");
        }
        return set;
    }

    /**
     * Whether {@code saved} can be replayed on the plot described by the
     * arguments, and what the user has to be told either way.
     *
     * @param saved       the loaded gate set
     * @param axes        the open plot's axes
     * @param fingerprint {@link GateSet#fingerprintOf(double[][])} of the open plot
     * @param nCells      cells on the open plot
     * @return the verdict
     */
    public static Compatibility check(GateSet saved, GateSet.Axes axes,
                                      String fingerprint, int nCells) {
        boolean sameColumns = eq(saved.getColumnX(), axes.columnX())
                && eq(saved.getColumnY(), axes.columnY())
                && eq(saved.getAxisMode(), axes.mode());

        if (!sameColumns) {
            return new Compatibility(Match.DIFFERENT_COLUMNS, false,
                    "These gates were drawn on " + describeAxes(saved)
                    + ", and this plot shows " + describeAxes(axes)
                    + ". A polygon drawn on one pair of axes means nothing on another, "
                    + "so it will not be loaded. Re-plot on the saved axes first.");
        }

        boolean sameData = fingerprint != null && fingerprint.equals(saved.getDataFingerprint());
        if (sameData) {
            return new Compatibility(Match.EXACT, true,
                    "Same axes and the same coordinates as when these gates were saved, "
                    + "so each one selects exactly the cells it selected then.");
        }

        if (saved.isEmbedding()) {
            // The case the user cannot see. The polygon lands where it always
            // did and holds a different population.
            return new Compatibility(Match.SAME_COLUMNS, true,
                    "WARNING: these gates were drawn on a DIFFERENT " + saved.getAxisName()
                    + " layout -- " + describeDataDifference(saved, fingerprint, nCells)
                    + ". Embedding coordinates belong to the run that produced them: "
                    + "reproducing them needs the same cells, the same measurements, the "
                    + "same parameters AND the same seed, and otherwise the layout may "
                    + "rotate, reflect or rescale freely. The gates will be drawn in the "
                    + "same place and will hold a different set of cells, which nothing "
                    + "on screen can show you. Check each gated population before you "
                    + "assign a class from it.");
        }

        return new Compatibility(Match.SAME_COLUMNS, true,
                "These gates were drawn on the same two measurements over different cells -- "
                + describeDataDifference(saved, fingerprint, nCells)
                + ". The axes are raw measurements, so a gate means the same thing here as "
                + "it did there; the cells it holds are this plot's cells.");
    }

    private static String describeDataDifference(GateSet saved, String fingerprint, int nCells) {
        if (saved.getNCells() > 0 && saved.getNCells() != nCells) {
            return String.format("%,d cells then, %,d now", saved.getNCells(), nCells);
        }
        if (saved.getDataFingerprint() == null) {
            return "the saved file recorded no coordinate fingerprint";
        }
        if (fingerprint == null) {
            return "this plot's coordinates could not be fingerprinted";
        }
        return "the same cell count but different coordinates";
    }

    /** Plain-language description of a saved set's axes. */
    public static String describeAxes(GateSet set) {
        return describeAxes(set.axes());
    }

    /** Plain-language description of a plot's axes. */
    public static String describeAxes(GateSet.Axes axes) {
        if (axes.isEmbedding()) {
            return "the " + axes.name() + " embedding";
        }
        return "'" + axes.columnX() + "' against '" + axes.columnY() + "'";
    }

    /**
     * Cells of {@code data} inside a gate.
     *
     * @param gate the gate
     * @param data per-cell {x, y} in the plot's data coordinates
     * @return the enclosed indices, ascending
     */
    public static int[] indicesIn(GateSet.Gate gate, double[][] data) {
        if (gate == null || data == null || !gate.isUsable()) {
            return new int[0];
        }
        List<double[]> poly = gate.vertexList();
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < data.length; i++) {
            double[] p = data[i];
            if (p == null || p.length < 2) continue;
            if (GateSet.contains(poly, p[0], p[1])) hits.add(i);
        }
        int[] out = new int[hits.size()];
        for (int i = 0; i < out.length; i++) out[i] = hits.get(i);
        return out;
    }

    /**
     * A filesystem-safe file name for a saved set.
     *
     * @param name the user's name for the set
     * @return the name with {@link #EXTENSION} appended
     */
    public static String fileNameFor(String name) {
        String base = FilenameSanitizer.sanitize(name == null || name.isBlank() ? "gates" : name);
        return base.endsWith(EXTENSION) ? base : base + EXTENSION;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
