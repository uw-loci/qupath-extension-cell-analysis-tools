package qupath.ext.qpcat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.CoOccurrenceResult;
import qupath.ext.qpcat.model.ClusteringResult;
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
    void gearyWritesThePooledRowAsAreaAll() {
        GearyCResult g = new GearyCResult();
        g.putMarker("Cell: CD8a: Mean", 0.42, 0.001, 0.0004, 6, 8);
        g.setThreshold(0.05);
        g.setPValueMethod("pval_z_sim_fdr_bh (normal tail of 1000 permutations, BH across 1 measurements)");

        String csv = SpatialStatsCsv.gearyCsv(g);

        assertThat(csv.lines().findFirst()).hasValue(
                "measurement,area,geary_c,p_value,p_value_uncorrected,"
                + "areas_above_threshold,n_areas,threshold,p_value_method");
        // The method text carries a comma, so it is quoted.
        assertThat(csv).contains("Cell: CD8a: Mean,all,0.42,0.001,4.0E-4,6,8,0.05,\"pval_z_sim_fdr_bh");
    }

    @Test
    void gearyWritesOneRowPerAreaWithOnlyThatAreasC() {
        // The per-area values are the consistency evidence; a CSV that carried
        // only the count would make the reader take "6 of 8" on trust.
        GearyCResult g = new GearyCResult();
        g.putMarker("CD3", 0.42, 0.001, 0.0004, 1, 2);
        g.setPerArea(Map.of("CD3", new java.util.LinkedHashMap<>(Map.of("img_A", 0.40))));
        g.getPerArea().get("CD3").put("img_B", 0.99);
        g.setThreshold(0.05);

        String csv = SpatialStatsCsv.gearyCsv(g);

        assertThat(csv).contains("CD3,img_A,0.4,,,,,0.05,\n");
        assertThat(csv).contains("CD3,img_B,0.99,,,,,0.05,\n");
    }

    @Test
    void gearyNamesWhichPValueItWroteSoACsvIsNotAmbiguousOnceItLeaves() {
        GearyCResult g = new GearyCResult();
        g.putMarker("Cell: CD8a: Mean", 0.42, 0.001, 0.0004);
        g.setPValueMethod("pval_z_sim_fdr_bh (normal tail of 999 permutations, Benjamini-Hochberg)");

        assertThat(SpatialStatsCsv.gearyCsv(g))
                .contains("pval_z_sim_fdr_bh (normal tail of 999 permutations, Benjamini-Hochberg)");
    }

    @Test
    void anUnrecordedUncorrectedPOrCountIsAnEmptyCell() {
        // A result parsed from a run before the fix has neither column.
        GearyCResult g = new GearyCResult();
        g.putMarker("Cell: CD8a: Mean", 0.42, 0.001);

        assertThat(SpatialStatsCsv.gearyCsv(g))
                .contains("Cell: CD8a: Mean,all,0.42,0.001,,,,,")
                .doesNotContain("NaN")
                .doesNotContain("null");
    }

    @Test
    void moranCsvMirrorsGeary() {
        ClusteringResult r = new ClusteringResult(
                new int[] {0, 1}, 2,
                new double[][] {{0.0, 0.0}, {1.0, 1.0}},
                new double[][] {{0.1}, {0.9}},
                new String[] {"CD3"});
        r.setSpatialAutocorrJson(
                "{\"CD3\":{\"I\":0.21,\"pval\":0.04,\"pval_uncorrected\":0.02,\"areas_above\":1,\"n_areas\":2}}");
        r.setSpatialAutocorrPerAreaJson("{\"CD3\":{\"img_A\":0.5,\"img_B\":-0.01}}");
        r.setSpatialAutocorrThreshold(0.05);
        r.setSpatialAutocorrPMethod("pval_z_sim_fdr_bh (x)");

        String csv = SpatialStatsCsv.moranCsv(r);

        assertThat(csv.lines().findFirst()).hasValue(
                "measurement,area,moran_i,p_value,p_value_uncorrected,"
                + "areas_above_threshold,n_areas,threshold,p_value_method");
        assertThat(csv).contains("CD3,all,0.21,0.04,0.02,1,2,0.05,pval_z_sim_fdr_bh (x)\n");
        assertThat(csv).contains("CD3,img_A,0.5,,,,,0.05,\n");
        assertThat(csv).contains("CD3,img_B,-0.01,,,,,0.05,\n");
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
        assertThat(SpatialStatsCsv.gearyCsv(null))
                .isEqualTo("measurement,area,geary_c,p_value,p_value_uncorrected,areas_above_threshold,n_areas,threshold,p_value_method\n");
        assertThat(SpatialStatsCsv.ripleyCsv(null, null)).isEqualTo("cluster,radius,k,l\n");
        assertThat(SpatialStatsCsv.coOccurrenceCsv(null, null))
                .isEqualTo("center_cluster,neighbor_cluster,radius,ratio\n");
    }
}
