package qupath.ext.qpcat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.ext.qpcat.model.GearyCResult;

/**
 * What the Moran's I and Geary's C tabs show, and in what order.
 * <p>
 * The statistic leads and ranks the rows; the consistency count across
 * independent areas sits beside it; the corrected p is last and labelled as a
 * guard. The reason is in the Spatial statistics doc ("Which number to read"):
 * at cohort scale a negligible I earns a tiny p, and a staining gradient on one
 * slide earns a pooled I a p-value cannot tell from tissue structure.
 */
class AutocorrTablesTest {

    private static ClusteringResult moran(String json, String perArea, Double threshold, String areas) {
        ClusteringResult r = new ClusteringResult(
                new int[] {0, 1}, 2,
                new double[][] {{0.0, 0.0}, {1.0, 1.0}},
                new double[][] {{0.1}, {0.9}},
                new String[] {"CD3"});
        r.setSpatialAutocorrJson(json);
        r.setSpatialAutocorrPerAreaJson(perArea);
        r.setSpatialAutocorrThreshold(threshold);
        r.setSpatialAutocorrAreas(areas);
        r.setSpatialAutocorrPMethod("pval_z_sim_fdr_bh (normal tail of 1000 permutations, one-sided, "
                + "Benjamini-Hochberg across 2 measurements)");
        return r;
    }

    @Test
    void moranRowsAreRankedByIWithTheCountBesideAndQLast() {
        String text = AutocorrTables.moranTable(moran(
                "{\"Noise\":{\"I\":0.004,\"pval\":0.0,\"pval_uncorrected\":0.0,\"areas_above\":0,\"n_areas\":8},"
                + "\"CD20\":{\"I\":0.55,\"pval\":1e-40,\"pval_uncorrected\":5e-41,\"areas_above\":8,\"n_areas\":8}}",
                null, 0.05, "8 area(s) evaluated"));

        String[] lines = text.split("\\R");
        assertThat(lines[0]).startsWith("Measurement").contains("Moran's I").contains("Areas >= 0.05");
        assertThat(lines[0].trim()).endsWith("q");
        assertThat(lines[2]).startsWith("CD20").contains("0.5500").contains("8 of 8").contains("1.0e-40");
        assertThat(lines[3]).startsWith("Noise").contains("0.0040").contains("0 of 8");
        // A p that underflowed prints as a bound, never as a bare zero that reads as "impossible".
        assertThat(lines[3]).contains("<1e-300").doesNotContain(" 0.0e+00");
        assertThat(text).contains("Ranked by Moran's I, the effect size");
        assertThat(text).contains("in how many of the 8 areas the measurement's own I reaches 0.05");
        assertThat(text).contains("8 area(s) evaluated.");
        assertThat(text).contains("q: pval_z_sim_fdr_bh").contains("A guard against noise, not the ranking");
        // The uncorrected p is in the CSV, not on screen: it was jargon leaking out.
        assertThat(text).doesNotContain("uncorrected");
    }

    @Test
    void aSingleAreaDropsTheCountColumnAndSaysWhy() {
        String text = AutocorrTables.moranTable(moran(
                "{\"CD20\":{\"I\":0.55,\"pval\":1e-4,\"pval_uncorrected\":5e-5,\"areas_above\":0,\"n_areas\":0}}",
                "{\"CD20\":{}}", 0.05, ""));

        String[] lines = text.split("\\R");
        assertThat(lines[0]).doesNotContain("Areas");
        assertThat(lines[2]).startsWith("CD20").doesNotContain(" of ");
        assertThat(text).contains("One area, so there is no consistency count.");
        assertThat(text).contains("Moran's I >= 0.05 is the cut");
    }

    @Test
    void aResultFromBeforeTheCountWasRecordedStillRenders() {
        // Older payloads have I and pval only; the method line says what that p was.
        ClusteringResult r = moran("{\"CD20\":{\"I\":0.55,\"pval\":0.03}}", null, null, null);
        r.setSpatialAutocorrPMethod(null);

        String text = AutocorrTables.moranTable(r);

        assertThat(text).contains("CD20").contains("0.5500").contains("3.0e-02");
        assertThat(text).contains("method not recorded");
        assertThat(text).doesNotContain("Areas").doesNotContain("null");
    }

    @Test
    void thePerAreaGridNamesWhichAreaCarriesTheStructure() {
        String text = AutocorrTables.moranTable(moran(
                "{\"CD20\":{\"I\":0.17,\"pval\":1e-9,\"pval_uncorrected\":5e-10,\"areas_above\":1,\"n_areas\":2}}",
                "{\"CD20\":{\"slide_A.tif\":0.50,\"slide_B.tif\":-0.02}}", 0.05, "2 area(s) evaluated"));

        assertThat(text).contains("1 of 2");
        assertThat(text).contains("Moran's I in each area on its own");
        // Area names are clipped to fit the column, and marked as clipped.
        assertThat(text).contains("slide_A.~").contains("slide_B.~");
        assertThat(text).contains("0.500").contains("-0.020");
    }

    @Test
    void manyAreasPointAtTheCsvInsteadOfAnUnreadableGrid() {
        Map<String, Double> perArea = new LinkedHashMap<>();
        for (int i = 0; i < 13; i++) perArea.put("core_" + i, 0.1);
        GearyCResult g = new GearyCResult();
        g.putMarker("CD3", 0.42, 0.001, 0.0004, 13, 13);
        g.setPerArea(Map.of("CD3", perArea));
        g.setThreshold(0.05);
        g.setAreas("13 area(s) evaluated");

        String text = AutocorrTables.gearyTable(g);

        assertThat(text).contains("13 of 13");
        assertThat(text).contains("Geary C per area is in the CSV export (13 areas");
        assertThat(text).doesNotContain("core_12");
    }

    @Test
    void gearyRanksSmallestCFirstAndMirrorsTheThreshold() {
        GearyCResult g = new GearyCResult();
        g.putMarker("Noise", 1.01, 0.4, 0.4, 0, 3);
        g.putMarker("CD20", 0.49, 1e-30, 1e-30, 3, 3);
        g.setThreshold(0.05);
        g.setNPermutations(1000);
        g.setGraphType("knn");
        g.setPValueMethod("pval_z_sim_fdr_bh (normal tail of 1000 permutations, one-sided, "
                + "Benjamini-Hochberg across 2 measurements)");

        String text = AutocorrTables.gearyTable(g);
        String[] lines = text.split("\\R");

        assertThat(lines[0]).contains("Geary C").contains("Areas <= 0.95");
        assertThat(lines[2]).startsWith("CD20").contains("0.4900").contains("3 of 3");
        assertThat(lines[3]).startsWith("Noise").contains("0 of 3");
        assertThat(text).contains("C is at most 0.95");
        assertThat(text).contains("Ranked by Geary's C, the effect size");
        assertThat(text).contains("Permutations: 1000").contains("Graph: knn");
    }

    @Test
    void passesMirrorsThePythonRule() {
        assertThat(AutocorrTables.passes(0.05, false, 0.05)).isTrue();
        assertThat(AutocorrTables.passes(0.049, false, 0.05)).isFalse();
        assertThat(AutocorrTables.passes(0.95, true, 0.05)).isTrue();
        assertThat(AutocorrTables.passes(0.951, true, 0.05)).isFalse();
        assertThat(AutocorrTables.passes(null, false, 0.05)).isFalse();
        assertThat(AutocorrTables.passes(Double.NaN, true, 0.05)).isFalse();
    }

    @Test
    void theProseUnderTheTableIsWrappedBecauseTheTextAreaCannot() {
        String text = AutocorrTables.moranTable(moran(
                "{\"CD20\":{\"I\":0.55,\"pval\":1e-4,\"pval_uncorrected\":5e-5,\"areas_above\":8,\"n_areas\":8}}",
                null, 0.05, "8 area(s) evaluated"));

        for (String line : text.split("\\R")) {
            assertThat(line.length()).as(line).isLessThanOrEqualTo(100);
        }
        assertThat(AutocorrTables.wrap("a bb ccc dddd", 6, "|")).isEqualTo("a bb|ccc|dddd");
    }

    @Test
    void thresholdTextHasNoTrailingZeros() {
        assertThat(AutocorrTables.trimNumber(0.05)).isEqualTo("0.05");
        assertThat(AutocorrTables.trimNumber(0.95)).isEqualTo("0.95");
        assertThat(AutocorrTables.trimNumber(0.1)).isEqualTo("0.1");
        assertThat(AutocorrTables.trimNumber(0.0)).isEqualTo("0.0");
    }
}
