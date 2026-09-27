package qupath.ext.qpcat.ui;

import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * A matplotlib PNG with a zoom control.
 * <p>
 * Fitting the image to the viewport is the right default -- a plot that runs off
 * the window is worse than a small one -- but on its own it made every generated
 * plot unreadable and unfixable. The image is always smaller than the viewport,
 * so the ScrollPane's scrollbars never engage, and a dotplot with forty markers
 * or a PAGA graph with twenty nodes shrinks to a smudge with no way to magnify
 * it. Zoom takes over when the user asks; past the window size the scrollbars do
 * their normal job.
 * <p>
 * Shared by the generated-plot tabs and the per-image spatial scatter, which had
 * the same clamp for the same reason.
 */
final class ZoomableImagePane extends VBox {

    private static final double MIN_ZOOM = 0.1;
    private static final double MAX_ZOOM = 8.0;
    private static final double STEP = 1.25;
    private static final double WHEEL_STEP = 1.1;

    private final ImageView view = new ImageView();
    private final ScrollPane scroll = new ScrollPane(view);
    private final Label zoomLabel = new Label("Fit");

    /** 0 means "fit to viewport"; otherwise a multiplier on the image's own size. */
    private double zoom;

    ZoomableImagePane() {
        super(2);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        scroll.setPannable(true);

        scroll.viewportBoundsProperty().addListener((obs, oldV, newV) -> apply());
        // Ctrl + wheel is the gesture people try first. A plain wheel is left to
        // the ScrollPane so a zoomed-in plot can still be scrolled.
        scroll.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (e.isControlDown() && e.getDeltaY() != 0) {
                multiply(e.getDeltaY() > 0 ? WHEEL_STEP : 1 / WHEEL_STEP);
                e.consume();
            }
        });

        Button out = small("-", "Shrink the plot (Ctrl + scroll down).");
        out.setOnAction(e -> multiply(1 / STEP));
        Button in = small("+", "Enlarge the plot (Ctrl + scroll up). Past the window size\n"
                + "the scrollbars take over, which is how you read a wide plot.");
        in.setOnAction(e -> multiply(STEP));
        Button fit = small("Fit", "Scale the whole plot back into the window.");
        fit.setOnAction(e -> setZoom(0));
        Button actual = small("100%", "Show the PNG at its own pixel size -- what gets exported.");
        actual.setOnAction(e -> setZoom(1.0));
        zoomLabel.setStyle("-fx-font-size: 11px;");

        HBox bar = new HBox(4, new Label("Zoom:"), out, in, fit, actual, zoomLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(2, 0, 2, 0));

        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(bar, scroll);
    }

    private static Button small(String text, String tip) {
        Button b = new Button(text);
        b.setStyle("-fx-font-size: 10px;");
        b.setTooltip(Tooltips.of(tip));
        return b;
    }

    /** Replace the displayed image, keeping the current zoom mode. */
    void setImage(Image image) {
        view.setImage(image);
        apply();
    }

    /** The view itself, for callers that keep a handle for export or comparison. */
    ImageView getView() {
        return view;
    }

    private void multiply(double factor) {
        // Start from what is ON SCREEN, so the first "+" out of Fit enlarges what
        // you are looking at rather than jumping to some unrelated scale.
        double current = zoom > 0 ? zoom : currentDisplayScale();
        setZoom(Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, current * factor)));
    }

    private double currentDisplayScale() {
        Image img = view.getImage();
        if (img == null || img.getWidth() <= 0) {
            return 1.0;
        }
        return view.getFitWidth() > 0 ? view.getFitWidth() / img.getWidth() : 1.0;
    }

    private void setZoom(double z) {
        zoom = z;
        apply();
        zoomLabel.setText(zoom <= 0 ? "Fit" : String.format("%.0f%%", zoom * 100.0));
    }

    private void apply() {
        Image img = view.getImage();
        if (zoom <= 0) {
            Bounds vb = scroll.getViewportBounds();
            if (vb != null) {
                // Both dimensions with preserveRatio scales to the smaller one, so
                // a wide plot fills the width and a tall one fills the height
                // instead of running off the bottom.
                view.setFitWidth(Math.max(50.0, vb.getWidth() - 4.0));
                view.setFitHeight(Math.max(50.0, vb.getHeight() - 4.0));
            }
        } else if (img != null) {
            // One dimension only: setting both would re-impose the fit clamp.
            view.setFitWidth(img.getWidth() * zoom);
            view.setFitHeight(0);
        }
    }
}
