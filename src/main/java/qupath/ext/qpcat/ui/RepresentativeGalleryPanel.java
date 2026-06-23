package qupath.ext.qpcat.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.CellRef;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.ext.qpcat.service.CellCropService;
import qupath.ext.qpcat.service.ViewerNavigator;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-cluster gallery of "representative" cell crops. For each cluster the
 * Python side ranks member cells by distance to the cluster center (medoid
 * first) under two definitions -- feature space and embedding space -- and the
 * gallery shows the top-K crops in a strip. Clicking a thumbnail navigates to
 * that cell; "Save montages" writes one PNG strip per cluster next to the other
 * result plots.
 *
 * <p>A medoid is a real cell, not a synthetic prototype; "representative" means
 * "near the center", not "pure". See BEST_PRACTICES.md.</p>
 */
public class RepresentativeGalleryPanel extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(RepresentativeGalleryPanel.class);
    private static final double THUMB_SIZE = 110;

    private final ClusteringResult result;
    private final QuPathGUI qupath;
    private final CellCropService cropService;

    private final ChoiceBox<String> spaceChoice;
    private final Spinner<Double> scaleSpinner;
    private final VBox clustersBox;

    private boolean useEmbedding = false;
    private double cropScale = CellCropService.DEFAULT_CROP_SCALE;

    // Crops loaded for the current (space, scale), keyed by cell index. Reused
    // by "Save montages" so it composes exactly what the user sees.
    private final Map<Integer, BufferedImage> loadedCrops = new ConcurrentHashMap<>();
    private long buildToken = 0;

    public RepresentativeGalleryPanel(ClusteringResult result, QuPathGUI qupath,
                                      CellCropService cropService) {
        this.result = result;
        this.qupath = qupath;
        this.cropService = cropService;

        setSpacing(8);
        setPadding(new Insets(8));

        // --- Controls ---
        spaceChoice = new ChoiceBox<>();
        spaceChoice.getItems().add("Feature-space medoid");
        boolean hasEmb = result.hasEmbeddingRepresentatives();
        if (hasEmb) spaceChoice.getItems().add("Embedding-space medoid");
        spaceChoice.getSelectionModel().selectFirst();
        spaceChoice.setOnAction(e -> {
            useEmbedding = spaceChoice.getSelectionModel().getSelectedIndex() == 1;
            rebuild();
        });

        scaleSpinner = new Spinner<>(1.0, 10.0, cropScale, 0.5);
        scaleSpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(scaleSpinner);
        scaleSpinner.setPrefWidth(80);
        scaleSpinner.valueProperty().addListener((obs, o, n) -> {
            if (n != null) {
                cropScale = n;
                rebuild();
            }
        });

        // Crops are rendered with the image's display settings (brightness /
        // contrast / channels). For the open image that's the LIVE viewer
        // display, so this button re-reads the crops after the user adjusts the
        // viewer -- otherwise multichannel/fluorescence crops look raw.
        Button refreshBtn = new Button("Update from viewer");
        refreshBtn.setTooltip(new Tooltip(
                "Re-render the crops using the current viewer brightness, contrast,\n"
                + "and channel settings. Adjust the image in the viewer, then click this."));
        refreshBtn.setOnAction(e -> rebuild());

        Button saveBtn = new Button("Save montages");
        saveBtn.setOnAction(e -> saveMontages());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox controls = new HBox(8,
                new Label("Center:"), spaceChoice,
                new Label("Crop x bbox:"), scaleSpinner,
                spacer, refreshBtn, saveBtn);
        controls.setAlignment(Pos.CENTER_LEFT);

        // --- Cluster rows ---
        clustersBox = new VBox(10);
        ScrollPane scroll = new ScrollPane(clustersBox);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(420);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(controls, scroll);
        rebuild();
    }

    private int clusterCount(int cluster) {
        int n = 0;
        int[] labels = result.getClusterLabels();
        if (labels == null) return 0;
        for (int l : labels) if (l == cluster) n++;
        return n;
    }

    private void rebuild() {
        final long token = ++buildToken;
        loadedCrops.clear();
        clustersBox.getChildren().clear();

        CellRef[] refs = result.getCellRefs();
        for (int c = 0; c < result.getNClusters(); c++) {
            int[] idx = result.getRepresentativeIndices(c, useEmbedding);

            // Header with color swatch + count
            Rectangle swatch = new Rectangle(12, 12, EmbeddingScatterPanel.clusterColorFor(c));
            swatch.setStroke(Color.GRAY);
            Label header = new Label("Cluster " + c + "  (" + clusterCount(c) + " cells)");
            header.setStyle("-fx-font-weight: bold;");
            HBox headerBox = new HBox(6, swatch, header);
            headerBox.setAlignment(Pos.CENTER_LEFT);

            HBox strip = new HBox(6);
            strip.setAlignment(Pos.CENTER_LEFT);
            if (idx.length == 0) {
                Label none = new Label("(no representatives)");
                none.setStyle("-fx-text-fill: #999; -fx-font-style: italic;");
                strip.getChildren().add(none);
            } else {
                for (int rank = 0; rank < idx.length; rank++) {
                    int cellIdx = idx[rank];
                    CellRef ref = (refs != null && cellIdx >= 0 && cellIdx < refs.length)
                            ? refs[cellIdx] : null;
                    strip.getChildren().add(buildThumb(cellIdx, ref, rank == 0, token));
                }
            }

            clustersBox.getChildren().addAll(headerBox, strip);
        }
    }

    private VBox buildThumb(int cellIdx, CellRef ref, boolean isMedoid, long token) {
        ImageView iv = new ImageView();
        iv.setPreserveRatio(true);
        iv.setFitWidth(THUMB_SIZE);
        iv.setFitHeight(THUMB_SIZE);
        iv.setSmooth(true);

        Label caption = new Label((isMedoid ? "medoid" : "cell " + cellIdx));
        caption.setStyle("-fx-font-size: 10px; -fx-text-fill: #555;");

        VBox box = new VBox(2, iv, caption);
        box.setAlignment(Pos.CENTER);
        box.setMinSize(THUMB_SIZE, THUMB_SIZE + 16);
        box.setStyle("-fx-border-color: " + (isMedoid ? "#333" : "#ccc")
                + "; -fx-border-width: " + (isMedoid ? 2 : 1) + ";");

        if (ref == null) {
            caption.setText("unavailable");
            return box;
        }

        box.setOnMouseClicked(e -> ViewerNavigator.navigateToCell(
                qupath, ref.getImageId(), ref.getImageName(), ref.getX(), ref.getY()));
        Region cursorTarget = box;
        cursorTarget.setStyle(cursorTarget.getStyle() + " -fx-cursor: hand;");

        // Async crop read
        Thread t = new Thread(() -> {
            BufferedImage crop = cropService.readCrop(ref, cropScale);
            if (crop != null) loadedCrops.put(cellIdx, crop);
            Image fx = (crop != null) ? SwingFXUtils.toFXImage(crop, null) : null;
            Platform.runLater(() -> {
                if (token != buildToken) return;  // a later rebuild superseded this
                if (fx != null) {
                    iv.setImage(fx);
                } else {
                    caption.setText("unavailable");
                }
            });
        }, "qpcat-gallery-crop");
        t.setDaemon(true);
        t.start();

        return box;
    }

    /**
     * Compose each cluster's loaded crops into a horizontal montage PNG, written
     * next to the other result plots. Uses whatever crops have loaded so far for
     * the current space/scale.
     */
    private void saveMontages() {
        File outDir = resolveOutputDir();
        if (outDir == null) return;

        CellRef[] refs = result.getCellRefs();
        int written = 0;
        for (int c = 0; c < result.getNClusters(); c++) {
            int[] idx = result.getRepresentativeIndices(c, useEmbedding);
            List<BufferedImage> crops = new ArrayList<>();
            for (int cellIdx : idx) {
                BufferedImage crop = loadedCrops.get(cellIdx);
                if (crop == null && refs != null && cellIdx >= 0 && cellIdx < refs.length) {
                    crop = cropService.readCrop(refs[cellIdx], cropScale);  // fill any gaps
                }
                if (crop != null) crops.add(crop);
            }
            if (crops.isEmpty()) continue;

            BufferedImage montage = composeHorizontal(crops);
            File out = new File(outDir, "cluster_" + c + "_representatives.png");
            try {
                ImageIO.write(montage, "png", out);
                written++;
            } catch (Exception e) {
                logger.warn("Failed to write montage for cluster {}: {}", c, e.getMessage());
            }
        }

        final int count = written;
        Platform.runLater(() -> Dialogs.showInfoNotification("QP-CAT",
                "Wrote " + count + " cluster montage(s) to " + outDir.getAbsolutePath()));
    }

    /** Horizontal strip of crops with a small gutter, top-aligned. */
    private static BufferedImage composeHorizontal(List<BufferedImage> crops) {
        int gutter = 4;
        int maxH = 0;
        int totalW = 0;
        for (BufferedImage c : crops) {
            maxH = Math.max(maxH, c.getHeight());
            totalW += c.getWidth() + gutter;
        }
        totalW = Math.max(1, totalW - gutter);
        BufferedImage montage = new BufferedImage(totalW, Math.max(1, maxH),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = montage.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, totalW, maxH);
        int x = 0;
        for (BufferedImage c : crops) {
            g.drawImage(c, x, 0, null);
            x += c.getWidth() + gutter;
        }
        g.dispose();
        return montage;
    }

    /**
     * Output directory for montages: the directory holding the result plots if
     * available, otherwise a user-chosen directory.
     */
    private File resolveOutputDir() {
        if (result.hasPlots()) {
            for (String p : result.getPlotPaths().values()) {
                if (p == null) continue;
                File parent = new File(p).getParentFile();
                if (parent != null && parent.isDirectory()) return parent;
            }
        }
        var chooser = new javafx.stage.DirectoryChooser();
        chooser.setTitle("Choose a folder for cluster montages");
        return chooser.showDialog(getScene() != null ? getScene().getWindow() : null);
    }
}
