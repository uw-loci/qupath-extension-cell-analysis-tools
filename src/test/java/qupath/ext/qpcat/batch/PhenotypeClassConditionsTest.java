package qupath.ext.qpcat.batch;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.PhenotypeRuleSet;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Class conditions on a phenotyping rule (issue #27): the YAML keys reach the
 * payload, a saved rule set round-trips them, and an older saved rule set
 * without them still loads as "no condition".
 */
class PhenotypeClassConditionsTest {

    @Test
    void classIsAndIsNotReachThePayloadAsLists() {
        BatchYamlSchema.PhenotypeRuleEntry r = new BatchYamlSchema.PhenotypeRuleEntry();
        r.setName("Ki67 tumor");
        r.setRequireMarkers(List.of("Ki67"));
        r.setClassIs(List.of("Tumor"));
        r.setClassIsNot(List.of("Immune", "Stroma"));
        String json = HeadlessPhenotypingWorkflow.buildRulesJson(List.of(r));
        assertThat(json)
                .contains("\"Ki67\":\"pos\"")
                .contains("\"classIs\":[\"Tumor\"]")
                .contains("\"classIsNot\":[\"Immune\",\"Stroma\"]");
    }

    @Test
    void rulesWithoutClassConditionsCarryNoClassKeys() {
        BatchYamlSchema.PhenotypeRuleEntry r = new BatchYamlSchema.PhenotypeRuleEntry();
        r.setName("T");
        r.setRequireMarkers(List.of("CD3"));
        assertThat(HeadlessPhenotypingWorkflow.buildRulesJson(List.of(r)))
                .doesNotContain("classIs");
    }

    @Test
    void savedRuleSetRoundTripsClassConditions() {
        PhenotypeRuleSet set = new PhenotypeRuleSet("with classes");
        PhenotypeRuleSet.RuleEntry e = new PhenotypeRuleSet.RuleEntry("Ki67 tumor", Map.of("Ki67", "pos"));
        e.setClassIs(List.of("Tumor"));
        e.setClassIsNot(List.of());
        set.getRules().add(e);
        Gson gson = new Gson();
        PhenotypeRuleSet back = gson.fromJson(gson.toJson(set), PhenotypeRuleSet.class);
        assertThat(back.getRules().get(0).getClassIs()).containsExactly("Tumor");
        assertThat(back.getRules().get(0).getClassIsNot()).isEmpty();
        assertThat(gson.toJson(set)).doesNotContain("classIsNot");
    }

    @Test
    void aRuleSetSavedBeforeClassConditionsReadsAsNone() {
        String old = "{\"version\":1,\"name\":\"old\",\"markers\":[\"CD3\"],\"gates\":{},"
                + "\"rules\":[{\"cellType\":\"T\",\"conditions\":{\"CD3\":\"pos\"}}]}";
        PhenotypeRuleSet set = new Gson().fromJson(old, PhenotypeRuleSet.class);
        assertThat(set.getRules().get(0).getClassIs()).isEmpty();
        assertThat(set.getRules().get(0).getClassIsNot()).isEmpty();
    }
}
