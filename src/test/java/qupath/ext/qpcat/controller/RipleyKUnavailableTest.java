package qupath.ext.qpcat.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.RipleyResult;
import qupath.ext.qpcat.service.SpatialStatsCsv;

/**
 * The K-unavailable flag, end to end from the Python payload to the CSV.
 * <p>
 * squidpy 1.6.6 dropped {@code mode='K'} (only F/G/L remain), so spatial_stats.py
 * zero-pads the K curves and says so. Java never read that flag, so the results window
 * drew a "Ripley K(r)" chart in which every cluster sat flat on zero -- indistinguishable
 * from a measured "no clustering at any radius" result, on a run where K was never
 * computed at all.
 */
class RipleyKUnavailableTest {

    private static final Gson GSON = new Gson();

    @Test
    void theFlagSurvivesTheJsonPayload() {
        String json = """
                {"cluster_names":["0"],"radii":[10.0,20.0],
                 "k_values":[[0.0,0.0]],"l_values":[[1.0,2.0]],
                 "poisson_k":[314.0,1256.0],"poisson_l":[0.0,0.0],
                 "k_unavailable":true}
                """;
        RipleyResult r = ClusteringWorkflow.parseRipley(json, GSON);
        assertThat(r.isKUnavailable()).isTrue();
        assertThat(r.getLValues()[0]).containsExactly(1.0, 2.0);
    }

    @Test
    void anOlderPayloadWithoutTheFlagStillReadsAsAvailable() {
        String json = """
                {"cluster_names":["0"],"radii":[10.0],"k_values":[[5.0]],"l_values":[[1.0]]}
                """;
        assertThat(ClusteringWorkflow.parseRipley(json, GSON).isKUnavailable()).isFalse();
    }

    @Test
    void theCsvLeavesKEmptyRatherThanWritingThePaddingZeros() {
        RipleyResult r = new RipleyResult();
        r.setClusterNames(java.util.List.of("0"));
        r.setRadii(new double[] {10.0});
        r.setKValues(new double[][] {{0.0}});
        r.setLValues(new double[][] {{1.5}});
        r.setPoissonK(new double[] {314.0});
        r.setPoissonL(new double[] {0.0});
        r.setKUnavailable(true);

        String csv = SpatialStatsCsv.ripleyCsv(r, null);

        assertThat(csv).contains("0,10.0,,1.5");
        assertThat(csv).doesNotContain("314.0");
    }

    @Test
    void whenKIsAvailableItIsWrittenAsUsual() {
        RipleyResult r = new RipleyResult();
        r.setClusterNames(java.util.List.of("0"));
        r.setRadii(new double[] {10.0});
        r.setKValues(new double[][] {{42.0}});
        r.setLValues(new double[][] {{1.5}});
        r.setPoissonK(new double[] {314.0});
        r.setPoissonL(new double[] {0.0});

        String csv = SpatialStatsCsv.ripleyCsv(r, null);

        assertThat(csv).contains("0,10.0,42.0,1.5").contains("Poisson null,10.0,314.0,0.0");
    }
}
