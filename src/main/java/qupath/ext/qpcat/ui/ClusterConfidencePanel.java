package qupath.ext.qpcat.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import qupath.ext.qpcat.model.MembershipConfidence;
import qupath.ext.qpcat.service.ResultApplier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * How marginal each cluster's labels were.
 *
 * <p>Every algorithm here assigns each cell to exactly one cluster, so a cell at
 * 0.51 / 0.49 reads identically to one at 1.00 / 0.00. This panel reports the
 * difference, per cluster, from whichever per-cell quantity the algorithm
 * actually computed -- see {@link MembershipConfidence} for the three kinds and
 * why they are not merged into one number.
 *
 * <p>The headline column is the <b>ambiguous share</b>, not an average.
 * Measured with a two-component GMM: two well-separated blobs gave a median
 * confidence of 1.0000 and a uniform continuum gave 0.9925, which tells the two
 * apart not at all, while the share of cells below 0.9 was 0.0% against 22.5%.
 * Most points on a line are still nearer one end than the other, so the cells
 * that matter are a minority and a mean hides them.
 */
public class ClusterConfidencePanel extends BorderPane {

    private static final String NOTE_STYLE =
            "-fx-font-size: 11px; -fx-text-fill: derive(-fx-text-base-color, 25%);";

    private final MembershipConfidence confidence;
    private final int[] clusterLabels;
    private final int nClusters;
    private final IntFunction<String> clusterNameFn;
    private final int noiseRowIndex;

    private final TableView<String[]> table = new TableView<>();
    private List<String> headers = List.of();

    /**
     * @param confidence    the run's membership confidence; must be usable
     * @param clusterLabels per-cell cluster id, index-aligned with the confidence
     * @param nClusters     row count of the cluster table, noise row included
     * @param noiseRowIndex index of the noise row, or -1 when there is none
     * @param clusterNameFn cluster id -> display name; null for "Cluster N"
     */
    public ClusterConfidencePanel(MembershipConfidence confidence, int[] clusterLabels,
                                  int nClusters, int noiseRowIndex,
                                  IntFunction<String> clusterNameFn) {
        this.confidence = confidence;
        this.clusterLabels = clusterLabels == null ? new int[0] : clusterLabels;
        this.nClusters = Math.max(0, nClusters);
        this.noiseRowIndex = noiseRowIndex;
        this.clusterNameFn = clusterNameFn != null ? clusterNameFn : c -> "Cluster " + c;

        setPadding(new Insets(10));
        setTop(buildHeader());
        setCenter(buildBody());
    }

    // ---- UI ----

    private Region buildHeader() {
        VBox box = new VBox(6);
        box.setPadding(new Insets(0, 0, 8, 0));

        MembershipConfidence.Kind kind = confidence.getKind();
        Label title = new Label("Membership confidence -- " + kind.getColumnName());
        title.setStyle("-fx-font-weight: bold;");

        Label what = new Label(kind.getExplanation());
        what.setWrapText(true);
        what.setMaxWidth(Double.MAX_VALUE);
        WrapHeight.bind(what);
        what.setStyle(BannerStyles.GUIDE_TEXT);

        double overall = confidence.ambiguousFraction();
        Label headline = new Label(String.format(
                "%.1f%% of %,d cells scored below %.2f -- their label was close to a "
                + "coin flip. Read this share, not an average: on a continuum the mean "
                + "stays high because most cells still sit nearer one end.",
                100.0 * overall, confidence.size(), MembershipConfidence.AMBIGUOUS_BELOW));
        headline.setWrapText(true);
        headline.setMaxWidth(Double.MAX_VALUE);
        WrapHeight.bind(headline);
        headline.setStyle(NOTE_STYLE);

        Button copyBtn = new Button("Copy table (TSV)");
        copyBtn.setOnAction(e -> copyAsTsv());

        Label where = new Label("Written to every cell as \""
                + ResultApplier.CONFIDENCE_PREFIX + kind.getColumnName()
                + "\". Map it in QuPath (Measure > Show measurement maps) to see where "
                + "the uncertain cells are -- if they form a band between two clusters, "
                + "that boundary is a gradient, not an edge.");
        where.setWrapText(true);
        where.setMaxWidth(Double.MAX_VALUE);
        WrapHeight.bind(where);
        where.setStyle(NOTE_STYLE);

        HBox controls = new HBox(8, copyBtn);
        controls.setAlignment(Pos.CENTER_LEFT);

        box.getChildren().addAll(title, what, headline, where, controls);
        return box;
    }

    private Region buildBody() {
        buildColumns();
        fillRows();
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        VBox body = new VBox(6, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        return body;
    }

    private void buildColumns() {
        List<String> h = new ArrayList<>();
        h.add("Cluster");
        h.add("Cells");
        h.add("Median " + confidence.getKind().getColumnName().toLowerCase());
        h.add(String.format("%% below %.2f", MembershipConfidence.AMBIGUOUS_BELOW));
        if (confidence.getRunnerUp() != null) {
            h.add("Most likely alternative");
        }
        headers = h;

        for (int i = 0; i < h.size(); i++) {
            final int col = i;
            TableColumn<String[], String> tc = new TableColumn<>(h.get(i));
            tc.setSortable(false);
            tc.setPrefWidth(i == 0 ? 160 : (i == h.size() - 1 ? 200 : 120));
            tc.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                    col < cd.getValue().length ? cd.getValue()[col] : ""));
            table.getColumns().add(tc);
        }
    }

    private void fillRows() {
        List<String[]> rows = new ArrayList<>();
        for (int c = 0; c < nClusters; c++) {
            int cells = 0;
            for (int label : clusterLabels) {
                if (label == c) {
                    cells++;
                }
            }
            if (cells == 0) {
                continue;
            }
            List<String> row = new ArrayList<>();
            row.add(c == noiseRowIndex ? "Noise" : clusterNameFn.apply(c));
            row.add(String.format("%,d", cells));
            double med = confidence.median(clusterLabels, c);
            row.add(Double.isNaN(med) ? "n/a" : String.format("%.3f", med));
            double amb = confidence.ambiguousFraction(clusterLabels, c);
            row.add(Double.isNaN(amb) ? "n/a" : String.format("%.1f%%", 100.0 * amb));
            if (confidence.getRunnerUp() != null) {
                row.add(describeAlternative(c));
            }
            rows.add(row.toArray(new String[0]));
        }
        table.getItems().setAll(rows);
        final double rowH = 26;
        table.setFixedCellSize(rowH);
        table.setPrefHeight(30 + rowH * Math.min(Math.max(rows.size(), 1), 18) + 2);
    }

    /** Where this cluster's ambiguous cells would have gone instead. */
    private String describeAlternative(int cluster) {
        int[] tally = confidence.runnerUpTally(clusterLabels, cluster, nClusters);
        if (tally == null) {
            return "";
        }
        int bestIdx = -1;
        int bestCount = 0;
        int total = 0;
        for (int i = 0; i < tally.length; i++) {
            total += tally[i];
            if (tally[i] > bestCount) {
                bestCount = tally[i];
                bestIdx = i;
            }
        }
        if (bestIdx < 0 || total == 0) {
            // No ambiguous cell in this cluster at all, which is a clean result
            // and should read as one rather than as missing data.
            return "(none ambiguous)";
        }
        String name = bestIdx == noiseRowIndex ? "Noise" : clusterNameFn.apply(bestIdx);
        return String.format("%s (%d of %d)", name, bestCount, total);
    }

    private void copyAsTsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Membership confidence: ")
          .append(confidence.getKind().getColumnName()).append(". ")
          .append(confidence.getKind().getExplanation()).append('\n');
        sb.append(String.format("# %.1f%% of %d cells below %.2f overall.%n",
                100.0 * confidence.ambiguousFraction(), confidence.size(),
                MembershipConfidence.AMBIGUOUS_BELOW));
        sb.append(String.join("\t", headers)).append('\n');
        for (String[] row : table.getItems()) {
            sb.append(String.join("\t", row)).append('\n');
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(sb.toString());
        Clipboard.getSystemClipboard().setContent(content);
    }
}
