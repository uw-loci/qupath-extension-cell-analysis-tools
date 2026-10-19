package qupath.ext.qpcat.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import qupath.ext.qpcat.model.ValidationReport;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * The Validation results tab: stability across seeds and agreement with the
 * classes the cells already had, each shown only when the run computed it.
 */
public class ValidationPanel extends ScrollPane {

    private static final String NOTE_STYLE =
            "-fx-font-size: 11px; -fx-text-fill: derive(-fx-text-base-color, 25%);";

    /**
     * @param report        the run's validation report
     * @param clusterNameFn cluster label -> display name (noise is -1)
     */
    public ValidationPanel(ValidationReport report, IntFunction<String> clusterNameFn) {
        IntFunction<String> names = c -> c < 0 ? "Noise" : clusterNameFn.apply(c);
        VBox box = new VBox(8);
        box.setPadding(new Insets(10));

        Button copy = new Button("Copy tables (TSV)");
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(report.toTsv(names));
            Clipboard.getSystemClipboard().setContent(content);
        });
        HBox controls = new HBox(8, copy);
        controls.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().add(controls);

        if (report.stability != null) {
            box.getChildren().addAll(heading("Stability across seeds"),
                    note(report.stabilitySummary()),
                    note("Per cluster: the consistency score is how many clusters of a re-run "
                            + "it takes to hold 95% of this cluster's cells, averaged over the "
                            + "re-runs (Pixie's score, Liu et al. 2023, measured against the "
                            + "clusters shown here). Kept together is the share of its cells "
                            + "found in one cluster of a re-run."),
                    table(List.of("Cluster", "Cells", "Consistency", "Kept together"),
                            report.stabilityRows(names)));
        }
        if (report.agreement != null) {
            List<String> headers = new ArrayList<>(List.of("Cluster", "Matched class"));
            headers.addAll(report.agreement.classes);
            box.getChildren().addAll(heading("Agreement with known classes"),
                    note(report.agreementSummary()),
                    note("Rows are clusters, columns the classes the cells carried before "
                            + "this run. ARI and NMI do not depend on which cluster number goes "
                            + "with which class; kappa does, so each cluster is first matched to "
                            + "at most one class to maximise the cells on the diagonal. A "
                            + "cluster left without a class counts as wrong for kappa, which is "
                            + "why splitting one class into two clusters lowers kappa but not "
                            + "purity."));
            if (report.agreement.error == null) {
                box.getChildren().add(table(headers, report.contingencyRows(names)));
            }
        }
        setContent(box);
        setFitToWidth(true);
    }

    private static Label heading(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }

    private static Label note(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMaxWidth(Double.MAX_VALUE);
        WrapHeight.bind(l);
        l.setStyle(NOTE_STYLE);
        return l;
    }

    private static TableView<String[]> table(List<String> headers, List<String[]> rows) {
        TableView<String[]> t = new TableView<>();
        for (int i = 0; i < headers.size(); i++) {
            final int col = i;
            TableColumn<String[], String> tc = new TableColumn<>(headers.get(i));
            tc.setSortable(false);
            tc.setPrefWidth(i == 0 ? 160 : 110);
            tc.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                    col < cd.getValue().length ? cd.getValue()[col] : ""));
            t.getColumns().add(tc);
        }
        t.getItems().setAll(rows);
        t.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        double rowH = 26;
        t.setFixedCellSize(rowH);
        t.setPrefHeight(30 + rowH * Math.min(Math.max(rows.size(), 1), 18) + 18);
        return t;
    }
}
