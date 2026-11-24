package qupath.ext.qpcat.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.util.List;
import org.junit.jupiter.api.Test;

/** UMAP on the GPU: the config field, the seed rule, the run notes and the memory estimates. */
class GpuUmapConfigTest {

    @Test
    void backendIsCpuUnlessGpuAndAppliesToUmapOnly() {
        ClusteringConfig c = new ClusteringConfig();
        assertEquals("cpu", c.getEmbeddingBackend());
        assertFalse(c.isGpuUmap());
        c.setEmbeddingBackend(" GPU ");
        assertEquals("gpu", c.getEmbeddingBackend());
        assertTrue(c.isGpuUmap(), "UMAP is the default embedding");
        c.setEmbeddingMethod(ClusteringConfig.EmbeddingMethod.TSNE);
        assertFalse(c.isGpuUmap());
        c.setEmbeddingBackend("cuda");
        assertEquals("cpu", c.getEmbeddingBackend());
    }

    @Test
    void aConfigSavedBeforeTheFieldLoadsAsCpu() {
        ClusteringConfig c = new Gson().fromJson("{\"embeddingBackend\": null}", ClusteringConfig.class);
        assertEquals("cpu", c.getEmbeddingBackend());
        ClusteringConfig back = new Gson().fromJson(new Gson().toJson(gpu()), ClusteringConfig.class);
        assertEquals("gpu", back.getEmbeddingBackend());
    }

    private static ClusteringConfig gpu() {
        ClusteringConfig c = new ClusteringConfig();
        c.setEmbeddingBackend("gpu");
        return c;
    }

    @Test
    void theSeedFollowsTheReproducibilitySettingOnBothDevices() {
        assertTrue(RunCostSummary.umapSeeded("auto", 199_999));
        assertFalse(RunCostSummary.umapSeeded("auto", 200_000));
        assertTrue(RunCostSummary.umapSeeded("reproducible", 4_000_000));
        assertFalse(RunCostSummary.umapSeeded("fast", 1_000));
    }

    @Test
    void theCostLineSaysTheGpuLayoutIsNotTheCpus() {
        assertTrue(RunCostSummary.describe(gpu()).stream().anyMatch(s -> s.startsWith("UMAP on the GPU")));
        assertTrue(RunCostSummary.describe(new ClusteringConfig()).stream()
                .noneMatch(s -> s.startsWith("UMAP on the GPU")));
    }

    @Test
    void aGpuEmbeddingIsARunNoteAndAFallbackIsNot() {
        ClusteringResult r = new ClusteringResult(new int[]{0, 1}, 2, new double[][]{{1, 2}, {3, 4}},
                new double[][]{{1}, {2}}, new String[]{"m"});
        r.setEmbeddingExecution("gpu (RTX on Linux; cuML 26.10.00; seeded, exact neighbor search, "
                + "repeats run to run on one card)");
        assertEquals(List.of("UMAP on the GPU."), r.runNotes());
        r.setEmbeddingExecution("cpu (gpu requested; no RAPIDS Python configured); seeded (reproducible, "
                + "single-threaded)");
        assertTrue(r.runNotes().isEmpty());
    }

    @Test
    void estimatesFollowTheMeasurement() {
        // 4M cells at n_neighbors 15: 4.05 GB on the card, 2.8-3.7 GB host seeded, 9.6 GB unseeded.
        double card = ScalingLimits.gpuUmapGb(4_000_000, 15);
        assertTrue(card > 3.5 && card < 4.5, "about 3.8 GiB, got " + card);
        double seeded = ScalingLimits.gpuUmapHostGb(4_000_000, 15, true);
        assertTrue(seeded > 3.0 && seeded < 4.0, "about 3.5 GiB, got " + seeded);
        double unseeded = ScalingLimits.gpuUmapHostGb(4_000_000, 15, false);
        assertTrue(unseeded > 8.5 && unseeded < 10.0, "about 9.2 GiB, got " + unseeded);
        assertTrue(ScalingLimits.gpuUmapGb(4_000_000, 50) > 2.5 * card, "scaled with n_neighbors");
    }

    @Test
    void checkFlagsOnlyWhenAskedForAndTooBig() {
        ScalingLimits.Request r = new ScalingLimits.Request(ClusteringConfig.Algorithm.KMEANS, 4_000_000, 25);
        r.availableRamGb = 10;
        r.umapSeeded = false;
        assertTrue(ScalingLimits.check(r).stream().noneMatch(f -> f.subject().startsWith("GPU UMAP")));
        r.gpuUmap = true;
        assertTrue(ScalingLimits.check(r).stream().anyMatch(f -> f.subject().equals("GPU UMAP: host memory")),
                "9.2 GiB unseeded is above 80 % of 10 GB");
        r.umapSeeded = true;
        assertTrue(ScalingLimits.check(r).stream().noneMatch(f -> f.subject().startsWith("GPU UMAP")),
                "seeded fits, and 4M cells fit a 16 GB card");
        r.nCells = 20_000_000;
        r.availableRamGb = 512;
        ScalingLimits.Finding f = ScalingLimits.check(r).stream()
                .filter(x -> x.subject().equals("GPU UMAP")).findFirst().orElseThrow();
        assertEquals(ScalingLimits.Severity.WARN, f.severity());
    }
}
