package qupath.ext.qpcat.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GateCoverageTest {

    /** Quantiles at probabilities 0..1 (n = 1001) of a sorted sample, as numpy's default. */
    private static double[] quantiles(double[] sorted) {
        double[] q = new double[1001];
        for (int i = 0; i < q.length; i++) {
            double pos = i / 1000.0 * (sorted.length - 1);
            int lo = (int) Math.floor(pos);
            int hi = Math.min(lo + 1, sorted.length - 1);
            q[i] = sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo]);
        }
        return q;
    }

    /** 63% of cells at exactly 0, the rest spread evenly over (0, 1]. */
    private static double[] zeroSpike() {
        double[] v = new double[1000];
        for (int i = 630; i < 1000; i++) {
            v[i] = (i - 629) / 370.0;
        }
        return v;
    }

    @Test
    void gateJustAboveTheZeroSpikeCountsOnlyTheTail() {
        // The histogram panel counted whole 50-bin bins by their centre, so a gate
        // of 0.0045 put the zero bin (centre 0.01) on the positive side: it read
        // 100% positive for a gate that calls 37.8% positive (tme_01, CD3).
        double frac = GateCoverage.positiveFraction(quantiles(zeroSpike()), 0.0045);
        assertEquals(0.37, frac, 0.002);
    }

    @Test
    void gateAtTheMinimumCallsEveryCellPositive() {
        assertEquals(1.0, GateCoverage.positiveFraction(quantiles(zeroSpike()), 0.0));
    }

    @Test
    void gateAboveTheMaximumCallsNoCellPositive() {
        assertEquals(0.0, GateCoverage.positiveFraction(quantiles(zeroSpike()), 1.5));
    }

    @Test
    void gateOnACeilingMassCountsTheMassPositive() {
        double[] v = new double[1000];
        for (int i = 0; i < 1000; i++) {
            v[i] = Math.min(i / 990.0, 1.0);
        }
        // values >= 1.0: indices 990..999
        assertEquals(0.01, GateCoverage.positiveFraction(quantiles(v), 1.0), 0.001);
    }

    @Test
    void uniformDataGivesTheComplement() {
        double[] v = new double[2001];
        for (int i = 0; i < v.length; i++) {
            v[i] = i / 2000.0;
        }
        assertEquals(0.25, GateCoverage.positiveFraction(quantiles(v), 0.75), 0.001);
    }

    @Test
    void noQuantilesIsNaN() {
        assertTrue(Double.isNaN(GateCoverage.positiveFraction(null, 0.5)));
    }

    @Test
    void warningOnlyOutsideFiveToNinetyFivePercent() {
        assertNotNull(GateCoverage.warning(0.996));
        assertNotNull(GateCoverage.warning(0.01));
        assertNull(GateCoverage.warning(0.24));
        assertNull(GateCoverage.warning(0.05));
        assertNull(GateCoverage.warning(0.95));
        assertNull(GateCoverage.warning(Double.NaN));
        assertTrue(GateCoverage.warning(0.996).contains("99.6%"));
    }
}
