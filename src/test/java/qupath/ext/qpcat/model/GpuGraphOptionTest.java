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
        // The member's 6M-cell, k=50 run: 430.8M nonzeros at about 62 B each.
        double gb = ScalingLimits.gpuGraphGb(6_000_000, 50);
        assertTrue(gb > 24 && gb < 26, "6M x 50 x 1.44 x 60 B is about 25 GB, got " + gb);
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
                "a run that fits the card gets no line");
    }
}
