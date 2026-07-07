package qupath.ext.qpcat.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * A glanceable, multi-view explorer of what defines each cluster, built from the
 * {@code marker_rankings} JSON the run already produces (per cluster, an ordered
 * list of {@code {name, score, logfoldchange, pval_adj}} from scanpy's Wilcoxon
 * {@code rank_genes_groups}). Three toggle-able views:
 *
 * <ul>
 *   <li><b>Measurements</b> -- one card per cluster, its top markers as bars
 *       (log2 fold-change vs. the rest); the most cluster-defining measurements.</li>
 *   <li><b>Channels</b> -- one card per cluster, the imaging channels those top
 *       markers come from, as chips colored by the channel's Viewer color; a
 *       shorter, more interpretable summary. Non-channel measurements (e.g.
 *       {@code Nucleus: Area}) collapse into a trailing "Other".</li>
 *   <li><b>Channels -&gt; clusters</b> -- one card per channel, listing the
 *       clusters that channel is a top marker of.</li>
 * </ul>
 *
 * <p>Channels are derived by matching each measurement name against the open
 * image's channel names; channel colors come from {@code ImageChannel.getColor()}
 * (independent of whether the channel is toggled on in the Viewer). When no
 * channel information is available the two channel views are disabled.</p>
 */
public class MarkerFingerprintPanel extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(MarkerFingerprintPanel.class);

    private static final double BAR_MAX_W = 130;   // px at the global-max value
    private static final double BAR_H = 11;
    private static final double VAL_W = 46;
    private static final double CARD_W = 300;

    private enum View { MEASUREMENTS, CHANNELS, CHANNELS_TO_CLUSTERS }

    private final Map<String, List<Map<String, Object>>> rankings;
    private final long[] clusterSizes;
    private final long totalCells;
    private final IntFunction<Color> clusterColor;

    // Channel name -> Viewer color (packed 0xRRGGBB), and names longest-first so a
    // measurement matches the most specific channel ("CD31" before "CD3").
    private final Map<String, Color> channelColor = new LinkedHashMap<>();
    private final List<String> channelNamesByLen = new ArrayList<>();

    private final FlowPane cards = new FlowPane(14, 14);
    private final Label noteLabel = new Label();
    private int topK = 5;
    private View view = View.MEASUREMENTS;

    /**
     * @param markerRankingsJson the run's {@code marker_rankings} payload (may be null)
     * @param clusterLabels      per-cell cluster id (for cluster sizes)
     * @param nClusters          number of clusters
     * @param clusterColorFn     cluster id -> palette color (matches the other tabs)
     * @param channelColors      channel name -> packed RGB (from the image), or null
     */
    public MarkerFingerprintPanel(String markerRankingsJson, int[] clusterLabels,
                                  int nClusters, IntFunction<Color> clusterColorFn,
                                  Map<String, Integer> channelColors) {
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

        if (channelColors != null) {
            // Longest names first for greedy longest-match extraction.
            List<String> names = new ArrayList<>(channelColors.keySet());
            names.removeIf(s -> s == null || s.isBlank());
            names.sort((a, b) -> Integer.compare(b.length(), a.length()));
            for (String nm : names) {
                Integer rgb = channelColors.get(nm);
                if (rgb != null) {
                    channelColor.put(nm, Color.rgb(
                            qupath.lib.common.ColorTools.red(rgb),
                            qupath.lib.common.ColorTools.green(rgb),
                            qupath.lib.common.ColorTools.blue(rgb)));
                    channelNamesByLen.add(nm);
                }
            }
        }

        setPadding(new Insets(10));
        setTop(buildHeader());

        cards.setPadding(new Insets(8, 0, 0, 0));
        ScrollPane scroll = new ScrollPane(cards);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");
        setCenter(scroll);

        rebuild();
    }

    /** Re-read colors and rebuild (cluster + channel colors are read live). */
    public void refreshColors() {
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

    private VBox buildHeader() {
        VBox box = new VBox(6);
        box.setPadding(new Insets(0, 0, 6, 0));

        Label title = new Label("Cluster / channel explorer");
        title.setStyle("-fx-font-weight: bold;");

        noteLabel.setWrapText(true);
        noteLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        Spinner<Integer> topSpinner = new Spinner<>(1, 12, topK, 1);
        topSpinner.setPrefWidth(70);
        topSpinner.setEditable(true);
        topSpinner.valueProperty().addListener((o, a, b) -> {
            if (b != null) {
                topK = b;
                rebuild();
            }
        });

        ToggleGroup viewGroup = new ToggleGroup();
        RadioButton measRadio = new RadioButton("Measurements");
        RadioButton chanRadio = new RadioButton("Channels");
        RadioButton chan2clusRadio = new RadioButton("Channels -> clusters");
        measRadio.setToggleGroup(viewGroup);
        chanRadio.setToggleGroup(viewGroup);
        chan2clusRadio.setToggleGroup(viewGroup);
        measRadio.setSelected(true);
        boolean haveChannels = !channelNamesByLen.isEmpty();
        chanRadio.setDisable(!haveChannels);
        chan2clusRadio.setDisable(!haveChannels);
        if (!haveChannels) {
            Tooltip t = new Tooltip("Open one of the clustered images so QP-CAT can read "
                    + "its channel names and colors.");
            chanRadio.setTooltip(t);
            chan2clusRadio.setTooltip(t);
        }
        measRadio.setOnAction(e -> { view = View.MEASUREMENTS; rebuild(); });
        chanRadio.setOnAction(e -> { view = View.CHANNELS; rebuild(); });
        chan2clusRadio.setOnAction(e -> { view = View.CHANNELS_TO_CLUSTERS; rebuild(); });

        HBox controls = new HBox(14,
                new HBox(8, new Label("Markers per cluster:"), topSpinner),
                new HBox(8, new Label("View:"), measRadio, chanRadio, chan2clusRadio));
        controls.setAlignment(Pos.CENTER_LEFT);
        ((HBox) controls.getChildren().get(0)).setAlignment(Pos.CENTER_LEFT);
        ((HBox) controls.getChildren().get(1)).setAlignment(Pos.CENTER_LEFT);

        box.getChildren().addAll(title, noteLabel, controls);
        return box;
    }

    private void rebuild() {
        cards.getChildren().clear();
        if (rankings.isEmpty()) {
            cards.getChildren().add(new Label("No marker rankings available for this result."));
            return;
        }
        switch (view) {
            case MEASUREMENTS -> buildMeasurementCards();
            case CHANNELS -> buildChannelPerClusterCards();
            case CHANNELS_TO_CLUSTERS -> buildChannelToClusterCards();
        }
    }

    // ---------- View 1: measurements per cluster ----------

    private void buildMeasurementCards() {
        noteLabel.setText("One card per cluster, tinted with its color. Bars show each "
                + "marker's enrichment (log2 fold-change vs. the rest), longest = most "
                + "cluster-defining. Hover a bar for the Wilcoxon score and adjusted p-value.");

        double globalMax = 1e-9;
        for (List<Map<String, Object>> markers : rankings.values()) {
            int shown = Math.min(topK, markers.size());
            for (int i = 0; i < shown; i++) {
                globalMax = Math.max(globalMax, Math.abs(barValue(markers.get(i))));
            }
        }
        for (String cid : sortedClusterIds()) {
            cards.getChildren().add(measurementCard(cid, rankings.get(cid), globalMax));
        }
    }

    private VBox measurementCard(String cid, List<Map<String, Object>> markers, double globalMax) {
        int clusterId = intOrNeg(cid);
        Color color = clusterColorFor(clusterId);
        VBox card = card(color);
        card.getChildren().add(clusterHeader(cid, clusterId, color));

        VBox rows = new VBox(6);
        int shown = markers == null ? 0 : Math.min(topK, markers.size());
        if (shown == 0) {
            rows.getChildren().add(muted("(no markers)"));
        }
        for (int i = 0; i < shown; i++) {
            rows.getChildren().add(measurementRow(markers.get(i), color, globalMax));
        }
        card.getChildren().add(rows);
        return card;
    }

    private VBox measurementRow(Map<String, Object> marker, Color color, double globalMax) {
        String mName = String.valueOf(marker.get("name"));
        double val = barValue(marker);
        double lfc = num(marker.get("logfoldchange"));
        double score = num(marker.get("score"));

        // Full-width, wrapping name so long measurement names read without hovering.
        Label nameLbl = new Label(mName);
        nameLbl.setStyle("-fx-font-size: 11px;");
        nameLbl.setWrapText(true);
        nameLbl.setMaxWidth(Double.MAX_VALUE);

        double frac = globalMax > 0 ? Math.min(1.0, Math.abs(val) / globalMax) : 0;
        double w = Math.max(2, frac * BAR_MAX_W);
        Rectangle bar = new Rectangle(w, BAR_H);
        bar.setArcWidth(3);
        bar.setArcHeight(3);
        bar.setFill(val >= 0 ? color : color.deriveColor(0, 0.5, 1.0, 0.6));

        String valText = Double.isNaN(lfc)
                ? (Double.isNaN(score) ? "n/a" : String.format("z=%.1f", score))
                : String.format("%+.1f", lfc);
        Label valLbl = new Label(valText);
        valLbl.setStyle("-fx-font-size: 10px; -fx-text-fill: #555;");
        valLbl.setMinWidth(VAL_W);
        HBox barLine = new HBox(6, bar, valLbl);
        barLine.setAlignment(Pos.CENTER_LEFT);

        VBox cell = new VBox(1, nameLbl, barLine);
        Tooltip.install(cell, new Tooltip(markerTooltip(mName, marker)));
        return cell;
    }

    // ---------- View 2: channels per cluster ----------

    private void buildChannelPerClusterCards() {
        noteLabel.setText("One card per cluster: the imaging channels its top markers come "
                + "from, colored by the channel's Viewer color (even if toggled off), most "
                + "cluster-defining first. \"Other\" = top markers that are not a channel "
                + "(e.g. Area) -- see the Measurements view for those.");

        for (String cid : sortedClusterIds()) {
            int clusterId = intOrNeg(cid);
            Color color = clusterColorFor(clusterId);
            VBox card = card(color);
            card.getChildren().add(clusterHeader(cid, clusterId, color));

            List<Map<String, Object>> markers = rankings.get(cid);
            int shown = markers == null ? 0 : Math.min(topK, markers.size());
            LinkedHashSet<String> chans = new LinkedHashSet<>();  // preserve rank order
            boolean hasOther = false;
            for (int i = 0; i < shown; i++) {
                String ch = channelOf(String.valueOf(markers.get(i).get("name")));
                if (ch != null) chans.add(ch);
                else hasOther = true;
            }

            FlowPane chips = new FlowPane(6, 6);
            if (chans.isEmpty() && !hasOther) {
                chips.getChildren().add(muted("(no markers)"));
            }
            for (String ch : chans) {
                chips.getChildren().add(chip(channelColor.getOrDefault(ch, Color.GRAY), ch));
            }
            if (hasOther) {
                chips.getChildren().add(chip(Color.gray(0.6), "Other"));
            }
            card.getChildren().add(chips);
            cards.getChildren().add(card);
        }
    }

    // ---------- View 3: channels -> clusters ----------

    private void buildChannelToClusterCards() {
        noteLabel.setText("One card per channel: the clusters it is a top marker of (within "
                + "the markers-per-cluster limit), strongest first. \"Other\" gathers clusters "
                + "whose top markers include a non-channel measurement.");

        // channel -> ordered list of {clusterId, bestLfc, bestRank}
        Map<String, List<double[]>> byChannel = new LinkedHashMap<>();
        List<double[]> otherClusters = new ArrayList<>();
        for (String cid : sortedClusterIds()) {
            int clusterId = intOrNeg(cid);
            List<Map<String, Object>> markers = rankings.get(cid);
            int shown = markers == null ? 0 : Math.min(topK, markers.size());
            // Best (lowest) rank + its lfc per channel within this cluster.
            Map<String, double[]> bestPerChannel = new LinkedHashMap<>();
            boolean other = false;
            for (int i = 0; i < shown; i++) {
                String ch = channelOf(String.valueOf(markers.get(i).get("name")));
                if (ch == null) { other = true; continue; }
                if (!bestPerChannel.containsKey(ch)) {
                    double lfc = num(markers.get(i).get("logfoldchange"));
                    bestPerChannel.put(ch, new double[]{clusterId, lfc, i});
                }
            }
            for (Map.Entry<String, double[]> e : bestPerChannel.entrySet()) {
                byChannel.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
            }
            if (other) otherClusters.add(new double[]{clusterId, Double.NaN, 0});
        }

        // Channels ordered by how many clusters they define (broadest first).
        List<String> channels = new ArrayList<>(byChannel.keySet());
        channels.sort((a, b) -> Integer.compare(byChannel.get(b).size(), byChannel.get(a).size()));

        if (channels.isEmpty() && otherClusters.isEmpty()) {
            cards.getChildren().add(muted("No channel markers found in the top markers."));
            return;
        }

        for (String ch : channels) {
            Color color = channelColor.getOrDefault(ch, Color.GRAY);
            VBox card = card(color);
            HBox header = new HBox(6, colorChip(color, 14), boldLabel(ch));
            header.setAlignment(Pos.CENTER_LEFT);
            card.getChildren().add(header);

            List<double[]> clusters = byChannel.get(ch);
            // Strongest association first: lowest rank, then highest lfc.
            clusters.sort((x, y) -> {
                int r = Double.compare(x[2], y[2]);
                if (r != 0) return r;
                return Double.compare(y[1], x[1]);
            });
            FlowPane chips = new FlowPane(6, 6);
            for (double[] c : clusters) {
                int clusterId = (int) c[0];
                String label = "Cluster " + clusterId;
                if (!Double.isNaN(c[1])) label += String.format(" (%+.1f)", c[1]);
                chips.getChildren().add(chip(clusterColorFor(clusterId), label));
            }
            card.getChildren().add(chips);
            cards.getChildren().add(card);
        }

        if (!otherClusters.isEmpty()) {
            Color gray = Color.gray(0.6);
            VBox card = card(gray);
            HBox header = new HBox(6, colorChip(gray, 14), boldLabel("Other (non-channel markers)"));
            header.setAlignment(Pos.CENTER_LEFT);
            card.getChildren().add(header);
            FlowPane chips = new FlowPane(6, 6);
            for (double[] c : otherClusters) {
                int clusterId = (int) c[0];
                chips.getChildren().add(chip(clusterColorFor(clusterId), "Cluster " + clusterId));
            }
            card.getChildren().add(chips);
            cards.getChildren().add(card);
        }
    }

    // ---------- shared building blocks ----------

    private VBox card(Color tint) {
        VBox card = new VBox(6);
        card.setPadding(new Insets(8));
        card.setPrefWidth(CARD_W);
        card.setStyle(String.format(
                "-fx-background-color: %s; -fx-border-color: #ccc; -fx-border-radius: 5; "
                + "-fx-background-radius: 5;", toRgba(tint, 0.07)));
        return card;
    }

    private HBox clusterHeader(String cid, int clusterId, Color color) {
        Label size = new Label(sizeText(clusterId));
        size.setStyle("-fx-font-size: 10.5px; -fx-text-fill: #777;");
        HBox header = new HBox(6, colorChip(color, 13), boldLabel("Cluster " + cid), size);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private String sizeText(int clusterId) {
        if (clusterId >= 0 && clusterId < clusterSizes.length && totalCells > 0) {
            long sz = clusterSizes[clusterId];
            return String.format("  %,d (%.1f%%)", sz, 100.0 * sz / totalCells);
        }
        return "";
    }

    /** A colored square + text "chip". */
    private HBox chip(Color color, String text) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-font-size: 11px;");
        HBox h = new HBox(4, colorChip(color, 11), lbl);
        h.setAlignment(Pos.CENTER_LEFT);
        h.setPadding(new Insets(1, 5, 1, 3));
        h.setStyle("-fx-background-color: rgba(0,0,0,0.04); -fx-background-radius: 4;");
        return h;
    }

    private static Rectangle colorChip(Color color, double size) {
        Rectangle r = new Rectangle(size, size, color);
        r.setArcWidth(3);
        r.setArcHeight(3);
        r.setStroke(Color.gray(0.6));
        return r;
    }

    private static Label boldLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }

    private static Label muted(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 10.5px; -fx-text-fill: #999;");
        return l;
    }

    /** Channel whose name is contained in the measurement name (longest match), else null. */
    private String channelOf(String measurementName) {
        if (measurementName == null) return null;
        for (String ch : channelNamesByLen) {
            if (measurementName.contains(ch)) return ch;
        }
        return null;
    }

    private List<String> sortedClusterIds() {
        List<String> ids = new ArrayList<>(rankings.keySet());
        ids.sort((a, b) -> {
            double da = parseOrMax(a);
            double db = parseOrMax(b);
            if (da != db) return Double.compare(da, db);
            return a.compareTo(b);
        });
        return ids;
    }

    private static String markerTooltip(String mName, Map<String, Object> marker) {
        double lfc = num(marker.get("logfoldchange"));
        double score = num(marker.get("score"));
        double padj = num(marker.get("pval_adj"));
        StringBuilder tip = new StringBuilder(mName);
        if (!Double.isNaN(lfc)) tip.append(String.format("%nlog2FC: %+.2f", lfc));
        if (!Double.isNaN(score)) tip.append(String.format("%nWilcoxon score: %.2f", score));
        if (!Double.isNaN(padj)) tip.append(String.format("%nadj. p-value: %.2e", padj));
        return tip.toString();
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

    private static int intOrNeg(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return -1; }
    }

    private static double parseOrMax(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return Double.MAX_VALUE; }
    }

    private Color clusterColorFor(int cluster) {
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
