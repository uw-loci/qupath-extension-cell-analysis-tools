package qupath.ext.qpcat.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Reusable 3-way image-scope control (Current image / All project images /
 * Specific images...) backed by {@link ProjectImageSelector}. Drop it into any
 * dialog that runs over one or many project images; call {@link #resolveEntries()}
 * at run time.
 *
 * <p>Extracted from the copy-pasted scope blocks in the clustering / phenotyping
 * / cellular-neighborhood dialogs so new tools don't add a fourth copy.</p>
 */
public final class ScopeSection extends VBox {

    private final QuPathGUI qupath;
    private final String pickerTitle;

    private final RadioButton scopeCurrentImage;
    private final RadioButton scopeAllImages;
    private final RadioButton scopeSpecificImages;
    private final Button chooseImagesButton;
    private final Label specificImagesLabel;
    private final Label unavailableHint = new Label();
    /** Structural unavailability, so a run-finished re-enable cannot undo it. */
    private boolean currentImageUnavailable;
    private boolean allImagesUnavailable;
    private boolean specificImagesUnavailable;
    private final List<ProjectImageEntry<BufferedImage>> selectedSubset = new ArrayList<>();

    /**
     * @param qupath      the QuPath GUI instance
     * @param pickerTitle title for the "Specific images..." subset picker dialog
     */
    public ScopeSection(QuPathGUI qupath, String pickerTitle) {
        this.qupath = qupath;
        this.pickerTitle = pickerTitle;
        setSpacing(6);

        ToggleGroup group = new ToggleGroup();
        scopeCurrentImage = new RadioButton("Current image");
        scopeCurrentImage.setToggleGroup(group);
        scopeAllImages = new RadioButton("All project images");
        scopeAllImages.setToggleGroup(group);
        scopeSpecificImages = new RadioButton("Specific images...");
        scopeSpecificImages.setToggleGroup(group);

        chooseImagesButton = new Button("Choose images...");
        chooseImagesButton.setOnAction(e -> openImageChooser());
        specificImagesLabel = new Label("(none chosen)");
        specificImagesLabel.setStyle("-fx-text-fill: #666;");

        Project<BufferedImage> project = qupath.getProject();
        // "Current image" means nothing with no image open. Rather than refusing
        // to show the tool at all, disable that option and start from a project
        // scope, so the user picks the images FIRST and everything downstream
        // (channels, measurements) is derived from what they picked.
        boolean haveOpenImage = qupath.getImageData() != null;
        if (!haveOpenImage) {
            scopeCurrentImage.setDisable(true);
            scopeCurrentImage.setTooltip(Tooltips.of(
                    "No image is open. Choose the project images to work on instead -- "
                    + "QP-CAT reads their channels and measurements directly."));
        }

        boolean multiImage = project != null && project.getImageList().size() > 1;
        if (!multiImage) {
            scopeAllImages.setDisable(true);
            scopeSpecificImages.setDisable(true);
            Tooltip tip = Tooltips.of(
                    "This project has fewer than two images, so there is nothing to run "
                    + "across. Add images to the project to enable a multi-image run.");
            scopeAllImages.setTooltip(tip);
            scopeSpecificImages.setTooltip(tip);
        } else {
            scopeAllImages.setText("All project images (" + project.getImageList().size() + ")");
        }

        // Initial selection: the open image when there is one, otherwise the
        // widest project scope available. Never leave every option unselected.
        if (haveOpenImage) {
            scopeCurrentImage.setSelected(true);
        } else if (project != null && !project.getImageList().isEmpty()) {
            if (multiImage) {
                scopeAllImages.setSelected(true);
            } else {
                scopeSpecificImages.setDisable(false);
                scopeSpecificImages.setSelected(true);
            }
        }

        // Read the structural state AFTER the selection logic, which re-enables
        // "Specific images..." for a one-image project with nothing open.
        currentImageUnavailable = scopeCurrentImage.isDisable();
        allImagesUnavailable = scopeAllImages.isDisable();
        specificImagesUnavailable = scopeSpecificImages.isDisable();

        // WHY a disabled option is disabled, on its own wrapped line rather
        // than appended to the radio labels. Radio text lays out on ONE line:
        // a parenthetical long enough to explain itself pushed the row past the
        // dialog's 550px content width, and JavaFX resolved that by ellipsizing
        // the labels to "..." -- so every option became unreadable in order to
        // explain one of them.
        List<String> reasons = new ArrayList<>();
        if (currentImageUnavailable) {
            reasons.add("\"Current image\" needs an image open");
        }
        if (allImagesUnavailable || specificImagesUnavailable) {
            reasons.add("a multi-image scope needs a project with two or more images");
        }
        if (reasons.isEmpty()) {
            unavailableHint.setVisible(false);
            unavailableHint.setManaged(false);
        } else {
            unavailableHint.setText("Unavailable here: " + String.join("; ", reasons) + ".");
        }
        unavailableHint.setWrapText(true);
        unavailableHint.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        chooseImagesButton.disableProperty().bind(scopeSpecificImages.selectedProperty().not());
        scopeSpecificImages.selectedProperty().addListener((obs, was, now) -> {
            if (now && selectedSubset.isEmpty()) {
                openImageChooser();
            }
        });

        // FlowPane, not HBox: a narrow dialog wraps the options onto a second
        // line instead of squeezing them until their labels ellipsize away.
        FlowPane radios = new FlowPane(15, 4, new Label("Scope:"), scopeCurrentImage,
                scopeAllImages, scopeSpecificImages);
        radios.setAlignment(Pos.CENTER_LEFT);
        HBox chooseRow = new HBox(8, chooseImagesButton, specificImagesLabel);
        chooseRow.setAlignment(Pos.CENTER_LEFT);
        chooseRow.setPadding(new Insets(0, 0, 0, 55));
        getChildren().addAll(radios, chooseRow, unavailableHint);
    }

    private void openImageChooser() {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            Dialogs.showWarningNotification("QPCAT", "No project is open.");
            return;
        }
        ProjectImageSelector.showDialog(qupath.getStage(), project, pickerTitle,
                selectedSubset.isEmpty() ? null : selectedSubset)
            .ifPresent(chosen -> {
                selectedSubset.clear();
                selectedSubset.addAll(chosen);
                int n = selectedSubset.size();
                specificImagesLabel.setText(n == 0 ? "(none chosen)"
                        : n + " image" + (n == 1 ? "" : "s") + " chosen");
            });
    }

    /** True when the "Current image" scope is selected. */
    public boolean isCurrentImage() {
        return scopeCurrentImage.isSelected();
    }

    /** True when the "All project images" scope is selected. */
    public boolean isAllImages() {
        return scopeAllImages.isSelected();
    }

    /** True when the "Specific images..." scope is selected. */
    public boolean isSpecificImages() {
        return scopeSpecificImages.isSelected();
    }

    /**
     * Sets an explanatory tooltip on the multi-image scope options ("All project
     * images" and "Specific images..."), e.g. to describe how cross-image runs
     * pool and write back.
     */
    public void setScopeTooltip(String text) {
        Tooltip tip = Tooltips.of(text);
        // Never overwrite the "why is this disabled" tooltip with a description
        // of what the option would do -- the reason it cannot be used is the
        // more useful of the two, and it is the only place that reason lives
        // besides the hint line.
        if (!scopeAllImages.isDisable()) {
            scopeAllImages.setTooltip(tip);
        }
        if (!scopeSpecificImages.isDisable()) {
            scopeSpecificImages.setTooltip(tip);
        }
    }

    /**
     * Enables or disables every scope control, e.g. while a run is in flight.
     * <p>
     * Options that are unavailable for a structural reason -- no image open, a
     * single-image project -- STAY disabled when re-enabled, so finishing a run
     * cannot hand the user an option that was never valid.
     */
    public void setControlsDisabled(boolean disabled) {
        scopeCurrentImage.setDisable(disabled || currentImageUnavailable);
        scopeAllImages.setDisable(disabled || allImagesUnavailable);
        scopeSpecificImages.setDisable(disabled || specificImagesUnavailable);
    }

    /** True when "Specific images..." is selected but nothing has been chosen. */
    public boolean isSpecificButEmpty() {
        return scopeSpecificImages.isSelected() && selectedSubset.isEmpty();
    }

    /** Add a listener fired whenever the chosen scope changes. */
    public void addScopeChangeListener(Runnable r) {
        scopeCurrentImage.selectedProperty().addListener((o, a, b) -> r.run());
        scopeAllImages.selectedProperty().addListener((o, a, b) -> r.run());
        scopeSpecificImages.selectedProperty().addListener((o, a, b) -> r.run());
    }

    /**
     * Resolve the chosen project images, or {@code null} for the current-image
     * scope. Returns an empty list only if "Specific images..." is chosen with no
     * selection (check {@link #isSpecificButEmpty()} first).
     */
    public List<ProjectImageEntry<BufferedImage>> resolveEntries() {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) return null;
        if (scopeAllImages.isSelected()) {
            return new ArrayList<>(project.getImageList());
        }
        if (scopeSpecificImages.isSelected()) {
            return new ArrayList<>(selectedSubset);
        }
        // "Current image": with a single-image project and nothing open, that
        // one image IS the scope -- returning null would leave callers with no
        // images at all.
        if (scopeCurrentImage.isDisabled() && project.getImageList().size() == 1) {
            return new ArrayList<>(project.getImageList());
        }
        return null;  // current image
    }
}
