package qupath.ext.qpcat.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Simplified overview of how clusters are distributed across a grouping
 * dimension (source image or parent annotation). Shows, per group, the
 * per-cluster cell counts and proportions in a numeric table plus one pie
 * chart, all colored with the shared cluster palette.
 *
 * <p>Purely a read-only summary built from the in-memory clustering result:
 * a per-cell cluster label array and a parallel per-cell group label array.
 * A cluster that appears in only one group is an immediate visual signal that
 * the clustering separated cells by image/region rather than by phenotype
 * (a batch effect), which is exactly what this view is meant to expose.</p>
 */
public class ClusterCompositionPanel extends BorderPane {

    private static final String NONE_LABEL = "(none)";

    private final int nClusters;
    private final IntFunction<Color> clusterColor;

    // Ordered group labels and their per-cluster counts (row-aligned).
    private final List<String> groups = new ArrayList<>();
    private final Map<String, long[]> counts = new LinkedHashMap<>();
    private final long[] clusterTotals;
    private long grandTotal = 0;

    private final TableView<String[]> table = new TableView<>();
    private boolean showPercent = false;

    /**
     * @param clusterLabels      per-cell cluster id (index-aligned with cellGroups)
     * @param nClusters          number of clusters
     * @param cellGroups         per-cell group label (image name / annotation name);
     *                           null entries are bucketed under "(none)"
     * @param groupDimensionLabel column header for the grouping ("Image" / "Annotation")
     * @param clusterColorFn     cluster id -> palette color (matches the other tabs)
     */
    public ClusterCompositionPanel(int[] clusterLabels, int nClusters, String[] cellGroups,
                                   String groupDimensionLabel, IntFunction<Color> clusterColorFn) {
        this.nClusters = Math.max(nClusters, 0);
        this.clusterColor = clusterColorFn;
        this.clusterTotals = new long[this.nClusters];

        tally(clusterLabels, cellGroups);

        setPadding(new Insets(10));
        setTop(buildHeader(groupDimensionLabel));
        setCenter(buildBody(groupDimensionLabel));
    }

    // ---- Data ----

    private void tally(int[] clusterLabels, String[] cellGroups) {
        int n = clusterLabels == null ? 0 : clusterLabels.length;
        for (int i = 0; i < n; i++) {
            int c = clusterLabels[i];
            if (c < 0 || c >= nClusters) {
                continue;  // noise / out-of-range labels are not charted
            }
            String g = (cellGroups != null && i < cellGroups.length && cellGroups[i] != null)
                    ? cellGroups[i] : NONE_LABEL;
            long[] row = counts.get(g);
            if (row == null) {
                row = new long[nClusters];
                counts.put(g, row);
                groups.add(g);
            }
            row[c]++;
            clusterTotals[c]++;
            grandTotal++;
        }
        groups.sort(String.CASE_INSENSITIVE_ORDER);
    }

    private long groupTotal(String group) {
        long t = 0;
        long[] row = counts.get(group);
        if (row != null) {
            for (long v : row) t += v;
        }
        return t;
    }

    // ---- UI ----

    private Region buildHeader(String groupDimensionLabel) {
        VBox box = new VBox(6);
        box.setPadding(new Insets(0, 0, 8, 0));

        Label title = new Label(String.format(
                "%d cells across %d %s%s, %d clusters",
                grandTotal, groups.size(),
                groupDimensionLabel.toLowerCase(),
                groups.size() == 1 ? "" : "s",
                nClusters));
        title.setStyle("-fx-font-weight: bold;");

        // Shared legend so every pie can hide its own (they would otherwise
        // each show default JavaFX colors that do not match the palette).
        FlowPane legend = new FlowPane(10, 4);
        for (int c = 0; c < nClusters; c++) {
            legend.getChildren().add(swatch(c, "Cluster " + c));
        }

        // Counts / percentage toggle.
        ToggleGroup tg = new ToggleGroup();
        RadioButton countsBtn = new RadioButton("Counts");
        RadioButton pctBtn = new RadioButton("Row %");
        countsBtn.setToggleGroup(tg);
        pctBtn.setToggleGroup(tg);
        countsBtn.setSelected(true);
        countsBtn.setOnAction(e -> { showPercent = false; refreshTable(groupDimensionLabel); });
        pctBtn.setOnAction(e -> { showPercent = true; refreshTable(groupDimensionLabel); });

        Button copyBtn = new Button("Copy table (TSV)");
        copyBtn.setOnAction(e -> copyTableAsTsv(groupDimensionLabel));

        HBox controls = new HBox(10, new Label("Show:"), countsBtn, pctBtn, copyBtn);
        controls.setAlignment(Pos.CENTER_LEFT);

        box.getChildren().addAll(title, legend, controls);
        return box;
    }

    private HBox swatch(int cluster, String text) {
        Rectangle r = new Rectangle(12, 12, colorFor(cluster));
        r.setArcWidth(3);
        r.setArcHeight(3);
        HBox h = new HBox(4, r, new Label(text));
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private Region buildBody(String groupDimensionLabel) {
        buildTableColumns(groupDimensionLabel);
        refreshTable(groupDimensionLabel);
        // Natural column widths + horizontal scroll: with many clusters the
        // constrained policy squeezed every header to "Clu...". Each column now
        // keeps a readable width and the table scrolls sideways instead.
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        // A fixed row height lets us size the table to show all its rows. Without
        // an explicit min height the tall pies below (Vgrow) starved the table
        // down to just its header row.
        final double rowH = 26;
        table.setFixedCellSize(rowH);
        int dataRows = groups.size() + 1;                 // groups + "All" summary
        int visibleRows = Math.min(dataRows, 10);
        double headerH = 30;
        table.setPrefHeight(headerH + rowH * visibleRows + 2);
        table.setMinHeight(headerH + rowH * Math.min(dataRows, 4) + 2);

        Label tableCaption = new Label("Per-" + groupDimensionLabel.toLowerCase()
                + " cluster counts (Cn = cluster n):");
        tableCaption.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");

        FlowPane pies = new FlowPane(14, 14);
        pies.setPadding(new Insets(10, 0, 0, 0));
        for (String group : groups) {
            pies.getChildren().add(buildPie(group));
        }
        ScrollPane piesScroll = new ScrollPane(pies);
        piesScroll.setFitToWidth(true);

        VBox body = new VBox(6, tableCaption, table, new Label("Per-"
                + groupDimensionLabel.toLowerCase() + " cluster proportions:"), piesScroll);
        VBox.setVgrow(piesScroll, javafx.scene.layout.Priority.ALWAYS);
        return body;
    }

    private void buildTableColumns(String groupDimensionLabel) {
        table.getColumns().clear();

        TableColumn<String[], String> groupCol = new TableColumn<>(groupDimensionLabel);
        groupCol.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                cd.getValue()[0]));
        groupCol.setPrefWidth(150);
        table.getColumns().add(groupCol);

        for (int c = 0; c < nClusters; c++) {
            final int col = c + 1;
            // Short header ("C0", "C1", ...) so 20+ columns stay readable; the
            // shared legend above maps each to its cluster + color.
            TableColumn<String[], String> cc = new TableColumn<>("C" + c);
            cc.setPrefWidth(48);
            cc.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                    col < cd.getValue().length ? cd.getValue()[col] : ""));
            table.getColumns().add(cc);
        }

        final int totalCol = nClusters + 1;
        TableColumn<String[], String> totCol = new TableColumn<>("Total");
        totCol.setPrefWidth(64);
        totCol.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                totalCol < cd.getValue().length ? cd.getValue()[totalCol] : ""));
        table.getColumns().add(totCol);
    }

    private void refreshTable(String groupDimensionLabel) {
        List<String[]> rows = new ArrayList<>();
        for (String group : groups) {
            long[] row = counts.get(group);
            long total = groupTotal(group);
            String[] disp = new String[nClusters + 2];
            disp[0] = group;
            for (int c = 0; c < nClusters; c++) {
                disp[c + 1] = formatCell(row[c], total);
            }
            disp[nClusters + 1] = String.valueOf(total);
            rows.add(disp);
        }
        // Trailing "All groups" summary row.
        String[] allRow = new String[nClusters + 2];
        allRow[0] = "All " + groupDimensionLabel.toLowerCase() + "s";
        for (int c = 0; c < nClusters; c++) {
            allRow[c + 1] = formatCell(clusterTotals[c], grandTotal);
        }
        allRow[nClusters + 1] = String.valueOf(grandTotal);
        rows.add(allRow);

        table.getItems().setAll(rows);
    }

    private String formatCell(long count, long total) {
        if (showPercent) {
            if (total <= 0) return "0.0%";
            return String.format("%.1f%%", 100.0 * count / total);
        }
        return String.valueOf(count);
    }

    private VBox buildPie(String group) {
        long[] row = counts.get(group);
        long total = groupTotal(group);

        PieChart chart = new PieChart();
        chart.setLegendVisible(false);
        chart.setLabelsVisible(false);
        chart.setPrefSize(220, 220);
        chart.setMinSize(200, 200);
        chart.setAnimated(false);

        for (int c = 0; c < nClusters; c++) {
            if (row[c] <= 0) continue;
            final int cluster = c;
            PieChart.Data d = new PieChart.Data("Cluster " + c, row[c]);
            chart.getData().add(d);
            double pct = total > 0 ? 100.0 * row[c] / total : 0;
            // Node exists only once the slice is laid out; style + tooltip then.
            d.nodeProperty().addListener((obs, oldN, newN) -> {
                if (newN != null) {
                    newN.setStyle("-fx-pie-color: " + toHex(colorFor(cluster)) + ";");
                    Tooltip.install(newN, new Tooltip(String.format(
                            "Cluster %d: %d cells (%.1f%%)", cluster, row[cluster], pct)));
                }
            });
        }

        Label caption = new Label(group + "  (" + total + " cells)");
        caption.setWrapText(true);
        caption.setMaxWidth(220);
        caption.setStyle("-fx-font-size: 11px;");
        VBox box = new VBox(2, chart, caption);
        box.setAlignment(Pos.TOP_CENTER);
        return box;
    }

    private void copyTableAsTsv(String groupDimensionLabel) {
        StringBuilder sb = new StringBuilder();
        sb.append(groupDimensionLabel);
        for (int c = 0; c < nClusters; c++) sb.append('\t').append("Cluster ").append(c);
        sb.append('\t').append("Total").append('\n');
        for (String[] row : table.getItems()) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) sb.append('\t');
                sb.append(row[i] == null ? "" : row[i]);
            }
            sb.append('\n');
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(sb.toString());
        Clipboard.getSystemClipboard().setContent(content);
    }

    private Color colorFor(int cluster) {
        Color c = clusterColor != null ? clusterColor.apply(cluster) : null;
        return c != null ? c : Color.GRAY;
    }

    private static String toHex(Color c) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }
}
