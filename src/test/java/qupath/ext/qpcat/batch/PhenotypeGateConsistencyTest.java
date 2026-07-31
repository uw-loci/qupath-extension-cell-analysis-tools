package qupath.ext.qpcat.batch;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The YAML schema is more expressive than the phenotyping engine, and these pin
 * where that gap is refused rather than approximated.
 *
 * <p>YAML gives each rule its own z-score threshold; {@code run_phenotyping.py}
 * keeps one gate per marker for all rules. A marker gated at two different
 * thresholds therefore has no faithful representation. Because rules are
 * first-match-wins, quietly picking one threshold re-routes cells into a
 * different phenotype and still produces plausible counts -- so E021 rejects the
 * config instead.
 */
class PhenotypeGateConsistencyTest {

    private static BatchYamlSchema.PhenotypeRuleEntry rule(
            String name, List<String> require, double requireZ,
            List<String> exclude, double excludeZ) {
        BatchYamlSchema.PhenotypeRuleEntry r = new BatchYamlSchema.PhenotypeRuleEntry();
        r.setName(name);
        r.setRequireMarkers(require);
        r.setRequireMinZscore(requireZ);
        if (exclude != null) r.setExcludeMarkers(exclude);
        r.setExcludeMaxZscore(excludeZ);
        return r;
    }

    private static BatchYamlSchema schemaWith(List<BatchYamlSchema.PhenotypeRuleEntry> rules) {
        BatchYamlSchema s = new BatchYamlSchema();
        s.setVersion("1.0");
        BatchYamlSchema.ScopeBlock scope = new BatchYamlSchema.ScopeBlock();
        scope.setProjects(List.of("/tmp/project"));
        s.setScope(scope);
        BatchYamlSchema.PhenotypingBlock p = new BatchYamlSchema.PhenotypingBlock();
        p.setEnabled(true);
        p.setRules(rules);
        s.setPhenotyping(p);
        return s;
    }

    private static List<String> gateErrors(BatchYamlSchema s) {
        List<String> out = new ArrayList<>();
        for (ValidationIssue i : BatchYamlValidator.validate(s).getIssues()) {
            if ("E021".equals(i.getCode())) out.add(i.getMessage());
        }
        return out;
    }

    @Test
    void defaultThresholdsNeverConflict() {
        // The overwhelmingly common case: nobody sets the z-scores, so every
        // marker resolves to 1.0 regardless of rule or direction. Strictness must
        // cost these configs nothing.
        var rules = List.of(
                rule("T_cell", List.of("CD3"), 1.0, List.of("PanCK"), 1.0),
                rule("Tumor", List.of("PanCK"), 1.0, null, 1.0),
                rule("B_cell", List.of("CD20"), 1.0, null, 1.0));
        assertThat(gateErrors(schemaWith(rules))).isEmpty();
    }

    @Test
    void sameMarkerAtTwoRequireThresholdsIsRejected() {
        // CD3 gated at 1.0 for T_cell but 2.0 for Activated_T. One gate per
        // marker means one of those rules cannot mean what it says.
        var rules = List.of(
                rule("T_cell", List.of("CD3"), 1.0, null, 1.0),
                rule("Activated_T", List.of("CD3", "Ki67"), 2.0, null, 1.0));
        List<String> errors = gateErrors(schemaWith(rules));
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0))
                .contains("CD3")
                .contains("1.0")
                .contains("2.0")
                .contains("T_cell")
                .contains("Activated_T");
    }

    @Test
    void requireAndExcludeThresholdsOnOneMarkerConflict() {
        // The subtler source: require_min_zscore and exclude_max_zscore are
        // separate YAML fields that default independently, so a marker used as
        // "exclude" in one rule and "require" in another easily ends up with two
        // thresholds without anyone intending it.
        var rules = List.of(
                rule("T_cell", List.of("CD3"), 1.0, List.of("PanCK"), 0.5),
                rule("Tumor", List.of("PanCK"), 1.0, null, 1.0));
        assertThat(gateErrors(schemaWith(rules)))
                .singleElement().asString().contains("PanCK");
    }

    @Test
    void sameMarkerAtTheSameThresholdIsFine() {
        // Reusing a marker is normal and must stay allowed; only DISAGREEING
        // thresholds are the problem.
        var rules = List.of(
                rule("T_cell", List.of("CD3"), 1.5, null, 1.0),
                rule("Cytotoxic_T", List.of("CD3", "CD8"), 1.5, null, 1.0));
        assertThat(gateErrors(schemaWith(rules))).isEmpty();
    }

    @Test
    void errorNamesEveryConflictingMarker() {
        var rules = List.of(
                rule("A", List.of("CD3", "CD8"), 1.0, null, 1.0),
                rule("B", List.of("CD3", "CD8"), 2.0, null, 1.0));
        assertThat(gateErrors(schemaWith(rules))).hasSize(2);
    }

    @Test
    void rulesToJsonPreserveOrderAndDirection() {
        var rules = List.of(
                rule("T_cell", List.of("CD3"), 1.0, List.of("PanCK"), 1.0),
                rule("Tumor", List.of("PanCK"), 1.0, null, 1.0));
        String json = HeadlessPhenotypingWorkflow.buildRulesJson(rules);
        // First-match-wins, so T_cell must come first.
        assertThat(json.indexOf("T_cell")).isLessThan(json.indexOf("Tumor"));
        assertThat(json).contains("\"CD3\":\"pos\"").contains("\"PanCK\":\"neg\"");
    }

    @Test
    void gatesJsonCarriesThresholdsPerMarker() {
        var rules = List.of(rule("T_cell", List.of("CD3"), 1.5, List.of("PanCK"), 0.25));
        String json = HeadlessPhenotypingWorkflow.buildGatesJson(rules, new ArrayList<>());
        assertThat(json).contains("\"CD3\":1.5").contains("\"PanCK\":0.25");
    }
}
