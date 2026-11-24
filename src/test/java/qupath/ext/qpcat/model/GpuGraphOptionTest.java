package qupath.ext.qpcat.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The GPU graph option: config normalization and the VRAM estimate (ledger step 6). */
class GpuGraphOptionTest {

    @Test
    void backendIsCpuUnlessGpu() {
        ClusteringConfig c = new ClusteringConfig();
        assertEquals("cpu", c.getGraphBackend());
        c.setGraphBackend(" GPU ");
        assertEquals("gpu", c.getGraphBackend());
        c.setGraphBackend("cuda");
        assertEquals("cpu", c.getGraphBackend());
        c.setGraphBackend(null);
        assertEquals("cpu", c.getGraphBackend());
    }

    @Test
    void estimateFollowsTheMeasuredRule() {
        // 6M cells x 50 x 1.44 = 432M nonzeros (the member's run had 430.8M) at 45 B each.
        double gb = ScalingLimits.gpuGraphGb(6_000_000, 50);
        assertTrue(gb > 17 && gb < 20, "6M x 50 x 1.44 x 45 B is about 18 GiB, got " + gb);
        // 3M x 50 needed 10.7 GB of card memory on 245M nonzeros (2026-11-24).
        double three = ScalingLimits.gpuGraphGb(3_000_000, 50);
        assertTrue(three > 8.5 && three < 10.5, "3M x 50 is about 9 GiB, got " + three);
        assertTrue(ScalingLimits.gpuGraphGb(1_000_000, 15) < 2.0);
    }

    @Test
    void checkFlagsOnlyWhenTheGpuIsAskedFor() {
        ScalingLimits.Request r = new ScalingLimits.Request(ClusteringConfig.Algorithm.LEIDEN, 6_000_000, 25);
        r.nNeighbors = 50;
        r.availableRamGb = 512;
        List<ScalingLimits.Finding> none = ScalingLimits.check(r);
        assertTrue(none.stream().noneMatch(f -> f.subject().startsWith("GPU graph")));
        r.gpuGraph = true;
        List<ScalingLimits.Finding> some = ScalingLimits.check(r);
        ScalingLimits.Finding f = some.stream().filter(x -> x.subject().startsWith("GPU graph")).findFirst().orElseThrow();
        assertEquals(ScalingLimits.Severity.WARN, f.severity());
        r.nCells = 500_000;
        assertTrue(ScalingLimits.check(r).stream().noneMatch(x -> x.subject().startsWith("GPU graph")),
                "a run that fits the card and the RAM gets no line");
    }

    @Test
    void hostMemoryOfTheGpuProcessIsChecked() {
        // 4M cells x 50 needs about 12.6 GiB of host RAM for the connectivities (47 B per
        // nonzero, measured 2026-11-24); a 12 GB WSL2 is too small, 64 GB is fine.
        ScalingLimits.Request r = new ScalingLimits.Request(ClusteringConfig.Algorithm.LEIDEN, 4_000_000, 25);
        r.nNeighbors = 50;
        r.gpuGraph = true;
        r.availableRamGb = 12;
        assertTrue(ScalingLimits.check(r).stream().anyMatch(x -> x.subject().equals("GPU graph: host memory")));
        r.availableRamGb = 64;
        assertTrue(ScalingLimits.check(r).stream().noneMatch(x -> x.subject().equals("GPU graph: host memory")));
        assertTrue(ScalingLimits.gpuGraphHostGb(4_000_000, 50) > 12 && ScalingLimits.gpuGraphHostGb(4_000_000, 50) < 13.5);
    }
}
