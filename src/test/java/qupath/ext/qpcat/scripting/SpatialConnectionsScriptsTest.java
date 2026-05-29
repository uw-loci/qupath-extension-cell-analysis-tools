package qupath.ext.qpcat.scripting;

import org.junit.jupiter.api.Test;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.DefaultPathObjectConnectionGroup;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectConnectionGroup;
import qupath.lib.objects.PathObjectConnections;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 invariants for {@link SpatialConnectionsScripts}. Exercises:
 *
 * <ul>
 *   <li>The same-class filter rebuild: 2 same-class cells + 2 different-
 *       class cells; after filter, the cross-class edge is dropped.</li>
 *   <li>Push-to-viewer with no saved result name raises an
 *       {@link IllegalArgumentException} (the no-result error path).</li>
 * </ul>
 */
@SuppressWarnings("deprecation")
class SpatialConnectionsScriptsTest {

    @Test
    void applySameClassFilterRebuildsGroupWithoutCrossClassEdges() {
        // Build 4 cells: A0 and A1 share class "A"; B0 and B1 share
        // class "B". Source group's edges: A0--A1, A0--B0, A1--B1.
        // After the filter, only A0--A1 should survive.
        PathClass classA = PathClass.fromString("A");
        PathClass classB = PathClass.fromString("B");

        PathObject a0 = newDet(0, 0, classA);
        PathObject a1 = newDet(10, 0, classA);
        PathObject b0 = newDet(0, 10, classB);
        PathObject b1 = newDet(10, 10, classB);
        List<PathObject> all = List.of(a0, a1, b0, b1);

        PathObjectConnectionGroup sourceView = new SimpleGroup(all)
                .addEdge(a0, a1).addEdge(a0, b0).addEdge(a1, b1);
        DefaultPathObjectConnectionGroup source =
                new DefaultPathObjectConnectionGroup(sourceView);

        ImageData<BufferedImage> imageData = new ImageData<>((ImageServer<BufferedImage>) null);
        PathObjectHierarchy h = imageData.getHierarchy();
        h.addObject(a0); h.addObject(a1); h.addObject(b0); h.addObject(b1);
        PathObjectConnections connections = new PathObjectConnections();
        connections.addGroup(source);
        imageData.setProperty(DefaultPathObjectConnectionGroup.KEY_OBJECT_CONNECTIONS, connections);

        SpatialConnectionsScripts.applySameClassFilter(imageData, true);

        // After filter: a single group attached; cross-class edges gone.
        Object o = imageData.getProperty(DefaultPathObjectConnectionGroup.KEY_OBJECT_CONNECTIONS);
        assertThat(o).isInstanceOf(PathObjectConnections.class);
        PathObjectConnections post = (PathObjectConnections) o;
        assertThat(post.getConnectionGroups()).hasSize(1);
        PathObjectConnectionGroup filtered = post.getConnectionGroups().get(0);

        assertThat(filtered.getConnectedObjects(a0)).containsExactly(a1);
        assertThat(filtered.getConnectedObjects(a1)).containsExactly(a0);
        assertThat(filtered.getConnectedObjects(b0)).isEmpty();
        assertThat(filtered.getConnectedObjects(b1)).isEmpty();

        // Source group is stashed for restore.
        assertThat(imageData.getProperty(
                SpatialConnectionsScripts.KEY_OVERLAY_SOURCE_GROUP)).isNotNull();

        // Toggle off restores the source.
        SpatialConnectionsScripts.applySameClassFilter(imageData, false);
        post = (PathObjectConnections) imageData.getProperty(
                DefaultPathObjectConnectionGroup.KEY_OBJECT_CONNECTIONS);
        assertThat(post.getConnectionGroups()).hasSize(1);
        PathObjectConnectionGroup restored = post.getConnectionGroups().get(0);
        // a0 had 2 edges in the source; both restored.
        assertThat(restored.getConnectedObjects(a0)).hasSize(2);
        assertThat(imageData.getProperty(
                SpatialConnectionsScripts.KEY_OVERLAY_SOURCE_GROUP)).isNull();
    }

    @Test
    void pushConnectionsToViewerRejectsBlankResultName() {
        ImageData<BufferedImage> imageData = new ImageData<>((ImageServer<BufferedImage>) null);
        assertThatThrownBy(() ->
                SpatialConnectionsScripts.pushConnectionsToViewer(imageData, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                SpatialConnectionsScripts.pushConnectionsToViewer(imageData, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                SpatialConnectionsScripts.pushConnectionsToViewer(null, "result"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pushConnectionsToViewerRaisesWhenNoProjectAvailable() {
        // No QuPathGUI in the test JVM means resolveProject() returns null;
        // the facade should raise IllegalStateException with a clear
        // message about the missing project.
        ImageData<BufferedImage> imageData = new ImageData<>((ImageServer<BufferedImage>) null);
        assertThatThrownBy(() ->
                SpatialConnectionsScripts.pushConnectionsToViewer(imageData, "no-such-result"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("project");
    }

    // ---- Test helpers ----

    private static PathObject newDet(double x, double y, PathClass cls) {
        return PathObjects.createDetectionObject(
                ROIs.createRectangleROI(x, y, 4, 4, ImagePlane.getDefaultPlane()),
                cls);
    }

    /**
     * Minimal mutable PathObjectConnectionGroup used only to seed the
     * copy-constructor source for tests.
     */
    private static final class SimpleGroup implements PathObjectConnectionGroup {
        private final List<PathObject> objects;
        private final java.util.Map<PathObject, List<PathObject>> map = new java.util.HashMap<>();

        SimpleGroup(List<PathObject> objects) {
            this.objects = new ArrayList<>(objects);
            for (PathObject p : objects) map.put(p, new ArrayList<>());
        }

        SimpleGroup addEdge(PathObject a, PathObject b) {
            map.get(a).add(b);
            map.get(b).add(a);
            return this;
        }

        @Override
        public boolean containsObject(PathObject pathObject) {
            return map.containsKey(pathObject);
        }

        @Override
        public Collection<PathObject> getPathObjects() {
            return objects;
        }

        @Override
        public List<PathObject> getConnectedObjects(PathObject pathObject) {
            return map.getOrDefault(pathObject, Collections.emptyList());
        }
    }
}
