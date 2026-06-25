package qupath.ext.qpcat.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.CellRef;
import qupath.ext.qpcat.service.MeasurementExtractor;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone "Plot & gate cells (2D)" tool. Plots cells on a 2D scatter -- either
 * an existing embedding (UMAP/t-SNE/PCA coordinates already on the cells) or a
 * biaxial pair of marker measurements -- pooled across an image scope, then lets
 * the user polygon-gate cells and select/classify them across images via the
 * shared {@link GateActionBar}.
 *
 * <p>It reads coordinates that already exist; it does not compute embeddings (use
 * "Map cells in 2D" or run clustering for that). Points are colored by current
 * classification so existing populations are visible while gating.</p>
 */
public class PlotAndGateDialog {

    private static final Logger logger = LoggerFactory.getLogger(PlotAndGateDialog.class);

    private final QuPathGUI qupath;
    private final Stage owner;

    private ScopeSection scope;
    private RadioButton axisEmbedding;
    private RadioButton axisBiaxial;
    private ComboBox<String> methodCombo;     // UMAP / t-SNE / PCA (embedding axis)
    private ComboBox<String> xMeasCombo;       // biaxial X
    private ComboBox<String> yMeasCombo;       // biaxial Y
    private Label statusLabel;
    private VBox plotArea;

    public PlotAndGateDialog(QuPathGUI qupath) {
        this.qupath = qupath;
        this.owner = qupath.getStage();
    }

    public void show() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.setTitle("QPCAT - Plot & Gate Cells (2D)");
        dialog.setHeaderText("Plot cells in 2D, then lasso-gate to select or classify them");
        dialog.setResizable(true);

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.setPrefWidth(680);
        content.getChildren().addAll(
                createIntro(),
                scope = new ScopeSection(qupath, "QPCAT - Select images to plot"),
                new Separator(),
                createAxisSection(),
                createStatusSection());

        plotArea = new VBox();
        plotArea.setPadding(new Insets(6, 0, 0, 0));
        content.getChildren().add(plotArea);

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(720);

        dialog.getDialogPane().setContent(scrollPane);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.show();
    }

    private VBox createIntro() {
        Label title = new Label("What this does");
        title.setStyle("-fx-font-weight: bold;");
        Label body = new Label(
                "Plot every cell as a point -- on an existing 2D embedding (UMAP / t-SNE / "
                + "PCA coordinates already added by 'Map cells in 2D' or clustering) or on any "
                + "two marker measurements (biaxial). Then click Gate, draw a polygon around a "
                + "group of points, and select or assign a class to those cells -- across every "
                + "image in scope. Points are colored by their current classification.");
        body.setWrapText(true);
        return new VBox(4, title, body, QpcatDocLinks.linkBar("24-recipes-worked-examples"));
    }

    private VBox createAxisSection() {
        ToggleGroup g = new ToggleGroup();
        axisEmbedding = new RadioButton("2D embedding");
        axisEmbedding.setToggleGroup(g);
        axisEmbedding.setSelected(true);
        axisBiaxial = new RadioButton("Two markers (biaxial)");
        axisBiaxial.setToggleGroup(g);

        methodCombo = new ComboBox<>();
        methodCombo.getItems().addAll("UMAP", "t-SNE", "PCA");
        methodCombo.getSelectionModel().selectFirst();
        methodCombo.setTooltip(new Tooltip(
                "Which existing embedding coordinates to plot. They must already be on the\n"
                + "cells (run 'Map cells in 2D' or clustering with this method first)."));

        List<String> measurements = currentMeasurementNames();
        xMeasCombo = new ComboBox<>();
        xMeasCombo.getItems().addAll(measurements);
        yMeasCombo = new ComboBox<>();
        yMeasCombo.getItems().addAll(measurements);
        if (measurements.size() >= 1) xMeasCombo.getSelectionModel().select(0);
        if (measurements.size() >= 2) yMeasCombo.getSelectionModel().select(1);

        HBox embRow = new HBox(8, new Label("Embedding:"), methodCombo);
        embRow.setAlignment(Pos.CENTER_LEFT);
        HBox biRow = new HBox(8, new Label("X:"), xMeasCombo, new Label("Y:"), yMeasCombo);
        biRow.setAlignment(Pos.CENTER_LEFT);

        // Show only the controls for the selected axis source.
        Runnable upd = () -> {
            boolean emb = axisEmbedding.isSelected();
            embRow.setVisible(emb); embRow.setManaged(emb);
            biRow.setVisible(!emb); biRow.setManaged(!emb);
        };
        axisEmbedding.selectedProperty().addListener((o, a, b) -> upd.run());
        upd.run();

        Button plotBtn = new Button("Plot");
        plotBtn.setOnAction(e -> doPlot());
        HBox axisRow = new HBox(15, new Label("Plot:"), axisEmbedding, axisBiaxial);
        axisRow.setAlignment(Pos.CENTER_LEFT);

        return new VBox(8, axisRow, embRow, biRow, plotBtn);
    }

    private VBox createStatusSection() {
        statusLabel = new Label("Pick a scope and axis, then Plot.");
        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-text-fill: #555;");
        return new VBox(4, statusLabel);
    }

    /** Measurement names available on the current image's detections (for the combos). */
    private List<String> currentMeasurementNames() {
        ImageData<BufferedImage> data = qupath.getImageData();
        if (data == null) return new ArrayList<>();
        return MeasurementExtractor.getAllMeasurements(data.getHierarchy().getDetectionObjects());
    }

    private void doPlot() {
        if (scope.isSpecificButEmpty()) {
            Dialogs.showWarningNotification("QPCAT",
                    "No images chosen. Click 'Choose images...' first.");
            return;
        }
        boolean emb = axisEmbedding.isSelected();
        final String colX;
        final String colY;
        final String axisName;
        final String labelX;
        final String labelY;
        if (emb) {
            String prefix = switch (methodCombo.getValue()) {
                case "PCA" -> "PCA";
                case "t-SNE" -> "tSNE";
                default -> "UMAP";
            };
            colX = prefix + "1";
            colY = prefix + "2";
            axisName = methodCombo.getValue();
            labelX = colX;
            labelY = colY;
        } else {
            colX = xMeasCombo.getValue();
            colY = yMeasCombo.getValue();
            if (colX == null || colY == null) {
                Dialogs.showWarningNotification("QPCAT", "Pick both X and Y measurements.");
                return;
            }
            axisName = "biaxial";
            labelX = colX;
            labelY = colY;
        }

        List<ProjectImageEntry<BufferedImage>> entries = scope.resolveEntries();
        statusLabel.setText("Reading cells...");
        plotArea.getChildren().clear();

        Thread t = new Thread(() -> {
            try {
                PlotData pd = pool(entries, colX, colY);
                Platform.runLater(() -> {
                    if (pd.count == 0) {
                        statusLabel.setText("No cells with '" + colX + "' and '" + colY
                                + "' found." + (emb
                                ? " Run 'Map cells in 2D' (or clustering) with this method first."
                                : ""));
                        return;
                    }
                    EmbeddingScatterPanel scatter = new EmbeddingScatterPanel();
                    scatter.setData(pd.embedding, pd.labels, pd.nClasses, axisName);
                    scatter.setAxisLabels(labelX, labelY);
                    scatter.setNavigation(pd.refs, qupath, null);  // no crop preview here
                    plotArea.getChildren().setAll(GateActionBar.wrap(scatter, pd.refs, qupath));
                    statusLabel.setText("Plotted " + pd.count + " cells across "
                            + pd.imagesUsed + " image(s), " + pd.nClasses
                            + " classification(s). Click Gate to lasso a region.");
                });
            } catch (Exception ex) {
                logger.error("Plot & gate failed", ex);
                Platform.runLater(() -> statusLabel.setText("Error: " + ex.getMessage()));
            }
        }, "qpcat-plot-gate");
        t.setDaemon(true);
        t.start();
    }

    /** Pooled scatter data read from existing measurements across the scope. */
    private static final class PlotData {
        double[][] embedding;
        int[] labels;
        int nClasses;
        CellRef[] refs;
        int count;
        int imagesUsed;
    }

    private PlotData pool(List<ProjectImageEntry<BufferedImage>> entries,
                          String colX, String colY) {
        List<double[]> xy = new ArrayList<>();
        List<CellRef> refs = new ArrayList<>();
        List<String> classOf = new ArrayList<>();
        int imagesUsed;

        Project<BufferedImage> project = qupath.getProject();
        ImageData<BufferedImage> openData = qupath.getImageData();
        ProjectImageEntry<BufferedImage> openEntry =
                (project != null && openData != null) ? project.getEntry(openData) : null;

        if (entries == null) {
            // Current image.
            if (openData == null) return emptyData();
            String id = openEntry != null ? openEntry.getID() : null;
            String name = openData.getServer().getMetadata().getName();
            collect(openData, id, name, colX, colY, xy, refs, classOf);
            imagesUsed = 1;
        } else {
            imagesUsed = 0;
            for (ProjectImageEntry<BufferedImage> entry : entries) {
                ImageData<BufferedImage> data;
                boolean isOpen = openEntry != null && openEntry.getID().equals(entry.getID());
                if (isOpen) {
                    data = openData;
                } else {
                    try {
                        data = entry.readImageData();
                    } catch (Exception e) {
                        logger.warn("Plot & gate: failed to read {}: {}",
                                entry.getImageName(), e.getMessage());
                        continue;
                    }
                }
                int before = xy.size();
                collect(data, entry.getID(), entry.getImageName(), colX, colY, xy, refs, classOf);
                if (xy.size() > before) imagesUsed++;
            }
        }

        // Map distinct classifications -> color index for the scatter.
        Map<String, Integer> classToIdx = new LinkedHashMap<>();
        int[] labels = new int[classOf.size()];
        for (int i = 0; i < classOf.size(); i++) {
            labels[i] = classToIdx.computeIfAbsent(classOf.get(i), k -> classToIdx.size());
        }

        PlotData pd = new PlotData();
        pd.count = xy.size();
        pd.embedding = xy.toArray(new double[0][]);
        pd.labels = labels;
        pd.nClasses = Math.max(1, classToIdx.size());
        pd.refs = refs.toArray(new CellRef[0]);
        pd.imagesUsed = imagesUsed;
        return pd;
    }

    private static void collect(ImageData<BufferedImage> data, String imageId, String imageName,
                                String colX, String colY, List<double[]> xy,
                                List<CellRef> refs, List<String> classOf) {
        for (PathObject det : data.getHierarchy().getDetectionObjects()) {
            var ml = det.getMeasurements();
            Number vx = ml.get(colX);
            Number vy = ml.get(colY);
            if (vx == null || vy == null
                    || Double.isNaN(vx.doubleValue()) || Double.isNaN(vy.doubleValue())) {
                continue;
            }
            var roi = det.getROI();
            if (roi == null) continue;
            xy.add(new double[]{vx.doubleValue(), vy.doubleValue()});
            double half = 0.5 * Math.max(roi.getBoundsWidth(), roi.getBoundsHeight());
            refs.add(new CellRef(imageId, imageName, roi.getCentroidX(), roi.getCentroidY(), half));
            PathClass pc = det.getPathClass();
            classOf.add((pc == null || pc == PathClass.getNullClass()) ? "Unclassified" : pc.toString());
        }
    }

    private static PlotData emptyData() {
        PlotData pd = new PlotData();
        pd.embedding = new double[0][];
        pd.labels = new int[0];
        pd.nClasses = 1;
        pd.refs = new CellRef[0];
        pd.count = 0;
        pd.imagesUsed = 0;
        return pd;
    }
}
