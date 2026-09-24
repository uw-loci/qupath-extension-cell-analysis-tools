package qupath.ext.qpcat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.CoOccurrenceResult;
import qupath.ext.qpcat.model.GearyCResult;
import qupath.ext.qpcat.model.RipleyResult;

/**
 * {@link SpatialStatsCsv}.
 * <p>
 * Written after a user asked, of the pairwise co-occurrence table, "in the entire output
 * table, I can't tell what the pairs are". On screen each ordered pair is a column, so 20
 * clusters is 400 columns and the labels scroll away. Long form names both clusters on
 * every row.
 */
class SpatialStatsCsvTest {

    @Test
    void pairwiseNamesBothClustersOnEveryRow() {
        CoOccurrenceResult coo = new CoOccurrenceResult();
        coo.setMode("pairwise");
        coo.setClusterNames(List.of("0", "1"));
        coo.setIntervals(new double[] {10.0, 20.0});
        coo.setData(new double[][][] {
            {{1.5, 1.4}, {0.5, 0.6}},
            {{0.9, 0.8}, {2.0, 2.1}}
        });

        String csv = SpatialStatsCsv.coOccurrenceCsv(coo, k -> "Cluster " + k);

        assertThat(csv.lines().findFirst()).hasValue("center_cluster,neighbor_cluster,radius,ratio");
        assertThat(csv).contains("Cluster 0,Cluster 1,10.0,0.5");
        assertThat(csv).contains("Cluster 1,Cluster 0,20.0,0.8");
        // 2 clusters x 2 clusters x 2 radii, plus the header.
        assertThat(csv.lines().count()).isEqualTo(9);
    }

    @Test
    void oneVsRestHasNoSecondCluster() {
        CoOccurrenceResult coo = new CoOccurrenceResult();
        coo.setMode("oneVsRest");
        coo.setClusterNames(List.of("0", "1"));
        coo.setIntervals(new double[] {10.0});
        coo.setData(new double[][][] {{{1.2}}, {{0.8}}});

        String csv = SpatialStatsCsv.coOccurrenceCsv(coo, null);

        assertThat(csv.lines().findFirst()).hasValue("cluster,radius,ratio");
        assertThat(csv).contains("0,10.0,1.2").contains("1,10.0,0.8");
    }

    @Test
    void renamedClustersTravelIntoTheCsv() {
        CoOccurrenceResult coo = new CoOccurrenceResult();
        coo.setMode("oneVsRest");
        coo.setClusterNames(List.of("0"));
        coo.setIntervals(new double[] {5.0});
        coo.setData(new double[][][] {{{1.0}}});

        assertThat(SpatialStatsCsv.coOccurrenceCsv(coo, k -> "Tumor, invasive"))
                .contains("\"Tumor, invasive\",5.0,1.0");
    }

    @Test
    void ripleyCarriesThePoissonNullAsItsOwnRows() {
        RipleyResult r = new RipleyResult();
        r.setClusterNames(List.of("0"));
        r.setRadii(new double[] {10.0, 20.0});
        r.setKValues(new double[][] {{100.0, 400.0}});
        r.setLValues(new double[][] {{1.0, 2.0}});
        r.setPoissonK(new double[] {314.0, 1256.0});
        r.setPoissonL(new double[] {0.0, 0.0});

        String csv = SpatialStatsCsv.ripleyCsv(r, k -> "Cluster " + k);

        assertThat(csv.lines().findFirst()).hasValue("cluster,radius,k,l");
        assertThat(csv).contains("Cluster 0,10.0,100.0,1.0");
        assertThat(csv).contains("Poisson null,20.0,1256.0,0.0");
    }

    @Test
    void gearyWritesOneRowPerMarker() {
        GearyCResult g = new GearyCResult();
        g.putMarker("Cell: CD8a: Mean", 0.42, 0.001);

        String csv = SpatialStatsCsv.gearyCsv(g);

        assertThat(csv.lines().findFirst()).hasValue("marker,geary_c,p_value");
        assertThat(csv).contains("Cell: CD8a: Mean,0.42,0.001");
    }

    @Test
    void aNonFiniteValueIsAnEmptyCellNotTheTextNaN() {
        RipleyResult r = new RipleyResult();
        r.setClusterNames(List.of("0"));
        r.setRadii(new double[] {10.0});
        r.setKValues(new double[][] {{Double.NaN}});
        r.setLValues(new double[][] {{Double.NaN}});

        assertThat(SpatialStatsCsv.ripleyCsv(r, null)).contains("0,10.0,,\n").doesNotContain("NaN");
    }

    @Test
    void emptyResultsStillProduceAHeader() {
        assertThat(SpatialStatsCsv.gearyCsv(null)).isEqualTo("marker,geary_c,p_value\n");
        assertThat(SpatialStatsCsv.ripleyCsv(null, null)).isEqualTo("cluster,radius,k,l\n");
        assertThat(SpatialStatsCsv.coOccurrenceCsv(null, null))
                .isEqualTo("center_cluster,neighbor_cluster,radius,ratio\n");
    }
}
