package qupath.ext.qpcat.controller;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.ClusteringConfig;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a class subset is and is not allowed to be combined with.
 *
 * <p>The dialog greys the spatial statistics out, so a run started there cannot
 * ask for both. A hand-edited {@code _config.json} or a scripted config can,
 * which is why the rule lives in the workflow rather than only in the UI.
 * Refusing beats silently switching someone's requested analysis off.
 */
class ClassSubsetRulesTest {

    private static ClusteringConfig subsetOf(String... classes) {
        ClusteringConfig config = new ClusteringConfig();
        config.setIncludedClasses(List.of(classes));
        return config;
    }

    @Test
    void anUnrestrictedRunIsNeverRefused() {
        ClusteringConfig config = new ClusteringConfig();
        config.setEnableSpatialAnalysis(true);
        config.setEnableRipley(true);
        config.setEnableGeary(true);
        config.setEnableCoOccurrencePairwise(true);
        config.setEnableCoOccurrenceOneVsRest(true);

        assertThatCode(() -> ClusteringWorkflow.enforceClassSubsetRules(config))
                .doesNotThrowAnyException();
    }

    @Test
    void everySpatialStatisticIsRefusedOnASubsetAndNamedInTheMessage() {
        record Case(String label, java.util.function.Consumer<ClusteringConfig> enable) {}
        List<Case> cases = List.of(
                new Case("neighborhood enrichment / Moran's I",
                        c -> c.setEnableSpatialAnalysis(true)),
                new Case("Ripley's L", c -> c.setEnableRipley(true)),
                new Case("Geary's C", c -> c.setEnableGeary(true)),
                new Case("co-occurrence (pairwise)", c -> c.setEnableCoOccurrencePairwise(true)),
                new Case("co-occurrence (one vs rest)",
                        c -> c.setEnableCoOccurrenceOneVsRest(true)));

        for (Case one : cases) {
            ClusteringConfig config = subsetOf("Tumor");
            one.enable().accept(config);
            assertThatThrownBy(() -> ClusteringWorkflow.enforceClassSubsetRules(config))
                    .as("subset + %s", one.label())
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(one.label())
                    .hasMessageContaining("neighbour graph");
        }
    }

    @Test
    void aSubsetThatSelectsNothingIsRefusedBeforeAnythingElse() {
        ClusteringConfig config = new ClusteringConfig();
        config.setIncludedClasses(List.of());

        assertThatThrownBy(() -> ClusteringWorkflow.enforceClassSubsetRules(config))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("No classifications are selected");

        // Unclassified alone is a population, so this is no longer empty.
        config.setIncludeUnclassified(true);
        assertThatCode(() -> ClusteringWorkflow.enforceClassSubsetRules(config))
                .doesNotThrowAnyException();
    }

    @Test
    void banksyAndSpatialSmoothingStayAllowedOnASubset() {
        // Deliberate: their output is cluster labels, not a reported tissue-level
        // number, so "sub-structure within this population, using its own spatial
        // arrangement" is coherent. RUN_INFO states what the graph spanned.
        ClusteringConfig config = subsetOf("Tumor");
        config.setAlgorithm(ClusteringConfig.Algorithm.BANKSY);
        config.setEnableSpatialSmoothing(true);

        assertThatCode(() -> ClusteringWorkflow.enforceClassSubsetRules(config))
                .doesNotThrowAnyException();
    }

    @Test
    void aNullConfigIsNotAnError() {
        assertThatCode(() -> ClusteringWorkflow.enforceClassSubsetRules(null))
                .doesNotThrowAnyException();
    }

    @Test
    void theRefusalNamesTheStatisticsRatherThanJustSayingNo() {
        ClusteringConfig config = subsetOf("Tumor", "Stroma");
        config.setEnableRipley(true);
        config.setEnableGeary(true);

        assertThatThrownBy(() -> ClusteringWorkflow.enforceClassSubsetRules(config))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Ripley's L, Geary's C");

        assertThat(config.describeClassSubset()).isEqualTo("Tumor, Stroma");
    }
}
