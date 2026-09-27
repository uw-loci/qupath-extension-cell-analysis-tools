package qupath.ext.qpcat.ui;

import javafx.scene.control.SpinnerValueFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Typed spinner input must respect the spinner's own range.
 *
 * <p>{@code SpinnerValueFactory.setValue} does NOT enforce min/max -- they
 * constrain the up/down arrows and nothing else. Committing typed text put
 * whatever was in the box straight through, so a 2-to-500 "n_neighbors" spinner
 * accepted 0 and the run died inside scikit-learn with "The 'n_neighbors'
 * parameter of KNeighborsTransformer must be an int in the range [1, inf) or
 * None. Got 0 instead" -- an error naming neither the control nor the dialog.
 */
class SpinnerClampTest {

    private static SpinnerValueFactory.IntegerSpinnerValueFactory ints(int min, int max, int init) {
        return new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max, init);
    }

    @Test
    void theReportedCaseIsCaught() {
        // Leiden n_neighbors: 2..500.
        assertThat(SpinnerUtils.clampToRange(ints(2, 500, 50), 0)).isEqualTo(2);
    }

    @Test
    void valuesInsideTheRangeAreUntouched() {
        assertThat(SpinnerUtils.clampToRange(ints(2, 500, 50), 15)).isEqualTo(15);
        assertThat(SpinnerUtils.clampToRange(ints(2, 500, 50), 2)).isEqualTo(2);
        assertThat(SpinnerUtils.clampToRange(ints(2, 500, 50), 500)).isEqualTo(500);
    }

    @Test
    void aboveTheMaximumComesBackToIt() {
        assertThat(SpinnerUtils.clampToRange(ints(2, 500, 50), 9999)).isEqualTo(500);
    }

    @Test
    void negativesAreClampedNotWrapped() {
        assertThat(SpinnerUtils.clampToRange(ints(2, 500, 50), -40)).isEqualTo(2);
    }

    @Test
    void doubleFactoriesClampToo() {
        // Leiden resolution: 0.01..10.0. Zero resolution is not a valid request.
        var f = new SpinnerValueFactory.DoubleSpinnerValueFactory(0.01, 10.0, 1.0);
        assertThat(SpinnerUtils.clampToRange(f, 0.0)).isEqualTo(0.01);
        assertThat(SpinnerUtils.clampToRange(f, 99.0)).isEqualTo(10.0);
        assertThat(SpinnerUtils.clampToRange(f, 1.5)).isEqualTo(1.5);
    }

    @Test
    void aFactoryWithNoDeclaredRangeIsLeftAlone() {
        var list = new SpinnerValueFactory.ListSpinnerValueFactory<>(
                javafx.collections.FXCollections.observableArrayList("a", "b"));
        assertThat(SpinnerUtils.clampToRange(list, "z")).isEqualTo("z");
    }
}
