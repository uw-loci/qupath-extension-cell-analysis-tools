package qupath.ext.qpcat.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import qupath.ext.qpcat.model.ChooseKResult;
import qupath.lib.gui.QuPathGUI;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The elbow, silhouette and gap curves for one k-sweep, side by side.
 *
 * <p>QP-CAT's KMeans note recommended choosing k with these three and provided
 * none of them. Showing all three rather than one number is the point: they
 * regularly disagree, and the disagreement is the finding.
 *
 * <p>Measured with the shipped sweep, five seeds, on 400 cells of 5-feature
 * Gaussian noise containing <b>no clusters at all</b>: the elbow said 3 or 4
 * every run, the silhouette said 6 to 8 every run, and the gap statistic said
 * k = 1 in five runs out of five. On three well-separated blobs all three agreed
 * on k = 3, five out of five. The dialog leads with that, because a user who
 * takes the elbow's number at face value on structureless data gets a confident
 * wrong answer and nothing warns them.
 */
public class ChooseKDialog {

    private static final String NOTE_STYLE =
            "-fx-font-size: 11px; -fx-text-fill: derive(-fx-text-base-color, 25%);";
    private static final String WARN_STYLE =
            "-fx-background-color: #fff3cd; -fx-text-fill: #7a5b00; "
            + "-fx-border-color: #ffe08a; -fx-border-width: 1; "
            + "-fx-background-radius: 4; -fx-border-radius: 4; -fx-padding: 8;";

    private ChooseKDialog() {}

    /**
     * Show the sweep and let the user send a k back to the clustering dialog.
     *
     * @param owner    window to own this one, or null
     * @param result   the sweep
     * @param applyK   receives the chosen k when the user applies one; may be null
     *                 to show the sweep read-only
     */
    public static void show(Stage owner, ChooseKResult result, IntConsumer applyK) {
        Stage stage = new Stage();
        stage.setTitle("QPCAT - Choose the number of clusters");
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.initModality(Modality.NONE);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(12));
        root.setTop(buildHeader(result));
        root.setCenter(buildBody(result));
        root.setBottom(buildButtons(stage, result, applyK));

        stage.setScene(new Scene(root, 980, 820));
        stage.show();
    }

    // ---- Header: what the sweep found, and what not to trust ----

    private static Region buildHeader(ChooseKResult result) {
        VBox box = new VBox(6);
        box.setPadding(new Insets(0, 0, 8, 0));

        Label title = new Label("Three statistics for choosing k, and they often disagree");
        title.setStyle("-fx-font-weight: bold;");

        ChooseKResult.Suggestions s = result.getSuggested();
        Label verdict = new Label(String.format(
                "Elbow: %s      Silhouette: %s      Gap statistic: %s",
                describe(s.getElbow()), describe(s.getSilhouette()), describe(s.getGap())));
        verdict.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");

        List<String> notes = new ArrayList<>();
        if (result.gapSaysNoStructure()) {
            notes.add("THE GAP STATISTIC SAYS k = 1: on this matrix it cannot distinguish your "
                    + "data from uniform noise over the same region. It is the only one of the "
                    + "three able to say that, and the other two will still name a k. Treat any "
                    + "clustering of this matrix as a description you imposed, not a structure "
                    + "you found -- and check the measurements and normalization before going on.");
        } else if (result.suggestionsDisagree()) {
            notes.add("The three disagree, which is normal rather than a fault. The silhouette "
                    + "has an actual optimum so it can be argmaxed; the elbow is a kink read off "
                    + "a curve that falls forever and has no optimum at all; the gap compares "
                    + "against a null. Where they disagree, prefer the silhouette and the gap "
                    + "over the elbow, then decide using what you know about the tissue.");
        }
        notes.add("Measured with this sweep on 400 cells of pure noise -- no clusters in it -- "
                + "over five seeds: the elbow said 3 or 4 every time, the silhouette said 6 to 8 "
                + "every time, and the gap said k = 1 all five times. On three clean blobs all "
                + "three agreed on k = 3, five out of five. So agreement is meaningful and the "
                + "elbow alone is not.");
        notes.addAll(result.getWarnings());

        Label warn = new Label(String.join("\n\n", notes));
        warn.setWrapText(true);
        warn.setMaxWidth(Double.MAX_VALUE);
        WrapHeight.bind(warn);
        warn.setStyle(WARN_STYLE);

        ChooseKResult.Meta m = result.getMeta();
        Label meta = new Label(String.format(
                "k = %d to %d on %,d cells x %d measurements, seed %d. Silhouette on %,d cells%s. "
                + "Gap: %s.",
                m.getKMin(), m.getKMax(), m.getNCells(), m.getNFeatures(), m.getSeed(),
                m.getSilhouetteCells(), m.isSilhouetteSubsampled() ? " (subsampled)" : "",
                m.getGapNReferences() > 0
                        ? m.getGapNReferences() + " reference datasets per k" : "not computed"));
        meta.setWrapText(true);
        WrapHeight.bind(meta);
        meta.setStyle(NOTE_STYLE);

        box.getChildren().addAll(title, verdict, warn, meta);
        return box;
    }

    private static String describe(Integer k) {
        return k == null ? "no suggestion" : "k = " + k;
    }

    // ---- Body: the three curves plus the numbers ----

    private static Region buildBody(ChooseKResult result) {
        FlowPane charts = new FlowPane(12, 12);
        charts.getChildren().add(chart(result, "Inertia (elbow)",
                "within-cluster sum of squares", result.getInertia(),
                result.getSuggested().getElbow()));
        charts.getChildren().add(chart(result, "Mean silhouette",
                "higher is better; has an optimum", result.getSilhouette(),
                result.getSuggested().getSilhouette()));
        if (result.getMeta().getGapNReferences() > 0) {
            charts.getChildren().add(chart(result, "Gap statistic",
                    "vs a uniform null; Tibshirani's rule", result.getGap(),
                    result.getSuggested().getGap()));
        }

        TableView<String[]> table = buildTable(result);

        VBox body = new VBox(8, charts, new Label("Values:"), table);
        VBox.setVgrow(table, Priority.ALWAYS);
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private static Region chart(ChooseKResult result, String title, String subtitle,
                                List<Double> values, Integer suggested) {
        NumberAxis x = new NumberAxis();
        x.setLabel("k");
        x.setForceZeroInRange(false);
        NumberAxis y = new NumberAxis();
        LineChart<Number, Number> chart = new LineChart<>(x, y);
        chart.setTitle(title + (suggested == null ? "" : "  (suggests k = " + suggested + ")"));
        chart.setLegendVisible(false);
        chart.setPrefSize(300, 240);
        chart.setAnimated(false);

        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        List<Integer> ks = result.getKs();
        for (int i = 0; i < ks.size() && i < values.size(); i++) {
            Double v = values.get(i);
            if (v != null && Double.isFinite(v)) {
                series.getData().add(new XYChart.Data<>(ks.get(i), v));
            }
        }
        chart.getData().add(series);

        Label caption = new Label(subtitle);
        caption.setStyle(NOTE_STYLE);
        caption.setWrapText(true);
        caption.setMaxWidth(300);
        WrapHeight.bind(caption);
        return new VBox(2, chart, caption);
    }

    private static TableView<String[]> buildTable(ChooseKResult result) {
        TableView<String[]> table = new TableView<>();
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        boolean hasGap = result.getMeta().getGapNReferences() > 0;

        String[] headers = hasGap
                ? new String[] {"k", "Inertia", "Silhouette", "Gap", "Gap std. error"}
                : new String[] {"k", "Inertia", "Silhouette"};
        for (int i = 0; i < headers.length; i++) {
            final int col = i;
            TableColumn<String[], String> tc = new TableColumn<>(headers[i]);
            tc.setSortable(false);
            tc.setPrefWidth(i == 0 ? 60 : 130);
            tc.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                    col < cd.getValue().length ? cd.getValue()[col] : ""));
            table.getColumns().add(tc);
        }

        List<String[]> rows = new ArrayList<>();
        List<Integer> ks = result.getKs();
        for (int i = 0; i < ks.size(); i++) {
            List<String> row = new ArrayList<>();
            row.add(String.valueOf(ks.get(i)));
            row.add(num(result.getInertia(), i, "%.4g"));
            row.add(num(result.getSilhouette(), i, "%.3f"));
            if (hasGap) {
                row.add(num(result.getGap(), i, "%.3f"));
                row.add(num(result.getGapStdError(), i, "%.3f"));
            }
            rows.add(row.toArray(new String[0]));
        }
        table.getItems().setAll(rows);
        final double rowH = 25;
        table.setFixedCellSize(rowH);
        table.setPrefHeight(30 + rowH * Math.min(Math.max(rows.size(), 1), 12) + 2);
        return table;
    }

    /** "n/a" rather than a blank, so an undefined value reads as undefined. */
    private static String num(List<Double> values, int i, String fmt) {
        if (values == null || i >= values.size()) {
            return "n/a";
        }
        Double v = values.get(i);
        return (v == null || !Double.isFinite(v)) ? "n/a" : String.format(fmt, v);
    }

    // ---- Footer: apply a k, and where to read more ----

    private static Region buildButtons(Stage stage, ChooseKResult result, IntConsumer applyK) {
        VBox box = new VBox(8);
        box.setPadding(new Insets(10, 0, 0, 0));

        Label readMore = new Label("Where each of these comes from, and when to believe it:");
        readMore.setStyle(NOTE_STYLE);

        FlowPane links = new FlowPane(10, 4);
        links.getChildren().addAll(
                docLink("QP-CAT: choosing k",
                        QpcatDocLinks.pageUrl("clustering.md") + "#choosing-k"),
                webLink("Rousseeuw 1987 (silhouette)",
                        "https://doi.org/10.1016/0377-0427(87)90125-7"),
                webLink("Tibshirani et al. 2001 (gap)",
                        "https://doi.org/10.1111/1467-9868.00293"),
                webLink("Schubert 2023: stop using the elbow",
                        "https://doi.org/10.1145/3606274.3606278"),
                webLink("scikit-learn silhouette guide",
                        "https://scikit-learn.org/stable/auto_examples/cluster/"
                        + "plot_kmeans_silhouette_analysis.html"),
                webLink("StatQuest: K-means clustering (video)",
                        "https://www.youtube.com/watch?v=4b5d3muPQmA"));

        ButtonBar bar = new ButtonBar();
        if (applyK != null) {
            for (Integer k : result.distinctSuggestions()) {
                Button use = new Button("Use k = " + k);
                use.setOnAction(e -> {
                    applyK.accept(k);
                    stage.close();
                });
                ButtonBar.setButtonData(use, ButtonBar.ButtonData.OTHER);
                bar.getButtons().add(use);
            }
        }
        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());
        ButtonBar.setButtonData(close, ButtonBar.ButtonData.CANCEL_CLOSE);
        bar.getButtons().add(close);

        HBox linkRow = new HBox(links);
        linkRow.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(readMore, linkRow, bar);
        return box;
    }

    private static Hyperlink docLink(String text, String url) {
        Hyperlink h = new Hyperlink(text);
        h.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        h.setOnAction(e -> QuPathGUI.openInBrowser(url));
        h.setTooltip(Tooltips.of("Opens " + url));
        return h;
    }

    private static Hyperlink webLink(String text, String url) {
        Hyperlink h = new Hyperlink(text);
        h.setStyle("-fx-font-size: 11px;");
        h.setOnAction(e -> QuPathGUI.openInBrowser(url));
        h.setTooltip(Tooltips.of("Opens " + url));
        return h;
    }
}
