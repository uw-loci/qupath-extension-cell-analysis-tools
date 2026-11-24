package qupath.ext.qpcat.controller;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import qupath.ext.qpcat.controller.ClusteringWorkflow.SpatialFrame;
import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.ext.qpcat.service.MeasurementExtractor.ImageSegment;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The unit of a clustering run's spatial outputs: microns with each image's own
 * pixel size when every image has one, pixels when none has, and a refusal
 * naming the images when the run mixes the two and writes a micron output.
 */
class SpatialPixelSizeRuleTest {

    @SuppressWarnings("unchecked")
    private static ImageData<BufferedImage> image(double um) {
        ImageServer<BufferedImage> server = Mockito.mock(ImageServer.class);
        PixelCalibration cal = Double.isNaN(um) ? PixelCalibration.getDefaultInstance()
                : new PixelCalibration.Builder().pixelSizeMicrons(um, um).build();
        Mockito.when(server.getPixelCalibration()).thenReturn(cal);
        return new ImageData<>(server);
    }

    /** A multi-image run: one segment of ten cells per pixel size (NaN = uncalibrated). */
    private static SpatialFrame run(double... pixelSizes) {
        List<ImageSegment> segs = new ArrayList<>();
        for (int i = 0; i < pixelSizes.length; i++) {
            segs.add(new ImageSegment(null, image(pixelSizes[i]), 10 * i, 10 * i + 10));
        }
        // The viewer shows a calibrated image that is not part of the run.
        return ClusteringWorkflow.spatialFrame(segs, image(0.123), "open.tif", "open-id");
    }

    private static List<String> names(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add("img" + i + ".tif");
        return out;
    }

    private static ClusteringConfig spatialRun() {
        ClusteringConfig config = new ClusteringConfig();
        config.setEnableSpatialAnalysis(true);
        return config;
    }

    // ---- (i) no image has a pixel size ----

    @Test
    void anUncalibratedRunStaysInPixelsAndIsNotRefused() {
        SpatialFrame frame = run(Double.NaN, Double.NaN);
        assertThat(frame.unit()).isEqualTo("px");
        assertThat(frame.mixed()).isFalse();
        assertThat(frame.pixelSizesUm()).containsExactly(Double.NaN, Double.NaN);
        assertThat(ClusteringWorkflow.micronOutputRefusal(spatialRun(), frame, names(2))).isNull();
    }

    @Test
    void theImageInTheViewerIsNotUsedForAMultiImageRun() {
        // It used to supply the pixel size sent to Python when no image in the run had one.
        assertThat(run(Double.NaN, Double.NaN).byImageName()).isEmpty();
        assertThat(run(Double.NaN, Double.NaN).inMicrons()).isFalse();
    }

    // ---- (ii) some images have a pixel size, some do not ----

    @Test
    void aMixedRunThatWritesPerCellDistancesIsRefusedNamingTheImages() {
        SpatialFrame frame = run(Double.NaN, 0.5, 0.25, Double.NaN);
        assertThat(frame.unit()).isEqualTo("px");
        assertThat(frame.mixed()).isTrue();

        String refusal = ClusteringWorkflow.micronOutputRefusal(spatialRun(), frame, names(4));
        assertThat(refusal)
                .contains("needs a pixel size on every image.")
                .contains("No pixel size on these images: img0.tif, img3.tif.")
                .contains("Pixel width")
                .contains("untick 'Write per-cell node measurements'");
        assertThat(refusal.chars().allMatch(c -> c < 128)).isTrue();
    }

    @Test
    void theRefusalListsFiveNamesThenACount() {
        double[] sizes = new double[9];
        java.util.Arrays.fill(sizes, Double.NaN);
        sizes[8] = 0.5;
        String refusal = ClusteringWorkflow.micronOutputRefusal(spatialRun(), run(sizes), names(9));
        assertThat(refusal).contains("img0.tif, img1.tif, img2.tif, img3.tif, img4.tif and 3 more.");
    }

    @Test
    void aMixedRunWithNoSpatialOutputStillRuns() {
        assertThat(ClusteringWorkflow.micronOutputRefusal(
                new ClusteringConfig(), run(Double.NaN, 0.5), names(2))).isNull();
    }

    @Test
    void aMixedRunWithThePerCellColumnsOffRunsInPixels() {
        ClusteringConfig config = spatialRun();
        config.setWriteNodeMeasurements(false);
        assertThat(ClusteringWorkflow.micronOutputRefusal(config, run(Double.NaN, 0.5), names(2)))
                .isNull();
    }

    @Test
    void aMixedRunWithAMicronDelaunayEdgeIsRefused() {
        ClusteringConfig config = spatialRun();
        config.setWriteNodeMeasurements(false);
        config.setSpatialGraphType("delaunay");
        config.setDelaunayMaxEdgeUm(40);
        assertThat(ClusteringWorkflow.micronOutputRefusal(config, run(Double.NaN, 0.5), names(2)))
                .startsWith("The Delaunay max edge is set in microns, so this run needs a pixel size")
                .contains("No pixel size on this image: img0.tif.")
                .contains("clear the Delaunay max edge");
    }

    // ---- (iii) every image has a pixel size, and they differ ----

    @Test
    void differingPixelSizesAreEachKeptForTheirOwnImage() {
        SpatialFrame frame = run(0.5, 0.25, 1.0);
        assertThat(frame.unit()).isEqualTo("um");
        assertThat(frame.pixelSizesUm()).containsExactly(0.5, 0.25, 1.0);
        assertThat(frame.starts()).containsExactly(0, 10, 20);
        assertThat(ClusteringWorkflow.micronOutputRefusal(spatialRun(), frame, names(3))).isNull();
    }

    // ---- a single-image run ----

    @Test
    void aSingleImageRunTakesTheOpenImagesPixelSize() {
        // extract() gives a single-image run one segment with no image attached;
        // it used to read as uncalibrated, so the run reported pixels.
        List<ImageSegment> single = List.of(new ImageSegment(null, null, 0, 10));
        SpatialFrame frame = ClusteringWorkflow.spatialFrame(single, image(0.5), "open.tif", "open-id");
        assertThat(frame.unit()).isEqualTo("um");
        assertThat(frame.pixelSizesUm()).containsExactly(0.5);
        assertThat(frame.byImageName()).containsEntry("open.tif", 0.5);
        assertThat(frame.byImageId()).containsEntry("open-id", 0.5);
    }

    @Test
    void aSingleUncalibratedImageRunsInPixels() {
        List<ImageSegment> single = List.of(new ImageSegment(null, null, 0, 10));
        SpatialFrame frame = ClusteringWorkflow.spatialFrame(single, image(Double.NaN), "open.tif", "open-id");
        assertThat(frame.unit()).isEqualTo("px");
        assertThat(frame.mixed()).isFalse();
        assertThat(ClusteringWorkflow.micronOutputRefusal(spatialRun(), frame, List.of("open.tif")))
                .isNull();
    }
}
