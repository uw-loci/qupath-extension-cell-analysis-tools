package qupath.ext.pyclustering.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.pyclustering.service.ApposeClusteringService;

/**
 * Dialog for downloading and setting up the Python clustering environment.
 */
public class SetupEnvironmentDialog {

    private static final Logger logger = LoggerFactory.getLogger(SetupEnvironmentDialog.class);

    private final Stage owner;
    private final Runnable onComplete;
    private Stage dialog;
    private Label statusLabel;
    private ProgressBar progressBar;
    private Button setupButton;
    private Button closeButton;

    public SetupEnvironmentDialog(Stage owner, Runnable onComplete) {
        this.owner = owner;
        this.onComplete = onComplete;
    }

    public void show() {
        dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.setTitle("PyClustering - Environment Setup");

        statusLabel = new Label("Click 'Setup' to download and configure the Python environment.\n"
                + "This requires an internet connection and approximately 1.5-2.5 GB of disk space.\n"
                + "No GPU is required -- all clustering runs on CPU.");
        statusLabel.setWrapText(true);

        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(450);

        setupButton = new Button("Setup Environment");
        setupButton.setOnAction(e -> startSetup());

        closeButton = new Button("Close");
        closeButton.setOnAction(e -> dialog.close());

        HBox buttonBox = new HBox(10, setupButton, closeButton);
        buttonBox.setAlignment(Pos.CENTER);

        VBox root = new VBox(15, statusLabel, progressBar, buttonBox);
        root.setPadding(new Insets(20));
        root.setPrefWidth(500);

        dialog.setScene(new Scene(root));
        dialog.setResizable(false);
        dialog.show();
    }

    private void startSetup() {
        setupButton.setDisable(true);
        progressBar.setProgress(-1);  // Indeterminate
        statusLabel.setText("Building Python environment...");

        Thread setupThread = new Thread(() -> {
            try {
                ApposeClusteringService.getInstance().initialize(
                        msg -> Platform.runLater(() -> statusLabel.setText(msg)));

                Platform.runLater(() -> {
                    progressBar.setProgress(1.0);
                    statusLabel.setText("Environment setup complete!");
                    setupButton.setDisable(true);
                    if (onComplete != null) {
                        onComplete.run();
                    }
                });
            } catch (Exception e) {
                logger.error("Environment setup failed", e);
                Platform.runLater(() -> {
                    progressBar.setProgress(0);
                    statusLabel.setText("Setup failed: " + e.getMessage());
                    setupButton.setDisable(false);
                });
            }
        }, "PyClustering-Setup");
        setupThread.setDaemon(true);
        setupThread.start();
    }
}
