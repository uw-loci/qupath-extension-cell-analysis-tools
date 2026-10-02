package qupath.ext.qpcat.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Minimal JavaFX harness for the interaction tests: starts the toolkit once,
 * runs work on the FX thread, and shows a real window so a {@link Canvas} is
 * on-scene and actually paints (the panel refuses to draw otherwise).
 *
 * <p>Needs a display. {@link #available()} is false without one, and the tests
 * skip rather than fail so the pre-push hook still passes on a headless box.</p>
 */
final class FxHarness {

    private static Boolean started;

    private FxHarness() {}

    /** True when the FX toolkit is up and a window can be shown. */
    static synchronized boolean available() {
        if (started != null) {
            return started;
        }
        if (System.getenv("DISPLAY") == null && System.getenv("WAYLAND_DISPLAY") == null) {
            started = false;
            return false;
        }
        CountDownLatch up = new CountDownLatch(1);
        try {
            Platform.startup(up::countDown);
        } catch (IllegalStateException alreadyRunning) {
            up.countDown();
        } catch (Throwable noToolkit) {
            started = false;
            return false;
        }
        try {
            started = up.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            started = false;
        }
        if (Boolean.TRUE.equals(started)) {
            Platform.runLater(() -> Platform.setImplicitExit(false));
        }
        return started;
    }

    /** Run on the FX thread and wait, rethrowing whatever it threw. */
    static void onFx(Runnable work) {
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                err.set(t);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("FX work did not finish within 30s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        Throwable t = err.get();
        if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        }
        if (t != null) {
            throw new AssertionError(t);
        }
    }

    /** Run on the FX thread and wait for a value. */
    static <T> T onFxGet(Supplier<T> work) {
        AtomicReference<T> out = new AtomicReference<>();
        onFx(() -> out.set(work.get()));
        return out.get();
    }

    /**
     * Put a node in a sized, laid-out {@link Scene} so its canvas has a scene and
     * real dimensions -- which is what the panel checks before it will paint.
     * <p>
     * No window is shown: an unshown scene lays out and snapshots fine, and
     * five windows flashing onto the desktop during {@code ./gradlew test} is a
     * poor trade for nothing the assertions need.
     */
    static Scene layOutOffscreen(javafx.scene.Node node) {
        StackPane root = new StackPane(node);
        Scene scene = new Scene(root, 900, 760);
        root.applyCss();
        root.layout();
        return scene;
    }

    /** The scatter panel's private plot canvas. */
    static Canvas canvasOf(EmbeddingScatterPanel panel) {
        try {
            Field f = EmbeddingScatterPanel.class.getDeclaredField("canvas");
            f.setAccessible(true);
            return (Canvas) f.get(panel);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("canvas field moved", e);
        }
    }

    /** Snapshot the canvas as painted right now (flushes its command buffer). */
    static WritableImage snapshot(Canvas canvas) {
        return canvas.snapshot(null, null);
    }

    /**
     * Darkest pixel in a square around (x, y), as perceived luminance 0..1. The
     * gate outline and its vertex dots are near-black on white, so "is the gate
     * drawn here" is "is some pixel here dark".
     */
    static double darkestNear(WritableImage img, double x, double y, int radius) {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        double darkest = 1.0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                int px = (int) Math.round(x) + dx;
                int py = (int) Math.round(y) + dy;
                if (px < 0 || py < 0 || px >= w || py >= h) continue;
                Color c = img.getPixelReader().getColor(px, py);
                double lum = 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
                darkest = Math.min(darkest, lum);
            }
        }
        return darkest;
    }

    /** Most-red pixel in a square around (x, y), as red-minus-other-channels. */
    static double rednessNear(WritableImage img, double x, double y, int radius) {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        double best = -1.0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                int px = (int) Math.round(x) + dx;
                int py = (int) Math.round(y) + dy;
                if (px < 0 || py < 0 || px >= w || py >= h) continue;
                Color c = img.getPixelReader().getColor(px, py);
                best = Math.max(best, c.getRed() - Math.max(c.getGreen(), c.getBlue()));
            }
        }
        return best;
    }
}
