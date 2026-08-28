package qupath.ext.qpcat.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
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
import qupath.ext.qpcat.service.ChannelMatcher;
import qupath.ext.qpcat.service.ViewerNavigator;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.ColorTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageChannel;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    /** Default number of channels shown in the per-cluster legend. */
    private static final int DEFAULT_LEGEND_CHANNELS = 4;

    private final ClusteringResult result;
    private final QuPathGUI qupath;
    private final CellCropService cropService;

    private final ChoiceBox<String> spaceChoice;
    private final Spinner<Double> scaleSpinner;
    private final CheckBox channelLegendCheck;
    private final Spinner<Integer> legendChannelSpinner;
    private final VBox clustersBox;

    // Per-cluster ranked marker (measurement) names from the run's marker
    // rankings, cluster id (as string) -> ordered measurement names. Empty when
    // the run produced no rankings.
    private final Map<String, List<String>> rankedMarkersByCluster;
    // Image channel name -> display color, in channel order. Empty when no image
    // is open (the legend then simply shows nothing).
    private final LinkedHashMap<String, Color> channelColors;

    private boolean useEmbedding = false;
    private double cropScale = CellCropService.DEFAULT_CROP_SCALE;
    private boolean showChannelLegend = false;
    private int legendChannels = DEFAULT_LEGEND_CHANNELS;

    // Issue #16: render each cluster's crops in ITS OWN top-ranked channels
    // instead of whatever the viewer happens to be showing. Off by default --
    // it makes the montages non-comparable across clusters, which is a real
    // cost and has to be chosen deliberately.
    private boolean perClusterChannels;
    private String fixedChannel;
    private CheckBox perClusterChannelsCheck;
    private ComboBox<String> fixedChannelCombo;
    private Label comparabilityWarning;

    /** Channel names tried in order for the fixed (usually nuclear) channel default. */
    private static final String[] NUCLEAR_HINTS = {"DAPI", "dapi", "Hoechst", "Nucleus"};

    // Crops loaded for the current (space, scale), keyed by cell index. Reused
    // by "Save montages" so it composes exactly what the user sees.
    private final Map<Integer, BufferedImage> loadedCrops = new ConcurrentHashMap<>();
    private long buildToken = 0;

    public RepresentativeGalleryPanel(ClusteringResult result, QuPathGUI qupath,
                                      CellCropService cropService) {
        this.result = result;
        this.qupath = qupath;
        this.cropService = cropService;
        this.rankedMarkersByCluster = parseRankedMarkers(result.getMarkerRankingsJson());
        this.channelColors = readChannelColors(qupath);

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
        refreshBtn.setTooltip(Tooltips.of(
                "Re-render the crops using the current viewer brightness, contrast,\n"
                + "and channel settings. Adjust the image in the viewer, then click this."));
        refreshBtn.setOnAction(e -> rebuild());

        Button saveBtn = new Button("Save montages");
        saveBtn.setOnAction(e -> saveMontages());

        // Optional per-cluster channel legend derived from the Marker Rankings.
        boolean legendPossible = !rankedMarkersByCluster.isEmpty() && !channelColors.isEmpty();

        legendChannelSpinner = new Spinner<>(1, 8, legendChannels, 1);
        legendChannelSpinner.setPrefWidth(70);
        legendChannelSpinner.setEditable(true);
        SpinnerUtils.commitOnFocusLoss(legendChannelSpinner);
        legendChannelSpinner.setDisable(true);
        legendChannelSpinner.valueProperty().addListener((obs, o, n) -> {
            if (n != null) {
                legendChannels = n;
                if (showChannelLegend) rebuild();
            }
        });
        Label legendChannelLabel = new Label("Channels:");

        channelLegendCheck = new CheckBox("Show channels from Marker Rankings");
        channelLegendCheck.setTooltip(Tooltips.of(
                "Append a small legend to each cluster showing the channels for its\n"
                + "top-ranked markers, matched by channel name appearing in the\n"
                + "measurement name. If channels or measurements were renamed so that\n"
                + "nothing matches, no channels are shown (this never errors)."));
        channelLegendCheck.setDisable(!legendPossible);
        if (!legendPossible) {
            channelLegendCheck.setTooltip(Tooltips.of(
                    rankedMarkersByCluster.isEmpty()
                            ? "This result has no Marker Rankings, so no channel legend can be built."
                            : "No image is open, so channel colors are unavailable for a legend."));
        }
        channelLegendCheck.selectedProperty().addListener((obs, o, n) -> {
            showChannelLegend = Boolean.TRUE.equals(n);
            legendChannelSpinner.setDisable(!showChannelLegend);
            rebuild();
        });

        // --- Per-cluster channels (issue #16) ---
        fixedChannelCombo = new ComboBox<>();
        fixedChannelCombo.getItems().addAll(channelColors.keySet());
        fixedChannelCombo.setPrefWidth(130);
        fixedChannelCombo.setDisable(true);
        fixedChannelCombo.setTooltip(Tooltips.of(
                "A channel shown in EVERY cluster's crops, on top of that cluster's\n"
                + "top-ranked markers. Normally the nuclear channel -- whatever it is\n"
                + "called in your data -- so each crop has a common anatomical\n"
                + "reference.\n\n"
                + "It does NOT count towards the 'Channels:' number: with that set to 3\n"
                + "you get the fixed channel plus 3 ranked markers."));
        fixedChannel = defaultNuclearChannel(fixedChannelCombo.getItems());
        if (fixedChannel != null) fixedChannelCombo.setValue(fixedChannel);
        fixedChannelCombo.valueProperty().addListener((obs, o, n) -> {
            fixedChannel = n;
            if (perClusterChannels) rebuild();
        });
        Label fixedChannelLabel = new Label("Fixed channel:");

        perClusterChannelsCheck = new CheckBox("Use per-cluster channels");
        perClusterChannelsCheck.setTooltip(Tooltips.of(
                "Render each cluster's crops using that cluster's own top-ranked\n"
                + "markers (from Marker Rankings), plus the fixed channel below,\n"
                + "instead of the channels currently shown in the viewer.\n\n"
                + "Useful for highly multiplexed data, where the viewer's channels\n"
                + "rarely happen to be the ones that define a given cluster.\n\n"
                + "WARNING: clusters are then displayed in DIFFERENT channels, so the\n"
                + "montages cannot be compared with each other."));
        perClusterChannelsCheck.setDisable(!legendPossible);
        perClusterChannelsCheck.selectedProperty().addListener((obs, o, n) -> {
            perClusterChannels = Boolean.TRUE.equals(n);
            fixedChannelCombo.setDisable(!perClusterChannels);
            comparabilityWarning.setVisible(perClusterChannels);
            comparabilityWarning.setManaged(perClusterChannels);
            loadedCrops.clear();   // cached crops were rendered with other channels
            rebuild();
        });

        comparabilityWarning = new Label(
                "THESE IMAGES CANNOT BE COMPARED WITH EACH OTHER. EACH CLUSTER IS SHOWN "
                + "IN DIFFERENT CHANNELS WITH DIFFERENT DISPLAY SETTINGS.");
        comparabilityWarning.setWrapText(true);
        comparabilityWarning.setStyle("-fx-font-weight: bold; -fx-text-fill: #7a2e00; "
                + "-fx-background-color: #fff3cd; -fx-border-color: #d9a400; "
                + "-fx-border-width: 1; -fx-padding: 6;");
        comparabilityWarning.setVisible(false);
        comparabilityWarning.setManaged(false);

        // A FlowPane, not an HBox. These controls want 1,479 px and the results
        // window opens at 850, so an HBox shrinks its children until the
        // checkbox labels ellipsize to "Show channels from Marker Ranki..." --
        // measured, not guessed. Wrapping keeps every label readable at any
        // window width.
        FlowPane controls = new FlowPane(8, 6,
                new Label("Center:"), spaceChoice,
                new Label("Crop x bbox:"), scaleSpinner,
                new Separator(Orientation.VERTICAL),
                channelLegendCheck, legendChannelLabel, legendChannelSpinner,
                new Separator(Orientation.VERTICAL),
                perClusterChannelsCheck, fixedChannelLabel, fixedChannelCombo,
                refreshBtn, saveBtn);
        controls.setAlignment(Pos.CENTER_LEFT);

        // --- Cluster rows ---
        clustersBox = new VBox(10);
        ScrollPane scroll = new ScrollPane(clustersBox);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(420);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(controls, comparabilityWarning, scroll);
        rebuild();
    }

    /**
     * Drop a WARNING.txt next to the exported montages when per-cluster channels
     * were used.
     *
     * <p>Each cluster is rendered in different channels, so the images are not
     * comparable -- and a PNG carries no record of that. Failure here is logged,
     * never thrown: losing the note is bad, losing the export is worse.
     */
    private void writeComparabilityWarning(File outDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("WARNING: THESE IMAGES CANNOT BE COMPARED WITH EACH OTHER\n");
        sb.append("========================================================\n\n");
        sb.append("Each cluster montage in this folder was rendered using THAT CLUSTER'S\n");
        sb.append("OWN top-ranked marker channels, plus a fixed reference channel");
        if (fixedChannel != null && !fixedChannel.isBlank()) {
            sb.append(" (\"").append(fixedChannel).append("\")");
        }
        sb.append(".\n\n");
        sb.append("Different clusters therefore show DIFFERENT CHANNELS, with different\n");
        sb.append("display settings. Brightness, contrast and colour do not carry the same\n");
        sb.append("meaning from one image to the next.\n\n");
        sb.append("Do NOT place these side by side to argue that one cluster is brighter,\n");
        sb.append("darker or differently coloured than another. They can only be read one\n");
        sb.append("at a time, as \"what does a typical cell of this cluster look like in the\n");
        sb.append("markers that define it\".\n\n");
        sb.append("For a comparable figure, turn OFF \"Use per-cluster channels\" and\n");
        sb.append("re-export: every cluster is then rendered with the same viewer\n");
        sb.append("channels and settings.\n\n");
        sb.append("On reporting microscopy images honestly, see the community checklists:\n");
        sb.append("  Schmied C, Nelson MS, Avilov S, et al. Community-developed checklists\n");
        sb.append("  for publishing images and image analyses.\n");
        sb.append("  Nature Methods 21, 170-181 (2024).\n");
        sb.append("  https://doi.org/10.1038/s41592-023-01987-9\n");
        try {
            java.nio.file.Files.writeString(new File(outDir, "WARNING.txt").toPath(),
                    sb.toString());
        } catch (Exception e) {
            logger.warn("Could not write WARNING.txt beside the montages: {}", e.getMessage());
        }
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
            Rectangle swatch = new Rectangle(12, 12,
                    EmbeddingScatterPanel.clusterColorFor(c, result::clusterName));
            swatch.setStroke(Color.GRAY);
            Label header = new Label(result.clusterName(c) + "  (" + clusterCount(c) + " cells)");
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
                    strip.getChildren().add(buildThumb(cellIdx, ref, rank == 0, token,
                            channelsForCluster(c)));
                }
            }

            if (showChannelLegend) {
                Region legend = buildChannelLegend(c);
                if (legend != null) {
                    strip.getChildren().addAll(new Separator(Orientation.VERTICAL), legend);
                }
            }

            clustersBox.getChildren().addAll(headerBox, strip);
        }
    }

    /**
     * Build the "channels from Marker Rankings" legend for one cluster: the
     * image channels backing that cluster's top-ranked markers, each as a
     * color swatch + name. Returns {@code null} when nothing matches (e.g. the
     * user renamed channels or measurements) so the caller shows no legend --
     * this never throws.
     */
    /**
     * First channel matching a common nuclear-stain name, or null.
     *
     * <p>Case-insensitive and substring-based, because real panels name this
     * channel "DAPI", "dapi", "DAPI (405)", "Hoechst 33342", "Nucleus" and so
     * on. Only a default -- the user can pick anything.
     */
    private static String defaultNuclearChannel(List<String> channelNames) {
        for (String hint : NUCLEAR_HINTS) {
            for (String name : channelNames) {
                if (name != null && name.toLowerCase(Locale.ROOT)
                        .contains(hint.toLowerCase(Locale.ROOT))) {
                    return name;
                }
            }
        }
        return null;
    }

    /**
     * Channels to render one cluster's crops in: the fixed channel first, then
     * that cluster's top-ranked markers. Null when per-cluster channels are off,
     * which leaves {@link CellCropService} on the viewer's own selection.
     *
     * <p>The fixed channel is deliberately NOT counted against the "Channels:"
     * spinner -- it is a constant reference, not one of the cluster's markers,
     * and silently spending one of the user's N on it would make the control
     * mean something different from what it says.
     */
    private List<String> channelsForCluster(int cluster) {
        if (!perClusterChannels) {
            return null;
        }
        List<String> out = new ArrayList<>();
        if (fixedChannel != null && !fixedChannel.isBlank()) {
            out.add(fixedChannel);
        }
        List<String> ranked = rankedMarkersByCluster.get(String.valueOf(cluster));
        if (ranked != null && !ranked.isEmpty() && !channelColors.isEmpty()) {
            for (String name : ChannelMatcher.matchChannels(
                    new ArrayList<>(channelColors.keySet()), ranked, legendChannels)) {
                if (!out.contains(name)) out.add(name);
            }
        }
        return out.isEmpty() ? null : out;
    }

    private Region buildChannelLegend(int cluster) {
        List<String> ranked = rankedMarkersByCluster.get(String.valueOf(cluster));
        if (ranked == null || ranked.isEmpty() || channelColors.isEmpty()) {
            return null;
        }
        List<String> matched = ChannelMatcher.matchChannels(
                new ArrayList<>(channelColors.keySet()), ranked, legendChannels);
        if (matched.isEmpty()) {
            return null;
        }

        VBox rows = new VBox(2);
        rows.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("Channels");
        title.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #555;");
        rows.getChildren().add(title);
        for (String name : matched) {
            Color color = channelColors.getOrDefault(name, Color.GRAY);
            Rectangle chip = new Rectangle(11, 11, color);
            chip.setStroke(Color.gray(0.6));
            Label lbl = new Label(name);
            lbl.setStyle("-fx-font-size: 11px;");
            HBox row = new HBox(5, chip, lbl);
            row.setAlignment(Pos.CENTER_LEFT);
            rows.getChildren().add(row);
        }
        rows.setPadding(new Insets(0, 4, 0, 0));
        Tooltip.install(rows, Tooltips.of(
                "Top channels for this cluster, from its Marker Rankings.\n"
                + "Matched by channel name appearing in the measurement name."));
        return rows;
    }

    /**
     * Parse the run's {@code marker_rankings} JSON into cluster id (as string)
     * -> ordered measurement names. Same JSON shape the fingerprint view reads
     * ({@code {name, score, logfoldchange, pval_adj}}); we keep only the ordered
     * {@code name}s. Returns an empty map on any problem (never throws).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> parseRankedMarkers(String json) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            Gson gson = new GsonBuilder().serializeNulls().setLenient().create();
            Type type = new TypeToken<Map<String, List<Map<String, Object>>>>() {}.getType();
            Map<String, List<Map<String, Object>>> parsed = gson.fromJson(json, type);
            if (parsed == null) {
                return out;
            }
            for (Map.Entry<String, List<Map<String, Object>>> e : parsed.entrySet()) {
                List<String> names = new ArrayList<>();
                if (e.getValue() != null) {
                    for (Map<String, Object> m : e.getValue()) {
                        Object nm = (m == null) ? null : m.get("name");
                        if (nm != null) names.add(String.valueOf(nm));
                    }
                }
                out.put(e.getKey(), names);
            }
        } catch (Exception e) {
            logger.warn("Could not parse marker rankings for channel legend: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Read the open image's channel names and display colors, in channel order.
     * Returns an empty map when no image is open or channels have no color (the
     * legend then shows nothing).
     */
    private static LinkedHashMap<String, Color> readChannelColors(QuPathGUI qupath) {
        LinkedHashMap<String, Color> out = new LinkedHashMap<>();
        if (qupath == null) {
            return out;
        }
        try {
            ImageData<BufferedImage> data = qupath.getImageData();
            if (data == null || data.getServer() == null) {
                return out;
            }
            for (ImageChannel ch : data.getServer().getMetadata().getChannels()) {
                if (ch == null || ch.getName() == null) continue;
                Integer rgb = ch.getColor();
                Color color = (rgb != null)
                        ? Color.rgb(ColorTools.red(rgb), ColorTools.green(rgb), ColorTools.blue(rgb))
                        : Color.GRAY;
                out.putIfAbsent(ch.getName(), color);
            }
        } catch (Exception e) {
            logger.warn("Could not read image channels for legend: {}", e.getMessage());
        }
        return out;
    }

    private VBox buildThumb(int cellIdx, CellRef ref, boolean isMedoid, long token,
                            List<String> clusterChannels) {
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
            BufferedImage crop = cropService.readCrop(ref, cropScale, clusterChannels);
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

        // The warning has to leave the app with the images. A caveat that lives
        // only in the panel is gone the moment someone drops these PNGs into a
        // figure, which is exactly when it matters.
        if (perClusterChannels) {
            writeComparabilityWarning(outDir);
        }

        CellRef[] refs = result.getCellRefs();
        int written = 0;
        for (int c = 0; c < result.getNClusters(); c++) {
            int[] idx = result.getRepresentativeIndices(c, useEmbedding);
            List<BufferedImage> crops = new ArrayList<>();
            for (int cellIdx : idx) {
                BufferedImage crop = loadedCrops.get(cellIdx);
                if (crop == null && refs != null && cellIdx >= 0 && cellIdx < refs.length) {
                    crop = cropService.readCrop(refs[cellIdx], cropScale,
                            channelsForCluster(c));  // fill any gaps
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
