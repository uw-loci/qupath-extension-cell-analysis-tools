package qupath.ext.qpcat.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.ClusteringResult;

/**
 * The post-hoc spatial CSV wrote only the upper triangle of the enrichment
 * matrix: no self-enrichment (the diagonal) and only one direction of each pair,
 * although a kNN graph makes A-to-B and B-to-A different numbers.
 */
class PostHocNhoodCsvTest {

    @Test
    void everyOrderedPairAndItsEdgeCountIsWritten() {
        ClusteringResult r = new ClusteringResult(
                new int[] {0, 1}, 2,
                new double[][] {{0.0, 0.0}, {1.0, 1.0}},
                new double[][] {{0.1}, {0.9}},
                new String[] {"CD3"});
        r.setNhoodEnrichment(new double[][] {{12.5, -3.0}, {-1.25, 4.0}});
        r.setNhoodCounts(new double[][] {{3.0, 410.0}, {398.0, 77.0}});
        r.setNhoodClusterNames(new String[] {"Tumor", "Stroma"});
        PostHocSpatialWorkflow.WindowResult wr = new PostHocSpatialWorkflow.WindowResult();
        wr.imageName = "img";
        wr.regionLabel = "whole image";
        wr.nCells = 2;
        wr.nClasses = 2;
        wr.result = r;

        String csv = PostHocSpatialWorkflow.buildCsv(List.of(wr));

        assertThat(csv).contains("nhood_z,Tumor > Tumor,12.5000,\n");
        assertThat(csv).contains("nhood_edges,Tumor > Tumor,3,\n");
        assertThat(csv).contains("nhood_z,Stroma > Tumor,-1.25000,\n");
        assertThat(csv).contains("nhood_edges,Tumor > Stroma,410,\n");
        assertThat(csv.lines().filter(l -> l.contains(",nhood_z,")).count()).isEqualTo(4);
    }

    @Test
    void numbersUseADecimalPointUnderTurkishAndGermanLocales() {
        // "%.6g" was formatted with the default locale, so de-DE and tr-TR wrote
        // "12,5000" and split the value across two CSV columns.
        Locale previous = Locale.getDefault();
        try {
            for (Locale l : List.of(Locale.forLanguageTag("tr-TR"), Locale.GERMANY)) {
                Locale.setDefault(l);
                everyOrderedPairAndItsEdgeCountIsWritten();
            }
        } finally {
            Locale.setDefault(previous);
        }
    }
}
