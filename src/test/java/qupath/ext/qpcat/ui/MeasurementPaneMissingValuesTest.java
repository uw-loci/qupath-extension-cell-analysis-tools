package qupath.ext.qpcat.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.MissingPolicy;
import qupath.ext.qpcat.service.MeasurementExtractor;

/** Issue #29: badges, the sort and the policy box appear only while a value is missing. */
class MeasurementPaneMissingValuesTest {

    private static final List<String> NAMES = List.of(
            "Cell: CD3 mean", "Nucleus: CD3 mean", "Nucleus: Ki67 mean", "Cell: Area");

    private final AtomicReference<MeasurementSelectionPane> pane = new AtomicReference<>();

    private static MeasurementExtractor.MissingReport report() {
        Map<String, Integer> per = new LinkedHashMap<>();
        per.put("Nucleus: CD3 mean", 37);
        per.put("Nucleus: Ki67 mean", 37);
        return new MeasurementExtractor.MissingReport(1530, 37, per);
    }

    @BeforeEach
    void setUp() {
        assumeTrue(FxHarness.available(), "needs a JavaFX display");
        FxHarness.onFx(() -> pane.set(new MeasurementSelectionPane()));
    }

    @Test
    void nothingShowsWithoutMissingValues() {
        List<Object> state = FxHarness.onFxGet(() -> {
            MeasurementSelectionPane p = pane.get();
            p.setMeasurements(NAMES, n -> true);
            return List.of(p.isMissingBoxShown(), p.isSortedByMissing(), p.getMissingPolicy());
        });
        assertThat(state).containsExactly(false, false, MissingPolicy.EXCLUDE_OBJECTS);
    }

    @Test
    void theBoxFollowsTheTicks() {
        List<Object> state = FxHarness.onFxGet(() -> {
            MeasurementSelectionPane p = pane.get();
            p.setMeasurements(NAMES, null);
            p.setMissingValues(report(), sel -> report(), null);
            boolean beforeTick = p.isMissingBoxShown();
            p.setSelected(List.of("Cell: CD3 mean"));
            boolean completeOnly = p.isMissingBoxShown();
            p.setSelected(List.of("Cell: CD3 mean", "Nucleus: CD3 mean"));
            boolean withGap = p.isMissingBoxShown();
            p.clearMissingValues();
            boolean cleared = p.isMissingBoxShown();
            return List.of(beforeTick, completeOnly, withGap, cleared);
        });
        assertThat(state).containsExactly(false, false, true, false);
    }

    @Test
    void sortPutsTheGapsFirstAndCanBeUndone() {
        List<Object> state = FxHarness.onFxGet(() -> {
            MeasurementSelectionPane p = pane.get();
            p.setMeasurements(NAMES, null);
            p.setMissingValues(report(), null, "tme_00.tif");
            p.setSortByMissing(true);
            String first = p.visibleNames().get(0);
            boolean sorted = p.isSortedByMissing();
            p.setSortByMissing(false);
            String firstAgain = p.visibleNames().get(0);
            return List.of(first, sorted, firstAgain, p.isSortedByMissing());
        });
        assertThat(state).containsExactly("Nucleus: CD3 mean", true, "Cell: CD3 mean", false);
    }

    @Test
    void excludeMeasurementsIsRefusedWhenEveryTickedMeasurementHasAGap() {
        List<Object> state = FxHarness.onFxGet(() -> {
            MeasurementSelectionPane p = pane.get();
            p.setMeasurements(NAMES, null);
            p.setMissingValues(report(), sel -> report(), null);
            p.setMissingPolicy(MissingPolicy.EXCLUDE_MEASUREMENTS);
            p.setSelected(List.of("Nucleus: CD3 mean", "Nucleus: Ki67 mean"));
            return List.of(p.isMissingBoxShown(), p.getMissingPolicy());
        });
        assertThat(state).containsExactly(true, MissingPolicy.EXCLUDE_OBJECTS);
    }

    @Test
    void badgeTextCarriesCountAndPercent() {
        assertThat(MeasurementSelectionPane.missingBadgeText(37, 1530)).isEqualTo("missing 37 (2.4%)");
        assertThat(MeasurementSelectionPane.missingBadgeText(5, 0)).isEqualTo("missing 5");
    }
}
