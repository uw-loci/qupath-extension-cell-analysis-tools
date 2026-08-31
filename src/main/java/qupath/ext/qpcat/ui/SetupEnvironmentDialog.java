package qupath.ext.qpcat.ui;

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
import qupath.ext.qpcat.model.ComputeVariant;
import qupath.ext.qpcat.preferences.QpcatPreferences;
import qupath.ext.qpcat.service.ApposeClusteringService;
import qupath.ext.qpcat.service.ApposeEnvLocation;

import javafx.stage.DirectoryChooser;

import java.io.File;
import java.nio.file.Path;

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
    private Label locationLabel;
    private ComboBox<ComputeVariant> variantCombo;

    public SetupEnvironmentDialog(Stage owner, Runnable onComplete) {
        this.owner = owner;
        this.onComplete = onComplete;
    }

    public void show() {
        dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.setTitle("QPCAT - Environment Setup");

        statusLabel = new Label("Click 'Setup' to download and configure the Python environment.\n"
                + "This requires an internet connection and approximately 1.5-2.5 GB of disk space.");
        statusLabel.setWrapText(true);

        // Location and variant are offered HERE, before anything is downloaded.
        // Both are far cheaper to choose now than to change later: changing
        // either builds a second environment and re-downloads several GB. The
        // preferences remain the way to change them afterwards.
        locationLabel = new Label();
        locationLabel.setWrapText(true);
        locationLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        refreshLocationLabel();

        Button browseBtn = new Button("Change...");
        browseBtn.setTooltip(Tooltips.of(
                "Choose where the environment is built.\n\n"
                + "The default is inside your home directory, which is right on most\n"
                + "machines. On HPC and managed desktops the home directory is often\n"
                + "quota-limited, and an environment this size fails there with\n"
                + "'Quota exceeded'. Point it at scratch or project storage instead."));
        browseBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Choose where to build the QP-CAT Python environment");
            String current = QpcatPreferences.getEnvBaseDir();
            File start = (current != null && !current.isBlank()) ? new File(current) : null;
            if (start != null && start.isDirectory()) {
                dc.setInitialDirectory(start);
            }
            File chosen = dc.showDialog(dialog);
            if (chosen != null) {
                QpcatPreferences.setEnvBaseDir(chosen.getAbsolutePath());
                refreshLocationLabel();
            }
        });

        Button defaultBtn = new Button("Use default");
        defaultBtn.setTooltip(Tooltips.of("Build under the standard Appose location "
                + "in your home directory."));
        defaultBtn.setOnAction(e -> {
            QpcatPreferences.setEnvBaseDir("");
            refreshLocationLabel();
        });

        variantCombo = new ComboBox<>();
        variantCombo.getItems().setAll(ComputeVariant.values());
        variantCombo.setValue(ComputeVariant.fromId(QpcatPreferences.getEnvVariant()));
        variantCombo.setTooltip(Tooltips.of(
                "CPU installs on any machine and is the right choice for almost\n"
                + "everyone: only the autoencoder uses a GPU at all, so clustering,\n"
                + "UMAP and spatial statistics are exactly as fast either way.\n\n"
                + "GPU requires an NVIDIA GPU -- the environment CANNOT be installed\n"
                + "without one. See documentation/GPU_ACCELERATION.md."));
        variantCombo.valueProperty().addListener((obs, o, n) -> {
            if (n != null) {
                QpcatPreferences.setEnvVariant(n.name());
                refreshLocationLabel();     // the env name, hence the path, changes
            }
        });

        HBox locationRow = new HBox(8, new Label("Install to:"), browseBtn, defaultBtn);
        locationRow.setAlignment(Pos.CENTER_LEFT);
        HBox variantRow = new HBox(8, new Label("Compute:"), variantCombo);
        variantRow.setAlignment(Pos.CENTER_LEFT);

        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(450);

        setupButton = new Button("Setup Environment");
        setupButton.setOnAction(e -> startSetup());
        setupButton.setTooltip(Tooltips.of(
                "Download and install the Python environment with\n"
                + "scikit-learn, scanpy, UMAP, and other dependencies.\n"
                + "Requires internet (~1.5-2.5 GB download)."));

        closeButton = new Button("Close");
        closeButton.setOnAction(e -> dialog.close());

        HBox buttonBox = new HBox(10, setupButton, closeButton);
        buttonBox.setAlignment(Pos.CENTER);

        VBox root = new VBox(12,
                QpcatDocLinks.linkBar("1-setting-up-the-environment"),
                statusLabel, locationRow, locationLabel, variantRow,
                progressBar, buttonBox);
        root.setPadding(new Insets(20));
        root.setPrefWidth(500);

        dialog.setScene(new Scene(root));
        dialog.setResizable(false);
        dialog.show();
    }

    /** Show the path the environment will actually be built at, fully resolved. */
    private void refreshLocationLabel() {
        Path dir = ApposeEnvLocation.resolve(
                QpcatPreferences.getEnvBaseDir(),
                ComputeVariant.fromId(QpcatPreferences.getEnvVariant()).envName());
        boolean built = ApposeEnvLocation.isBuilt(dir);
        locationLabel.setText(dir + (built ? "   [already built]" : ""));
    }

    private void startSetup() {
        setupButton.setDisable(true);
        // Locked once the download starts: changing either mid-build would leave
        // the staged manifest and the build target pointing at different places.
        variantCombo.setDisable(true);
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
        }, "QPCAT-Setup");
        setupThread.setDaemon(true);
        setupThread.start();
    }
}
