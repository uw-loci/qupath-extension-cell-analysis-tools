package qupath.ext.qpcat.ui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.service.AnnDataImporter;
import qupath.ext.qpcat.service.ApposeClusteringService;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;

/**
 * "Import from AnnData (.h5ad)": pick a file, choose the obs column to use as
 * the classification and the values to add as measurements, check how many
 * cells match, then write.
 */
public final class AnnDataImportDialog {

    private static final Logger logger = LoggerFactory.getLogger(AnnDataImportDialog.class);

    private static final String TITLE = "QPCAT - Import from AnnData";
    private static final String NO_CLASS = "(leave classifications unchanged)";

    private AnnDataImportDialog() {}

    /** What the user chose to import. */
    private record Choice(String classColumn, List<String> numberColumns,
                          List<String> embeddingKeys) {}

    /**
     * Run the import, starting with the file chooser.
     *
     * @param qupath the QuPath GUI
     */
    @SuppressWarnings("unchecked")
    public static void show(QuPathGUI qupath) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(TITLE + " (.h5ad)");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("AnnData files", "*.h5ad"));
        if (qupath.getProject() != null) {
            try {
                File dir = qupath.getProject().getPath().getParent().toFile();
                if (dir.isDirectory()) chooser.setInitialDirectory(dir);
            } catch (Exception ignored) {
                // no project folder to start in
            }
        }
        File file = chooser.showOpenDialog(qupath.getStage());
        if (file == null) return;
        Path path = file.toPath();
        Project<BufferedImage> project = (Project<BufferedImage>) qupath.getProject();
        ImageData<BufferedImage> open = (ImageData<BufferedImage>) qupath.getImageData();

        Dialogs.showInfoNotification(TITLE, "Reading " + file.getName() + "...");
        background("read", () -> AnnDataImporter.inspect(path), summary -> {
            if (!summary.hasImageIds() && open == null) {
                UserErrors.show(logger, TITLE, "This file does not say which image its cells "
                        + "came from (no qupath_image_id column), so they are matched "
                        + "against the open image. Open that image first.");
                return;
            }
            Choice choice = chooseColumns(qupath, file.getName(), summary);
            if (choice == null) return;
            background("match", () -> {
                var contents = AnnDataImporter.read(path, choice.classColumn(),
                        choice.numberColumns(), choice.embeddingKeys());
                return Map.entry(contents, AnnDataImporter.run(project, open, contents, false));
            }, planned -> {
                var contents = planned.getKey();
                var plan = planned.getValue();
                if (plan.matched() == 0) {
                    UserErrors.show(logger, TITLE, "None of the file's cells match a cell "
                            + "in QuPath.\n\n" + plan.describe() + "\n\nCells are matched by "
                            + "object id: the cell names (obs_names) QP-CAT's export writes. "
                            + "Export from QP-CAT again and keep those names in Python.");
                    return;
                }
                if (!Dialogs.showConfirmDialog(TITLE + ": confirm", confirmText(contents, plan))) {
                    return;
                }
                background("import", () -> AnnDataImporter.run(project, open, contents, true),
                        done -> Dialogs.showMessageDialog(TITLE + ": done",
                                "Imported from " + file.getName() + "\n\n" + done.describe()));
            });
        });
    }

    private static String confirmText(AnnDataImporter.Contents contents,
                                      AnnDataImporter.Report plan) {
        StringBuilder sb = new StringBuilder(plan.describe()).append("\n\n");
        if (contents.hasClasses()) {
            sb.append(String.format("Classification: %,d matched cell(s) change class. "
                    + "Cells with no value in the column keep theirs.%n", plan.reclassified()));
        }
        if (!contents.valueNames().isEmpty()) {
            sb.append("Measurements: ");
            List<String> names = new ArrayList<>();
            for (String n : contents.valueNames()) names.add(AnnDataImporter.MEASUREMENT_PREFIX + n);
            sb.append(String.join(", ", names)).append(" (replaced if present)\n");
        }
        sb.append("\nCells that did not match are left as they are. Every changed image "
                + "is saved. Import?");
        return sb.toString();
    }

    private static Choice chooseColumns(QuPathGUI qupath, String fileName,
                                        AnnDataImporter.Summary summary) {
        ComboBox<String> classBox = new ComboBox<>();
        classBox.getItems().add(NO_CLASS);
        Map<String, String> classByLabel = new LinkedHashMap<>();
        Map<CheckBox, String> numberBoxes = new LinkedHashMap<>();
        for (AnnDataImporter.Column c : summary.getColumns()) {
            if (c.isAsClass()) {
                String label = c.getName() + "  (" + c.getNValues() + " values)";
                classByLabel.put(label, c.getName());
                classBox.getItems().add(label);
            }
            if (c.isAsNumber()) {
                numberBoxes.put(DataCheckBoxes.of(c.getName()
                        + (c.getNMissing() > 0 ? "  (" + c.getNMissing() + " missing)" : "")),
                        c.getName());
            }
        }
        classBox.getSelectionModel().selectFirst();
        Map<CheckBox, String> embeddingBoxes = new LinkedHashMap<>();
        for (AnnDataImporter.Embedding e : summary.getEmbeddings()) {
            embeddingBoxes.put(DataCheckBoxes.of(e.getKey() + "  (" + e.getNDims() + " axes)"),
                    e.getKey());
        }

        VBox box = new VBox(8);
        box.setPadding(new Insets(12));
        StringBuilder head = new StringBuilder(String.format("%s: %,d cells", fileName,
                summary.getNCells()));
        if (summary.hasImageIds()) {
            head.append(" from ").append(summary.getImages().size()).append(" image(s)");
        } else {
            head.append(". The file does not name its images, so the cells are matched "
                    + "against the open image.");
        }
        Label header = new Label(head.toString());
        header.setWrapText(true);
        box.getChildren().addAll(header, new Label("Classification from column:"), classBox);
        if (!numberBoxes.isEmpty()) {
            box.getChildren().add(new Label("Add as measurements (named \""
                    + AnnDataImporter.MEASUREMENT_PREFIX + "<column>\"):"));
            box.getChildren().addAll(numberBoxes.keySet());
        }
        if (!embeddingBoxes.isEmpty()) {
            box.getChildren().add(new Label("Add embeddings (obsm), one measurement per axis:"));
            box.getChildren().addAll(embeddingBoxes.keySet());
        }
        Label note = new Label("Cells are matched by object id, the cell names QP-CAT's "
                + "export writes. The next step shows how many match before anything changes.");
        note.setWrapText(true);
        note.setStyle(BannerStyles.GUIDE_TEXT + BannerStyles.GUIDE_BOX);
        box.getChildren().add(note);

        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(Math.min(520, 160 + 28 * (numberBoxes.size()
                + embeddingBoxes.size())));

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(VersionedTitle.of(TITLE));
        if (qupath.getStage() != null) dialog.initOwner(qupath.getStage());
        ButtonType check = new ButtonType("Check matches...", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(check, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().setPrefWidth(520);
        var answer = dialog.showAndWait();
        if (answer.isEmpty() || answer.get() != check) return null;

        String classColumn = classByLabel.get(classBox.getValue());
        List<String> numbers = numberBoxes.entrySet().stream()
                .filter(e -> e.getKey().isSelected()).map(Map.Entry::getValue).toList();
        List<String> embeddings = embeddingBoxes.entrySet().stream()
                .filter(e -> e.getKey().isSelected()).map(Map.Entry::getValue).toList();
        if (classColumn == null && numbers.isEmpty() && embeddings.isEmpty()) {
            Dialogs.showWarningNotification(TITLE, "Nothing was chosen to import.");
            return null;
        }
        return new Choice(classColumn, numbers, embeddings);
    }

    /** Run {@code work} off the FX thread, then {@code then} on it; errors are shown. */
    private static <T> void background(String step, Callable<T> work, Consumer<T> then) {
        Thread t = new Thread(() -> {
            try {
                T result = ApposeClusteringService.withExtensionClassLoader(work);
                Platform.runLater(() -> then.accept(result));
            } catch (Exception e) {
                logger.error("AnnData import ({}) failed", step, e);
                Platform.runLater(() -> UserErrors.show(logger, TITLE,
                        "Could not " + step + " the file:\n" + e.getMessage()));
            }
        }, "QPCAT-AnnDataImport-" + step);
        t.setDaemon(true);
        t.start();
    }
}
