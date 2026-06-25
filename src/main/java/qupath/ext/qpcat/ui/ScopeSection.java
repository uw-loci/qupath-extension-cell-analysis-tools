package qupath.ext.qpcat.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
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
        scopeCurrentImage.setSelected(true);
        scopeAllImages = new RadioButton("All project images");
        scopeAllImages.setToggleGroup(group);
        scopeSpecificImages = new RadioButton("Specific images...");
        scopeSpecificImages.setToggleGroup(group);

        chooseImagesButton = new Button("Choose images...");
        chooseImagesButton.setOnAction(e -> openImageChooser());
        specificImagesLabel = new Label("(none chosen)");
        specificImagesLabel.setStyle("-fx-text-fill: #666;");

        Project<BufferedImage> project = qupath.getProject();
        boolean multiImage = project != null && project.getImageList().size() > 1;
        if (!multiImage) {
            scopeAllImages.setDisable(true);
            scopeSpecificImages.setDisable(true);
            String hint = " (requires project with multiple images)";
            scopeAllImages.setText("All project images" + hint);
            scopeSpecificImages.setText("Specific images..." + hint);
        } else {
            scopeAllImages.setText("All project images (" + project.getImageList().size() + ")");
        }

        chooseImagesButton.disableProperty().bind(scopeSpecificImages.selectedProperty().not());
        scopeSpecificImages.selectedProperty().addListener((obs, was, now) -> {
            if (now && selectedSubset.isEmpty()) {
                openImageChooser();
            }
        });

        HBox radios = new HBox(15, new Label("Scope:"), scopeCurrentImage, scopeAllImages,
                scopeSpecificImages);
        radios.setAlignment(Pos.CENTER_LEFT);
        HBox chooseRow = new HBox(8, chooseImagesButton, specificImagesLabel);
        chooseRow.setAlignment(Pos.CENTER_LEFT);
        chooseRow.setPadding(new Insets(0, 0, 0, 55));
        getChildren().addAll(radios, chooseRow);
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
        return null;  // current image
    }
}
