package qupath.ext.qpcat.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pre-run cost summary must keep "not reproducible" and "not comparable"
 * apart. Saying a seeded, fully repeatable option makes a run non-reproducible
 * overstates the cost, and would push users away from a setting that is safe to
 * use as long as they do not mix it with runs that lack it.
 */
class RunCostSummaryTest {

    private static ClusteringConfig defaults() {
        ClusteringConfig c = new ClusteringConfig();
        c.setEmbeddingMethod(ClusteringConfig.EmbeddingMethod.UMAP);
        c.setEmbeddingExecutionMode("reproducible");
        return c;
    }

    @Test
    void aPlainRunReportsNoCostsButStillSaysSomething() {
        String line = RunCostSummary.describeLine(defaults());
        assertThat(RunCostSummary.describe(defaults())).isEmpty();
        // "No costs" and "nothing was checked" must not look the same.
        assertThat(line).contains("repeatable").contains("comparable");
    }

    @Test
    void onlyTheUnseededUmapPathIsCalledNotReproducible() {
        ClusteringConfig fast = defaults();
        fast.setEmbeddingExecutionMode("fast");
        assertThat(RunCostSummary.describe(fast))
                .anySatisfy(s -> assertThat(s).contains("NOT reproducible"));
    }

    @Test
    void thePrecursorIsComparabilityNotReproducibility() {
        ClusteringConfig c = defaults();
        c.setPcaPrecursor(true);
        String line = RunCostSummary.describeLine(c);
        assertThat(line).contains("not comparable");
        assertThat(line)
                .as("the precursor is seeded; it must never be described as non-reproducible")
                .doesNotContain("NOT reproducible");
        assertThat(line).contains("repeatable");
    }

    @Test
    void spatialSmoothingIsAlsoComparabilityOnly() {
        ClusteringConfig c = defaults();
        c.setEnableSpatialSmoothing(true);
        String line = RunCostSummary.describeLine(c);
        assertThat(line).contains("not comparable").doesNotContain("NOT reproducible");
    }

    @Test
    void autoModeQualifiesItsClaimByCellCount() {
        ClusteringConfig c = defaults();
        c.setEmbeddingExecutionMode("auto");
        assertThat(RunCostSummary.describeLine(c))
                .contains("above")
                .contains("200,000");
    }

    @Test
    void aNonUmapEmbeddingCarriesNoUmapCost() {
        ClusteringConfig c = defaults();
        c.setEmbeddingMethod(ClusteringConfig.EmbeddingMethod.PCA);
        c.setEmbeddingExecutionMode("fast");
        assertThat(RunCostSummary.describe(c))
                .as("the execution mode only governs UMAP")
                .isEmpty();
    }

    @Test
    void costsAccumulate() {
        ClusteringConfig c = defaults();
        c.setEmbeddingExecutionMode("fast");
        c.setPcaPrecursor(true);
        c.setEnableSpatialSmoothing(true);
        assertThat(RunCostSummary.describe(c)).hasSize(3);
    }

    @Test
    void nullConfigIsEmptyRatherThanThrowing() {
        assertThat(RunCostSummary.describe(null)).isEmpty();
    }
}
