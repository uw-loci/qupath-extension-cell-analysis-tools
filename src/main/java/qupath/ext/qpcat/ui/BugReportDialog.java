package qupath.ext.qpcat.ui;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.service.BugReportService;
import qupath.ext.qpcat.service.BugReportService.BugReport;
import qupath.ext.qpcat.service.BugReportService.Result;
import qupath.lib.gui.QuPathGUI;

/**
 * "Report a bug" dialog. Collects a description plus opt-in artifacts (system
 * info, the QP-CAT operation log, the QuPath log, a window screenshot) and
 * submits them to the Cloudflare Worker via {@link BugReportService}, which
 * files a GitHub Issue. No GitHub account is required from the user.
 *
 * <p>Screenshot note: the extension does not own QuPath's window/widget tree,
 * so no automatic widget redaction is possible. The user is shown a mandatory
 * full-window preview and an explicit warning before anything is sent.</p>
 *
 * <p>The network call runs on a daemon background thread so the FX thread never
 * blocks; results are marshalled back via {@link Platform#runLater}.</p>
 */
public class BugReportDialog {

    private static final Logger logger = LoggerFactory.getLogger(BugReportDialog.class);

    /** Screenshots are downscaled to this width before encoding to stay under the size cap. */
    private static final int SCREENSHOT_MAX_WIDTH = 1600;

    private final Stage stage;
    private final Window owner;

    private final TextField summaryField = new TextField();
    private final TextArea descriptionArea = new TextArea();
    private final CheckBox chkSysInfo = new CheckBox("Include system info (versions, OS)");
    private final CheckBox chkSessionLog = new CheckBox("Include QP-CAT operation log");
    private final CheckBox chkQuPathLog = new CheckBox("Include QuPath log");
    private final CheckBox chkScreenshot = new CheckBox("Include a screenshot of the QuPath window");
    private final Label screenshotWarning =
            new Label("The screenshot is NOT auto-redacted. You will preview it before sending. "
                    + "Close anything sensitive first.");
    private final Label statusLabel = new Label("");
    private final Button submitButton = new Button("Submit");

    private volatile boolean submitting = false;

    private BugReportDialog(QuPathGUI qupath) {
        this.owner = qupath != null && qupath.getStage() != null ? qupath.getStage() : null;
        this.stage = new Stage();
        stage.setTitle("Report a bug");
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setScene(new Scene(buildContent()));
        stage.setMinWidth(560);
    }

    /** Builds and shows the dialog on the FX thread. */
    public static void show() {
        Runnable r = () -> new BugReportDialog(QuPathGUI.getInstance()).stage.show();
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    private Region buildContent() {
        VBox root = new VBox(10);
        root.setPadding(new Insets(16));

        Label summaryHeading = new Label("Summary (used as the issue title):");
        summaryHeading.setStyle("-fx-font-weight: bold;");

        summaryField.setPromptText("One line, e.g. \"Harmony batch correction crashes on 2 images\"");

        Label heading =
                new Label("Describe the bug (minimum " + BugReportService.MIN_DESCRIPTION_CHARS + " characters):");
        heading.setStyle("-fx-font-weight: bold;");

        descriptionArea.setWrapText(true);
        descriptionArea.setPromptText("What were you doing, what happened, and what did you expect to happen?");
        descriptionArea.setPrefRowCount(7);
        VBox.setVgrow(descriptionArea, Priority.ALWAYS);

        chkSysInfo.setSelected(true);

        boolean sessionLogAvailable = BugReportService.isSessionLogAvailable();
        chkSessionLog.setSelected(sessionLogAvailable);
        chkSessionLog.setDisable(!sessionLogAvailable);
        if (!sessionLogAvailable) {
            chkSessionLog.setText("Include QP-CAT operation log (none yet)");
        }

        boolean quPathLogAvailable = BugReportService.isQuPathLogAvailable();
        chkQuPathLog.setSelected(quPathLogAvailable);
        chkQuPathLog.setDisable(!quPathLogAvailable);
        if (!quPathLogAvailable) {
            chkQuPathLog.setText("Include QuPath log (none captured this session)");
        }

        chkScreenshot.setSelected(false);
        screenshotWarning.setWrapText(true);
        screenshotWarning.setStyle("-fx-text-fill: -fx-accent;");
        screenshotWarning.setVisible(false);
        screenshotWarning.setManaged(false);
        chkScreenshot.selectedProperty().addListener((obs, was, now) -> {
            screenshotWarning.setVisible(now);
            screenshotWarning.setManaged(now);
            stage.sizeToScene();
        });

        VBox options = new VBox(6, chkSysInfo, chkSessionLog, chkQuPathLog, chkScreenshot, screenshotWarning);

        statusLabel.setWrapText(true);

        submitButton.setDefaultButton(true);
        submitButton.setOnAction(e -> onSubmit());
        Button cancelButton = new Button("Cancel");
        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(e -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, cancelButton, submitButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        if (!BugReportService.isConfigured()) {
            Label notConfigured = new Label("Bug reporting is not set up yet: the Worker URL has not been configured.");
            notConfigured.setWrapText(true);
            notConfigured.setStyle("-fx-text-fill: -fx-accent;");
            submitButton.setDisable(true);
            root.getChildren().add(notConfigured);
        }

        root.getChildren().addAll(
                summaryHeading, summaryField, heading, descriptionArea, options, statusLabel, buttons);
        return root;
    }

    private void onSubmit() {
        if (submitting) {
            return;
        }
        String summary = summaryField.getText().trim();
        if (summary.length() < BugReportService.MIN_SUMMARY_CHARS) {
            setStatus("Please enter a one-line summary (at least "
                    + BugReportService.MIN_SUMMARY_CHARS + " characters). It becomes the issue title.", true);
            return;
        }
        if (summary.length() > BugReportService.MAX_SUMMARY_CHARS) {
            setStatus("Summary is too long (max " + BugReportService.MAX_SUMMARY_CHARS + " characters).", true);
            return;
        }

        String description = descriptionArea.getText().trim();
        if (description.length() < BugReportService.MIN_DESCRIPTION_CHARS) {
            setStatus("Please enter at least " + BugReportService.MIN_DESCRIPTION_CHARS + " characters.", true);
            return;
        }
        if (description.length() > BugReportService.MAX_DESCRIPTION_CHARS) {
            setStatus("Description is too long (max " + BugReportService.MAX_DESCRIPTION_CHARS + " characters).", true);
            return;
        }

        // Screenshot capture + mandatory preview happen on the FX thread.
        String screenshotBase64 = null;
        if (chkScreenshot.isSelected()) {
            WritableImage snapshot = captureWindow();
            if (snapshot == null) {
                setStatus("Could not capture the window. Try unchecking the screenshot option.", true);
                return;
            }
            if (!confirmScreenshotPreview(snapshot)) {
                return; // user cancelled at preview -- keep the form open
            }
            screenshotBase64 = encodePng(snapshot);
            if (screenshotBase64 == null) {
                setStatus("Could not encode the screenshot.", true);
                return;
            }
            if (screenshotBase64.length() > BugReportService.MAX_SCREENSHOT_B64_CHARS) {
                setStatus("Screenshot is too large. Shrink the QuPath window and try again.", true);
                return;
            }
        }

        String sysinfo = chkSysInfo.isSelected() ? BugReportService.gatherSysInfo() : null;
        Map<String, String> artifacts =
                BugReportService.gatherLogArtifacts(chkSessionLog.isSelected(), chkQuPathLog.isSelected());

        BugReport report = new BugReport(summary, description, sysinfo, artifacts, screenshotBase64);

        submitting = true;
        submitButton.setDisable(true);
        submitButton.setText("Submitting...");
        summaryField.setDisable(true);
        descriptionArea.setDisable(true);
        setStatus("Submitting...", false);

        Thread worker = new Thread(
                () -> {
                    Result result = BugReportService.submit(report);
                    Platform.runLater(() -> onResult(result));
                },
                "qpcat-bug-report-submit");
        worker.setDaemon(true);
        worker.start();
    }

    private void onResult(Result result) {
        submitting = false;
        submitButton.setDisable(false);
        submitButton.setText("Submit");
        summaryField.setDisable(false);
        descriptionArea.setDisable(false);

        if (result.ok()) {
            stage.close();
            showSuccess(result.issueNumber(), result.issueUrl());
        } else {
            setStatus(result.error() != null ? result.error() : "Submission failed.", true);
        }
    }

    /** Confirmation dialog with a clickable link to the created issue. */
    private void showSuccess(Integer issueNumber, String url) {
        String number = issueNumber != null ? "#" + issueNumber : "";
        Label message = new Label(number.isEmpty()
                ? "Your bug report was submitted. Thank you!"
                : "Your bug report was submitted as issue " + number + ". Thank you!");
        message.setWrapText(true);

        VBox content = new VBox(10, message);
        content.setPadding(new Insets(4));

        if (url != null && !url.isEmpty()) {
            Label hint = new Label("View it or add more detail here:");
            hint.setWrapText(true);
            Hyperlink link = new Hyperlink(url);
            link.setTooltip(new Tooltip("Open the issue in your browser"));
            link.setOnAction(e -> QuPathGUI.openInBrowser(url));
            content.getChildren().addAll(hint, link);
        }

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Thanks!");
        alert.setHeaderText(null);
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.getDialogPane().setContent(content);
        alert.showAndWait();
    }

    private void setStatus(String message, boolean isError) {
        statusLabel.setText(message);
        statusLabel.setStyle(isError ? "-fx-text-fill: -fx-accent;" : "-fx-text-fill: -fx-text-base-color;");
    }

    // ---- screenshot helpers ------------------------------------------------

    private WritableImage captureWindow() {
        try {
            if (owner instanceof Stage ownerStage && ownerStage.getScene() != null) {
                return ownerStage.getScene().snapshot(null);
            }
        } catch (Exception e) {
            logger.warn("Window capture failed: {}", e.getMessage());
        }
        return null;
    }

    private boolean confirmScreenshotPreview(WritableImage image) {
        Dialog<ButtonType> preview = new Dialog<>();
        preview.setTitle("Screenshot preview");
        if (owner != null) {
            preview.initOwner(owner);
        }
        Label note = new Label("This is exactly what will be attached to the bug report. "
                + "Anything visible here is uploaded. OK to include it?");
        note.setWrapText(true);
        note.setMaxWidth(720);
        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        view.setFitWidth(720);
        VBox content = new VBox(10, note, view);
        content.setPadding(new Insets(10));
        preview.getDialogPane().setContent(content);
        preview.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        return preview.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    private String encodePng(WritableImage image) {
        try {
            BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
            buffered = scaleToMaxWidth(buffered, SCREENSHOT_MAX_WIDTH);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(buffered, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            logger.warn("Screenshot encoding failed: {}", e.getMessage());
            return null;
        }
    }

    private static BufferedImage scaleToMaxWidth(BufferedImage source, int maxWidth) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= maxWidth || width == 0) {
            return source;
        }
        double ratio = (double) maxWidth / width;
        int newWidth = maxWidth;
        int newHeight = Math.max(1, (int) Math.round(height * ratio));
        BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, newWidth, newHeight, null);
        g.dispose();
        return scaled;
    }
}
