package qupath.ext.qpcat.batch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code any_markers}: the YAML side of the at-least-one group.
 * <p>
 * Before it existed, "Macrophage = CD68 or CD163 or CD206" had to be three rules
 * sharing a name -- the idiom whose counts silently overwrote each other until
 * 2026-08-19. A rule may now carry only {@code any_markers} and no
 * {@code require_markers}, so the validator has to accept either as the rule's
 * positive criterion.
 */
class PhenotypeAnyMarkersTest {

    private static BatchYamlSchema.PhenotypeRuleEntry rule(
            String name, List<String> require, List<String> any, List<String> exclude) {
        BatchYamlSchema.PhenotypeRuleEntry r = new BatchYamlSchema.PhenotypeRuleEntry();
        r.setName(name);
        r.setRequireMarkers(require);
        r.setAnyMarkers(any);
        r.setExcludeMarkers(exclude);
        return r;
    }

    @Test
    void anyMarkersBecomeAnyposInThePayload() {
        String json = HeadlessPhenotypingWorkflow.buildRulesJson(List.of(
                rule("Macrophage", List.of(), List.of("CD68", "CD163"), List.of())));
        assertThat(json).contains("\"CD68\":\"anypos\"").contains("\"CD163\":\"anypos\"");
    }

    @Test
    void requireAndAnyCoexistInOneRule() {
        String json = HeadlessPhenotypingWorkflow.buildRulesJson(List.of(
                rule("Mac", List.of("CD45"), List.of("CD68", "CD163"), List.of())));
        assertThat(json).contains("\"CD45\":\"pos\"").contains("\"CD68\":\"anypos\"");
    }

    /**
     * A marker in BOTH lists keeps the stricter reading. Relaxing a required
     * marker into an OR group would quietly widen the rule.
     */
    @Test
    void aMarkerInBothListsStaysRequired() {
        String json = HeadlessPhenotypingWorkflow.buildRulesJson(List.of(
                rule("Mac", List.of("CD68"), List.of("CD68", "CD163"), List.of())));
        assertThat(json).contains("\"CD68\":\"pos\"").doesNotContain("\"CD68\":\"anypos\"");
    }

    @Test
    void aRuleWithOnlyAnyMarkersValidates() {
        BatchYamlSchema cfg = new BatchYamlSchema();
        cfg.setPhenotyping(new BatchYamlSchema.PhenotypingBlock());
        cfg.getPhenotyping().setEnabled(true);
        cfg.getPhenotyping().setRules(List.of(
                rule("Macrophage", List.of(), List.of("CD68", "CD163"), List.of())));
        assertThat(errorsFor(cfg)).noneMatch(s -> s.contains("require_markers"));
    }

    @Test
    void aRuleWithNoPositiveCriterionAtAllStillFails() {
        BatchYamlSchema cfg = new BatchYamlSchema();
        cfg.setPhenotyping(new BatchYamlSchema.PhenotypingBlock());
        cfg.getPhenotyping().setEnabled(true);
        cfg.getPhenotyping().setRules(List.of(
                rule("Nothing", List.of(), List.of(), List.of("CD3"))));
        assertThat(errorsFor(cfg)).anyMatch(s -> s.contains("require_markers"));
    }

    private static List<String> errorsFor(BatchYamlSchema cfg) {
        return BatchYamlValidator.validate(cfg).getErrors().stream()
                .map(Object::toString)
                .toList();
    }
}
