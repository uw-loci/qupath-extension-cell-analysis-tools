package qupath.ext.qpcat.batch;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Batch measurement and rule-marker names are resolved against the images before
 * any work (E010, H01), and an omitted clustering.measurements means the
 * documented default (H03).
 */
class BatchMeasurementsTest {

    private static final List<String> QUPATH_CELL = List.of(
            "Centroid X um", "Centroid Y um", "Cell: Area um^2",
            "Nucleus: CD3 mean", "Cell: CD3 mean", "Cytoplasm: CD3 mean", "Cell: CD3 std dev",
            "Cell: CD8 mean", "Nucleus: CD8 mean",
            "QPCAT UMAP1", "QPCAT spatial: Mean distance");

    @SafeVarargs
    private static BatchMeasurements catalog(List<String>... perImage) {
        Map<String, Set<String>> byImage = new LinkedHashMap<>();
        for (int i = 0; i < perImage.length; i++) {
            byImage.put("image_" + (i + 1), new LinkedHashSet<>(perImage[i]));
        }
        return new BatchMeasurements(byImage, List.of());
    }

    private static BatchYamlSchema schema(String yaml) {
        BatchYamlParser.ParseOutcome po = BatchYamlParser.parseString(
                "version: '1.0'\nscope: { projects: [/tmp/p] }\n" + yaml);
        assertThat(po.getIssues()).isEmpty();
        return po.getSchema();
    }

    // ---- resolve ----

    @Test
    void exactNameResolvesToItself() {
        var r = BatchMeasurements.resolve("Cell: CD3 mean", QUPATH_CELL);
        assertThat(r.resolved()).isEqualTo("Cell: CD3 mean");
    }

    @Test
    void theShortNameTheDialogShowsResolvesWhenUnique() {
        // MeasurementNames.shorten("Nucleus: CD3 mean") == "Nucleus CD3 mean"
        var r = BatchMeasurements.resolve("Nucleus CD3 mean", QUPATH_CELL);
        assertThat(r.resolved()).isEqualTo("Nucleus: CD3 mean");
    }

    @Test
    void aShortNameShortForSeveralIsAmbiguous() {
        // "Cell: CD3: Mean" and "Detection: CD3: Mean" both shorten to "CD3 Mean".
        var r = BatchMeasurements.resolve("CD3 Mean",
                List.of("Cell: CD3: Mean", "Detection: CD3: Mean", "Cell: CD8: Mean"));
        assertThat(r.resolved()).isNull();
        assertThat(r.ambiguous()).isTrue();
        assertThat(r.candidates()).containsExactly("Cell: CD3: Mean", "Detection: CD3: Mean");
    }

    @Test
    void aBareMarkerIsNotResolvedButItsMeasurementsAreOffered() {
        var r = BatchMeasurements.resolve("CD3", QUPATH_CELL);
        assertThat(r.resolved()).isNull();
        assertThat(r.ambiguous()).isFalse();
        assertThat(r.candidates()).containsExactly("Nucleus: CD3 mean", "Cell: CD3 mean",
                "Cytoplasm: CD3 mean", "Cell: CD3 std dev");
    }

    // ---- phenotyping rules ----

    @Test
    void ruleMarkerWrittenAsTheBareMarkerIsE010ListingCandidates() {
        var plan = catalog(QUPATH_CELL).plan(schema("phenotyping:\n  rules:\n"
                + "    - name: T\n      require_markers: [CD3]\n"), false, "/p");
        assertThat(plan.hasErrors()).isTrue();
        assertThat(plan.getIssues()).singleElement().satisfies(i -> {
            assertThat(i.getCode()).isEqualTo("E010");
            assertThat(i.getFieldPath()).isEqualTo("phenotyping.rules[0].require_markers");
            assertThat(i.getMessage()).contains("'CD3'").contains("Cell: CD3 mean")
                    .contains("Nucleus: CD3 mean").contains("full name");
        });
    }

    @Test
    void ruleMarkerNotInTheDataIsE010WithASuggestion() {
        var plan = catalog(QUPATH_CELL).plan(schema("phenotyping:\n  rules:\n"
                + "    - name: T\n      require_markers: ['Cell: CD3 mean']\n"
                + "      exclude_markers: ['Cell: CD8 maen']\n"), false, "/p");
        assertThat(plan.getIssues()).singleElement().satisfies(i -> {
            assertThat(i.getCode()).isEqualTo("E010");
            assertThat(i.getFieldPath()).isEqualTo("phenotyping.rules[0].exclude_markers");
            assertThat(i.getMessage()).contains("Did you mean 'Cell: CD8 mean'");
        });
    }

    @Test
    void resolvedShortNamesReachTheRulesAndTheMeasurementList() {
        var plan = catalog(QUPATH_CELL).plan(schema("phenotyping:\n  rules:\n"
                + "    - name: T\n      require_markers: ['Nucleus CD3 mean']\n"
                + "      exclude_markers: ['Cell: CD8 mean']\n"
                + "    - name: N\n      any_markers: ['Nucleus CD8 mean', 'Cell: CD8 mean']\n"),
                false, "/p");
        assertThat(plan.getIssues()).isEmpty();
        assertThat(plan.getRules().get(0).getRequireMarkers()).containsExactly("Nucleus: CD3 mean");
        assertThat(plan.getRules().get(1).getAnyMarkers())
                .containsExactly("Nucleus: CD8 mean", "Cell: CD8 mean");
        assertThat(plan.getPhenotypingMeasurements())
                .containsExactly("Nucleus: CD3 mean", "Cell: CD8 mean", "Nucleus: CD8 mean");
        assertThat(plan.getNotes()).anyMatch(n -> n.contains("'Nucleus CD3 mean' resolved to "
                + "'Nucleus: CD3 mean'"));
    }

    @Test
    void ruleMarkerMissingFromOneImageIsE010() {
        List<String> without = List.of("Cell: CD8 mean");
        var plan = catalog(QUPATH_CELL, without).plan(schema("phenotyping:\n  rules:\n"
                + "    - name: T\n      require_markers: ['Cell: CD3 mean']\n"), false, "/p");
        assertThat(plan.getIssues()).singleElement().satisfies(i -> {
            assertThat(i.getCode()).isEqualTo("E010");
            assertThat(i.getMessage()).contains("missing from 1 of 2 images").contains("image_2");
        });
    }

    @Test
    void twoSpellingsOfOneMarkerAtDifferentGatesAreE021() {
        var plan = catalog(QUPATH_CELL).plan(schema("phenotyping:\n  rules:\n"
                + "    - name: A\n      require_markers: ['Nucleus CD3 mean']\n"
                + "      require_min_zscore: 1.0\n"
                + "    - name: B\n      require_markers: ['Nucleus: CD3 mean']\n"
                + "      require_min_zscore: 2.0\n"), false, "/p");
        assertThat(plan.getIssues()).anyMatch(i -> i.getCode().equals("E021"));
    }

    // ---- clustering measurements ----

    @Test
    void omittedMeasurementsAreEveryMeanQpcatDidNotWrite() {
        var plan = catalog(QUPATH_CELL).plan(schema("clustering: { type: leiden }\n"), true, "/p");
        assertThat(plan.isClusteringDefaulted()).isTrue();
        assertThat(plan.getClusteringMeasurements()).containsExactly(
                "Nucleus: CD3 mean", "Cell: CD3 mean", "Cytoplasm: CD3 mean",
                "Cell: CD8 mean", "Nucleus: CD8 mean");
        assertThat(plan.getNotes()).singleElement().asString()
                .contains("clustering.measurements not set").contains("(5)");
    }

    @Test
    void theDefaultOnlyTakesMeansPresentInEveryImage() {
        var plan = catalog(QUPATH_CELL, List.of("Cell: CD3 mean", "Centroid X um"))
                .plan(schema("clustering: { type: leiden }\n"), true, "/p");
        assertThat(plan.getClusteringMeasurements()).containsExactly("Cell: CD3 mean");
    }

    @Test
    void noMeanMeasurementAtAllIsE010() {
        var plan = catalog(List.of("Centroid X um", "Cell: Area um^2"))
                .plan(schema("clustering: { type: leiden }\n"), true, "/p");
        assertThat(plan.getIssues()).anyMatch(i -> i.getCode().equals("E010")
                && i.getFieldPath().equals("clustering.measurements"));
    }

    @Test
    void listedClusteringMeasurementsAreResolvedAndChecked() {
        var plan = catalog(QUPATH_CELL, List.of("Cell: CD3 mean", "Nucleus: CD3 mean"))
                .plan(schema("clustering:\n  type: leiden\n"
                        + "  measurements: ['Nucleus CD3 mean', 'Cell: CD8 mean', 'CD4']\n"),
                        true, "/p");
        assertThat(plan.getClusteringMeasurements())
                .containsExactly("Nucleus: CD3 mean", "Cell: CD8 mean");
        assertThat(plan.getIssues()).anyMatch(i -> i.getCode().equals("W010")
                && i.getMessage().contains("Cell: CD8 mean"));
        assertThat(plan.getIssues()).anyMatch(i -> i.getCode().equals("E010")
                && i.getFieldPath().equals("clustering.measurements[2]"));
    }

    @Test
    void twoEntriesResolvingToOneMeasurementAreE023() {
        var plan = catalog(QUPATH_CELL).plan(schema("clustering:\n  type: leiden\n"
                + "  measurements: ['Nucleus CD3 mean', 'Nucleus: CD3 mean']\n"), true, "/p");
        assertThat(plan.getIssues()).anyMatch(i -> i.getCode().equals("E023"));
    }

    @Test
    void classOnlyRulesReadTheClusteringPanel() {
        var plan = catalog(QUPATH_CELL).plan(schema("phenotyping:\n  rules:\n"
                + "    - name: Tumor\n      class_is: [Tumor]\n"), false, "/p");
        assertThat(plan.getIssues()).isEmpty();
        assertThat(plan.getPhenotypingMeasurements()).isNotEmpty()
                .allMatch(n -> n.contains("mean") && !n.contains("QPCAT"));
    }
}
