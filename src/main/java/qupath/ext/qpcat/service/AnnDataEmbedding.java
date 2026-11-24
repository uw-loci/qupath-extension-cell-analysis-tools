package qupath.ext.qpcat.service;

import qupath.lib.objects.PathObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the 2D embedding an AnnData export carries in {@code obsm}.
 *
 * <p>QP-CAT writes its embeddings as {@code QPCAT UMAP1/2}, {@code QPCAT PCA1/2}
 * and {@code QPCAT tSNE1/2}; results from before it marked its columns used
 * {@code UMAP1/2} and so on. Until 0.26.0 the export looked only for the
 * unmarked names, so QP-CAT's own embedding never reached the file (a foreign
 * {@code UMAP1} column did), and cells missing the pair were written as (0, 0).
 */
public final class AnnDataEmbedding {

    private AnnDataEmbedding() {}

    /**
     * The embedding found on the cells.
     *
     * @param columnX the first coordinate's measurement
     * @param columnY the second coordinate's measurement
     * @param obsmKey the AnnData key ({@code X_umap}, {@code X_pca}, {@code X_tsne})
     * @param coords  per cell {x, y}; NaN where the cell lacks the pair
     * @param missing how many cells lack one or both values
     */
    public record Pick(String columnX, String columnY, String obsmKey,
                       double[][] coords, int missing) {}

    private record Candidate(String x, String y, String obsmKey) {}

    /** Today's names first, then the names results used before QP-CAT marked its columns. */
    static List<Candidate> candidates() {
        List<Candidate> out = new ArrayList<>();
        String[][] methods = {{"umap", "X_umap"}, {"pca", "X_pca"}, {"tsne", "X_tsne"}};
        for (String[] m : methods) {
            String p = ResultApplier.getEmbeddingPrefix(m[0]);
            out.add(new Candidate(p + "1", p + "2", m[1]));
        }
        for (String[] m : methods) {
            String p = ResultApplier.legacyEmbeddingPrefix(m[0], null);
            out.add(new Candidate(p + "1", p + "2", m[1]));
        }
        return out;
    }

    /**
     * The first embedding at least one cell carries, in {@link #candidates()} order.
     *
     * @param detections the exported cells, in export order
     * @return the pick, or null when no cell carries any known embedding pair
     */
    public static Pick pick(List<PathObject> detections) {
        if (detections == null || detections.isEmpty()) return null;
        for (Candidate c : candidates()) {
            boolean anyPresent = false;
            for (PathObject det : detections) {
                var ml = det.getMeasurements();
                if (ml.containsKey(c.x()) && ml.containsKey(c.y())) {
                    anyPresent = true;
                    break;
                }
            }
            if (!anyPresent) continue;
            double[][] coords = new double[detections.size()][2];
            int missing = 0;
            for (int i = 0; i < detections.size(); i++) {
                var ml = detections.get(i).getMeasurements();
                Number x = ml.get(c.x());
                Number y = ml.get(c.y());
                double xv = x == null ? Double.NaN : x.doubleValue();
                double yv = y == null ? Double.NaN : y.doubleValue();
                if (!Double.isFinite(xv) || !Double.isFinite(yv)) {
                    missing++;
                    xv = Double.NaN;
                    yv = Double.NaN;
                }
                coords[i][0] = xv;
                coords[i][1] = yv;
            }
            return new Pick(c.x(), c.y(), c.obsmKey(), coords, missing);
        }
        return null;
    }

    /**
     * One line for the user about what the export did with the embedding.
     *
     * @param pick the pick, or null
     * @param nCells cells exported
     * @return the note, or null when there is nothing to say
     */
    public static String note(Pick pick, int nCells) {
        if (pick == null) return null;
        String base = "Embedding " + pick.columnX() + "/" + pick.columnY() + " written as obsm['"
                + pick.obsmKey() + "']";
        if (pick.missing() == 0) return base + ".";
        return base + "; " + pick.missing() + " of " + nCells + " cells had no value and were "
                + "written as NaN (filter them before plotting in scanpy).";
    }
}
