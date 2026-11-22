package qupath.ext.qpcat.batch;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.ClassConditionKind;
import qupath.ext.qpcat.model.PhenotypeRuleSet;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Class conditions on a phenotyping rule (issues #27 and #28): the YAML keys reach
 * the payload under the keys the worker reads, a saved rule set round-trips them,
 * an older saved rule set without them still loads as "no condition", and the
 * component split agrees with QuPath's derived-class separator.
 */
class PhenotypeClassConditionsTest {

    @Test
    void classIsAndIsNotReachThePayloadAsLists() {
        BatchYamlSchema.PhenotypeRuleEntry r = new BatchYamlSchema.PhenotypeRuleEntry();
        r.setName("Ki67 tumor");
        r.setRequireMarkers(List.of("Ki67"));
        r.setClassCondition(ClassConditionKind.IS, List.of("Tumor"));
        r.setClassCondition(ClassConditionKind.IS_NOT, List.of("Immune", "Stroma"));
        String json = HeadlessPhenotypingWorkflow.buildRulesJson(List.of(r));
        assertThat(json)
                .contains("\"Ki67\":\"pos\"")
                .contains("\"classIs\":[\"Tumor\"]")
                .contains("\"classIsNot\":[\"Immune\",\"Stroma\"]")
                .doesNotContain("classHas")
                .doesNotContain("classLacks");
    }

    @Test
    void classHasAndLacksReachThePayloadAsLists() {
        BatchYamlSchema.PhenotypeRuleEntry r = new BatchYamlSchema.PhenotypeRuleEntry();
        r.setName("CD45+ Ki67-");
        r.setClassCondition(ClassConditionKind.HAS, List.of("CD45"));
        r.setClassCondition(ClassConditionKind.LACKS, List.of("Ki67"));
        String json = HeadlessPhenotypingWorkflow.buildRulesJson(List.of(r));
        assertThat(json)
                .contains("\"classHas\":[\"CD45\"]")
                .contains("\"classLacks\":[\"Ki67\"]")
                .doesNotContain("classIs");
    }

    @Test
    void rulesWithoutClassConditionsCarryNoClassKeys() {
        BatchYamlSchema.PhenotypeRuleEntry r = new BatchYamlSchema.PhenotypeRuleEntry();
        r.setName("T");
        r.setRequireMarkers(List.of("CD3"));
        assertThat(HeadlessPhenotypingWorkflow.buildRulesJson(List.of(r)))
                .doesNotContain("class");
    }

    @Test
    void yamlKeysParseIntoTheirKinds() {
        BatchYamlParser.ParseOutcome po = BatchYamlParser.parseString(
                "version: '1.0'\n"
                        + "scope: { projects: [/tmp/p] }\n"
                        + "phenotyping:\n"
                        + "  enabled: true\n"
                        + "  rules:\n"
                        + "    - name: CD45+ Ki67-\n"
                        + "      require_markers: [CD3]\n"
                        + "      class_is: [Immune]\n"
                        + "      class_is_not: [Tumor]\n"
                        + "      class_has: [CD45]\n"
                        + "      class_lacks: [Ki67, CD8]\n");
        assertThat(po.getIssues().stream()
                .anyMatch(i -> i.getSeverity() == ValidationIssue.Severity.ERROR))
                .isFalse();
        Map<ClassConditionKind, List<String>> cc =
                po.getSchema().getPhenotyping().getRules().get(0).getClassConditions();
        assertThat(cc.get(ClassConditionKind.IS)).containsExactly("Immune");
        assertThat(cc.get(ClassConditionKind.IS_NOT)).containsExactly("Tumor");
        assertThat(cc.get(ClassConditionKind.HAS)).containsExactly("CD45");
        assertThat(cc.get(ClassConditionKind.LACKS)).containsExactly("Ki67", "CD8");
    }

    @Test
    void aMisspelledClassKeyIsRejectedWithASuggestion() {
        BatchYamlParser.ParseOutcome po = BatchYamlParser.parseString(
                "version: '1.0'\n"
                        + "scope: { projects: [/tmp/p] }\n"
                        + "phenotyping:\n"
                        + "  enabled: true\n"
                        + "  rules:\n"
                        + "    - name: X\n"
                        + "      require_markers: [CD3]\n"
                        + "      class_have: [CD45]\n");
        assertThat(po.getIssues())
                .anyMatch(i -> i.getSeverity() == ValidationIssue.Severity.ERROR
                        && i.getFieldPath().endsWith("class_have")
                        && i.getMessage().contains("class_has"));
    }

    @Test
    void savedRuleSetRoundTripsClassConditions() {
        PhenotypeRuleSet set = new PhenotypeRuleSet("with classes");
        PhenotypeRuleSet.RuleEntry e = new PhenotypeRuleSet.RuleEntry("Ki67 tumor", Map.of("Ki67", "pos"));
        e.setClassCondition(ClassConditionKind.IS, List.of("Tumor"));
        e.setClassCondition(ClassConditionKind.IS_NOT, List.of());
        e.setClassCondition(ClassConditionKind.HAS, List.of("CD45"));
        e.setClassCondition(ClassConditionKind.LACKS, List.of("Ki67"));
        set.getRules().add(e);
        Gson gson = new Gson();
        String json = gson.toJson(set);
        PhenotypeRuleSet back = gson.fromJson(json, PhenotypeRuleSet.class);
        Map<ClassConditionKind, List<String>> cc = back.getRules().get(0).getClassConditions();
        assertThat(cc.get(ClassConditionKind.IS)).containsExactly("Tumor");
        assertThat(cc.get(ClassConditionKind.IS_NOT)).isEmpty();
        assertThat(cc.get(ClassConditionKind.HAS)).containsExactly("CD45");
        assertThat(cc.get(ClassConditionKind.LACKS)).containsExactly("Ki67");
        assertThat(json).doesNotContain("classIsNot")
                .contains("\"classHas\":[\"CD45\"]")
                .contains("\"classLacks\":[\"Ki67\"]");
    }

    @Test
    void aRuleSetSavedBeforeClassConditionsReadsAsNone() {
        String old = "{\"version\":1,\"name\":\"old\",\"markers\":[\"CD3\"],\"gates\":{},"
                + "\"rules\":[{\"cellType\":\"T\",\"conditions\":{\"CD3\":\"pos\"},\"classIs\":[\"Tumor\"]}]}";
        PhenotypeRuleSet set = new Gson().fromJson(old, PhenotypeRuleSet.class);
        Map<ClassConditionKind, List<String>> cc = set.getRules().get(0).getClassConditions();
        assertThat(cc.get(ClassConditionKind.IS)).containsExactly("Tumor");
        assertThat(cc.get(ClassConditionKind.IS_NOT)).isEmpty();
        assertThat(cc.get(ClassConditionKind.HAS)).isEmpty();
        assertThat(cc.get(ClassConditionKind.LACKS)).isEmpty();
    }

    @Test
    void componentsSplitOnQuPathsDerivedClassSeparator() {
        assertThat(ClassConditionKind.componentsOf("CD45: Ki67")).containsExactly("CD45", "Ki67");
        assertThat(ClassConditionKind.componentsOf("CD45")).containsExactly("CD45");
        assertThat(ClassConditionKind.componentsOf("CD45: Positive")).containsExactly("CD45", "Positive");
        // A colon without the following space is part of the name, as in QuPath.
        assertThat(ClassConditionKind.componentsOf("Tumor:Ki67")).containsExactly("Tumor:Ki67");
        assertThat(ClassConditionKind.componentsOf("")).isEmpty();
        assertThat(ClassConditionKind.componentsOf(null)).isEmpty();
    }

    @Test
    void yamlKeysAndJsonKeysAreDistinctPerKind() {
        assertThat(ClassConditionKind.yamlKeys())
                .containsExactly("class_is", "class_is_not", "class_has", "class_lacks");
        assertThat(ClassConditionKind.fromYamlKey("class_lacks")).isEqualTo(ClassConditionKind.LACKS);
        assertThat(ClassConditionKind.fromYamlKey("require_markers")).isNull();
    }
}
