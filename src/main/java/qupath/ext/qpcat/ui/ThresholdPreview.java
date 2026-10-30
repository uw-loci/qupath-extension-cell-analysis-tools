package qupath.ext.qpcat.ui;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.GateSplit;
import qupath.ext.qpcat.model.MarkerScaling;
import qupath.ext.qpcat.service.DetectionSelector;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.interfaces.ROI;

/**
 * The live preview of one marker's gate on the open image: the control row
 * under the histogram, and the overlay it drives. Nothing is written to any
 * cell. Must be called on the FX thread.
 */
final class ThresholdPreview {

    private static final Logger logger = LoggerFactory.getLogger(ThresholdPreview.class);
    private static final String NO_THRESHOLDS = "Click Compute Thresholds first.";

    private final QuPathGUI qupath;
    private final BooleanSupplier openImageInScope;
    private final CheckBox enable = new CheckBox("Show on the open image");
    private final ComboBox<ThresholdPreviewOverlay.Show> show = new ComboBox<>();
    private final Label status = new Label(NO_THRESHOLDS);
    private final VBox node;
    private final ChangeListener<ImageData<BufferedImage>> imageListener =
            (obs, was, now) -> reload();

    private Map<String, MarkerScaling> scalings;
    private String marker;
    private double gate;
    private QuPathViewer viewer;
    private ThresholdPreviewOverlay overlay;
    private ThresholdPreviewOverlay.Cells shown;
    // Bumped by every load, so a slow load for an earlier marker or image is ignored.
    private int loadId;

    ThresholdPreview(QuPathGUI qupath, BooleanSupplier openImageInScope) {
        this.qupath = qupath;
        this.openImageInScope = openImageInScope;

        enable.setDisable(true);
        enable.setTooltip(Tooltips.of(
                "Color the open image's cells by which side of this marker's gate they fall on,\n"
                + "using the histogram's colors, and follow the red line as it moves. Cells are\n"
                + "judged exactly as the run will judge them. Nothing is written to any cell.\n"
                + "Dots when zoomed out, cell outlines once few enough cells are in view."));
        enable.selectedProperty().addListener((obs, was, on) -> {
            if (on) {
                reload();
            } else {
                hide();
            }
        });

        show.getItems().addAll(ThresholdPreviewOverlay.Show.values());
        show.setValue(ThresholdPreviewOverlay.Show.BOTH);
        show.setOnAction(e -> {
            if (overlay != null) {
                overlay.setShow(show.getValue());
            }
        });

        status.setWrapText(true);
        WrapHeight.bind(status);
        status.setStyle("-fx-font-style: italic; -fx-text-fill: derive(-fx-text-base-color, 25%);");

        HBox row = new HBox(8, enable, show);
        row.setAlignment(Pos.CENTER_LEFT);
        node = new VBox(4, row, status);
        qupath.imageDataProperty().addListener(imageListener);
    }

    Node node() {
        return node;
    }

    /** @param scalings each marker's scaling from Compute Thresholds, or null when out of date */
    void setScalings(Map<String, MarkerScaling> scalings) {
        this.scalings = scalings;
        enable.setDisable(scalings == null);
        if (scalings == null) {
            enable.setSelected(false);
            hide();
            status.setText(NO_THRESHOLDS);
        } else if (enable.isSelected()) {
            reload();
        } else {
            status.setText("");
        }
    }

    /** Follow the histogram's marker and red line. */
    void update(String marker, double gate) {
        boolean sameMarker = marker != null && marker.equals(this.marker);
        this.marker = marker;
        this.gate = gate;
        if (!enable.isSelected()) {
            return;
        }
        if (sameMarker && shown != null) {
            overlay.setGate(gate);
            showSplit();
        } else {
            reload();
        }
    }

    /** Take the overlay off the viewer and stop following the image. */
    void dispose() {
        qupath.imageDataProperty().removeListener(imageListener);
        hide();
    }

    private void hide() {
        loadId++;
        shown = null;
        if (overlay != null) {
            viewer.getCustomOverlayLayers().remove(overlay);
            viewer.repaint();
        }
        overlay = null;
        viewer = null;
        status.setText(scalings == null ? NO_THRESHOLDS : "");
    }

    /** Read the open image's cells for the current marker off the FX thread. */
    private void reload() {
        if (!enable.isSelected() || scalings == null) {
            return;
        }
        QuPathViewer active = qupath.getViewer();
        ImageData<BufferedImage> imageData = active == null ? null : active.getImageData();
        MarkerScaling scaling = marker == null ? null : scalings.get(marker);
        hide();
        if (imageData == null) {
            status.setText("Open an image to preview this gate.");
            return;
        }
        if (scaling == null) {
            status.setText("Click a marker column header to preview its gate.");
            return;
        }
        int id = ++loadId;
        String loadMarker = marker;
        status.setText("Reading cells...");
        Thread reader = new Thread(() -> {
            ThresholdPreviewOverlay.Cells cells = read(imageData, loadMarker, scaling);
            Platform.runLater(() -> {
                if (id == loadId) {
                    attach(active, cells);
                }
            });
        }, "QPCAT-ThresholdPreview");
        reader.setDaemon(true);
        reader.start();
    }

    private void attach(QuPathViewer target, ThresholdPreviewOverlay.Cells cells) {
        viewer = target;
        overlay = new ThresholdPreviewOverlay(target);
        overlay.setShow(show.getValue());
        overlay.setCells(cells, gate);
        target.getCustomOverlayLayers().add(overlay);
        shown = cells;
        showSplit();
    }

    private void showSplit() {
        String name = shown.imageData().getServer().getMetadata().getName();
        status.setText(name + ": " + GateSplit.of(shown.values(), gate).label()
                + (openImageInScope.getAsBoolean() ? "" : "  (not one of the images this run covers)"));
    }

    /** The same cells and values the run reads, normalized as the run normalizes them. */
    private static ThresholdPreviewOverlay.Cells read(ImageData<BufferedImage> imageData, String marker,
                                                      MarkerScaling scaling) {
        List<PathObject> cells = DetectionSelector.filterToCellsWhenPresent(
                imageData.getHierarchy().getDetectionObjects(), "threshold preview");
        int n = cells.size();
        PathObject[] objects = cells.toArray(new PathObject[0]);
        float[] x = new float[n];
        float[] y = new float[n];
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            ROI roi = objects[i].getROI();
            x[i] = (float) roi.getCentroidX();
            y[i] = (float) roi.getCentroidY();
            Number raw = objects[i].getMeasurements().get(marker);
            values[i] = raw == null ? Double.NaN : scaling.apply(raw.doubleValue());
        }
        logger.debug("Threshold preview read {} cells for {}", n, marker);
        return new ThresholdPreviewOverlay.Cells(imageData, objects, x, y, values);
    }
}
