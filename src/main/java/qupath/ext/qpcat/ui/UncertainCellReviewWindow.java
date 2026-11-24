package qupath.ext.qpcat.ui;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.preferences.QpcatPreferences;
import qupath.ext.qpcat.service.CellClasses;
import qupath.ext.qpcat.service.ImageDataResources;
import qupath.ext.qpcat.service.TrainingLabels;
import qupath.ext.qpcat.service.UncertaintyQueue;
import qupath.ext.qpcat.service.UncertaintyQueue.Candidate;
import qupath.ext.qpcat.service.ViewerNavigator;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/**
 * [EXPERIMENTAL] Shows the cells the autoencoder classifier was least sure about, one at a
 * time, and records the user's answer as a classified point on the cell. The next training
 * run reads those points as labels ("Point annotations" label source), so each round of
 * answers is spent where the classifier is weakest.
 */
public final class UncertainCellReviewWindow {

    private static final Logger logger = LoggerFactory.getLogger(UncertainCellReviewWindow.class);

    /** Name of every point this window writes, so they can be found and removed later. */
    public static final String POINT_NAME = "QP-CAT review label";

    private final QuPathGUI qupath;
    private final Supplier<List<ProjectImageEntry<BufferedImage>>> checkedEntries;
    private final BooleanSupplier cellsOnly;
    private final Supplier<String[]> trainedClasses;
    private final Runnable retrain;

    private Stage stage;
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>(rows);
    private final FlowPane classButtons = new FlowPane(6, 6);
    private final Label status = new Label();
    private final Spinner<Integer> countSpinner = SpinnerUtils.intSpinner(5, 500, 50, 5, 90);
    private final CheckBox spreadCheck = new CheckBox("Take turns between predicted classes");
    private final Deque<Answer> history = new ArrayDeque<>();
    private int answered;
    /** What the Predicted column calls a cell predicted to have no class. */
    private String noClassLabel = CellClasses.UNCLASSIFIED_DISPLAY;

    /** One table row: a candidate cell and the label given to it (empty until answered). */
    public static final class Row {
        final Candidate cell;
        final SimpleStringProperty answer = new SimpleStringProperty("");

        Row(Candidate cell) {
            this.cell = cell;
        }
    }

    private record Answer(Row row, PathObject point, ImageData<BufferedImage> imageData,
                          ProjectImageEntry<BufferedImage> entry) {}

    /**
     * @param checkedEntries images to rank cells from (empty = the open image)
     * @param trainedClasses class names of the trained model, offered as answers (may be null)
     * @param retrain        runs training again with the current dialog settings
     */
    public UncertainCellReviewWindow(QuPathGUI qupath,
                                     Supplier<List<ProjectImageEntry<BufferedImage>>> checkedEntries,
                                     BooleanSupplier cellsOnly, Supplier<String[]> trainedClasses,
                                     Runnable retrain) {
        this.qupath = qupath;
        this.checkedEntries = checkedEntries;
        this.cellsOnly = cellsOnly;
        this.trainedClasses = trainedClasses;
        this.retrain = retrain;
    }

    /** Shows the window, or brings it to the front when it is already open. */
    public void show() {
        if (stage != null) {
            stage.show();
            stage.toFront();
            return;
        }
        stage = new Stage();
        stage.initOwner(qupath.getStage());
        stage.setTitle(VersionedTitle.of("QP-CAT - Review Uncertain Cells"));

        Label intro = new Label("Experimental. The cells the classifier was least sure about, least "
                + "certain first (the AE_confidence measurement). Select a row to go to the cell, then "
                + "confirm the prediction or click the right class. Each answer is saved as a classified "
                + "point on the cell; the next training run uses it as a label when 'Point annotations' "
                + "is ticked, and a point overrides every other label source.");
        intro.setWrapText(true);
        WrapHeight.bind(intro);

        Button refresh = new Button("Rank cells");
        refresh.setOnAction(e -> reload());
        spreadCheck.setTooltip(Tooltips.of("Least confident cell of each predicted class in turn, so one "
                + "large uncertain class cannot fill the list."));
        HBox top = new HBox(8, new Label("Cells:"), countSpinner, spreadCheck, refresh);
        top.setAlignment(Pos.CENTER_LEFT);

        TableColumn<Row, String> imageCol = new TableColumn<>("Image");
        imageCol.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().cell.imageName()));
        imageCol.setPrefWidth(150);
        TableColumn<Row, String> predCol = new TableColumn<>("Predicted");
        predCol.setCellValueFactory(r -> new SimpleStringProperty(
                r.getValue().cell.predicted() != null ? r.getValue().cell.predicted() : noClassLabel));
        predCol.setPrefWidth(130);
        TableColumn<Row, String> confCol = new TableColumn<>("Confidence");
        confCol.setCellValueFactory(r -> new SimpleStringProperty(
                String.format("%.3f", r.getValue().cell.confidence())));
        confCol.setPrefWidth(90);
        TableColumn<Row, String> answerCol = new TableColumn<>("Your label");
        answerCol.setCellValueFactory(r -> r.getValue().answer);
        answerCol.setPrefWidth(130);
        table.getColumns().setAll(List.of(imageCol, predCol, confCol, answerCol));
        table.setPlaceholder(new Label("Press 'Rank cells'. Cells get a confidence when the classifier "
                + "is trained on or applied to their image."));
        table.getSelectionModel().selectedItemProperty().addListener((obs, o, row) -> {
            if (row != null) {
                Candidate c = row.cell;
                ViewerNavigator.navigateToCell(qupath, c.imageId(), c.imageName(), c.x(), c.y());
            }
        });
        table.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                confirmPrediction();
                e.consume();
            } else if (e.getCode().isDigitKey()) {
                int k = e.getCode().getCode() - KeyCode.DIGIT1.getCode();
                if (e.getCode().isKeypadKey()) {
                    k = e.getCode().getCode() - KeyCode.NUMPAD1.getCode();
                }
                if (k >= 0 && k < classButtons.getChildren().size()) {
                    ((Button) classButtons.getChildren().get(k)).fire();
                    e.consume();
                }
            }
        });
        VBox.setVgrow(table, Priority.ALWAYS);

        Button confirm = new Button("Confirm (Enter)");
        confirm.setOnAction(e -> confirmPrediction());
        confirm.setTooltip(Tooltips.of("Label the selected cell with the class the classifier predicted."));
        Button undo = new Button("Undo");
        undo.setOnAction(e -> undoLast());
        undo.setTooltip(Tooltips.of("Remove the point written by the last answer."));
        TextField otherClass = new TextField();
        otherClass.setPromptText("Another class name");
        otherClass.setPrefColumnCount(14);
        Button otherButton = new Button("Use this class");
        otherButton.setOnAction(e -> {
            if (otherClass.getText().isBlank()) {
                return;
            }
            try {
                answer(CellClasses.typedName(otherClass.getText(), "Class name"));
            } catch (CellClasses.ClassNameException ex) {
                status.setText(ex.getMessage());
                if (ex.suggestion() != null) {
                    otherClass.setText(ex.suggestion());
                }
            }
        });
        HBox answerRow = new HBox(8, confirm, undo, otherClass, otherButton);
        answerRow.setAlignment(Pos.CENTER_LEFT);

        Button retrainButton = new Button("Train again with these answers");
        retrainButton.setTooltip(Tooltips.of("Ticks 'Point annotations' in the classifier dialog and "
                + "starts training with its current settings. Press 'Rank cells' when it finishes."));
        retrainButton.setOnAction(e -> retrain.run());
        HBox bottom = new HBox(8, status, new HBox(), retrainButton);
        HBox.setHgrow(bottom.getChildren().get(1), Priority.ALWAYS);
        bottom.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, intro, top, table, new Label("Click the right class (or press its number):"),
                classButtons, answerRow, bottom);
        root.setPadding(new Insets(12));
        stage.setScene(new Scene(root, 700, 660));
        updateStatus();
        rebuildClassButtons(List.of());
        stage.show();
    }

    private void reload() {
        int limit = countSpinner.getValue();
        boolean spread = spreadCheck.isSelected();
        boolean onlyCells = cellsOnly.getAsBoolean();
        List<ProjectImageEntry<BufferedImage>> entries = new ArrayList<>(checkedEntries.get());
        double matchDistance = QpcatPreferences.getAePointMatchDistance();
        status.setText("Ranking cells...");
        Thread t = new Thread(() -> {
            List<Candidate> all = new ArrayList<>();
            ImageData<BufferedImage> live = qupath.getImageData();
            var project = qupath.getProject();
            if (entries.isEmpty() && live != null) {
                var entry = project == null ? null : project.getEntry(live);
                all.addAll(UncertaintyQueue.collect(live.getHierarchy(), entry == null ? null : entry.getID(),
                        live.getServer().getMetadata().getName(), onlyCells, matchDistance));
            }
            for (ProjectImageEntry<BufferedImage> entry : entries) {
                ImageData<BufferedImage> data = null;
                try {
                    var liveEntry = (project != null && live != null) ? project.getEntry(live) : null;
                    data = entry.equals(liveEntry) ? live : entry.readImageData();
                    all.addAll(UncertaintyQueue.collect(data.getHierarchy(), entry.getID(),
                            entry.getImageName(), onlyCells, matchDistance));
                } catch (Exception ex) {
                    logger.warn("Could not read {}: {}", entry.getImageName(), ex.getMessage());
                } finally {
                    ImageDataResources.closeUnless(data, live);
                }
            }
            List<Candidate> ranked = UncertaintyQueue.rank(all, limit, spread);
            Platform.runLater(() -> {
                Set<String> classes = new LinkedHashSet<>();
                String[] trained = trainedClasses.get();
                if (trained != null) {
                    classes.addAll(List.of(trained));
                }
                // "No class" is not an answer a point can carry: null predictions, and
                // the trained model's word for them, get no button.
                classes.remove(TrainingLabels.UNCLASSIFIED);
                all.forEach(c -> {
                    if (c.predicted() != null) classes.add(c.predicted());
                });
                noClassLabel = CellClasses.noClassLabel(classes, CellClasses.UNCLASSIFIED_DISPLAY);
                rows.setAll(ranked.stream().map(Row::new).toList());
                List<String> sorted = new ArrayList<>(classes);
                sorted.sort(UncertainCellReviewWindow::compareNatural);
                rebuildClassButtons(sorted);
                status.setText(ranked.isEmpty()
                        ? "No unanswered cell has a confidence value. Train, or apply the model, first."
                        : ranked.size() + " of " + all.size() + " unanswered cells listed.");
                if (!ranked.isEmpty()) {
                    table.getSelectionModel().select(0);
                    table.requestFocus();
                }
            });
        }, "QPCAT-UncertaintyQueue");
        t.setDaemon(true);
        t.start();
    }

    /** Orders "Cluster 2" before "Cluster 10": a trailing number compares as a number. */
    static int compareNatural(String a, String b) {
        var ma = java.util.regex.Pattern.compile("^(.*?)(\\d+)$").matcher(a);
        var mb = java.util.regex.Pattern.compile("^(.*?)(\\d+)$").matcher(b);
        if (ma.matches() && mb.matches() && ma.group(1).equals(mb.group(1))) {
            return new java.math.BigInteger(ma.group(2)).compareTo(new java.math.BigInteger(mb.group(2)));
        }
        return a.compareToIgnoreCase(b);
    }

    private void rebuildClassButtons(List<String> classes) {
        classButtons.getChildren().clear();
        for (int i = 0; i < classes.size(); i++) {
            String name = classes.get(i);
            Button b = new Button((i < 9 ? (i + 1) + "  " : "") + name);
            b.setOnAction(e -> answer(name));
            classButtons.getChildren().add(b);
        }
    }

    private void confirmPrediction() {
        Row row = table.getSelectionModel().getSelectedItem();
        if (row != null) {
            answer(row.cell.predicted());
        }
    }

    /** Writes {@code className} as a classified point on the selected cell, then moves on. */
    private void answer(String className) {
        Row row = table.getSelectionModel().getSelectedItem();
        if (row == null) {
            status.setText("Select a cell first.");
            return;
        }
        if (className == null) {
            status.setText("This cell was predicted to have no class, and a point needs a class. "
                    + "Choose the class it should have.");
            return;
        }
        PathClass pathClass;
        try {
            pathClass = CellClasses.forName(className, "Class name");
        } catch (CellClasses.ClassNameException ex) {
            status.setText(ex.getMessage());
            return;
        }
        Candidate c = row.cell;
        ImageData<BufferedImage> data = qupath.getImageData();
        var project = qupath.getProject();
        ProjectImageEntry<BufferedImage> entry = (project != null && data != null) ? project.getEntry(data) : null;
        boolean open = data != null && (c.imageId() != null && entry != null
                ? c.imageId().equals(entry.getID())
                : c.imageName() != null && c.imageName().equals(data.getServer().getMetadata().getName()));
        if (!open) {
            status.setText("That cell's image is not open yet; opening it. Answer again once it shows.");
            ViewerNavigator.navigateToCell(qupath, c.imageId(), c.imageName(), c.x(), c.y());
            return;
        }
        if (!row.answer.get().isEmpty()) {
            removeAnswer(row);
        }
        PathObject point = PathObjects.createAnnotationObject(
                ROIs.createPointsROI(c.x(), c.y(), ImagePlane.getPlane(c.z(), c.t())),
                pathClass);
        point.setName(POINT_NAME);
        data.getHierarchy().addObject(point);
        save(data, entry);
        row.answer.set(pathClass.toString());
        history.push(new Answer(row, point, data, entry));
        answered++;
        updateStatus();
        selectNextUnanswered();
    }

    private void undoLast() {
        Answer last = history.peek();
        if (last == null) {
            status.setText("Nothing to undo.");
            return;
        }
        removeAnswer(last.row());
        table.getSelectionModel().select(last.row());
    }

    /** Removes the point written for {@code row}, if it is still in its image. */
    private void removeAnswer(Row row) {
        for (var it = history.iterator(); it.hasNext(); ) {
            Answer a = it.next();
            if (a.row() == row) {
                a.imageData().getHierarchy().removeObject(a.point(), false);
                save(a.imageData(), a.entry());
                it.remove();
                answered--;
                break;
            }
        }
        row.answer.set("");
        updateStatus();
    }

    private void save(ImageData<BufferedImage> data, ProjectImageEntry<BufferedImage> entry) {
        if (entry == null) {
            return; // no project: the point stays in the open image until the user saves it
        }
        Thread t = new Thread(() -> {
            try {
                entry.saveImageData(data);
                Platform.runLater(() -> data.setChanged(false));
            } catch (Exception ex) {
                Platform.runLater(() -> UserErrors.notify(logger, "Review Uncertain Cells",
                        "Could not save the label to " + entry.getImageName() + ": " + ex.getMessage()));
            }
        }, "QPCAT-ReviewSave");
        t.setDaemon(true);
        t.start();
    }

    private void selectNextUnanswered() {
        int from = table.getSelectionModel().getSelectedIndex();
        for (int k = 1; k <= rows.size(); k++) {
            int i = (from + k) % rows.size();
            if (rows.get(i).answer.get().isEmpty()) {
                table.getSelectionModel().select(i);
                table.scrollTo(Math.max(0, i - 3));
                return;
            }
        }
        status.setText("Every listed cell is answered. Train again, then rank the cells again.");
    }

    private void updateStatus() {
        status.setText(answered + " answered in this window.");
    }
}
