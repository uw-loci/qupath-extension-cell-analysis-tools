package qupath.ext.qpcat.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The validation JSON from run_clustering.py, as the results tab reads it. */
class ValidationReportTest {

    // Shape of run_clustering.py's "validation" output (stability_across_seeds and
    // agreement_with_classes), trimmed to two clusters.
    static final String JSON = "{\"stability\":{\"n_runs\":3,\"coverage\":0.95,"
            + "\"pairwise_ari\":[1.0,0.9,0.9],\"ari_mean\":0.9333,\"ari_min\":0.9,"
            + "\"ari_vs_reference\":[1.0,0.9],\"consistency_overall\":1.25,"
            + "\"per_cluster\":[{\"cluster\":0,\"n_cells\":10,\"consistency\":1.0,"
            + "\"kept_together\":1.0},{\"cluster\":1,\"n_cells\":10,\"consistency\":1.5,"
            + "\"kept_together\":0.75}],\"seeds\":[43,44],\"reference_seed\":42},"
            + "\"agreement\":{\"n_cells\":20,\"n_unclassified_excluded\":2,\"ari\":0.81,"
            + "\"nmi\":0.8,\"kappa_matched\":0.9,\"matched_share\":0.95,\"purity\":0.95,"
            + "\"clusters\":[-1,0,1],\"classes\":[\"B cell\",\"Tumor\"],"
            + "\"contingency\":[[0,1],[10,0],[0,9]],"
            + "\"matching\":[{\"cluster\":0,\"class\":\"B cell\"},{\"cluster\":1,\"class\":\"Tumor\"}]}}";

    @Test
    void bothChecksAreReadAndSummarised() {
        ValidationReport r = ValidationReport.parse(JSON);
        assertThat(r).isNotNull();
        assertThat(r.stabilitySummary())
                .contains("3 runs: seed 42 plus 2 others")
                .contains("mean 0.933, lowest 0.900")
                .contains("Consistency score 1.25");
        assertThat(r.agreementSummary())
                .contains("20 cells compared, 2 unclassified excluded")
                .contains("Adjusted Rand index 0.810")
                .contains("Cohen's kappa 0.900");
    }

    @Test
    void theContingencyTableNamesTheMatchAndShowsNoise() {
        ValidationReport r = ValidationReport.parse(JSON);
        var rows = r.contingencyRows(c -> c < 0 ? "Noise" : "Cluster " + c);
        assertThat(rows.get(0)).containsExactly("Noise", "(none)", "0", "1");
        assertThat(rows.get(1)).containsExactly("Cluster 0", "B cell", "10", "0");
        assertThat(r.stabilityRows(c -> "C" + c).get(1)).containsExactly("C1", "10", "1.50", "75.0%");
    }

    @Test
    void theTsvCarriesBothTables() {
        String tsv = ValidationReport.parse(JSON).toTsv(c -> "Cluster " + c);
        assertThat(tsv).contains("Cluster\tCells\tConsistency\tKept together\n")
                .contains("Cluster\tMatched class\tB cell\tTumor\n")
                .contains("Cluster 1\tTumor\t0\t9\n");
    }

    @Test
    void nothingToShowIsNull() {
        assertThat(ValidationReport.parse(null)).isNull();
        assertThat(ValidationReport.parse("{}")).isNull();
        assertThat(ValidationReport.parse("not json")).isNull();
    }

    @Test
    void anAgreementWithNoClassifiedCellSaysSo() {
        ValidationReport r = ValidationReport.parse("{\"agreement\":{\"n_cells\":0,"
                + "\"n_unclassified_excluded\":5,\"error\":\"no cell in the run carries a "
                + "classification\"}}");
        assertThat(r.agreementSummary()).startsWith("Not computed: no cell").contains("5 unclassified");
        assertThat(r.contingencyRows(c -> "x")).isEmpty();
    }

    @Test
    void theReportSurvivesSaveAndReload() {
        ClusteringResult res = new ClusteringResult(new int[]{0, 1}, 2, null, null, new String[]{"m"});
        res.setValidationJson(JSON);
        SavedClusteringResult saved = SavedClusteringResult.fromResult(res, "r", "kmeans", "zscore", "none");
        assertThat(saved.toClusteringResult().getValidationJson()).isEqualTo(JSON);
    }

    @Test
    void theStabilityCheckMultipliesLeidenTimeInTheScaleGuard() {
        ScalingLimits.Request r = new ScalingLimits.Request(
                ClusteringConfig.Algorithm.LEIDEN, 1_000_000, 20);
        r.nNeighbors = 15;
        r.availableRamGb = 512;
        r.stabilityRepeats = 20;
        var findings = ScalingLimits.check(r);
        assertThat(findings).anySatisfy(f -> assertThat(f.subject()).startsWith("Stability check"));
        assertThat(ScalingLimits.isBlocked(findings)).isTrue();   // 20 x 560 s > 2 h

        r.stabilityRepeats = 0;
        assertThat(ScalingLimits.check(r)).noneSatisfy(
                f -> assertThat(f.subject()).startsWith("Stability check"));

        // HDBSCAN is deterministic; the check never runs, so it costs nothing.
        ScalingLimits.Request h = new ScalingLimits.Request(
                ClusteringConfig.Algorithm.HDBSCAN, 10_000, 20);
        h.stabilityRepeats = 20;
        assertThat(ScalingLimits.check(h)).noneSatisfy(
                f -> assertThat(f.subject()).startsWith("Stability check"));
    }

    @Test
    void theCostLineSaysTheClusteringRunsAgain() {
        ClusteringConfig c = new ClusteringConfig();
        c.setAlgorithm(ClusteringConfig.Algorithm.KMEANS);
        c.setStabilityRepeats(5);
        assertThat(RunCostSummary.describe(c)).anySatisfy(
                s -> assertThat(s).contains("re-runs the clustering 5 more times"));
        c.setAlgorithm(ClusteringConfig.Algorithm.HDBSCAN);
        assertThat(RunCostSummary.describe(c)).noneSatisfy(
                s -> assertThat(s).contains("re-runs the clustering"));
    }
}
