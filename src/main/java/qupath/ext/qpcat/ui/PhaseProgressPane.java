package qupath.ext.qpcat.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

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

    public PhaseProgressPane() {
        setSpacing(3);
    }

    private static final class Row {
        final Label glyph = new Label(PENDING);
        final Label text;
        final HBox box;

        Row(String label) {
            text = new Label(label);
            glyph.setMinWidth(16);
            box = new HBox(8, glyph, text);
            box.setAlignment(Pos.CENTER_LEFT);
        }
    }

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

    /** All phases back to pending. */
    public void reset() {
        for (String t : order) {
            setState(t, PENDING, false, false);
        }
    }

    /** Mark the named phase active and every earlier phase done. */
    public void advanceTo(String token) {
        if (!rows.containsKey(token)) {
            return;   // not an expected phase for this run -- ignore
        }
        int target = order.indexOf(token);
        for (int i = 0; i < order.size(); i++) {
            String t = order.get(i);
            if (i < target) {
                setState(t, DONE, true, false);
            } else if (i == target) {
                setState(t, ACTIVE, false, true);
            } else {
                setState(t, PENDING, false, false);
            }
        }
    }

    /** Mark every phase done. */
    public void complete() {
        for (String t : order) {
            setState(t, DONE, true, false);
        }
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
