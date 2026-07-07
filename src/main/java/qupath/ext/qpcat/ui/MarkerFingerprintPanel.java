package qupath.ext.qpcat.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * "Marker fingerprint" small-multiples view of a clustering result: one compact
 * card per cluster showing its top-ranked markers as horizontal bars, laid out
 * in a wrapping grid so every cluster reads at a glance without decoding a
 * heatmap color scale.
 *
 * <p>Built entirely from the {@code marker_rankings} JSON the run already
 * produces (per cluster, an ordered list of {@code {name, score, logfoldchange,
 * pval_adj}} from scanpy's Wilcoxon {@code rank_genes_groups}). Bar length
 * encodes log2 fold-change vs. the rest (falling back to the Wilcoxon score when
 * a marker's fold-change is undefined), normalized across all shown markers so
 * magnitudes are comparable between clusters. The card header is tinted with the
 * cluster's palette color and shows the cluster size.</p>
 */
public class MarkerFingerprintPanel extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(MarkerFingerprintPanel.class);

    private static final double BAR_MAX_W = 118;   // px at the global-max value
    private static final double BAR_H = 11;
    private static final double NAME_W = 58;
    private static final double VAL_W = 46;

    private final Map<String, List<Map<String, Object>>> rankings;
    private final long[] clusterSizes;
    private final long totalCells;
    private final IntFunction<Color> clusterColor;

    private final FlowPane cards = new FlowPane(14, 14);
    private int topK = 5;

    /**
     * @param markerRankingsJson the run's {@code marker_rankings} payload (may be null)
     * @param clusterLabels      per-cell cluster id (for cluster sizes)
     * @param nClusters          number of clusters
     * @param clusterColorFn     cluster id -> palette color (matches the other tabs)
     */
    public MarkerFingerprintPanel(String markerRankingsJson, int[] clusterLabels,
                                  int nClusters, IntFunction<Color> clusterColorFn) {
        this.clusterColor = clusterColorFn;
        this.rankings = parse(markerRankingsJson);

        int n = Math.max(nClusters, 0);
        long[] sizes = new long[n];
        long total = 0;
        if (clusterLabels != null) {
            for (int lab : clusterLabels) {
                if (lab >= 0 && lab < n) {
                    sizes[lab]++;
                    total++;
                }
            }
        }
        this.clusterSizes = sizes;
        this.totalCells = total;

        setPadding(new Insets(10));
        setTop(buildHeader());

        cards.setPadding(new Insets(8, 0, 0, 0));
        ScrollPane scroll = new ScrollPane(cards);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");
        setCenter(scroll);

        rebuild();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<Map<String, Object>>> parse(String json) {
        if (json == null || json.isBlank()) {
            return java.util.Collections.emptyMap();
        }
        try {
            Gson gson = new GsonBuilder().serializeNulls().create();
            Type type = new TypeToken<Map<String, List<Map<String, Object>>>>() {}.getType();
            Map<String, List<Map<String, Object>>> parsed = gson.fromJson(json, type);
            return parsed != null ? parsed : java.util.Collections.emptyMap();
        } catch (Exception e) {
            logger.warn("Could not parse marker rankings for fingerprint view: {}", e.getMessage());
            return java.util.Collections.emptyMap();
        }
    }

    private Region buildHeader() {
        VBox box = new VBox(6);
        box.setPadding(new Insets(0, 0, 6, 0));

        Label title = new Label("Top markers per cluster");
        title.setStyle("-fx-font-weight: bold;");

        Label note = new Label("One card per cluster, tinted with its color. Bars show each "
                + "marker's enrichment (log2 fold-change vs. the rest), longest = most "
                + "cluster-defining. Hover a bar for the Wilcoxon score and adjusted p-value.");
        note.setWrapText(true);
        note.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        Spinner<Integer> topSpinner = new Spinner<>(1, 12, topK, 1);
        topSpinner.setPrefWidth(70);
        topSpinner.setEditable(true);
        topSpinner.valueProperty().addListener((o, a, b) -> {
            if (b != null) {
                topK = b;
                rebuild();
            }
        });
        HBox controls = new HBox(8, new Label("Markers per cluster:"), topSpinner);
        controls.setAlignment(Pos.CENTER_LEFT);

        box.getChildren().addAll(title, note, controls);
        return box;
    }

    private void rebuild() {
        cards.getChildren().clear();
        if (rankings.isEmpty()) {
            cards.getChildren().add(new Label("No marker rankings available for this result."));
            return;
        }

        // Global max magnitude across every shown marker so bar lengths are
        // comparable between clusters. log2FC preferred; fall back to score.
        double globalMax = 1e-9;
        for (List<Map<String, Object>> markers : rankings.values()) {
            int shown = Math.min(topK, markers.size());
            for (int i = 0; i < shown; i++) {
                globalMax = Math.max(globalMax, Math.abs(barValue(markers.get(i))));
            }
        }

        for (String cid : sortedClusterIds()) {
            cards.getChildren().add(buildCard(cid, rankings.get(cid), globalMax));
        }
    }

    private List<String> sortedClusterIds() {
        List<String> ids = new ArrayList<>(rankings.keySet());
        // Numeric order when the keys are integers ("0","1",...,"10"); lexical otherwise.
        ids.sort((a, b) -> {
            double da = parseOrMax(a);
            double db = parseOrMax(b);
            if (da != db) return Double.compare(da, db);
            return a.compareTo(b);
        });
        return ids;
    }

    private static double parseOrMax(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return Double.MAX_VALUE; }
    }

    private VBox buildCard(String cid, List<Map<String, Object>> markers, double globalMax) {
        int clusterId = -1;
        try { clusterId = Integer.parseInt(cid); } catch (Exception ignore) { /* keep -1 */ }

        Color color = colorFor(clusterId);

        // Header: color chip + "Cluster N" + size.
        Rectangle chip = new Rectangle(13, 13, color);
        chip.setArcWidth(3);
        chip.setArcHeight(3);
        chip.setStroke(Color.gray(0.6));
        Label name = new Label("Cluster " + cid);
        name.setStyle("-fx-font-weight: bold;");
        String sizeText = "";
        if (clusterId >= 0 && clusterId < clusterSizes.length && totalCells > 0) {
            long sz = clusterSizes[clusterId];
            sizeText = String.format("  %,d (%.1f%%)", sz, 100.0 * sz / totalCells);
        }
        Label size = new Label(sizeText);
        size.setStyle("-fx-font-size: 10.5px; -fx-text-fill: #777;");
        HBox header = new HBox(6, chip, name, size);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox rows = new VBox(3);
        int shown = markers == null ? 0 : Math.min(topK, markers.size());
        if (shown == 0) {
            Label empty = new Label("(no markers)");
            empty.setStyle("-fx-font-size: 10.5px; -fx-text-fill: #999;");
            rows.getChildren().add(empty);
        }
        for (int i = 0; i < shown; i++) {
            rows.getChildren().add(buildBarRow(markers.get(i), color, globalMax));
        }

        VBox card = new VBox(6, header, rows);
        card.setPadding(new Insets(8));
        card.setPrefWidth(NAME_W + BAR_MAX_W + VAL_W + 40);
        // Faint tint of the cluster color as the card background so groups of
        // cards are visually separable at a glance.
        card.setStyle(String.format(
                "-fx-background-color: %s; -fx-border-color: #ccc; -fx-border-radius: 5; "
                + "-fx-background-radius: 5;", toRgba(color, 0.07)));
        return card;
    }

    private HBox buildBarRow(Map<String, Object> marker, Color color, double globalMax) {
        String mName = String.valueOf(marker.get("name"));
        double val = barValue(marker);
        double lfc = num(marker.get("logfoldchange"));
        double score = num(marker.get("score"));
        double padj = num(marker.get("pval_adj"));

        Label nameLbl = new Label(mName);
        nameLbl.setStyle("-fx-font-size: 11px;");
        nameLbl.setMinWidth(NAME_W);
        nameLbl.setMaxWidth(NAME_W);
        nameLbl.setPrefWidth(NAME_W);

        double frac = globalMax > 0 ? Math.min(1.0, Math.abs(val) / globalMax) : 0;
        double w = Math.max(2, frac * BAR_MAX_W);
        Rectangle bar = new Rectangle(w, BAR_H);
        bar.setArcWidth(3);
        bar.setArcHeight(3);
        // Negative enrichment (rare for a top marker) drawn muted so it is distinct.
        bar.setFill(val >= 0 ? color : color.deriveColor(0, 0.5, 1.0, 0.6));

        String valText = Double.isNaN(lfc)
                ? (Double.isNaN(score) ? "n/a" : String.format("z=%.1f", score))
                : String.format("%+.1f", lfc);
        Label valLbl = new Label(valText);
        valLbl.setStyle("-fx-font-size: 10px; -fx-text-fill: #555;");
        valLbl.setMinWidth(VAL_W);

        HBox row = new HBox(6, nameLbl, bar, valLbl);
        row.setAlignment(Pos.CENTER_LEFT);

        StringBuilder tip = new StringBuilder(mName);
        if (!Double.isNaN(lfc)) tip.append(String.format("%nlog2FC: %+.2f", lfc));
        if (!Double.isNaN(score)) tip.append(String.format("%nWilcoxon score: %.2f", score));
        if (!Double.isNaN(padj)) tip.append(String.format("%nadj. p-value: %.2e", padj));
        Tooltip.install(row, new Tooltip(tip.toString()));
        return row;
    }

    /** Bar magnitude for a marker: log2 fold-change if finite, else Wilcoxon score. */
    private static double barValue(Map<String, Object> marker) {
        double lfc = num(marker.get("logfoldchange"));
        if (!Double.isNaN(lfc)) return lfc;
        double score = num(marker.get("score"));
        return Double.isNaN(score) ? 0 : score;
    }

    private static double num(Object o) {
        if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            return (Double.isNaN(d) || Double.isInfinite(d)) ? Double.NaN : d;
        }
        return Double.NaN;
    }

    private Color colorFor(int cluster) {
        if (cluster < 0) return Color.GRAY;
        Color c = clusterColor != null ? clusterColor.apply(cluster) : null;
        return c != null ? c : Color.GRAY;
    }

    private static String toRgba(Color c, double alpha) {
        return String.format("rgba(%d,%d,%d,%.2f)",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255), alpha);
    }
}
