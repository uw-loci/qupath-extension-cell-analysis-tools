package qupath.ext.qpcat.service;

import java.util.List;

import org.junit.jupiter.api.Test;

import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResultApplier#applySubclusterLabels} names a parent cluster's cells with
 * hierarchical "&lt;parent&gt;.N" classes. These are the labels the
 * "Sub-cluster..." action writes onto the detections, and the names
 * {@code ClusteringWorkflow.buildSubclusterNames} has to reproduce exactly --
 * if the two drift, "Manage Clusters" shows names nothing on the cells carries.
 */
class SubclusterLabelsTest {

    private static PathObject detection() {
        return PathObjects.createDetectionObject(
                ROIs.createRectangleROI(0, 0, 1, 1, ImagePlane.getDefaultPlane()));
    }

    @Test
    void appliesHierarchicalParentDotLabelNames() {
        PathObject a = detection();
        PathObject b = detection();
        PathObject c = detection();
        new ResultApplier().applySubclusterLabels(
                List.of(a, b, c), new int[] {0, 1, 0}, "Cluster 3");
        assertThat(a.getPathClass().toString()).isEqualTo("Cluster 3.0");
        assertThat(b.getPathClass().toString()).isEqualTo("Cluster 3.1");
        assertThat(c.getPathClass().toString()).isEqualTo("Cluster 3.0");
    }

    @Test
    void sameSubLabelResolvesToOneClassInstance() {
        PathObject a = detection();
        PathObject c = detection();
        new ResultApplier().applySubclusterLabels(
                List.of(a, c), new int[] {0, 0}, "Cluster 3");
        assertThat(a.getPathClass()).isSameAs(c.getPathClass());
    }

    @Test
    void aRenamedParentIsHonoured() {
        PathObject a = detection();
        new ResultApplier().applySubclusterLabels(
                List.of(a), new int[] {2}, "Macrophage");
        assertThat(a.getPathClass().toString()).isEqualTo("Macrophage.2");
    }

    /**
     * Noise is NOT routed to "Noise (unclustered)" the way top-level clustering
     * does it -- sub-clustering names it "&lt;parent&gt;.-1" like any other
     * label. The saved result's default names must match, so this pins the
     * behaviour the name map is built against.
     */
    @Test
    void negativeNoiseLabelsAreNamedLikeAnyOther() {
        PathObject a = detection();
        PathObject b = detection();
        new ResultApplier().applySubclusterLabels(
                List.of(a, b), new int[] {-1, 0}, "Cluster 3");
        assertThat(a.getPathClass().toString()).isEqualTo("Cluster 3.-1");
        assertThat(b.getPathClass().toString()).isEqualTo("Cluster 3.0");
    }
}
