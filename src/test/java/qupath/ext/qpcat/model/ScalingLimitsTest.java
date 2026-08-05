package qupath.ext.qpcat.model;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.ClusteringConfig.Algorithm;
import qupath.ext.qpcat.model.ScalingLimits.Finding;
import qupath.ext.qpcat.model.ScalingLimits.Request;
import qupath.ext.qpcat.model.ScalingLimits.Severity;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pins the cost models against the measurements they were fitted to.
 *
 * <p>These numbers came from a ladder run in the real QP-CAT Appose environment
 * (16 cores, 20 features, 20 clusters, peak RSS sampled from {@code /proc},
 * 6 GB cap). If someone retunes a coefficient, these tests say which measured
 * point it stopped reproducing -- that is the whole reason they exist. A model
 * that drifts from its measurements silently is worse than no model, because
 * the guard would then block runs that are fine or admit runs that die.
 */
class ScalingLimitsTest {

    /** Generous: we care that the model is right in ORDER, not to 3 decimals. */
    private static final double TOL = 0.35;

    // ---- Calibration: does each formula still reproduce its measurements? ---

    @Test
    void agglomerativeMatchesMeasuredPeaks() {
        // 10,000 cells -> 0.93 GB measured; 20,000 -> 3.17 GB measured.
        assertThat(ScalingLimits.agglomerativePeakGb(10_000)).isCloseTo(0.93, within(TOL));
        assertThat(ScalingLimits.agglomerativePeakGb(20_000)).isCloseTo(3.17, within(TOL));
    }

    @Test
    void agglomerativeIsQuadraticNotLinear() {
        // The whole point of blocking it: doubling the cells quadruples the cost.
        double at20k = ScalingLimits.agglomerativePeakGb(20_000) - 0.2;
        double at40k = ScalingLimits.agglomerativePeakGb(40_000) - 0.2;
        assertThat(at40k / at20k).isCloseTo(4.0, within(0.05));
    }

    @Test
    void coOccurrenceMatchesMeasuredPeaks() {
        // 20,000 -> 2.27 GB measured; 50,000 -> 4.47 GB measured (20 clusters,
        // 50 intervals).
        assertThat(ScalingLimits.coOccurrencePeakGb(20_000, 20, 50))
                .isCloseTo(2.27, within(TOL));
        assertThat(ScalingLimits.coOccurrencePeakGb(50_000, 20, 50))
                .isCloseTo(4.47, within(TOL));
    }

    @Test
    void coOccurrenceGrowsWithClusterCountSquared() {
        // Doubling clusters quadruples memory -- the trap that makes it blow up
        // on a config that looked fine with fewer clusters.
        double k20 = ScalingLimits.coOccurrencePeakGb(50_000, 20, 50) - 0.95;
        double k40 = ScalingLimits.coOccurrencePeakGb(50_000, 40, 50) - 0.95;
        assertThat(k40 / k20).isCloseTo(4.0, within(0.05));
    }

    @Test
    void ripleyMatchesMeasuredPeaks() {
        // Balanced clusters: 100,000 cells / 20 -> 5,000 per cluster -> 1.50 GB;
        // 250,000 / 20 -> 12,500 -> 5.19 GB.
        assertThat(ScalingLimits.ripleyPeakGb(5_100)).isCloseTo(1.50, within(TOL));
        assertThat(ScalingLimits.ripleyPeakGb(12_800)).isCloseTo(5.19, within(TOL));
    }

    @Test
    void hdbscanMatchesMeasuredTimes() {
        // 50,000 -> 34.4 s measured; 100,000 -> 151 s measured.
        assertThat(ScalingLimits.hdbscanSeconds(50_000)).isCloseTo(34.4, within(4.0));
        assertThat(ScalingLimits.hdbscanSeconds(100_000)).isCloseTo(151.0, within(4.0));
    }

    @Test
    void leidenMatchesMeasuredPeak() {
        // 250,000 cells at n_neighbors=50 -> 4.87 GB measured.
        assertThat(ScalingLimits.leidenPeakGb(250_000, 50)).isCloseTo(4.87, within(TOL));
    }

    // ---- Policy: the right verdict on the machines people actually use ------

    @Test
    void agglomerativeAtOneMillionIsBlockedEvenOnAHugeMachine() {
        // 1M cells needs ~7,450 GB. No workstation makes this fine, and that is
        // exactly the case the user must not discover by crashing.
        Request r = new Request(Algorithm.AGGLOMERATIVE, 1_000_000, 20);
        r.availableRamGb = 512;
        assertThat(ScalingLimits.check(r))
                .singleElement()
                .extracting(Finding::severity).isEqualTo(Severity.BLOCK);
    }

    @Test
    void agglomerativeVerdictDependsOnTheMachine() {
        // 40,000 cells needs ~12 GB: impossible on a laptop, fine on a server.
        // A fixed cell-count cap could not express this.
        Request laptop = new Request(Algorithm.AGGLOMERATIVE, 40_000, 20);
        laptop.availableRamGb = 8;
        assertThat(ScalingLimits.isBlocked(ScalingLimits.check(laptop))).isTrue();

        Request server = new Request(Algorithm.AGGLOMERATIVE, 40_000, 20);
        server.availableRamGb = 512;
        assertThat(ScalingLimits.check(server)).isEmpty();
    }

    @Test
    void theCheapMethodsAreNeverFlagged() {
        // Measured flat to 250k. Warning about these would train users to click
        // through warnings, which is how the real ones get ignored.
        for (Algorithm a : List.of(Algorithm.KMEANS, Algorithm.MINIBATCHKMEANS,
                Algorithm.GMM, Algorithm.NONE)) {
            Request r = new Request(a, 1_000_000, 40);
            r.availableRamGb = 16;
            assertThat(ScalingLimits.check(r))
                    .as("%s at 1M cells", a)
                    .isEmpty();
        }
    }

    @Test
    void hdbscanAtOneMillionIsBlockedOnTime() {
        Request r = new Request(Algorithm.HDBSCAN, 1_000_000, 20);
        r.availableRamGb = 512;  // memory is not the problem; time is
        List<Finding> f = ScalingLimits.check(r);
        assertThat(f).singleElement().extracting(Finding::severity).isEqualTo(Severity.BLOCK);
        assertThat(f.get(0).predictedSeconds()).isGreaterThan(7200);
    }

    @Test
    void coOccurrenceIsBlockedByMemoryOnAnOrdinaryMachine() {
        // 1M cells x 20 clusters needs ~75 GB, which no laptop has.
        Request r = new Request(Algorithm.LEIDEN, 1_000_000, 20);
        r.coOccurrence = true;
        r.nClusters = 20;
        r.availableRamGb = 32;
        assertThat(ScalingLimits.check(r))
                .anySatisfy(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.BLOCK);
                    assertThat(f.subject()).contains("Co-occurrence");
                    assertThat(f.predictedPeakGb()).isGreaterThan(70);
                });
    }

    @Test
    void coOccurrenceOnABigMachineIsBoundByTimeNotMemory() {
        // The interesting case: 75 GB fits in 512 GB, so memory stops being the
        // constraint and the N^2 kernel (~80 min) becomes the thing to warn
        // about. Reporting "needs 75 GB" here would be true but useless.
        Request r = new Request(Algorithm.LEIDEN, 1_000_000, 20);
        r.coOccurrence = true;
        r.nClusters = 20;
        r.availableRamGb = 512;
        List<Finding> f = ScalingLimits.check(r);
        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.severity()).isEqualTo(Severity.WARN);
            assertThat(x.predictedSeconds()).isGreaterThan(600);
            assertThat(x.predictedPeakGb()).isZero();
        });
    }

    @Test
    void ripleyUsesTheRealLargestClusterWhenKnown() {
        // One dominant cluster is the realistic case and is far worse than the
        // balanced assumption -- 400k in one cluster, not 1M/20 = 50k.
        double balanced = ScalingLimits.ripleyPeakGb(1_000_000 / 20);
        double skewed = ScalingLimits.ripleyPeakGb(400_000);
        assertThat(skewed).isGreaterThan(balanced * 50);

        // And the exact count must actually reach the verdict: 400k in one
        // cluster is ~4,300 GB, blocked even on the 512 GB machine that the
        // balanced assumption would have waved through.
        Request skewedReq = new Request(Algorithm.LEIDEN, 1_000_000, 20);
        skewedReq.ripley = true;
        skewedReq.nClusters = 20;
        skewedReq.largestClusterSize = 400_000;
        skewedReq.clusterCountIsExact = true;
        skewedReq.availableRamGb = 512;
        assertThat(ScalingLimits.isBlocked(ScalingLimits.check(skewedReq))).isTrue();

        Request balancedReq = new Request(Algorithm.LEIDEN, 1_000_000, 20);
        balancedReq.ripley = true;
        balancedReq.nClusters = 20;
        balancedReq.availableRamGb = 512;
        assertThat(ScalingLimits.isBlocked(ScalingLimits.check(balancedReq))).isFalse();
    }

    @Test
    void findingsAreOrderedWorstFirst() {
        // Agglomerative at 200k needs ~298 GB (block); co-occurrence needs
        // ~16 GB against 24 GB of RAM (warn). The block must come first.
        Request r = new Request(Algorithm.AGGLOMERATIVE, 200_000, 20);
        r.coOccurrence = true;
        r.nClusters = 20;
        r.availableRamGb = 24;
        List<Finding> f = ScalingLimits.check(r);
        assertThat(f).hasSize(2);
        assertThat(f.get(0).severity()).isEqualTo(Severity.BLOCK);
        assertThat(f.get(1).severity()).isEqualTo(Severity.WARN);
    }

    @Test
    void everyFindingCarriesARemedy() {
        // A block that does not say what to do instead just moves the dead end.
        Request r = new Request(Algorithm.AGGLOMERATIVE, 1_000_000, 20);
        r.ripley = true;
        r.coOccurrence = true;
        r.availableRamGb = 16;
        List<Finding> findings = ScalingLimits.check(r);
        assertThat(findings).isNotEmpty();
        assertThat(findings).allSatisfy(f -> {
            assertThat(f.remedy()).isNotBlank();
            assertThat(f.describe()).isNotBlank();
        });
    }

    @Test
    void zeroCellsIsNotAnError() {
        // The dialog calls this before an image is open.
        assertThat(ScalingLimits.check(new Request(Algorithm.AGGLOMERATIVE, 0, 20))).isEmpty();
    }

    @Test
    void detectRamEitherMeasuresTheMachineOrSaysItCannot() {
        // No third option. A fabricated number here is what blocked a user's
        // 430k-cell Leiden run against an invented 8 GB on a machine QP-CAT
        // could not read.
        var ram = ScalingLimits.detectRamGb();
        if (ram.isPresent()) {
            assertThat(ram.getAsDouble()).isGreaterThan(0.5);
        }
    }

    @Test
    void anUnreadableMachineNeverBlocks() {
        // availableRamGb <= 0 with detection unavailable must degrade to a
        // warning, never a refusal -- we cannot refuse against a number we do
        // not have. Simulated by asking for a request whose RAM we leave unset
        // and asserting the CONTRACT: nothing blocked purely on memory unless a
        // real total was known.
        Request r = new Request(Algorithm.LEIDEN, 429_536, 20);
        r.nNeighbors = 50;
        r.availableRamGb = 0;   // ask the machine
        var findings = ScalingLimits.check(r);
        if (ScalingLimits.detectRamGb().isEmpty()) {
            assertThat(findings).noneMatch(f -> f.severity() == Severity.BLOCK);
        }
    }

    @Test
    void anUnknownMachineWarnsWithTheEstimateInsteadOfBlocking() {
        // The message has to carry the prediction and admit it cannot judge it.
        List<Finding> out = new ArrayList<>();
        ScalingLimits.addMemoryForTest(out, 7.8, java.util.OptionalDouble.empty(),
                "Leiden on 429,536 cells", "why", "Lower n_neighbors.");
        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.WARN);
            assertThat(f.predictedPeakGb()).isEqualTo(7.8);
            assertThat(f.remedy()).contains("could not read this machine's total memory");
        });
    }

    @Test
    void durationsReadNaturally() {
        assertThat(ScalingLimits.humanDuration(45)).isEqualTo("45 seconds");
        assertThat(ScalingLimits.humanDuration(600)).isEqualTo("10 minutes");
        assertThat(ScalingLimits.humanDuration(20_370)).isEqualTo("5.7 hours");
    }
}
