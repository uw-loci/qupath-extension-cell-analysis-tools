package qupath.ext.qpcat.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
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

import qupath.ext.qpcat.service.CompositionComparison;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntFunction;

/**
 * Does a cluster's abundance differ between experimental groups of images?
 *
 * <p>Every other composition tab describes: here is the makeup of each image,
 * each region, each core. This one tests, which is a different kind of claim and
 * carries different ways to be wrong, so the panel shows the design's problems
 * above the numbers rather than in a footnote.
 *
 * <p>The grouping comes from an image-metadata key (treatment, condition,
 * responder status), read live from the project, so a saved result reopened
 * months later can be compared a different way without re-clustering. The unit of
 * replication is the image: {@link CompositionComparison} works on per-image
 * proportions, never pooled cells.
 *
 * <p>Statistics, their caveats and the alternatives worth moving to are in
 * {@link CompositionComparison}; this class is the view.
 */
public class ClusterGroupComparisonPanel extends BorderPane {

    private static final String WARN_STYLE =
            "-fx-background-color: #fff3cd; -fx-text-fill: #7a5b00; "
            + "-fx-border-color: #ffe08a; -fx-border-width: 1; "
            + "-fx-background-radius: 4; -fx-border-radius: 4; -fx-padding: 8;";

    private final int[] clusterLabels;
    private final int nClusters;
    private final String[] cellImageIds;
    private final Map<String, Map<String, String>> metadataByImageId;
    private final IntFunction<String> clusterNameFn;

    private final ComboBox<String> keyCombo = new ComboBox<>();
    private final TableView<String[]> table = new TableView<>();
    private final Label warnings = new Label();
    private final Label summary = new Label();
    private final Button copyBtn = new Button("Copy table (TSV)");

    /** Last computed comparison, for the Copy button; null before a key is chosen. */
    private CompositionComparison.Result current;
    private List<String> currentHeaders = List.of();

    /**
     * @param clusterLabels     per-cell cluster id; negative ids (noise) are excluded
     * @param nClusters         number of clusters
     * @param cellImageIds      per-cell source image id, index-aligned with the labels
     * @param metadataByImageId image id -> that image's metadata map
     * @param clusterNameFn     cluster id -> display name; null for "Cluster N"
     */
    public ClusterGroupComparisonPanel(int[] clusterLabels, int nClusters, String[] cellImageIds,
                                       Map<String, Map<String, String>> metadataByImageId,
                                       IntFunction<String> clusterNameFn) {
        this.clusterLabels = clusterLabels == null ? new int[0] : clusterLabels;
        this.nClusters = Math.max(0, nClusters);
        this.cellImageIds = cellImageIds == null ? new String[0] : cellImageIds;
        this.metadataByImageId = metadataByImageId == null ? Map.of() : metadataByImageId;
        this.clusterNameFn = clusterNameFn != null ? clusterNameFn : c -> "Cluster " + c;

        setPadding(new Insets(10));
        setTop(buildHeader());
        setCenter(buildBody());

        if (!keyCombo.getItems().isEmpty()) {
            keyCombo.getSelectionModel().selectFirst();
        }
        recompute();
    }

    /**
     * Metadata keys present on the images this result was clustered from.
     *
     * @return the keys, sorted; empty when no image carries metadata
     */
    public List<String> availableKeys() {
        return new ArrayList<>(keyCombo.getItems());
    }

    // ---- UI ----

    private Region buildHeader() {
        VBox box = new VBox(6);
        box.setPadding(new Insets(0, 0, 8, 0));

        Label title = new Label("Cluster abundance between groups of images");
        title.setStyle("-fx-font-weight: bold;");

        Set<String> keys = new TreeSet<>();
        for (Map<String, String> meta : metadataByImageId.values()) {
            if (meta != null) {
                keys.addAll(meta.keySet());
            }
        }
        keyCombo.getItems().setAll(keys);
        keyCombo.setTooltip(Tooltips.of(
                "The image-metadata key that says which group each image belongs to\n"
                + "(e.g. treatment, condition, responder). Set these in QuPath's\n"
                + "project pane, or with the Project Metadata Browser extension.\n"
                + "Images with no value for the key are left out of the test rather\n"
                + "than pooled into an \"unset\" group -- missing metadata is not a\n"
                + "condition."));
        keyCombo.valueProperty().addListener((obs, old, now) -> recompute());

        HBox row = new HBox(8, new Label("Group images by:"), keyCombo, copyBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        copyBtn.setOnAction(e -> copyAsTsv());
        copyBtn.setDisable(true);

        warnings.setWrapText(true);
        warnings.setMaxWidth(Double.MAX_VALUE);
        WrapHeight.bind(warnings);
        warnings.setStyle(WARN_STYLE);
        warnings.setManaged(false);
        warnings.setVisible(false);

        summary.setStyle("-fx-font-size: 11px; -fx-text-fill: derive(-fx-text-base-color, 25%);");
        summary.setWrapText(true);
        WrapHeight.bind(summary);

        box.getChildren().addAll(title, row, warnings, summary);
        return box;
    }

    private Region buildBody() {
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("Choose an image-metadata key above."));
        VBox body = new VBox(6, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        return body;
    }

    // ---- Compute ----

    private void recompute() {
        String key = keyCombo.getValue();
        table.getItems().clear();
        table.getColumns().clear();
        current = null;
        currentHeaders = List.of();
        copyBtn.setDisable(true);

        if (key == null || key.isBlank()) {
            show(List.of(), "");
            return;
        }

        Map<String, String> groupByImage = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : metadataByImageId.entrySet()) {
            Map<String, String> meta = e.getValue();
            groupByImage.put(e.getKey(), meta == null ? null : meta.get(key));
        }

        CompositionComparison.Tally tally = CompositionComparison.tally(
                clusterLabels, nClusters, cellImageIds, groupByImage);

        List<String> dropped = new ArrayList<>();
        if (tally.imagesWithoutAGroup() > 0) {
            dropped.add(tally.imagesWithoutAGroup() + " image(s) have no value for '" + key
                    + "' and were left out. Missing metadata is not a condition, so they are "
                    + "not pooled into an extra group.");
        }
        if (tally.imagesWithoutCells() > 0) {
            dropped.add(tally.imagesWithoutCells() + " image(s) contributed no clustered cell "
                    + "(all noise, or filtered out) and were left out.");
        }

        if (tally.groupNames().size() < 2) {
            List<String> msg = new ArrayList<>(dropped);
            msg.add("'" + key + "' puts every remaining image in the same group ("
                    + (tally.groupNames().isEmpty() ? "none" : tally.groupNames().get(0))
                    + "), so there is nothing to compare. Pick a key that splits the images, "
                    + "or set the key on more of them.");
            show(msg, "");
            return;
        }

        CompositionComparison.Result r;
        try {
            r = CompositionComparison.compare(tally.countsPerImage(), tally.groupOfImage(),
                    tally.groupNames(), clusterNames());
        } catch (IllegalArgumentException ex) {
            List<String> msg = new ArrayList<>(dropped);
            msg.add("Cannot compare: " + ex.getMessage());
            show(msg, "");
            return;
        }

        current = r;
        List<String> all = new ArrayList<>(dropped);
        all.addAll(r.getWarnings());
        all.add("Clusters were defined from these same cells without seeing the group labels, "
                + "so this test is not circular -- but it cannot tell a biological shift from a "
                + "batch effect. Read the Composition by image tab first: if a cluster lives in "
                + "one image, this is testing staining, not biology.");
        buildColumns(r);
        fillRows(r);
        show(all, describe(r, tally));
        copyBtn.setDisable(false);
    }

    private List<String> clusterNames() {
        List<String> out = new ArrayList<>(nClusters);
        for (int c = 0; c < nClusters; c++) {
            String n = clusterNameFn.apply(c);
            out.add(n == null || n.isBlank() ? "Cluster " + c : n);
        }
        return out;
    }

    private static String describe(CompositionComparison.Result r,
                                   CompositionComparison.Tally tally) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.getTestName()).append(" on per-image proportions, ")
                .append(tally.imageKeys().size()).append(" image(s): ");
        List<String> parts = new ArrayList<>();
        for (int g = 0; g < r.getGroupNames().size(); g++) {
            parts.add(r.getGroupNames().get(g) + " n=" + r.getImagesPerGroup()[g]);
        }
        sb.append(String.join(", ", parts));
        sb.append(". q = Benjamini-Hochberg across the ").append(r.getClusters().size())
                .append(" cluster(s) in this table.");
        if (!Double.isNaN(r.getMinAchievableP())) {
            sb.append(String.format(" Smallest p this design can return: %.3f.",
                    r.getMinAchievableP()));
        }
        return sb.toString();
    }

    private void buildColumns(CompositionComparison.Result r) {
        List<String> headers = new ArrayList<>();
        headers.add("Cluster");
        for (int g = 0; g < r.getGroupNames().size(); g++) {
            headers.add(r.getGroupNames().get(g) + " (n=" + r.getImagesPerGroup()[g] + ")");
        }
        headers.add(r.getTestName().startsWith("Mann") ? "U" : "H");
        headers.add("p");
        headers.add("q (BH)");
        currentHeaders = headers;

        for (int i = 0; i < headers.size(); i++) {
            final int col = i;
            TableColumn<String[], String> tc = new TableColumn<>(headers.get(i));
            tc.setSortable(false);
            tc.setPrefWidth(i == 0 ? 160 : 110);
            tc.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                    col < cd.getValue().length ? cd.getValue()[col] : ""));
            table.getColumns().add(tc);
        }
    }

    private void fillRows(CompositionComparison.Result r) {
        List<String[]> rows = new ArrayList<>();
        for (CompositionComparison.ClusterStat cs : r.getClusters()) {
            List<String> cells = new ArrayList<>();
            cells.add(cs.name());
            for (CompositionComparison.GroupStat gs : cs.groups()) {
                cells.add(Double.isNaN(gs.medianProportion()) ? "n/a"
                        : String.format("%.1f%%", 100.0 * gs.medianProportion()));
            }
            cells.add(formatStatistic(cs.statistic()));
            cells.add(formatP(cs.pValue()));
            cells.add(formatP(cs.qValue()));
            rows.add(cells.toArray(new String[0]));
        }
        table.getItems().setAll(rows);
        final double rowH = 26;
        table.setFixedCellSize(rowH);
        table.setPrefHeight(30 + rowH * Math.min(Math.max(rows.size(), 1), 18) + 2);
    }

    private static String formatStatistic(double v) {
        if (Double.isNaN(v)) {
            return "n/a";
        }
        return v == Math.rint(v) ? String.format("%.0f", v) : String.format("%.2f", v);
    }

    /** Group medians are the description; a p below 0.001 is not more informative. */
    private static String formatP(double v) {
        if (Double.isNaN(v)) {
            return "n/a";
        }
        return v < 0.001 ? "<0.001" : String.format("%.3f", v);
    }

    private void show(List<String> messages, String summaryText) {
        boolean any = !messages.isEmpty();
        warnings.setText(any ? String.join("\n\n", messages) : "");
        warnings.setManaged(any);
        warnings.setVisible(any);
        summary.setText(summaryText);
        summary.setManaged(!summaryText.isEmpty());
        summary.setVisible(!summaryText.isEmpty());
    }

    private void copyAsTsv() {
        if (current == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        // The caveats travel with the numbers: a table pasted into a manuscript
        // draft without them is exactly how a 3-versus-3 q-value becomes a claim.
        sb.append("# ").append(summary.getText()).append('\n');
        for (String w : current.getWarnings()) {
            sb.append("# ").append(w.replace('\n', ' ')).append('\n');
        }
        sb.append(String.join("\t", currentHeaders)).append('\n');
        for (String[] row : table.getItems()) {
            sb.append(String.join("\t", row)).append('\n');
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(sb.toString());
        Clipboard.getSystemClipboard().setContent(content);
    }

    /** Keys shared by at least one image; used to decide whether the tab is worth showing. */
    public static Set<String> metadataKeysOf(Map<String, Map<String, String>> metadataByImageId) {
        Set<String> keys = new LinkedHashSet<>();
        if (metadataByImageId != null) {
            for (Map<String, String> meta : metadataByImageId.values()) {
                if (meta != null) {
                    keys.addAll(meta.keySet());
                }
            }
        }
        return keys;
    }
}
