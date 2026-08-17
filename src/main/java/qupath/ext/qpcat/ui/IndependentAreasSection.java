package qupath.ext.qpcat.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.controlsfx.control.CheckComboBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.AreaLevel;
import qupath.ext.qpcat.model.AreaLevelSpec;
import qupath.ext.qpcat.service.AreaResolver;
import qupath.ext.qpcat.service.DetectionSelector;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static qupath.ext.qpcat.ui.UiLabels.tipLabel;

/**
 * "Independent areas": an ordered list of hierarchy levels that split cells
 * into physically separate analysis areas, so no spatial graph is ever built
 * across a boundary that tissue does not cross.
 * <p>
 * The rows read outermost-first and describe a nesting path. Everything BELOW
 * the deepest row stays in one area -- with {@code Images > TMA cores}, the
 * Tumor and Stroma annotations inside a core still share a graph, because they
 * are continuous tissue and the interface between them is usually the thing
 * being measured.
 * <p>
 * The live preview is the point of the control. Both ways of getting this
 * wrong are silent: too deep invents boundaries inside one specimen, too
 * shallow invents adjacency between two, and neither shows up in the output as
 * anything but plausible clusters. Seeing "55 areas, 340 cells unassigned"
 * before starting a forty-minute run is what makes the choice checkable.
 */
public final class IndependentAreasSection extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(IndependentAreasSection.class);

    /** Levels the user can add. IMAGES is implicit and always outermost. */
    private static final List<AreaLevel> ADDABLE =
            List.of(AreaLevel.TMA_CORES, AreaLevel.ANNOTATIONS);

    private final QuPathGUI qupath;
    private final VBox rowBox = new VBox(4);
    private final List<LevelRow> rows = new ArrayList<>();
    private final Label previewLabel = new Label();
    private final List<Runnable> changeListeners = new ArrayList<>();

    public IndependentAreasSection(QuPathGUI qupath) {
        this.qupath = qupath;
        setSpacing(6);
        setPadding(new Insets(8, 0, 4, 0));

        Label heading = new Label("Independent areas:");
        heading.setTooltip(Tooltips.of(
                "Split cells into physically separate areas. No spatial graph -- BANKSY, "
                + "smoothing, Ripley, co-occurrence -- is ever built across two areas, "
                + "because the distance between a cell in one TMA core and a cell in the "
                + "next is not a distance through tissue.\n\n"
                + "Rows read outermost first. Anything below the last row stays together: "
                + "with Images > TMA cores, Tumor and Stroma inside a core still share a "
                + "graph, so the interface between them is preserved."));

        // Images is always the outermost level and is not removable: two images
        // have unrelated coordinate frames, so joining them is never correct.
        Label imagesRow = new Label("1. Images (always separate)");
        imagesRow.setStyle("-fx-text-fill: derive(-fx-text-base-color, 25%);");
        imagesRow.setTooltip(Tooltips.of(
                "Cells from different images never share a spatial graph. Image pixel "
                + "coordinates are unrelated between images -- (100,100) in one image is "
                + "not near (100,100) in another -- so this level cannot be removed."));

        Button addButton = new Button("+");
        addButton.setTooltip(Tooltips.of("Add a level below the last one"));
        addButton.setOnAction(e -> {
            addRow(defaultNextLevel());
            fireChanged();
        });

        HBox addRow = new HBox(8, addButton, tipLabel("Add level", addButton));
        addRow.setAlignment(Pos.CENTER_LEFT);

        previewLabel.setWrapText(true);
        previewLabel.setStyle("-fx-text-fill: derive(-fx-text-base-color, 25%);");

        getChildren().addAll(heading, imagesRow, rowBox, addRow, previewLabel);
        refreshPreview();
    }

    /** One editable level row: a level combo, an optional class picker, a remove button. */
    private final class LevelRow {
        private final HBox node;
        private final ComboBox<AreaLevel> levelCombo = new ComboBox<>();
        private final CheckComboBox<String> classPicker = new CheckComboBox<>();

        LevelRow(AreaLevel initial) {
            levelCombo.getItems().addAll(ADDABLE);
            levelCombo.setValue(initial);
            levelCombo.setTooltip(Tooltips.of(
                    "TMA cores: split by dearrayed core, however many annotation layers "
                    + "sit between the core and the cells.\n\n"
                    + "Annotations: split by annotation OBJECT, not by class. Three tissue "
                    + "sections on one slide usually share a single 'Tissue' class, and "
                    + "grouping by class would merge them straight back together."));

            classPicker.setTooltip(Tooltips.of(
                    "Which annotation classes mark an area boundary. Leave everything "
                    + "unticked to treat any annotation as a boundary."));
            classPicker.getItems().setAll(annotationClassesInScope());

            Button remove = new Button("-");
            remove.setTooltip(Tooltips.of("Remove this level"));
            remove.setOnAction(e -> {
                rows.remove(this);
                rebuildRows();
                fireChanged();
            });

            node = new HBox(8, new Label(), levelCombo, classPicker, remove);
            node.setAlignment(Pos.CENTER_LEFT);

            levelCombo.valueProperty().addListener((o, a, b) -> {
                updateClassPickerVisibility();
                fireChanged();
            });
            classPicker.getCheckModel().getCheckedItems()
                    .addListener((javafx.collections.ListChangeListener<String>) c -> fireChanged());
            updateClassPickerVisibility();
        }

        private void updateClassPickerVisibility() {
            boolean show = levelCombo.getValue() == AreaLevel.ANNOTATIONS;
            classPicker.setVisible(show);
            classPicker.setManaged(show);
        }

        void setIndexLabel(int oneBased) {
            ((Label) node.getChildren().get(0)).setText(oneBased + ".");
        }

        AreaLevelSpec toSpec() {
            AreaLevel level = levelCombo.getValue();
            if (level == AreaLevel.ANNOTATIONS) {
                return new AreaLevelSpec(
                        level, new ArrayList<>(classPicker.getCheckModel().getCheckedItems()));
            }
            return new AreaLevelSpec(level);
        }

        void applySpec(AreaLevelSpec spec) {
            levelCombo.setValue(spec.getLevel());
            classPicker.getCheckModel().clearChecks();
            for (String cls : spec.getAnnotationClasses()) {
                if (classPicker.getItems().contains(cls)) {
                    classPicker.getCheckModel().check(cls);
                }
            }
            updateClassPickerVisibility();
        }
    }

    private void addRow(AreaLevel level) {
        rows.add(new LevelRow(level));
        rebuildRows();
    }

    private void rebuildRows() {
        rowBox.getChildren().clear();
        for (int i = 0; i < rows.size(); i++) {
            LevelRow row = rows.get(i);
            row.setIndexLabel(i + 2);  // 1 is the fixed Images level
            rowBox.getChildren().add(row.node);
        }
    }

    /**
     * What a newly added row should start as: TMA cores when the image is
     * dearrayed and no row covers them yet, else annotations.
     */
    private AreaLevel defaultNextLevel() {
        if (currentImageHasTmaGrid() && rows.stream().noneMatch(
                r -> r.levelCombo.getValue() == AreaLevel.TMA_CORES)) {
            return AreaLevel.TMA_CORES;
        }
        return AreaLevel.ANNOTATIONS;
    }

    /** The configured levels, always beginning with the implicit Images level. */
    public List<AreaLevelSpec> getAreaLevels() {
        List<AreaLevelSpec> levels = new ArrayList<>();
        levels.add(new AreaLevelSpec(AreaLevel.IMAGES));
        for (LevelRow row : rows) {
            levels.add(row.toSpec());
        }
        return levels;
    }

    /** Restores a saved configuration. The implicit Images level is skipped. */
    public void setAreaLevels(List<AreaLevelSpec> levels) {
        rows.clear();
        if (levels != null) {
            for (AreaLevelSpec spec : levels) {
                if (spec.getLevel() == AreaLevel.IMAGES) {
                    continue;
                }
                LevelRow row = new LevelRow(spec.getLevel());
                row.applySpec(spec);
                rows.add(row);
            }
        }
        rebuildRows();
        refreshPreview();
    }

    /**
     * Seeds the default for a dialog the user has not touched: split by TMA
     * core when the image is dearrayed, otherwise images only.
     * <p>
     * Auto-detecting rather than defaulting to images-only means a TMA does the
     * right thing for a user who has never heard of this control -- and the
     * preview line says so, so it is visible rather than silent.
     */
    public void applyAutoDetectedDefault() {
        rows.clear();
        if (currentImageHasTmaGrid()) {
            rows.add(new LevelRow(AreaLevel.TMA_CORES));
            logger.info("Independent areas: TMA grid detected, defaulting to per-core areas");
        }
        rebuildRows();
        refreshPreview();
    }

    /** Registers a listener fired whenever the configuration changes. */
    public void addChangeListener(Runnable r) {
        changeListeners.add(r);
    }

    /**
     * True when at least one level splits below the image level, i.e. there is
     * something to partition that per-image grouping could not already express.
     */
    public boolean hasSubImageLevels() {
        return !rows.isEmpty();
    }

    private void fireChanged() {
        refreshPreview();
        for (Runnable r : changeListeners) {
            r.run();
        }
    }

    /**
     * Recomputes the preview line. Resolved against the CURRENT image only:
     * a project-scope preview would have to open every image, which is far too
     * slow for a control that updates on every click. The label says which it
     * is rather than implying the number covers the whole run.
     */
    public void refreshPreview() {
        ImageData<BufferedImage> imageData = currentImageData();
        if (imageData == null) {
            previewLabel.setText("Open an image to preview how it splits.");
            return;
        }
        List<AreaLevelSpec> levels = getAreaLevels();
        if (rows.isEmpty()) {
            previewLabel.setText(
                    "One area per image. Cells within an image all share a spatial graph.");
            return;
        }
        try {
            List<PathObject> detections = new ArrayList<>(
                    imageData.getHierarchy().getDetectionObjects());
            if (detections.isEmpty()) {
                previewLabel.setText("No detections in the current image to preview.");
                return;
            }
            detections = DetectionSelector.filterToCellsWhenPresent(detections, imageName());
            AreaResolver.AreaAssignment areas = AreaResolver.resolve(
                    detections, (int[]) null, List.of(imageName()), levels);
            StringBuilder sb = new StringBuilder("Current image: ").append(areas.describe());
            if (areas.isSingleArea()) {
                sb.append(". Nothing is split -- check the level and class selection.");
            }
            previewLabel.setText(sb.toString());
        } catch (Exception e) {
            // A preview is a convenience; never let it block the dialog.
            logger.debug("Area preview failed: {}", e.getMessage());
            previewLabel.setText("Could not preview this image's areas.");
        }
    }

    private boolean currentImageHasTmaGrid() {
        ImageData<BufferedImage> imageData = currentImageData();
        if (imageData == null) {
            return false;
        }
        PathObjectHierarchy hierarchy = imageData.getHierarchy();
        return hierarchy.getTMAGrid() != null && hierarchy.getTMAGrid().nCores() > 0;
    }

    private List<String> annotationClassesInScope() {
        Set<String> names = new LinkedHashSet<>();
        ImageData<BufferedImage> imageData = currentImageData();
        if (imageData != null) {
            for (PathObject annotation : imageData.getHierarchy().getAnnotationObjects()) {
                PathClass pc = annotation.getPathClass();
                if (pc != null && pc != PathClass.getNullClass()) {
                    names.add(pc.toString());
                }
            }
        }
        return new ArrayList<>(names);
    }

    @SuppressWarnings("unchecked")
    private ImageData<BufferedImage> currentImageData() {
        return qupath == null ? null : (ImageData<BufferedImage>) qupath.getImageData();
    }

    private String imageName() {
        ImageData<BufferedImage> imageData = currentImageData();
        if (imageData != null && imageData.getServer() != null) {
            String name = imageData.getServer().getMetadata().getName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return "Current image";
    }
}
