package qupath.ext.pyclustering.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Singleton window that displays Python stderr/debug output from the Appose service.
 * Useful for debugging Python-side errors, logging, and monitoring task execution.
 */
public class PythonConsoleWindow {

    private static final Logger logger = LoggerFactory.getLogger(PythonConsoleWindow.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter FILE_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");
    private static final int MAX_LINES = 5000;

    private static PythonConsoleWindow instance;

    private Stage stage;
    private TextArea textArea;
    private Label lineCountLabel;
    private int lineCount = 0;
    private boolean autoScroll = true;

    private PythonConsoleWindow() {}

    public static synchronized PythonConsoleWindow getInstance() {
        if (instance == null) {
            instance = new PythonConsoleWindow();
        }
        return instance;
    }

    /**
     * Show the console window, creating it if needed.
     */
    public void show() {
        if (stage == null) {
            createWindow();
        }
        stage.show();
        stage.toFront();
    }

    /**
     * Append a line of output to the console. Thread-safe.
     */
    public void appendLine(String text) {
        if (Platform.isFxApplicationThread()) {
            doAppend(text);
        } else {
            Platform.runLater(() -> doAppend(text));
        }
    }

    /**
     * Clear all console output.
     */
    public void clear() {
        if (Platform.isFxApplicationThread()) {
            doClear();
        } else {
            Platform.runLater(this::doClear);
        }
    }

    /**
     * Returns a Consumer suitable for use as the Appose debug listener.
     */
    public java.util.function.Consumer<String> asListener() {
        return this::appendLine;
    }

    private void createWindow() {
        stage = new Stage();
        stage.setTitle("PyClustering - Python Console");

        textArea = new TextArea();
        textArea.setEditable(false);
        textArea.setWrapText(false);
        textArea.setFont(Font.font("monospace", 11));
        textArea.setPrefWidth(700);
        textArea.setPrefHeight(400);

        lineCountLabel = new Label("0 lines");
        lineCountLabel.setStyle("-fx-text-fill: #666;");

        Button clearBtn = new Button("Clear");
        clearBtn.setOnAction(e -> doClear());
        clearBtn.setTooltip(new Tooltip("Clear all console output."));

        Button scrollBtn = new Button("Auto-scroll: ON");
        scrollBtn.setOnAction(e -> {
            autoScroll = !autoScroll;
            scrollBtn.setText("Auto-scroll: " + (autoScroll ? "ON" : "OFF"));
        });
        scrollBtn.setTooltip(new Tooltip(
                "Toggle automatic scrolling to the latest output."));

        Button saveBtn = new Button("Save Log...");
        saveBtn.setOnAction(e -> saveLogToFile());
        saveBtn.setTooltip(new Tooltip(
                "Save the current console output to a text file."));

        HBox toolbar = new HBox(10, clearBtn, scrollBtn, saveBtn, lineCountLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(5));

        VBox root = new VBox(toolbar, textArea);
        VBox.setVgrow(textArea, Priority.ALWAYS);
        root.setPadding(new Insets(5));

        Scene scene = new Scene(root, 720, 450);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            // Just hide, don't destroy
            e.consume();
            stage.hide();
        });
    }

    private void saveLogToFile() {
        if (textArea == null || textArea.getText().isEmpty()) return;

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Python Console Log");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Text files", "*.txt"));
        fileChooser.setInitialFileName(
                "python_console_" + LocalDateTime.now().format(FILE_TIME_FMT) + ".txt");

        File file = fileChooser.showSaveDialog(stage);
        if (file == null) return;

        try {
            Files.writeString(file.toPath(), textArea.getText());
            logger.info("Python console log saved to {}", file.getAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to save console log: {}", e.getMessage());
        }
    }

    private void doAppend(String text) {
        if (textArea == null) return;

        String timestamp = LocalTime.now().format(TIME_FMT);
        textArea.appendText("[" + timestamp + "] " + text + "\n");
        lineCount++;

        // Trim if too many lines
        if (lineCount > MAX_LINES) {
            String content = textArea.getText();
            int cutIdx = content.indexOf('\n', content.length() / 4);
            if (cutIdx > 0) {
                textArea.setText(content.substring(cutIdx + 1));
                lineCount = (int) textArea.getText().lines().count();
            }
        }

        lineCountLabel.setText(lineCount + " lines");

        if (autoScroll) {
            textArea.setScrollTop(Double.MAX_VALUE);
            textArea.positionCaret(textArea.getLength());
        }
    }

    private void doClear() {
        if (textArea != null) {
            textArea.clear();
            lineCount = 0;
            lineCountLabel.setText("0 lines");
        }
    }
}
