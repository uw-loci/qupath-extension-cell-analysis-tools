package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CompositionFigure} is the single tally behind three consumers -- the
 * Results-window Composition tabs, the batch figure exporter, and the headless
 * YAML batch. These tests pin the contract those three share: the arithmetic,
 * the CSV shape, and that rendering never throws on the degenerate inputs a
 * real result can actually contain.
 */
class CompositionFigureTest {

    // Three images, three clusters, deliberately unbalanced.
    private static CompositionFigure sample() {
        int[] labels =    {0, 0, 1, 1, 1, 2,  0, 2, 2,  1};
        String[] groups = {"a", "a", "a", "a", "a", "a",  "b", "b", "b",  "c"};
        return CompositionFigure.tally(labels, 3, groups, "Image");
    }

    @Test
    void tallyCountsPerGroupAndPerCluster() {
        CompositionFigure f = sample();
        assertThat(f.getGroups()).containsExactly("a", "b", "c");
        assertThat(f.countsFor("a")).containsExactly(2, 3, 1);
        assertThat(f.countsFor("b")).containsExactly(1, 0, 2);
        assertThat(f.countsFor("c")).containsExactly(0, 1, 0);
        assertThat(f.groupTotal("a")).isEqualTo(6);
        assertThat(f.getClusterTotals()).containsExactly(3, 4, 3);
        assertThat(f.getGrandTotal()).isEqualTo(10);
    }

    @Test
    void noiseLabelsAreExcludedNotCharted() {
        // HDBSCAN and Leiden-with-noise emit -1. Charting it as a cluster would
        // both invent a slice and inflate every percentage denominator.
        int[] labels = {0, -1, -1, 1};
        String[] groups = {"a", "a", "a", "a"};
        CompositionFigure f = CompositionFigure.tally(labels, 2, groups, "Image");
        assertThat(f.getGrandTotal()).isEqualTo(2);
        assertThat(f.groupTotal("a")).isEqualTo(2);
    }

    @Test
    void outOfRangeLabelsAreExcluded() {
        int[] labels = {0, 7};
        CompositionFigure f = CompositionFigure.tally(labels, 2, new String[]{"a", "a"}, "Image");
        assertThat(f.getGrandTotal()).isEqualTo(1);
    }

    @Test
    void nullGroupsBucketUnderNone() {
        int[] labels = {0, 0, 1};
        String[] groups = {"a", null, null};
        CompositionFigure f = CompositionFigure.tally(labels, 2, groups, "Annotation");
        assertThat(f.getGroups()).contains(CompositionFigure.NONE_LABEL);
        assertThat(f.groupTotal(CompositionFigure.NONE_LABEL)).isEqualTo(2);
    }

    @Test
    void shortGroupArrayDoesNotThrow() {
        // Defensive: a saved result whose per-cell arrays disagree in length
        // must degrade to "(none)", not blow up the export.
        int[] labels = {0, 0, 0};
        CompositionFigure f = CompositionFigure.tally(labels, 1, new String[]{"a"}, "Image");
        assertThat(f.getGrandTotal()).isEqualTo(3);
        assertThat(f.groupTotal(CompositionFigure.NONE_LABEL)).isEqualTo(2);
    }

    @Test
    void csvCarriesBothCountsAndPercentagesPlusAnAllRow() {
        String csv = sample().toCsv();
        List<String> lines = csv.lines().toList();
        // header + 3 groups + all-groups
        assertThat(lines).hasSize(5);
        assertThat(lines.get(0))
                .isEqualTo("Image,Cluster 0 (n),Cluster 0 (%),Cluster 1 (n),Cluster 1 (%),"
                        + "Cluster 2 (n),Cluster 2 (%),Total");
        // Group "a": 2/6, 3/6, 1/6
        assertThat(lines.get(1)).isEqualTo("a,2,33.33,3,50.00,1,16.67,6");
        assertThat(lines.get(4)).startsWith("All images,3,30.00,4,40.00,3,30.00,10");
    }

    @Test
    void csvQuotesGroupNamesContainingCommas() {
        // Real image names do contain commas ("slide 3, region B.ome.tif").
        CompositionFigure f = CompositionFigure.tally(
                new int[]{0}, 1, new String[]{"slide 3, region B"}, "Image");
        assertThat(f.toCsv()).contains("\"slide 3, region B\"");
    }

    @Test
    void percentagesAreZeroNotNaNForAnEmptyGroup() {
        // A group can end up empty when every one of its cells was noise.
        CompositionFigure f = CompositionFigure.tally(
                new int[]{-1}, 2, new String[]{"a"}, "Image");
        assertThat(f.toCsv()).doesNotContain("NaN");
    }

    @Test
    void renderProducesAnImageSizedByTheGroupCount() {
        BufferedImage one = CompositionFigure.tally(
                new int[]{0}, 1, new String[]{"a"}, "Image")
                .render(1.0, c -> 0xFF0000);
        BufferedImage many = CompositionFigure.tally(
                new int[]{0, 0, 0, 0, 0}, 1,
                new String[]{"a", "b", "c", "d", "e"}, "Image")
                .render(1.0, c -> 0xFF0000);
        assertThat(many.getHeight()).isGreaterThan(one.getHeight());
        assertThat(one.getWidth()).isGreaterThan(0);
    }

    @Test
    void renderScalesWithoutUpsampling() {
        CompositionFigure f = sample();
        BufferedImage at1 = f.render(1.0, c -> 0x336699);
        BufferedImage at2 = f.render(2.0, c -> 0x336699);
        assertThat(at2.getWidth()).isEqualTo(at1.getWidth() * 2);
        assertThat(at2.getHeight()).isEqualTo(at1.getHeight() * 2);
    }

    @Test
    void renderSurvivesAnEmptyTally() {
        // Everything was noise: still produce a readable stub, because an export
        // that throws here would abort a whole batch over an empty pie.
        CompositionFigure f = CompositionFigure.tally(
                new int[]{-1, -1}, 3, new String[]{"a", "a"}, "Image");
        assertThat(f.isEmpty()).isTrue();
        BufferedImage img = f.render(1.0, c -> 0x000000);
        assertThat(img).isNotNull();
        assertThat(img.getWidth()).isGreaterThan(0);
    }

    @Test
    void renderSurvivesNullLabels() {
        CompositionFigure f = CompositionFigure.tally(null, 3, null, "Image");
        assertThat(f.isEmpty()).isTrue();
        assertThat(f.render(1.0, null)).isNotNull();
        assertThat(f.toCsv()).isNotBlank();
    }

    @Test
    void dpiScaleIsClampedSoAHugeDpiCannotAllocateAGigapixel() {
        assertThat(CompositionFigure.scaleForDpi(96)).isEqualTo(1.0);
        assertThat(CompositionFigure.scaleForDpi(300)).isCloseTo(300 / 96.0, within(1e-9));
        assertThat(CompositionFigure.scaleForDpi(4800)).isEqualTo(4.0);
        assertThat(CompositionFigure.scaleForDpi(0)).isEqualTo(1.0);
        assertThat(CompositionFigure.scaleForDpi(-5)).isEqualTo(1.0);
    }

    @Test
    void titleMatchesTheInteractivePanelHeader() {
        assertThat(sample().title()).isEqualTo("10 cells across 3 images, 3 clusters");
        CompositionFigure single = CompositionFigure.tally(
                new int[]{0}, 1, new String[]{"a"}, "Annotation");
        assertThat(single.title()).isEqualTo("1 cells across 1 annotation, 1 clusters");
    }

    private static org.assertj.core.data.Offset<Double> within(double v) {
        return org.assertj.core.data.Offset.offset(v);
    }
}
