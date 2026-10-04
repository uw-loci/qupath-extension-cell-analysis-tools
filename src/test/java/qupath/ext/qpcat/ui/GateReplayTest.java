package qupath.ext.qpcat.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.GateSet;
import qupath.ext.qpcat.service.GateStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A gate saved off one plot and put back on another, through the real panel.
 *
 * <p>{@link qupath.ext.qpcat.service.GateStoreTest} covers the file and the
 * compatibility decision in isolation. What this adds is the panel's side of
 * replay: that a reloaded polygon selects the same cells the drawing handlers
 * would have, that making it active fires the gate callback so
 * "Select in open image" and "Assign class..." light up, and that reloading
 * does not disturb a gate already being drawn.
 *
 * <p>Needs a display; skips without one.
 */
class GateReplayTest {

    /** Cells on a sparse even-integer grid, so none sits on a gate vertex. */
    private static final double[][] CELLS = buildGrid();

    /** The square (1,1)-(5,5): holds (2,2), (2,4), (4,2), (4,4). */
    private static final List<double[]> GATE = List.of(
            new double[] {1, 1}, new double[] {5, 1},
            new double[] {5, 5}, new double[] {1, 5});

    private static double[][] buildGrid() {
        List<double[]> out = new ArrayList<>();
        for (int x = 0; x <= 10; x += 2) {
            for (int y = 0; y <= 10; y += 2) {
                out.add(new double[] {x, y});
            }
        }
        return out.toArray(new double[0][]);
    }

    private EmbeddingScatterPanel panel;

    @BeforeEach
    void setUp() {
        assumeTrue(FxHarness.available(), "no display: JavaFX toolkit unavailable");
        FxHarness.onFx(() -> {
            panel = new EmbeddingScatterPanel();
            FxHarness.layOutOffscreen(panel);
            panel.setData(CELLS, new int[CELLS.length], 1, "UMAP");
        });
    }

    @Test
    void aReplayedPolygonSelectsTheCellsItEncloses() {
        assertThat(FxHarness.onFxGet(() -> panel.indicesIn(GATE))).hasSize(4);
    }

    @Test
    void makingAReplayedGateActiveFiresTheCallbackThatEnablesTheActions() {
        // Without this, a loaded gate is an outline and nothing more: the two
        // buttons that act on a gate read getGatedIndices(), and they are enabled
        // by the callback.
        AtomicInteger reported = new AtomicInteger(-1);
        int count = FxHarness.onFxGet(() -> {
            panel.setOnGate(indices -> reported.set(indices.length));
            return panel.setActiveGate(GATE);
        });
        assertThat(count).isEqualTo(4);
        assertThat(reported.get()).isEqualTo(4);
        assertThat(FxHarness.onFxGet(() -> panel.getGatedIndices())).hasSize(4);
    }

    @Test
    void aReplayedOutlineDoesNotDisturbTheGateBeingDrawn() {
        AtomicInteger fired = new AtomicInteger(0);
        FxHarness.onFx(() -> {
            panel.setOnGate(indices -> fired.incrementAndGet());
            panel.setActiveGate(GATE);                 // an active selection exists
            panel.addCommittedGate("loaded", List.of(
                    new double[] {6, 6}, new double[] {10, 6}, new double[] {10, 10}));
        });
        // The active gate is untouched, and adding an outline is not a selection.
        assertThat(FxHarness.onFxGet(() -> panel.getGatedIndices())).hasSize(4);
        assertThat(FxHarness.onFxGet(() -> panel.getCommittedGateCount())).isEqualTo(1);
        assertThat(fired.get()).isEqualTo(1);
    }

    @Test
    void aDegenerateReplayIsIgnoredRatherThanClearingTheActiveGate() {
        FxHarness.onFx(() -> {
            panel.setActiveGate(GATE);
            panel.addCommittedGate("two points", List.of(
                    new double[] {0, 0}, new double[] {1, 1}));
        });
        assertThat(FxHarness.onFxGet(() -> panel.getCommittedGateCount())).isZero();
        assertThat(FxHarness.onFxGet(() -> panel.getGatedIndices())).hasSize(4);
        assertThat(FxHarness.onFxGet(() -> panel.setActiveGate(
                List.of(new double[] {0, 0}, new double[] {1, 1})))).isZero();
    }

    @Test
    void committedGatesComeBackOutWithTheirLabelsAndVertices() {
        // This is what "Save gates..." reads, so it has to survive the round trip
        // through the panel as well as through the file.
        FxHarness.onFx(() -> {
            panel.addCommittedGate("Population A", GATE);
            panel.addCommittedGate("Population B", List.of(
                    new double[] {6, 6}, new double[] {10, 6}, new double[] {10, 10}));
        });
        assertThat(FxHarness.onFxGet(() -> panel.getCommittedGateLabels()))
                .containsExactly("Population A", "Population B");
        List<double[]> back = FxHarness.onFxGet(() -> panel.getCommittedGateVertices(0));
        assertThat(back).hasSize(4);
        assertThat(back.get(0)).containsExactly(1, 1);
        assertThat(back.get(2)).containsExactly(5, 5);
        // Out of range is empty, not an exception.
        assertThat(FxHarness.onFxGet(() -> panel.getCommittedGateVertices(9))).isEmpty();
    }

    @Test
    void theFingerprintFromThePlotIsTheOneTheStoreCompares() {
        String fromPanel = FxHarness.onFxGet(() -> panel.dataFingerprint());
        assertThat(fromPanel).isEqualTo(GateSet.fingerprintOf(CELLS));
        assertThat(FxHarness.onFxGet(() -> panel.getCellCount())).isEqualTo(CELLS.length);

        GateSet.Axes axes = new GateSet.Axes(GateSet.MODE_EMBEDDING, "UMAP",
                "UMAP1", "UMAP2", "1 image(s)");
        GateSet saved = new GateSet(axes, CELLS.length, fromPanel, "test", "now",
                List.of(new GateSet.Gate("A", new double[][] {{1, 1}, {5, 1}, {5, 5}, {1, 5}}, 4)));
        assertThat(GateStore.check(saved, axes, fromPanel, CELLS.length).match())
                .isEqualTo(GateStore.Match.EXACT);
        // And the store's replay agrees with the panel's, cell for cell.
        assertThat(GateStore.indicesIn(saved.getGates().get(0), CELLS))
                .isEqualTo(FxHarness.onFxGet(() -> panel.indicesIn(GATE)));
    }

    @Test
    void newDataDropsEveryGateBecauseAGateBelongsToWhatItWasDrawnOn() {
        FxHarness.onFx(() -> {
            panel.addCommittedGate("Population A", GATE);
            panel.setActiveGate(GATE);
            panel.setData(CELLS, new int[CELLS.length], 1, "UMAP");
        });
        assertThat(FxHarness.onFxGet(() -> panel.getCommittedGateCount())).isZero();
        assertThat(FxHarness.onFxGet(() -> panel.getGatedIndices())).isEmpty();
    }

    @Test
    void theStoresFileNameIsUsableAsAPath() {
        // It goes straight into a FileChooser's initial name.
        String name = GateStore.fileNameFor("UMAP_gates");
        assertThat(Path.of(name).getFileName().toString()).isEqualTo(name);
    }
}
