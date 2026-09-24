package qupath.ext.qpcat.ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A vertical checklist of named phases that fill in as a long task runs. Only the
 * phases that will actually run are shown ({@link #configure(List)} with the
 * relevant subset); call {@link #advanceTo(String)} as each phase begins -- prior
 * phases are marked done, the named one becomes active, later ones stay pending --
 * and {@link #complete()} when finished. Unknown tokens are ignored, so a phase
 * that is skipped or too fast to report simply completes when a later one starts.
 *
 * <p>Honest by design: phase durations are very uneven, so "on phase 4 of 6
 * (Clustering)" reads better than a single bar that sits still then lurches.</p>
 *
 * <p>Each phase shows how long it took once it finishes, and the running phase shows
 * its elapsed time ticking, so a long run says which step is the expensive one instead
 * of leaving the user to guess. A phase that was skipped, or that completed without ever
 * being reported as active, shows no time rather than a made-up one.</p>
 */
public final class PhaseProgressPane extends VBox {

    /** One phase: a stable token (matched against progress reports) + a label. */
    public record Phase(String token, String label) {}

    // Glyphs as unicode escapes (rendered as circles); source stays ASCII.
    private static final String PENDING = "\u25CB";   // hollow circle
    private static final String ACTIVE = "\u25D0";    // half-filled circle
    private static final String DONE = "\u25CF";      // filled circle

    private final Map<String, Row> rows = new LinkedHashMap<>();
    private final List<String> order = new ArrayList<>();

    /** Ticks the running phase's elapsed time; only alive while a phase is active. */
    private final Timeline ticker = new Timeline(
            new KeyFrame(Duration.seconds(1), e -> tickActive()));

    private String activeToken;

    public PhaseProgressPane() {
        setSpacing(3);
        ticker.setCycleCount(Animation.INDEFINITE);
        // Stop ticking whenever the checklist is hidden. complete() covers a run that
        // finishes, but a cancelled or failed run only hides the pane -- and an
        // INDEFINITE timeline left running would tick for the life of the dialog.
        // Hooking visibility catches every exit, including ones added later.
        visibleProperty().addListener((obs, was, isVisible) -> {
            if (!isVisible) {
                ticker.stop();
            }
        });
    }

    private static final class Row {
        final Label glyph = new Label(PENDING);
        final Label text;
        final Label time = new Label("");
        final HBox box;
        /** When this phase was reported active, or 0 if it never was. */
        long startedAtMillis;
        /** Measured duration once it finished, or -1 while unknown. */
        long durationMillis = -1;

        Row(String label) {
            text = new Label(label);
            glyph.setMinWidth(16);
            time.setStyle(TIME_STYLE);
            box = new HBox(8, glyph, text, time);
            box.setAlignment(Pos.CENTER_LEFT);
        }
    }

    private static final String TIME_STYLE =
            "-fx-font-size: 11px; -fx-text-fill: derive(-fx-text-base-color, 35%);";

    /** (Re)build the checklist for the phases that will run, in order. */
    public void configure(List<Phase> phases) {
        getChildren().clear();
        rows.clear();
        order.clear();
        for (Phase p : phases) {
            Row r = new Row(p.label());
            rows.put(p.token(), r);
            order.add(p.token());
            getChildren().add(r.box);
        }
        reset();
    }

    /** All phases back to pending, with their timings cleared. */
    public void reset() {
        ticker.stop();
        activeToken = null;
        for (String t : order) {
            Row r = rows.get(t);
            r.startedAtMillis = 0;
            r.durationMillis = -1;
            r.time.setText("");
            setState(t, PENDING, false, false);
        }
    }

    /** Mark the named phase active and every earlier phase done. */
    public void advanceTo(String token) {
        if (!rows.containsKey(token)) {
            return;   // not an expected phase for this run -- ignore
        }
        int target = order.indexOf(token);
        long now = System.currentTimeMillis();
        for (int i = 0; i < order.size(); i++) {
            String t = order.get(i);
            Row r = rows.get(t);
            if (i < target) {
                stopTiming(r, now);
                setState(t, DONE, true, false);
            } else if (i == target) {
                if (r.startedAtMillis == 0) {
                    r.startedAtMillis = now;
                }
                setState(t, ACTIVE, false, true);
            } else {
                setState(t, PENDING, false, false);
            }
        }
        activeToken = token;
        tickActive();
        ticker.playFromStart();
    }

    /** Mark every phase done, recording the running phase's final time. */
    public void complete() {
        ticker.stop();
        long now = System.currentTimeMillis();
        for (String t : order) {
            stopTiming(rows.get(t), now);
            setState(t, DONE, true, false);
        }
        activeToken = null;
    }

    /**
     * Freeze a phase's measured duration. A phase that was never reported active keeps a
     * blank time: it was skipped or ran inside another step, and inventing a number for it
     * would be a measurement we did not make.
     */
    private void stopTiming(Row r, long now) {
        if (r == null || r.startedAtMillis == 0 || r.durationMillis >= 0) {
            return;
        }
        r.durationMillis = Math.max(0, now - r.startedAtMillis);
        r.time.setText(formatDuration(r.durationMillis));
    }

    /** Update the running phase's elapsed time. */
    private void tickActive() {
        Row r = (activeToken == null) ? null : rows.get(activeToken);
        if (r == null || r.startedAtMillis == 0) {
            return;
        }
        r.time.setText(formatDuration(System.currentTimeMillis() - r.startedAtMillis));
    }

    /**
     * Compact elapsed time: "8s", "1m 04s", "1h 06m". Sub-second reads "<1s" rather than
     * "0s", so a step that ran is never shown as having taken no time at all.
     *
     * @param millis elapsed milliseconds
     * @return a short human-readable duration
     */
    static String formatDuration(long millis) {
        if (millis < 1000) {
            return "<1s";
        }
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%dh %02dm", hours, minutes);
        }
        if (minutes > 0) {
            return String.format("%dm %02ds", minutes, seconds);
        }
        return seconds + "s";
    }

    private void setState(String token, String glyph, boolean done, boolean active) {
        Row r = rows.get(token);
        if (r == null) {
            return;
        }
        r.glyph.setText(glyph);
        if (done) {
            r.glyph.setStyle("-fx-text-fill: #2e7d32;");           // green
            r.text.setStyle("-fx-text-fill: -fx-text-base-color;");
        } else if (active) {
            r.glyph.setStyle("-fx-text-fill: #1565c0;");           // accent blue
            r.text.setStyle("-fx-font-weight: bold;");
        } else {
            r.glyph.setStyle("-fx-text-fill: derive(-fx-text-base-color, 45%);");
            r.text.setStyle("-fx-text-fill: derive(-fx-text-base-color, 45%);");
        }
    }
}
