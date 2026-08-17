package qupath.ext.qpcat.batch;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.AreaLevel;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * YAML surface for independent areas.
 * <p>
 * These keys are part of QP-CAT's public batch schema, so the failure modes
 * that matter are the quiet ones: a typo'd level accepted as something else,
 * or {@code batch_key: areas} falling back to per-image batches when no areas
 * were configured. Either would run to completion and produce a result the
 * file never asked for.
 */
class BatchYamlAreaLevelsTest {

    private static BatchYamlParser.ParseOutcome parse(String clusteringBody) {
        return BatchYamlParser.parseString(
                "version: '1.0'\n"
                + "scope:\n"
                + "  projects: [/tmp/p.qpproj]\n"
                + "clustering:\n"
                + "  type: leiden\n"
                + clusteringBody);
    }

    private static boolean hasError(java.util.List<ValidationIssue> issues, String path) {
        return issues.stream().anyMatch(
                i -> i.getSeverity() == ValidationIssue.Severity.ERROR
                        && i.getFieldPath().contains(path));
    }

    @Test
    void areaLevelsParseIntoOrderedSpecs() {
        BatchYamlParser.ParseOutcome outcome = parse(
                "  area_levels:\n"
                + "    - level: tma_cores\n"
                + "    - level: annotations\n"
                + "      annotation_classes: [Tissue, Region]\n");

        assertThat(outcome.getIssues()).isEmpty();
        var levels = outcome.getSchema().getClustering().getAreaLevels();
        assertThat(levels).hasSize(2);
        assertThat(levels.get(0).getLevel()).isEqualTo(AreaLevel.TMA_CORES);
        assertThat(levels.get(1).getLevel()).isEqualTo(AreaLevel.ANNOTATIONS);
        assertThat(levels.get(1).getAnnotationClasses()).containsExactly("Tissue", "Region");
    }

    @Test
    void anAbsentAreaLevelsBlockMeansImagesOnly() {
        BatchYamlParser.ParseOutcome outcome = parse("");
        assertThat(outcome.getIssues()).isEmpty();
        assertThat(outcome.getSchema().getClustering().getAreaLevels()).isEmpty();
    }

    @Test
    void anUnknownLevelIsRejectedRatherThanSilentlyIgnored() {
        BatchYamlParser.ParseOutcome outcome = parse(
                "  area_levels:\n    - level: cores\n");
        assertThat(hasError(outcome.getIssues(), "area_levels[0].level")).isTrue();
    }

    @Test
    void listingImagesExplicitlyIsRejectedBecauseItIsImplicit() {
        // Accepting it would imply the order is the user's to choose, when in
        // fact images is always outermost and never removable.
        BatchYamlParser.ParseOutcome outcome = parse(
                "  area_levels:\n    - level: images\n");
        assertThat(hasError(outcome.getIssues(), "area_levels[0].level")).isTrue();
    }

    @Test
    void aMissingLevelKeyIsAnError() {
        BatchYamlParser.ParseOutcome outcome = parse(
                "  area_levels:\n    - annotation_classes: [Tissue]\n");
        assertThat(hasError(outcome.getIssues(), "area_levels[0].level")).isTrue();
    }

    @Test
    void anUnknownFieldInsideALevelIsReportedWithItsIndex() {
        BatchYamlParser.ParseOutcome outcome = parse(
                "  area_levels:\n    - level: tma_cores\n      classes: [Tissue]\n");
        assertThat(hasError(outcome.getIssues(), "area_levels[0].classes")).isTrue();
    }

    @Test
    void aNonListAreaLevelsValueIsReported() {
        BatchYamlParser.ParseOutcome outcome = parse("  area_levels: tma_cores\n");
        assertThat(hasError(outcome.getIssues(), "area_levels")).isTrue();
    }

    // --- batch_key ------------------------------------------------------

    @Test
    void batchKeyAreasWithoutAreaLevelsIsRejected() {
        // The dangerous case: this would otherwise run and correct per image,
        // which is not what the file asked for.
        BatchYamlParser.ParseOutcome outcome = parse(
                "  batch_correction: true\n  batch_key: areas\n");
        ValidationResult result = BatchYamlValidator.validate(outcome.getSchema());
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getIssues()).anyMatch(
                i -> i.getFieldPath().equals("clustering.area_levels"));
    }

    @Test
    void batchKeyAreasWithAreaLevelsValidates() {
        BatchYamlParser.ParseOutcome outcome = parse(
                "  batch_correction: true\n"
                + "  batch_key: areas\n"
                + "  area_levels:\n    - level: tma_cores\n");
        ValidationResult result = BatchYamlValidator.validate(outcome.getSchema());
        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void anUnknownBatchKeyIsRejected() {
        BatchYamlParser.ParseOutcome outcome = parse("  batch_key: cores\n");
        ValidationResult result = BatchYamlValidator.validate(outcome.getSchema());
        assertThat(result.getIssues()).anyMatch(
                i -> i.getSeverity() == ValidationIssue.Severity.ERROR
                        && i.getFieldPath().equals("clustering.batch_key"));
    }

    @Test
    void batchKeyWithoutBatchCorrectionWarnsRatherThanFailing() {
        BatchYamlParser.ParseOutcome outcome = parse(
                "  batch_key: areas\n  area_levels:\n    - level: tma_cores\n");
        ValidationResult result = BatchYamlValidator.validate(outcome.getSchema());
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.getIssues()).anyMatch(
                i -> i.getSeverity() == ValidationIssue.Severity.WARNING
                        && i.getFieldPath().equals("clustering.batch_key"));
    }

    @Test
    void twoUnfilteredAnnotationLevelsWarnBecauseTheSecondCannotResolve() {
        BatchYamlParser.ParseOutcome outcome = parse(
                "  area_levels:\n"
                + "    - level: annotations\n"
                + "    - level: annotations\n");
        ValidationResult result = BatchYamlValidator.validate(outcome.getSchema());
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.getIssues()).anyMatch(
                i -> i.getSeverity() == ValidationIssue.Severity.WARNING
                        && i.getFieldPath().equals("clustering.area_levels"));
    }
}
