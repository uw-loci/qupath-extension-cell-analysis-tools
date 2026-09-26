package qupath.ext.qpcat.ui;

import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Expand..." MOVES the measurement list into a window rather than copying it, so
 * closing that window has to put the real node back. If the restore misses, the
 * Measurements section is simply empty for the rest of the session -- no
 * exception, nothing in the log, just a missing control.
 *
 * <p>The window itself needs a JavaFX stage; this is the part that does not, and
 * it is the part that fails silently.
 */
class MeasurementPanePopOutTest {

    private static VBox parentOf(Node... children) {
        return new VBox(children);
    }

    @Test
    void theNodeGoesBackWhereItWas() {
        Node a = new Region();
        Node pane = new Region();
        Node c = new Region();
        VBox parent = parentOf(a, pane, c);

        parent.getChildren().remove(pane);
        assertThat(parent.getChildren()).containsExactly(a, c);

        MeasurementSelectionPane.restoreInto(parent, pane, 1);
        assertThat(parent.getChildren()).containsExactly(a, pane, c);
    }

    @Test
    void restoringTwiceDoesNotDuplicateIt() {
        Node pane = new Region();
        VBox parent = parentOf(new Region(), pane);
        parent.getChildren().remove(pane);

        MeasurementSelectionPane.restoreInto(parent, pane, 1);
        MeasurementSelectionPane.restoreInto(parent, pane, 1);
        assertThat(parent.getChildren()).hasSize(2);
        assertThat(parent.getChildren().filtered(n -> n == pane)).hasSize(1);
    }

    @Test
    void aShrunkenParentStillTakesItBack() {
        // The section rebuilt itself while the window was open, so the old index
        // is now past the end. Better at the end than stranded.
        Node pane = new Region();
        VBox parent = parentOf(new Region(), new Region(), pane);
        parent.getChildren().remove(pane);
        parent.getChildren().remove(0);

        MeasurementSelectionPane.restoreInto(parent, pane, 2);
        assertThat(parent.getChildren()).contains(pane);
        assertThat(parent.getChildren()).hasSize(2);
    }

    @Test
    void aNullParentIsIgnoredRatherThanThrowing() {
        MeasurementSelectionPane.restoreInto(null, new Region(), 0);
        VBox parent = parentOf();
        MeasurementSelectionPane.restoreInto(parent, null, 0);
        assertThat(parent.getChildren()).isEmpty();
    }

    @Test
    void aNegativeIndexClampsToTheFront() {
        Node pane = new Region();
        Node other = new Region();
        VBox parent = parentOf(other);
        MeasurementSelectionPane.restoreInto(parent, pane, -3);
        assertThat(parent.getChildren()).containsExactly(pane, other);
    }
}
