package qupath.ext.qpcat.batch;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A phenotyping rule's name becomes the cells' class, so the dry run refuses one
 * QuPath would read differently (E026) instead of letting the run stop on it.
 */
class PhenotypeRuleNameTest {

    private static BatchYamlSchema schemaWith(String... names) {
        List<BatchYamlSchema.PhenotypeRuleEntry> rules = new ArrayList<>();
        for (String name : names) {
            BatchYamlSchema.PhenotypeRuleEntry r = new BatchYamlSchema.PhenotypeRuleEntry();
            r.setName(name);
            r.setRequireMarkers(List.of("CD3"));
            rules.add(r);
        }
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

    private static List<String> nameErrors(BatchYamlSchema s) {
        List<String> out = new ArrayList<>();
        for (ValidationIssue i : BatchYamlValidator.validate(s).getIssues()) {
            if ("E026".equals(i.getCode())) out.add(i.getMessage());
        }
        return out;
    }

    @Test
    void ordinaryAndSharedNamesPass() {
        assertThat(nameErrors(schemaWith("T_cell", "CD8+ T", "T_cell"))).isEmpty();
    }

    @Test
    void aColonAReservedWordAndACaseOnlyVariantAreRefused() {
        assertThat(nameErrors(schemaWith("CD8:T"))).singleElement()
                .asString().contains("derived class").contains("CD8-T");
        assertThat(nameErrors(schemaWith("Unknown"))).singleElement()
                .asString().contains("reserved");
        assertThat(nameErrors(schemaWith("CD8", "cd8"))).singleElement()
                .asString().contains("differ only in case");
    }
}
