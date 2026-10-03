package qupath.ext.qpcat.ui;

import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The "Composition by group" tab end to end: per-cell labels in, a table of
 * per-group medians and q-values out, with the design's problems shown above it.
 *
 * <p>The statistics are pinned in {@code CompositionComparisonTest}. What these
 * tests hold is the wiring a unit test of the maths cannot see: that the warning
 * box is actually populated and visible, that the tab refuses rather than invents
 * a comparison when a key does not split the images, and that switching keys
 * recomputes instead of leaving the previous key's numbers on screen.
 */
class ClusterGroupComparisonPanelTest {

    @BeforeEach
    void requireToolkit() {
        assumeTrue(FxHarness.available(), "no display: JavaFX toolkit unavailable");
    }

    /** Six images, ten cells each; cluster 0 is scarce in Control, abundant in Treated. */
    private static int[] labels() {
        int[] out = new int[60];
        for (int img = 0; img < 6; img++) {
            int inCluster0 = img >= 3 ? 9 : 1;
            for (int k = 0; k < 10; k++) {
                out[img * 10 + k] = k < inCluster0 ? 0 : 1;
            }
        }
        return out;
    }

    private static String[] imageIds() {
        String[] out = new String[60];
        for (int i = 0; i < 60; i++) {
            out[i] = "img" + (i / 10);
        }
        return out;
    }

    /** Metadata with a key that splits the images and one that does not. */
    private static Map<String, Map<String, String>> metadata() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (int img = 0; img < 6; img++) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("condition", img >= 3 ? "Treated" : "Control");
            m.put("scanner", "Axio");
            out.put("img" + img, m);
        }
        return out;
    }

    private static ClusterGroupComparisonPanel panel(
            Map<String, Map<String, String>> metadata) {
        return FxHarness.onFxGet(() -> {
            ClusterGroupComparisonPanel p = new ClusterGroupComparisonPanel(
                    labels(), 2, imageIds(), metadata, c -> "Cluster " + (c + 1));
            FxHarness.layOutOffscreen(p);
            return p;
        });
    }

    @SuppressWarnings("unchecked")
    private static TableView<String[]> tableOf(ClusterGroupComparisonPanel p) {
        return (TableView<String[]>) field(p, "table");
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<String> comboOf(ClusterGroupComparisonPanel p) {
        return (ComboBox<String>) field(p, "keyCombo");
    }

    private static Label labelOf(ClusterGroupComparisonPanel p, String name) {
        return (Label) field(p, name);
    }

    private static Object field(ClusterGroupComparisonPanel p, String name) {
        try {
            Field f = ClusterGroupComparisonPanel.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(p);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(name + " field moved", e);
        }
    }

    private static List<String> rowTexts(TableView<String[]> t) {
        List<String> out = new ArrayList<>();
        for (String[] row : t.getItems()) {
            out.add(String.join(" | ", row));
        }
        return out;
    }

    // ---- The comparison reaches the table ----

    @Test
    void aSplittingKeyProducesOneRowPerClusterWithGroupMedians() {
        ClusterGroupComparisonPanel p = panel(metadata());
        FxHarness.onFx(() -> comboOf(p).setValue("condition"));

        TableView<String[]> t = tableOf(p);
        assertThat(t.getItems()).hasSize(2);
        // Cluster | Control (n=3) | Treated (n=3) | U | p | q
        assertThat(t.getColumns()).hasSize(6);
        assertThat(t.getColumns().get(1).getText()).isEqualTo("Control (n=3)");
        assertThat(t.getColumns().get(2).getText()).isEqualTo("Treated (n=3)");
        assertThat(t.getColumns().get(3).getText()).isEqualTo("U");

        assertThat(rowTexts(t).get(0)).startsWith("Cluster 1 | 10.0% | 90.0%");
        assertThat(rowTexts(t).get(1)).startsWith("Cluster 2 | 90.0% | 10.0%");
        // 3 versus 3: the exact test bottoms out at 0.1, and it must print that
        // rather than the 0.047 a normal approximation would give.
        assertThat(rowTexts(t).get(0)).contains("0.100");
    }

    @Test
    void theRenamedClusterNameReachesTheTable() {
        ClusterGroupComparisonPanel p = FxHarness.onFxGet(() -> {
            ClusterGroupComparisonPanel q = new ClusterGroupComparisonPanel(
                    labels(), 2, imageIds(), metadata(),
                    c -> c == 0 ? "Exhausted CD8" : "Macrophage");
            FxHarness.layOutOffscreen(q);
            return q;
        });
        FxHarness.onFx(() -> comboOf(p).setValue("condition"));
        assertThat(rowTexts(tableOf(p)).get(0)).startsWith("Exhausted CD8 |");
    }

    // ---- The warnings are shown, not buried ----

    @Test
    void theUnreachableSignificanceWarningIsVisibleAboveTheNumbers() {
        ClusterGroupComparisonPanel p = panel(metadata());
        FxHarness.onFx(() -> comboOf(p).setValue("condition"));

        Label warn = labelOf(p, "warnings");
        assertThat(warn.isVisible()).isTrue();
        assertThat(warn.isManaged()).isTrue();
        assertThat(warn.getText())
                .contains("No cluster can reach p < 0.05")
                .contains("compositional")
                .contains("batch effect");

        Label summary = labelOf(p, "summary");
        assertThat(summary.getText())
                .contains("Mann-Whitney U")
                .contains("Control n=3")
                .contains("Benjamini-Hochberg");
    }

    @Test
    void droppedImagesAreReportedRatherThanSilentlyExcluded() {
        Map<String, Map<String, String>> meta = metadata();
        meta.get("img2").remove("condition");          // untagged
        ClusterGroupComparisonPanel p = panel(meta);
        FxHarness.onFx(() -> comboOf(p).setValue("condition"));

        assertThat(labelOf(p, "warnings").getText())
                .contains("1 image(s) have no value for 'condition'");
        assertThat(labelOf(p, "summary").getText()).contains("Control n=2");
    }

    // ---- It refuses rather than inventing a comparison ----

    @Test
    void aKeyThatDoesNotSplitTheImagesIsRefusedWithAnExplanation() {
        ClusterGroupComparisonPanel p = panel(metadata());
        FxHarness.onFx(() -> comboOf(p).setValue("scanner"));

        assertThat(tableOf(p).getItems()).isEmpty();
        assertThat(labelOf(p, "warnings").getText())
                .contains("puts every remaining image in the same group")
                .contains("Axio");
    }

    @Test
    void switchingKeysRecomputesInsteadOfLeavingStaleNumbers() {
        ClusterGroupComparisonPanel p = panel(metadata());
        FxHarness.onFx(() -> comboOf(p).setValue("condition"));
        assertThat(tableOf(p).getItems()).hasSize(2);

        FxHarness.onFx(() -> comboOf(p).setValue("scanner"));
        assertThat(tableOf(p).getItems()).isEmpty();
        assertThat(tableOf(p).getColumns()).isEmpty();

        FxHarness.onFx(() -> comboOf(p).setValue("condition"));
        assertThat(tableOf(p).getItems()).hasSize(2);
    }

    @Test
    void aProjectWithNoMetadataOffersNoKeys() {
        Map<String, Map<String, String>> bare = new LinkedHashMap<>();
        for (int img = 0; img < 6; img++) {
            bare.put("img" + img, Map.of());
        }
        ClusterGroupComparisonPanel p = panel(bare);
        assertThat(p.availableKeys()).isEmpty();
        assertThat(tableOf(p).getItems()).isEmpty();
        assertThat(ClusterGroupComparisonPanel.metadataKeysOf(bare)).isEmpty();
    }

    @Test
    void keysAreOfferedSortedAndTheFirstIsSelectedOnOpen() {
        ClusterGroupComparisonPanel p = panel(metadata());
        assertThat(p.availableKeys()).containsExactly("condition", "scanner");
        // "condition" splits the images, so the tab opens on a real comparison
        // rather than making the user find the key that works.
        assertThat(comboOf(p).getValue()).isEqualTo("condition");
        assertThat(tableOf(p).getItems()).hasSize(2);
    }

    // ---- Three groups ----

    @Test
    void threeGroupsSwitchTheReportedTestToKruskalWallis() {
        Map<String, Map<String, String>> meta = new LinkedHashMap<>();
        String[] dose = {"Low", "Low", "Mid", "Mid", "High", "High"};
        for (int img = 0; img < 6; img++) {
            meta.put("img" + img, Map.of("dose", dose[img]));
        }
        ClusterGroupComparisonPanel p = panel(meta);
        FxHarness.onFx(() -> comboOf(p).setValue("dose"));

        TableView<String[]> t = tableOf(p);
        assertThat(t.getColumns()).hasSize(7);          // cluster + 3 groups + H + p + q
        assertThat(t.getColumns().get(4).getText()).isEqualTo("H");
        assertThat(labelOf(p, "summary").getText()).contains("Kruskal-Wallis H");
        // The min-achievable bound is only defined for two groups, so the
        // warning that quotes it must not appear here claiming 0.000.
        assertThat(labelOf(p, "summary").getText())
                .doesNotContain("Smallest p this design can return");
    }
}
