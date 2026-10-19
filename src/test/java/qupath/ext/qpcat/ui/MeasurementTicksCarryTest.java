package qupath.ext.qpcat.ui;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The clustering dialog restores the last run's ticks BEFORE a multi-image scope
 * has finished reading its measurement names; the list that arrives afterwards
 * must not wipe them.
 */
class MeasurementTicksCarryTest {

    private static MeasurementSelectionPane newPane() {
        assumeTrue(FxHarness.available(), "needs a JavaFX display");
        AtomicReference<MeasurementSelectionPane> pane = new AtomicReference<>();
        FxHarness.onFx(() -> pane.set(new MeasurementSelectionPane()));
        return pane.get();
    }

    @Test
    void ticksRestoredBeforeTheListArrivesAreApplied() {
        MeasurementSelectionPane pane = newPane();
        List<String> selected = FxHarness.onFxGet(() -> {
            pane.setMeasurements(List.of(), null);           // scope read still running
            pane.setSelected(List.of("CD3: Mean", "CD8: Mean"));
            pane.setMeasurements(List.of("CD3: Mean", "CD8: Mean", "Area"), null);
            return pane.getSelected();
        });
        assertThat(selected).containsExactly("CD3: Mean", "CD8: Mean");
    }

    @Test
    void ticksSurviveAReReadOfTheSameNames() {
        MeasurementSelectionPane pane = newPane();
        List<String> selected = FxHarness.onFxGet(() -> {
            pane.setMeasurements(List.of("CD3: Mean", "Area"), null);
            pane.setSelected(List.of("CD3: Mean"));
            pane.setMeasurements(List.of("CD3: Mean", "Area", "CD8: Mean"), null);
            return pane.getSelected();
        });
        assertThat(selected).containsExactly("CD3: Mean");
    }

    @Test
    void aTickTheNewListLacksComesBackWithAListThatHasIt() {
        MeasurementSelectionPane pane = newPane();
        List<String> selected = FxHarness.onFxGet(() -> {
            pane.setMeasurements(List.of("CD3: Mean", "CD20: Mean"), null);
            pane.setSelected(List.of("CD3: Mean", "CD20: Mean"));
            pane.setMeasurements(List.of("CD3: Mean"), null);
            pane.setMeasurements(List.of("CD3: Mean", "CD20: Mean"), null);
            return pane.getSelected();
        });
        assertThat(selected).containsExactly("CD3: Mean", "CD20: Mean");
    }

    @Test
    void anUntickedNameStaysUnticked() {
        MeasurementSelectionPane pane = newPane();
        List<String> selected = FxHarness.onFxGet(() -> {
            pane.setMeasurements(List.of("CD3: Mean", "Area"), null);
            pane.setSelected(List.of());
            pane.setMeasurements(List.of("CD3: Mean", "Area"), null);
            return pane.getSelected();
        });
        assertThat(selected).isEmpty();
    }
}
