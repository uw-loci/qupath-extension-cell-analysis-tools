package qupath.ext.qpcat.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The "Hide QP-CAT's own measurements" box hides rows without touching ticks. */
class MeasurementPaneHideQpcatTest {

    private static final List<String> NAMES = List.of(
            "Cell: CD3 mean", "Cell: CD8 mean", "QPCAT UMAP1", "QPCAT spatial: Mean distance");

    private final AtomicReference<MeasurementSelectionPane> pane = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        assumeTrue(FxHarness.available(), "needs a JavaFX display");
        FxHarness.onFx(() -> pane.set(new MeasurementSelectionPane()));
    }

    @Test
    void qpcatRowsAreHiddenByDefaultAndShownOnUntick() {
        List<Integer> counts = FxHarness.onFxGet(() -> {
            pane.get().setMeasurements(NAMES, null);
            int hidden = pane.get().visibleCount();
            pane.get().setHideQpcat(false);
            int shown = pane.get().visibleCount();
            return List.of(hidden, shown);
        });
        assertThat(counts).containsExactly(2, 4);
    }

    @Test
    void aRestoredSelectionWithQpcatOutputUnhidesIt() {
        List<Object> state = FxHarness.onFxGet(() -> {
            pane.get().setMeasurements(NAMES, null);
            pane.get().setSelected(List.of("Cell: CD3 mean", "QPCAT UMAP1"));
            return List.of(pane.get().isHidingQpcat(), pane.get().visibleCount(), pane.get().getSelected());
        });
        assertThat(state.get(0)).isEqualTo(false);
        assertThat(state.get(1)).isEqualTo(4);
        assertThat(state.get(2)).isEqualTo(List.of("Cell: CD3 mean", "QPCAT UMAP1"));
    }

    @Test
    void hidingKeepsTicksAndSelectMeanOnlyCannotReachHiddenRows() {
        List<Object> state = FxHarness.onFxGet(() -> {
            pane.get().setMeasurements(NAMES, null);
            pane.get().setSelected(List.of("QPCAT spatial: Mean distance"));
            pane.get().setHideQpcat(true);
            return List.of(pane.get().visibleCount(), pane.get().getSelected());
        });
        assertThat(state.get(0)).isEqualTo(2);
        assertThat(state.get(1)).isEqualTo(List.of("QPCAT spatial: Mean distance"));
    }
}
