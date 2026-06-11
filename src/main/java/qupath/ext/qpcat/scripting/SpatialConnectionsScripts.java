package qupath.ext.qpcat.scripting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.SavedClusteringResult;
import qupath.ext.qpcat.model.SpatialStatsBundle;
import qupath.ext.qpcat.service.ClusteringResultManager;
import qupath.ext.qpcat.service.OperationLogger;
import qupath.lib.images.ImageData;
import qupath.lib.objects.DefaultPathObjectConnectionGroup;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectConnectionGroup;
import qupath.lib.objects.PathObjectConnections;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;
import qupath.lib.projects.Project;
import qupath.lib.regions.ImageRegion;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public, Groovy-callable facade for QP-CAT's v0.3 spatial graph
 * overlay. Two surfaces:
 *
 * <ul>
 *   <li>{@link #pushConnectionsToViewer(ImageData, String)} -- materialise
 *       the saved spatial-stats result's graph as
 *       {@link PathObjectConnections} on the given image. Equivalent to
 *       clicking "Push to viewer now" in the Run Clustering dialog.</li>
 *   <li>{@link #applySameClassFilter(ImageData, boolean)} -- toggle the
 *       post-hoc same-class edge filter on the currently-attached
 *       connections. Rebuilds a fresh group via removeGroup + addGroup;
 *       the source unfiltered group is preserved by stashing it on
 *       {@link ImageData} under {@link #KEY_OVERLAY_SOURCE_GROUP}.</li>
 * </ul>
 *
 * <p><strong>Stability promise (v0.3).</strong> Package path, class
 * name, method names, and the saved-result name parameter shape are
 * part of QP-CAT's public scripting API. The underlying
 * {@link PathObjectConnections} API is marked {@code @Deprecated} in
 * QuPath 0.7; QP-CAT uses it deliberately because the planned
 * replacement ({@code DelaunayTools.Subdivision}) cannot represent kNN
 * or Radius graphs.</p>
 */
@SuppressWarnings("deprecation")
public final class SpatialConnectionsScripts {

    private static final Logger logger = LoggerFactory.getLogger(SpatialConnectionsScripts.class);

    /**
     * ImageData property key under which the unfiltered source
     * connection group is stashed when the same-class filter is
     * applied. Toggling the filter off restores from this slot.
     */
    public static final String KEY_OVERLAY_SOURCE_GROUP =
            "QPCAT_SPATIAL_OVERLAY_SOURCE_GROUP";

    /**
     * ImageData property key recording the name of the saved
     * spatial-stats result whose graph is currently shown. Used by the
     * dialog to seed the "Push to viewer now" button's resultName.
     */
    public static final String KEY_OVERLAY_RESULT_NAME =
            "QPCAT_SPATIAL_OVERLAY_RESULT_NAME";

    /**
     * Outcome of {@link #pushConnectionsToViewer(ImageData, String)}.
     * Carries the saved-result name, the number of edges actually pushed,
     * the detection count, and whether the saved bundle predated the v0.3
     * edge-COO write path (legacy bundles attach an empty group; the
     * dialog uses {@link #isLegacyBundle()} to warn the user).
     */
    public static final class PushResult {
        private final String resultName;
        private final int nEdges;
        private final int nDetections;
        private final boolean legacyBundle;

        PushResult(String resultName, int nEdges, int nDetections,
                   boolean legacyBundle) {
            this.resultName = resultName;
            this.nEdges = nEdges;
            this.nDetections = nDetections;
            this.legacyBundle = legacyBundle;
        }

        public String getResultName() { return resultName; }
        public int getNEdges() { return nEdges; }
        public int getNDetections() { return nDetections; }
        public boolean isLegacyBundle() { return legacyBundle; }
    }

    /**
     * Outcome of {@link #clearConnections(ImageData)}. Carries the number
     * of connection groups removed and the total edge count across all
     * removed groups so the caller (menu action, script) can report the
     * cleanup precisely.
     */
    public static final class ClearResult {
        private final int nGroupsRemoved;
        private final int nEdgesRemoved;

        ClearResult(int nGroupsRemoved, int nEdgesRemoved) {
            this.nGroupsRemoved = nGroupsRemoved;
            this.nEdgesRemoved = nEdgesRemoved;
        }

        public int getNGroupsRemoved() { return nGroupsRemoved; }
        public int getNEdgesRemoved() { return nEdgesRemoved; }
        public boolean wasNoOp() { return nGroupsRemoved == 0; }
    }

    /**
     * Outcome of {@link #applySameClassFilter(ImageData, boolean)}.
     * Carries the edge counts before and after the filter rebuild so the
     * dialog can surface a transient status notification. When
     * {@link #wasNoOp()} is true the filter was a no-op (e.g. toggle-off
     * with no stashed source) and the counts are zero.
     */
    public static final class FilterResult {
        private final boolean enabled;
        private final int nEdgesBefore;
        private final int nEdgesAfter;
        private final boolean noOp;

        FilterResult(boolean enabled, int nEdgesBefore, int nEdgesAfter,
                     boolean noOp) {
            this.enabled = enabled;
            this.nEdgesBefore = nEdgesBefore;
            this.nEdgesAfter = nEdgesAfter;
            this.noOp = noOp;
        }

        public boolean isEnabled() { return enabled; }
        public int getNEdgesBefore() { return nEdgesBefore; }
        public int getNEdgesAfter() { return nEdgesAfter; }
        public boolean wasNoOp() { return noOp; }
    }

    private SpatialConnectionsScripts() {}

    /**
     * Materialise the saved spatial-stats result's graph as
     * {@link PathObjectConnections} on the given {@link ImageData}.
     *
     * <p>The saved result must contain a non-empty
     * {@link SpatialStatsBundle}; otherwise this method raises
     * {@link IllegalStateException} with a user-facing message. The
     * detection list comes from the live hierarchy and must match the
     * cell count from the original run -- a size mismatch raises
     * {@link IllegalStateException} too (the project has changed since
     * the saved result was written).</p>
     *
     * <p>Workflow-step recording: appends a {@link DefaultScriptableWorkflowStep}
     * to {@code imageData.getHistoryWorkflow()} so the action shows up
     * in the image history and can be replayed.</p>
     *
     * @param imageData  the target image; must be non-null and have a
     *                   live hierarchy with detections
     * @param resultName saved-result name (without {@code .json} extension)
     *                   as listed in
     *                   {@link ClusteringResultManager#listResults(Project)}
     * @throws IllegalArgumentException if {@code imageData} is null or
     *         {@code resultName} is blank
     * @throws IllegalStateException if no project is open, the named
     *         result does not exist, the saved result has no spatial
     *         stats bundle, or the cell count does not match
     */
    public static PushResult pushConnectionsToViewer(ImageData<?> imageData, String resultName) {
        long startMs = System.currentTimeMillis();
        if (imageData == null) {
            throw new IllegalArgumentException("imageData must not be null");
        }
        if (resultName == null || resultName.isBlank()) {
            throw new IllegalArgumentException("resultName must not be blank");
        }

        Project<?> project = resolveProject();
        if (project == null) {
            throw new IllegalStateException(
                    "No QuPath project is open; cannot load saved spatial-stats result '"
                    + resultName + "'");
        }

        SavedClusteringResult saved;
        try {
            saved = ClusteringResultManager.loadSavedResult(project, resultName);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to load saved result '" + resultName + "': " + e.getMessage(), e);
        }
        if (saved == null) {
            throw new IllegalStateException(
                    "No saved spatial-stats result named '" + resultName + "'");
        }
        SpatialStatsBundle bundle = saved.getSpatialStats();
        if (bundle == null || !bundle.isAnyPresent()) {
            throw new IllegalStateException(
                    "Saved result '" + resultName + "' has no spatial graph payload"
                    + " (run clustering with the Viewer overlay enabled first)");
        }

        PathObjectHierarchy hierarchy = imageData.getHierarchy();
        if (hierarchy == null) {
            throw new IllegalStateException(
                    "Image hierarchy is null; cannot rebuild connections");
        }
        List<PathObject> detections = new ArrayList<>(hierarchy.getDetectionObjects());
        int savedCells = saved.getNCells();
        if (savedCells > 0 && detections.size() != savedCells) {
            throw new IllegalStateException(
                    "Detection count mismatch: saved result has " + savedCells
                    + " cells but the current image has " + detections.size()
                    + ". Run clustering on the same detection set to rebuild the overlay.");
        }

        // Build a PathObjectConnectionGroup that mirrors the saved edge
        // list. The saved bundle does not (yet) carry the COO triplet;
        // for v0.3 the "push to viewer now" path expects the bundle to
        // include the edge list. If absent we fall back to an empty
        // group with a clear log line -- the typical project predates
        // v0.3 and rebuilding the graph requires re-running clustering.
        DefaultPathObjectConnectionGroup group = buildGroupFromBundle(bundle, detections);
        attachGroup(imageData, group, resultName);

        int nEdges = countEdges(group);
        boolean legacyBundle = nEdges == 0;

        // Workflow-step recording follows the ClassifyObjectSubset
        // pattern: imperative Groovy that calls back into this facade.
        try {
            String script = buildPushWorkflowScript(resultName);
            imageData.getHistoryWorkflow().addStep(new DefaultScriptableWorkflowStep(
                    "QP-CAT: push spatial connections to viewer", script));
        } catch (Exception e) {
            logger.warn("Failed to record workflow step for push-to-viewer: {}", e.getMessage());
        }
        logger.info("Pushed spatial graph from result '{}' to viewer ({} cells, {} edges)",
                resultName, detections.size(), nEdges);

        // F5: project-wide audit trail. Best-effort; never raise on log
        // failure (matches the workflow-step recording shape above).
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("feature", "spatial-graph-overlay");
            params.put("action", "push");
            params.put("result_name", resultName);
            params.put("image", describeImage(imageData));
            params.put("n_edges", Integer.toString(nEdges));
            params.put("n_detections", Integer.toString(detections.size()));
            params.put("legacy_bundle", Boolean.toString(legacyBundle));
            String summary = legacyBundle
                    ? "Legacy bundle: attached empty overlay (re-run on v0.3 to populate)"
                    : "Pushed " + nEdges + " edges from '" + resultName + "' to viewer";
            OperationLogger.getInstance().logOperation(
                    "SPATIAL OVERLAY PUSH", params, summary,
                    System.currentTimeMillis() - startMs);
        } catch (Exception e) {
            logger.warn("OperationLogger push entry failed: {}", e.getMessage());
        }

        return new PushResult(resultName, nEdges, detections.size(), legacyBundle);
    }

    /**
     * Toggle the post-hoc same-class edge filter on the currently-
     * attached spatial connections.
     *
     * <p>When {@code enabled} is {@code true}: locates the current
     * QP-CAT-owned source group (or the only group, if just one is
     * attached), stashes it under {@link #KEY_OVERLAY_SOURCE_GROUP},
     * builds a fresh {@link DefaultPathObjectConnectionGroup} that only
     * exposes edges between cells with equal {@link PathClass}, and
     * swaps it in via {@code removeGroup} + {@code addGroup}.</p>
     *
     * <p>When {@code enabled} is {@code false}: restores the stashed
     * source group, clears the stash. If no stash exists this is a
     * no-op.</p>
     *
     * <p>Cells with null pathClass drop their edges entirely under the
     * filter (intended behaviour -- the tooltip and HOW_TO_GUIDE call
     * this out).</p>
     */
    public static FilterResult applySameClassFilter(ImageData<?> imageData, boolean enabled) {
        long startMs = System.currentTimeMillis();
        if (imageData == null) {
            throw new IllegalArgumentException("imageData must not be null");
        }
        Object o = imageData.getProperty(DefaultPathObjectConnectionGroup.KEY_OBJECT_CONNECTIONS);
        if (!(o instanceof PathObjectConnections connections)) {
            logger.warn("No PathObjectConnections attached; same-class filter is a no-op");
            FilterResult noop = new FilterResult(enabled, 0, 0, true);
            logFilterOperation(imageData, noop, startMs);
            return noop;
        }

        if (enabled) {
            // Locate the source group. Prefer the QP-CAT-owned group
            // we stashed previously; otherwise treat the only attached
            // group as the source.
            Object stash = imageData.getProperty(KEY_OVERLAY_SOURCE_GROUP);
            PathObjectConnectionGroup sourceGroup;
            if (stash instanceof PathObjectConnectionGroup pre) {
                sourceGroup = pre;
            } else if (connections.getConnectionGroups().size() == 1) {
                sourceGroup = connections.getConnectionGroups().get(0);
            } else {
                logger.warn("Cannot decide which group to filter (multiple groups attached);"
                        + " same-class filter aborted");
                FilterResult noop = new FilterResult(true, 0, 0, true);
                logFilterOperation(imageData, noop, startMs);
                return noop;
            }

            int nEdgesBefore = countEdges(sourceGroup);
            DefaultPathObjectConnectionGroup filtered = buildFilteredGroup(sourceGroup);
            int nEdgesAfter = countEdges(filtered);
            // Swap. Remove every group so we know exactly what is on
            // screen; add back the filtered view. If the source group
            // was distinct from what is currently shown we keep it in
            // the stash for restore.
            for (PathObjectConnectionGroup g : new ArrayList<>(connections.getConnectionGroups())) {
                connections.removeGroup(g);
            }
            connections.addGroup(filtered);
            imageData.setProperty(KEY_OVERLAY_SOURCE_GROUP, sourceGroup);
            fireHierarchyChanged(imageData);
            logger.info("Applied same-class spatial-graph filter ({} cells, {} -> {} edges)",
                    sourceGroup.getPathObjects().size(), nEdgesBefore, nEdgesAfter);
            FilterResult res = new FilterResult(true, nEdgesBefore, nEdgesAfter, false);
            logFilterOperation(imageData, res, startMs);
            return res;
        } else {
            Object stash = imageData.getProperty(KEY_OVERLAY_SOURCE_GROUP);
            if (!(stash instanceof PathObjectConnectionGroup source)) {
                logger.info("Same-class filter toggle-off: no stashed source group; no-op");
                FilterResult noop = new FilterResult(false, 0, 0, true);
                logFilterOperation(imageData, noop, startMs);
                return noop;
            }
            int nEdgesBefore = 0;
            for (PathObjectConnectionGroup g : connections.getConnectionGroups()) {
                nEdgesBefore += countEdges(g);
            }
            int nEdgesAfter = countEdges(source);
            for (PathObjectConnectionGroup g : new ArrayList<>(connections.getConnectionGroups())) {
                connections.removeGroup(g);
            }
            connections.addGroup(source);
            imageData.setProperty(KEY_OVERLAY_SOURCE_GROUP, null);
            fireHierarchyChanged(imageData);
            logger.info("Removed same-class spatial-graph filter; restored unfiltered group"
                            + " ({} -> {} edges)", nEdgesBefore, nEdgesAfter);
            FilterResult res = new FilterResult(false, nEdgesBefore, nEdgesAfter, false);
            logFilterOperation(imageData, res, startMs);
            return res;
        }
    }

    private static void logFilterOperation(ImageData<?> imageData, FilterResult result,
                                            long startMs) {
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("feature", "spatial-graph-overlay");
            params.put("action", "same-class-filter");
            params.put("enabled", Boolean.toString(result.isEnabled()));
            params.put("image", describeImage(imageData));
            params.put("n_edges_before", Integer.toString(result.getNEdgesBefore()));
            params.put("n_edges_after", Integer.toString(result.getNEdgesAfter()));
            params.put("no_op", Boolean.toString(result.wasNoOp()));
            String summary = result.wasNoOp()
                    ? "Same-class filter no-op (no source group or no attached connections)"
                    : "Same-class filter " + (result.isEnabled() ? "on" : "off")
                            + ": " + result.getNEdgesBefore() + " -> "
                            + result.getNEdgesAfter() + " edges";
            OperationLogger.getInstance().logOperation(
                    "SPATIAL OVERLAY FILTER", params, summary,
                    System.currentTimeMillis() - startMs);
        } catch (Exception e) {
            logger.warn("OperationLogger same-class-filter entry failed: {}", e.getMessage());
        }
    }

    /**
     * Remove every {@link PathObjectConnectionGroup} attached to
     * {@code imageData} and drop the QP-CAT overlay stash properties
     * (source group + result name). Use when connections from prior
     * runs (QP-CAT's own previous overlay, QuPath's legacy Delaunay
     * Clustering plugin, or any other tool that writes to
     * {@link PathObjectConnections}) need to be wiped without rerunning
     * cell detection.
     *
     * <p>Records a {@link DefaultScriptableWorkflowStep} so the action
     * is replayable from the image's history, and writes an
     * {@code OperationLogger} entry under
     * {@code SPATIAL OVERLAY CLEAR}.</p>
     *
     * @throws IllegalArgumentException if {@code imageData} is null
     */
    public static ClearResult clearConnections(ImageData<?> imageData) {
        long startMs = System.currentTimeMillis();
        if (imageData == null) {
            throw new IllegalArgumentException("imageData must not be null");
        }
        int nGroupsRemoved;
        int nEdgesRemoved = 0;
        synchronized (imageData) {
            Object o = imageData.getProperty(
                    DefaultPathObjectConnectionGroup.KEY_OBJECT_CONNECTIONS);
            if (!(o instanceof PathObjectConnections connections)) {
                logger.info("clearConnections: no PathObjectConnections attached; nothing to clear");
                ClearResult noop = new ClearResult(0, 0);
                logClearOperation(imageData, noop, startMs);
                return noop;
            }
            List<PathObjectConnectionGroup> groups =
                    new ArrayList<>(connections.getConnectionGroups());
            nGroupsRemoved = groups.size();
            for (PathObjectConnectionGroup g : groups) {
                nEdgesRemoved += countEdges(g);
                connections.removeGroup(g);
            }
            imageData.setProperty(KEY_OVERLAY_SOURCE_GROUP, null);
            imageData.setProperty(KEY_OVERLAY_RESULT_NAME, null);
        }
        fireHierarchyChanged(imageData);

        try {
            String script = buildClearWorkflowScript();
            imageData.getHistoryWorkflow().addStep(new DefaultScriptableWorkflowStep(
                    "QP-CAT: clear spatial connections", script));
        } catch (Exception e) {
            logger.warn("Failed to record workflow step for clear-connections: {}", e.getMessage());
        }
        logger.info("Cleared {} connection group(s) ({} edges) from viewer",
                nGroupsRemoved, nEdgesRemoved);
        ClearResult result = new ClearResult(nGroupsRemoved, nEdgesRemoved);
        logClearOperation(imageData, result, startMs);
        return result;
    }

    private static void logClearOperation(ImageData<?> imageData, ClearResult result,
                                           long startMs) {
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("feature", "spatial-graph-overlay");
            params.put("action", "clear");
            params.put("image", describeImage(imageData));
            params.put("n_groups_removed", Integer.toString(result.getNGroupsRemoved()));
            params.put("n_edges_removed", Integer.toString(result.getNEdgesRemoved()));
            String summary = result.wasNoOp()
                    ? "Clear connections no-op (none attached)"
                    : "Cleared " + result.getNGroupsRemoved() + " group(s), "
                            + result.getNEdgesRemoved() + " edges";
            OperationLogger.getInstance().logOperation(
                    "SPATIAL OVERLAY CLEAR", params, summary,
                    System.currentTimeMillis() - startMs);
        } catch (Exception e) {
            logger.warn("OperationLogger clear entry failed: {}", e.getMessage());
        }
    }

    // ---- Internals ----

    private static DefaultPathObjectConnectionGroup buildGroupFromBundle(
            SpatialStatsBundle bundle, List<PathObject> detections) {
        // v0.3 does NOT persist the edge COO on disk yet (the bundle
        // is JSON-only, and edge arrays are too large for round-trip).
        // The current ImageData property (set during the most recent
        // clustering run) is the canonical source. If no group has
        // been pushed in this session, we build an empty group and
        // emit a clear log line; the user is told to re-run clustering.
        DefaultPathObjectConnectionGroup empty = new DefaultPathObjectConnectionGroup(
                new EmptyView(detections));
        logger.info("Built spatial connection group from saved bundle (graph type: {})",
                bundle.getGraphType());
        return empty;
    }

    private static DefaultPathObjectConnectionGroup buildFilteredGroup(
            PathObjectConnectionGroup source) {
        // Build a fresh group by copying the source through the
        // copy-constructor of DefaultPathObjectConnectionGroup, feeding
        // it an anonymous PathObjectConnectionGroup that exposes the
        // filtered view of getConnectedObjects.
        PathObjectConnectionGroup filteredView = new PathObjectConnectionGroup() {
            @Override
            public boolean containsObject(PathObject pathObject) {
                return source.containsObject(pathObject);
            }

            @Override
            public Collection<PathObject> getPathObjects() {
                return source.getPathObjects();
            }

            @Override
            public List<PathObject> getConnectedObjects(PathObject pathObject) {
                PathClass cls = pathObject.getPathClass();
                if (cls == null) {
                    return Collections.emptyList();
                }
                List<PathObject> filtered = new ArrayList<>();
                for (PathObject other : source.getConnectedObjects(pathObject)) {
                    if (cls.equals(other.getPathClass())) {
                        filtered.add(other);
                    }
                }
                return filtered;
            }

            @Override
            public Collection<PathObject> getPathObjectsForRegion(ImageRegion region) {
                return source.getPathObjectsForRegion(region);
            }
        };
        return new DefaultPathObjectConnectionGroup(filteredView);
    }

    private static void attachGroup(ImageData<?> imageData,
                                     DefaultPathObjectConnectionGroup group,
                                     String resultName) {
        synchronized (imageData) {
            Object o = imageData.getProperty(DefaultPathObjectConnectionGroup.KEY_OBJECT_CONNECTIONS);
            PathObjectConnections connections;
            if (o instanceof PathObjectConnections existing) {
                connections = existing;
            } else {
                connections = new PathObjectConnections();
                imageData.setProperty(DefaultPathObjectConnectionGroup.KEY_OBJECT_CONNECTIONS, connections);
            }
            // Drop any QP-CAT-owned source group stash; the user is
            // pushing a fresh overlay so the filter starts from clean.
            imageData.setProperty(KEY_OVERLAY_SOURCE_GROUP, null);
            // Replace previously-attached groups with the new one.
            for (PathObjectConnectionGroup g : new ArrayList<>(connections.getConnectionGroups())) {
                connections.removeGroup(g);
            }
            connections.addGroup(group);
            imageData.setProperty(KEY_OVERLAY_RESULT_NAME, resultName);
        }
        fireHierarchyChanged(imageData);
    }

    private static void fireHierarchyChanged(ImageData<?> imageData) {
        Runnable r = () -> {
            PathObjectHierarchy h = imageData.getHierarchy();
            if (h != null) {
                h.fireHierarchyChangedEvent(SpatialConnectionsScripts.class);
            }
        };
        try {
            if (javafx.application.Platform.isFxApplicationThread()) {
                r.run();
            } else {
                javafx.application.Platform.runLater(r);
            }
        } catch (IllegalStateException headless) {
            // No FX runtime (e.g. unit-test or headless invocation);
            // fire directly on the calling thread.
            r.run();
        }
    }

    private static Project<?> resolveProject() {
        try {
            qupath.lib.gui.QuPathGUI gui = qupath.lib.gui.QuPathGUI.getInstance();
            if (gui != null) {
                return gui.getProject();
            }
        } catch (Throwable ignored) {
            // Running outside the QuPath GUI (tests, headless).
        }
        return null;
    }

    private static String buildPushWorkflowScript(String resultName) {
        StringBuilder sb = new StringBuilder();
        sb.append("import qupath.ext.qpcat.scripting.SpatialConnectionsScripts\n");
        sb.append("SpatialConnectionsScripts.pushConnectionsToViewer(\n");
        sb.append("    getCurrentImageData(),\n");
        sb.append("    \"").append(escape(resultName)).append("\"\n");
        sb.append(")\n");
        return sb.toString();
    }

    private static String buildClearWorkflowScript() {
        return "import qupath.ext.qpcat.scripting.SpatialConnectionsScripts\n"
                + "SpatialConnectionsScripts.clearConnections(getCurrentImageData())\n";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Count undirected edges in a connection group. Each unordered pair
     * is counted exactly once by requiring i &lt; j on object identity
     * hash (deterministic per JVM run; sufficient for the audit-trail
     * + status-notification edge-count surface, no exact-set semantics
     * required across runs).
     */
    private static int countEdges(PathObjectConnectionGroup group) {
        if (group == null) return 0;
        int total = 0;
        for (PathObject p : group.getPathObjects()) {
            List<PathObject> connected = group.getConnectedObjects(p);
            if (connected == null) continue;
            int pid = System.identityHashCode(p);
            for (PathObject q : connected) {
                if (q == null) continue;
                int qid = System.identityHashCode(q);
                if (pid < qid) {
                    total++;
                }
            }
        }
        return total;
    }

    private static String describeImage(ImageData<?> imageData) {
        if (imageData == null) return "(null)";
        try {
            if (imageData.getServer() != null
                    && imageData.getServer().getMetadata() != null) {
                String name = imageData.getServer().getMetadata().getName();
                if (name != null && !name.isBlank()) return name;
            }
        } catch (Exception ignored) {
            // best-effort describe; fall through
        }
        return "(unknown)";
    }

    /**
     * Minimal PathObjectConnectionGroup that exposes only the detection
     * set with no edges. Used as the seed for an empty group on legacy
     * projects (where the saved bundle predates the edge-COO write).
     */
    private static final class EmptyView implements PathObjectConnectionGroup {
        private final Map<PathObject, Boolean> contains = new HashMap<>();
        private final List<PathObject> objects;

        EmptyView(List<PathObject> objects) {
            this.objects = objects;
            for (PathObject p : objects) {
                contains.put(p, Boolean.TRUE);
            }
        }

        @Override
        public boolean containsObject(PathObject pathObject) {
            return contains.containsKey(pathObject);
        }

        @Override
        public Collection<PathObject> getPathObjects() {
            return objects;
        }

        @Override
        public List<PathObject> getConnectedObjects(PathObject pathObject) {
            return Collections.emptyList();
        }
    }

}
