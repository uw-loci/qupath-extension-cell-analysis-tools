"""
Spatial statistics expansion (v1) helpers for QP-CAT.

This module is *imported* from run_clustering.py via runpy / exec inline
loading - it does not stand alone as an Appose task. The helpers run
synchronously inside the parent task's Python interpreter so they share
the AnnData object that was built for the existing Moran's I / nhood
enrichment branch.

Zero new pip dependencies (see Phase 0 feasibility 4.0):
  - squidpy >= 1.4 (already pinned) provides:
      sq.gr.spatial_neighbors           - graph build (kNN / radius / Delaunay)
      sq.gr.ripley(mode='K' | 'L')      - Ripley point-pattern stats
      sq.gr.spatial_autocorr(mode='geary') - Geary's C per measurement
      sq.gr.co_occurrence               - co-occurrence (pairwise + one-vs-rest)
  - scipy >= 1.10 (already pinned) provides scipy.spatial.Delaunay
  - matplotlib >= 3.7 (transitive via scanpy/squidpy) - Phase 5 PNG output

Function contract: every callable below either populates `task.outputs`
with a JSON or NDArray-backed entry, or logs a warning and returns
without setting outputs. Failures never bubble out of the helper - the
Java side checks `task.outputs.containsKey(...)` per the existing
`hasSpatialAutocorr` / `hasNhoodEnrichment` pattern.

Phase 5 enhancement (Feature B precondition): each helper accepts an
optional `plot_dir` + `plot_dpi` + `persist_plots` triplet. When all are
supplied and persist_plots is truthy, a matplotlib PNG is written next
to the existing run_clustering.py outputs. Filenames are part of the
public contract consumed by FigureExportScripts' PlotKind enum:
  ripley_k_l.png
  geary_c.png
  co_occurrence_pairwise.png
  co_occurrence_one_vs_rest.png

ASCII-only logging and error messages per the QPSC encoding policy
(Windows cp1252 production).
"""

import json
import logging
import math
import os

import numpy as np

logger = logging.getLogger("qpcat.spatial_stats")


# ---------------------------------------------------------------------------
# Scale guards
#
# Ripley's L and co-occurrence are the two helpers here whose cost is set by
# something the Java side cannot know before clustering runs: the number of
# clusters, and the size of the largest one. Java refuses configurations it can
# predict from the cell count alone (see ScalingLimits.java); these two need the
# real labels, so they are checked here, at the last possible moment.
#
# On a breach we log and return WITHOUT setting task.outputs -- the documented
# contract for every helper in this module. That degrades one sub-analysis
# instead of killing a clustering run that may already have taken ten minutes.
#
# The coefficients MUST match ScalingLimits.java. Both sides are pinned to the
# same measurements: python_tests/test_scaling_guard.py and ScalingLimitsTest.
# ---------------------------------------------------------------------------

_BASE_GB = 0.2
_BLOCK_RAM_FRACTION = 0.85


def _total_ram_gb():
    """Physical RAM in GB, or None when it cannot be determined.

    No psutil in the QP-CAT environment, so this uses sysconf on POSIX and
    GlobalMemoryStatusEx on Windows.

    There is deliberately no fallback number. RAM is a physical property of the
    user's machine; substituting a guess means skipping an analysis on the
    strength of a figure we invented. None means unknown, and unknown never
    skips -- see _refuse_if_too_big.
    """
    try:
        return (os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES")) / 1024.0**3
    except (ValueError, AttributeError, OSError):
        pass
    try:
        import ctypes

        class _MemStatus(ctypes.Structure):
            _fields_ = [
                ("dwLength", ctypes.c_ulong),
                ("dwMemoryLoad", ctypes.c_ulong),
                ("ullTotalPhys", ctypes.c_ulonglong),
                ("ullAvailPhys", ctypes.c_ulonglong),
                ("ullTotalPageFile", ctypes.c_ulonglong),
                ("ullAvailPageFile", ctypes.c_ulonglong),
                ("ullTotalVirtual", ctypes.c_ulonglong),
                ("ullAvailVirtual", ctypes.c_ulonglong),
                ("ullAvailExtendedVirtual", ctypes.c_ulonglong),
            ]

        stat = _MemStatus()
        stat.dwLength = ctypes.sizeof(_MemStatus)
        ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(stat))
        if stat.ullTotalPhys > 0:
            return stat.ullTotalPhys / 1024.0**3
    except Exception:
        pass
    return None


def co_occurrence_peak_gb(n_cells, n_clusters, n_intervals):
    """Peak allocation of squidpy's co-occurrence kernel, in GB.

    squidpy 1.6.6's `_occur_count` allocates (n_cells, n_intervals *
    n_clusters ** 2) int32 up front. Note `n_splits` does NOT bound this any
    more -- in 1.6.6 it survives only in a log message.
    """
    k = max(1, int(n_clusters))
    return (
        _BASE_GB + 0.75 + (float(n_cells) * int(n_intervals) * k * k * 4.0) / 1024.0**3
    )


def ripley_peak_gb(largest_cluster_size):
    """Peak allocation of Ripley's L, in GB.

    squidpy runs `pdist` over the observed cells of each cluster in turn, so the
    LARGEST cluster sets the peak. `n_observations` caps only the simulated
    point patterns, not this.
    """
    m = float(largest_cluster_size)
    return _BASE_GB + 0.6 + 2.68e-8 * m * m


def _cluster_sizes(adata, cluster_key):
    """(n_clusters, largest_cluster_size) for the labels actually present."""
    try:
        labels = np.asarray(adata.obs[cluster_key])
        _, counts = np.unique(labels, return_counts=True)
        if counts.size == 0:
            return 0, 0
        return int(counts.size), int(counts.max())
    except Exception:
        return 0, 0


def _refuse_if_too_big(what, predicted_gb, remedy):
    """True when `what` must be skipped. Logs the reason either way."""
    ram = _total_ram_gb()
    if ram is None:
        # Unknown machine: report the prediction and run anyway. Skipping here
        # would discard work on the basis of a number we do not have.
        logger.warning(
            "%s is predicted to need about %.0f GB. This machine's total memory "
            "could not be read, so QP-CAT cannot tell whether that fits; "
            "proceeding. %s",
            what,
            predicted_gb,
            remedy,
        )
        return False
    if predicted_gb < ram * _BLOCK_RAM_FRACTION:
        if predicted_gb > ram * 0.5:
            logger.warning(
                "%s needs about %.0f GB of %.0f GB -- this may swap",
                what,
                predicted_gb,
                ram,
            )
        return False
    logger.warning(
        "SKIPPING %s: it needs about %.0f GB but this machine has %.0f GB. %s",
        what,
        predicted_gb,
        ram,
        remedy,
    )
    return True


def _safe_kwargs(fn, **kw):
    """Keep only kwargs that `fn` accepts. Used to pass single-threading hints
    (n_jobs=1 / show_progress_bar=False / seed) to squidpy without a TypeError
    when a given squidpy version renamed or dropped one. The caller forces
    serial execution to avoid the numba/joblib deadlock seen inside the Appose
    worker subprocess on Windows."""
    import inspect

    try:
        params = inspect.signature(fn).parameters
    except (TypeError, ValueError):
        return kw
    if any(p.kind == p.VAR_KEYWORD for p in params.values()):
        return kw
    return {k: v for k, v in kw.items() if k in params}


# Phase 5: Public filename contract consumed by Feature B's PlotKind enum.
# Keep these stable; downstream FigureExportScripts.exportFigures references
# the exact strings. Bumping a name is a breaking change for the export API.
PLOT_FILE_RIPLEY = "ripley_k_l.png"
PLOT_FILE_GEARY = "geary_c.png"
PLOT_FILE_COOC_PAIRWISE = "co_occurrence_pairwise.png"
PLOT_FILE_COOC_ONE_VS_REST = "co_occurrence_one_vs_rest.png"


def _should_persist(plot_dir, persist_plots):
    """Common gate for the Phase 5 PNG-output enhancement.

    Returns True only when persist_plots is truthy AND plot_dir is a
    non-empty string AND we can create / reach that directory. Every
    savefig path checks this first; a False result means "skip the plot,
    return JSON only" - the existing v1 contract.
    """
    if not persist_plots:
        return False
    if not plot_dir:
        return False
    try:
        os.makedirs(plot_dir, exist_ok=True)
    except Exception as e:
        logger.warning("spatial-stats plot dir not writable: %s (%s)", plot_dir, e)
        return False
    return True


def adaptive_permutations(n_cells, override=0):
    """Resolve permutation count via the v1 adaptive default.

    override > 0  -> use as-is (user override from QpcatPreferences).
    override == 0 -> 1000 for n <= 50k, 100 for 50k-500k, 50 above.

    Mirrors ClusteringConfig.resolvePermutations on the Java side.
    """
    if override is not None and override > 0:
        return int(override)
    if n_cells <= 50_000:
        return 1000
    if n_cells <= 500_000:
        return 100
    return 50


def area_slices(area_ids, n_cells=None):
    """Partition cell indices by independent area.

    Returns an ordered list of (area_id, index_array) pairs, sorted by area
    id so a run is reproducible -- never iterate a set or a dict insertion
    order here, because the block assembly below depends on this order.

    `area_ids` of None (or a single distinct value) yields ONE slice covering
    every cell, which is the un-partitioned case and reduces every caller
    below to its pre-areas behaviour.
    """
    if area_ids is None:
        if n_cells is None:
            raise ValueError("area_slices needs n_cells when area_ids is None")
        return [(0, np.arange(int(n_cells), dtype=np.int64))]
    ids = np.asarray(area_ids, dtype=np.int64).ravel()
    if n_cells is not None and ids.shape[0] != int(n_cells):
        raise ValueError(
            "area_ids length (%d) does not match n_cells (%d)"
            % (ids.shape[0], int(n_cells))
        )
    return [(int(a), np.flatnonzero(ids == a)) for a in np.unique(ids)]


def _knn_cap(k, n_block):
    """Largest usable neighbour count for a block of n_block cells.

    sklearn raises when it is asked for more neighbours than there are other
    points, so a small area must reduce k rather than take the whole run down
    with it. Returns 0 when the block cannot support a graph at all.
    """
    return max(0, min(int(k), int(n_block) - 1))


def _build_graph_block(coords, graph_type, k, radius, delaunay_max_edge):
    """Build (connectivities, distances) for ONE coordinate frame.

    This is the single-frame logic that used to be inlined in
    build_spatial_graph. Extracted so that the partitioned path and the
    un-partitioned path are the SAME code with a different number of blocks,
    rather than two implementations that can drift apart.
    """
    import anndata as ad
    import scipy.sparse as sp
    import squidpy as sq

    n_block = int(coords.shape[0])
    if n_block < 2:
        # A one-cell area has no neighbours. An empty block is the honest
        # answer; those cells then contribute nothing to graph-based
        # statistics instead of borrowing a neighbour from another specimen.
        empty = sp.csr_matrix((n_block, n_block), dtype=np.float64)
        return empty, empty.copy()

    block = ad.AnnData(X=np.zeros((n_block, 1), dtype=np.float32))
    block.obsm["spatial"] = np.asarray(coords, dtype=np.float64)

    if graph_type == "knn":
        n_neighs = _knn_cap(k, n_block)
        if n_neighs < 1:
            empty = sp.csr_matrix((n_block, n_block), dtype=np.float64)
            return empty, empty.copy()
        sq.gr.spatial_neighbors(
            block, coord_type="generic", n_neighs=n_neighs, delaunay=False
        )
    elif graph_type == "radius":
        sq.gr.spatial_neighbors(
            block, coord_type="generic", radius=radius, delaunay=False
        )
    elif graph_type == "delaunay":
        sq.gr.spatial_neighbors(block, coord_type="generic", delaunay=True)
        if delaunay_max_edge is not None and delaunay_max_edge > 0:
            dists = block.obsp["spatial_distances"]
            conn = block.obsp["spatial_connectivities"]
            mask = dists > delaunay_max_edge
            dists = dists.tolil()
            conn = conn.tolil()
            for i, j in zip(*mask.nonzero()):
                dists[i, j] = 0
                conn[i, j] = 0
            block.obsp["spatial_distances"] = dists.tocsr()
            block.obsp["spatial_connectivities"] = conn.tocsr()
    else:
        raise ValueError("Unknown spatial graph type: %s" % graph_type)

    return block.obsp["spatial_connectivities"], block.obsp["spatial_distances"]


def _auto_radius(coords, slices):
    """Median within-area 1-NN distance times 5, the historical auto-radius.

    Derived ONCE across all areas rather than per area: a radius is a
    biological length scale, and letting each core pick its own would make
    the resulting statistics incomparable between cores -- which is the whole
    point of keeping the areas separate in the first place.
    """
    nn = []
    for _area_id, idx in slices:
        if idx.size < 2:
            continue
        d = _median_nn_distance(coords[idx])
        if d is not None and d > 0:
            nn.append(d)
    if not nn:
        return 50.0
    return float(np.median(nn) * 5.0)


def build_spatial_graph(
    adata,
    graph_type="knn",
    k=15,
    radius=-1.0,
    delaunay_max_edge=-1.0,
    area_ids=None,
):
    """Build adata.obsp['spatial_connectivities'] via squidpy.

    graph_type is one of "knn", "radius", "delaunay".
    radius < 0 means auto-derive from median NN distance times 5.
    delaunay_max_edge < 0 means do not prune Delaunay edges.

    When `area_ids` is given, one graph is built per independent area and the
    blocks are assembled into a single block-diagonal matrix in the original
    cell order. No edge then joins two areas -- a distance between cells in
    different TMA cores, tissue sections or images is not a distance through
    tissue, so an edge across it is an invented adjacency that nothing
    downstream can detect.

    A single area reduces to exactly one block and the identity permutation,
    so un-partitioned runs are unchanged.

    Returns the resolved (graph_type, effective_param) tuple for audit
    logging. On failure, logs a warning and re-raises so the caller can
    decide whether to fall back.
    """
    import scipy.sparse as sp

    n_cells = adata.shape[0]
    coords = np.asarray(adata.obsm["spatial"], dtype=np.float64)
    slices = area_slices(area_ids, n_cells)

    if graph_type == "radius" and (radius is None or radius < 0):
        radius = _auto_radius(coords, slices)

    conns = []
    dists = []
    order = []
    reduced_k = []
    for area_id, idx in slices:
        if graph_type == "knn" and _knn_cap(k, idx.size) < int(k):
            reduced_k.append((area_id, int(idx.size), _knn_cap(k, idx.size)))
        conn, dist = _build_graph_block(
            coords[idx], graph_type, k, radius, delaunay_max_edge
        )
        conns.append(conn)
        dists.append(dist)
        order.append(idx)

    if reduced_k:
        # One line with a count, not one per area: a 55-core TMA with several
        # sparse cores would otherwise bury the log.
        smallest = min(reduced_k, key=lambda r: r[2])
        logger.warning(
            "Spatial graph: %d area(s) had k reduced below %d to fit the area "
            "size (smallest: area %d, %d cells, k=%d)",
            len(reduced_k),
            int(k),
            smallest[0],
            smallest[1],
            smallest[2],
        )

    if len(slices) == 1:
        adata.obsp["spatial_connectivities"] = conns[0]
        adata.obsp["spatial_distances"] = dists[0]
    else:
        # block_diag concatenates in slice order; permute back to the original
        # cell order so every downstream index still means the same cell.
        concatenated = np.concatenate(order)
        inverse = np.argsort(concatenated)
        adata.obsp["spatial_connectivities"] = sp.block_diag(conns, format="csr")[
            inverse, :
        ][:, inverse]
        adata.obsp["spatial_distances"] = sp.block_diag(dists, format="csr")[
            inverse, :
        ][:, inverse]
        logger.info(
            "Spatial graph built per area: %d areas, no edges between them",
            len(slices),
        )

    # Mirror the metadata squidpy itself records. Nothing we call reads it
    # today (sq.gr helpers validate adata.obsp only), but leaving the graph
    # undescribed would make any future squidpy plotting call silently see an
    # un-built graph.
    adata.uns["spatial_neighbors"] = {
        "connectivities_key": "spatial_connectivities",
        "distances_key": "spatial_distances",
        "params": {
            "n_neighbors": int(k) if graph_type == "knn" else None,
            "coord_type": "generic",
            "radius": radius if graph_type == "radius" else None,
            "transform": None,
            "qpcat_n_areas": len(slices),
        },
    }

    if graph_type == "knn":
        return ("knn", k)
    if graph_type == "radius":
        return ("radius", radius)
    return ("delaunay", delaunay_max_edge)


def _median_nn_distance(coords):
    """Median 1-nearest-neighbor distance -- a density-robust cell-scale estimate.

    Returns None if it cannot be computed (too few points / degenerate coords), so
    callers can fall back to a coarser heuristic.
    """
    try:
        from scipy.spatial import cKDTree

        if coords is None or len(coords) < 2:
            return None
        tree = cKDTree(np.asarray(coords, dtype=float))
        # k=2: the first neighbor is the point itself (distance 0), the second is
        # its nearest neighbor.
        dists, _ = tree.query(np.asarray(coords, dtype=float), k=2)
        nn = dists[:, 1]
        nn = nn[np.isfinite(nn) & (nn > 0)]
        if nn.size == 0:
            return None
        return float(np.median(nn))
    except Exception:
        return None


def _csv_cell(value):
    """Minimal CSV quoting. Area labels contain ' | ' and can contain commas."""
    text = "" if value is None else str(value)
    if any(ch in text for ch in [",", '"', "\n", "\r"]):
        return '"' + text.replace('"', '""') + '"'
    return text


def _csv_row(values):
    return ",".join(_csv_cell(v) for v in values)


def build_area_summary_csv(area_ids, area_names, cluster_labels, cluster_names=None):
    """Wide per-area summary: one row per area, one column per cluster.

    This is the file a user actually opens. It answers "what is in each core"
    -- the question that only becomes askable once areas exist -- and it is
    deliberately separate from the cluster-level output: a cluster's marker
    profile is a property of the CLUSTER, identical in every area, and
    repeating it 55 times would be noise.

    Columns: area, n_cells, n_clusters_present, then one fraction column per
    cluster, then the matching count columns.
    """
    ids = np.asarray(area_ids, dtype=np.int64).ravel()
    labels = np.asarray(cluster_labels).ravel()
    if ids.shape[0] != labels.shape[0]:
        raise ValueError(
            "area_ids length (%d) does not match cluster label count (%d)"
            % (ids.shape[0], labels.shape[0])
        )

    present = sorted(set(int(v) for v in labels))
    if cluster_names is None:
        names = {c: "Cluster %d" % c for c in present}
    else:
        listed = list(cluster_names)
        names = {
            c: (listed[c] if 0 <= c < len(listed) else "Cluster %d" % c)
            for c in present
        }

    header = ["area", "n_cells", "n_clusters_present"]
    header += ["%s_frac" % names[c] for c in present]
    header += ["%s_count" % names[c] for c in present]
    rows = [_csv_row(header)]

    for area_id, idx in area_slices(ids, ids.shape[0]):
        area_labels = labels[idx]
        n = int(area_labels.shape[0])
        counts = {c: int(np.count_nonzero(area_labels == c)) for c in present}
        n_present = sum(1 for c in present if counts[c] > 0)
        row = [area_label(area_names, area_id), n, n_present]
        # n is never 0 -- area_slices only yields ids that occur -- so the
        # division needs no guard, but be explicit rather than rely on it.
        row += ["%.6f" % (counts[c] / n) if n else "" for c in present]
        row += [counts[c] for c in present]
        rows.append(_csv_row(row))

    return "\n".join(rows) + "\n"


def build_area_statistics_csv(per_area_by_statistic):
    """Long-format per-area statistics.

    One row per (area, statistic, key). Deliberately the same shape as the
    CSV PostHocSpatialWorkflow already writes, so the two workflows produce
    files that can be concatenated rather than reconciled.

    `per_area_by_statistic` maps a statistic name to {area_label: result},
    where each result is whatever that helper emitted (a JSON string or an
    already-decoded object).
    """
    import json as _json

    header = ["area", "statistic", "key", "value", "p_value"]
    rows = [_csv_row(header)]

    for statistic in sorted(per_area_by_statistic):
        by_area = per_area_by_statistic[statistic] or {}
        for area in sorted(by_area):
            payload = by_area[area]
            if isinstance(payload, str):
                try:
                    payload = _json.loads(payload)
                except ValueError:
                    logger.warning(
                        "Area '%s': %s result is not valid JSON; skipped in the CSV",
                        area,
                        statistic,
                    )
                    continue
            if not isinstance(payload, dict):
                continue
            p_values = payload.get("p_values") or {}
            emitted = False
            for key, value in sorted(p_values.items()):
                rows.append(_csv_row([area, statistic, key, "", value]))
                emitted = True
            # Scalar summaries some helpers emit alongside the curves.
            for key in ("mean", "max", "n_permutations", "graph_type"):
                if key in payload and not isinstance(payload[key], (list, dict)):
                    rows.append(_csv_row([area, statistic, key, payload[key], ""]))
                    emitted = True
            if not emitted:
                # Record the area rather than omitting it: a missing row would
                # read as "not measured" when it may mean "no p-values".
                rows.append(_csv_row([area, statistic, "computed", "1", ""]))

    return "\n".join(rows) + "\n"


class _CapturingTask:
    """Stands in for the Appose task while a helper runs on one area.

    Every helper in this module reports by writing task.outputs[...] or by
    logging and writing nothing. Handing them this instead of the real task
    lets a per-area caller collect each area's result without changing any of
    the statistics themselves.
    """

    def __init__(self):
        self.outputs = {}

    def update(self, *args, **kwargs):
        pass


def area_label(area_names, area_id):
    """Display label for an area id, falling back to a stable synthetic name."""
    if area_names is not None:
        try:
            name = list(area_names)[int(area_id)]
            if name:
                return str(name)
        except (IndexError, ValueError, TypeError):
            pass
    return "Area %d" % int(area_id)


def slice_adata_for_area(adata, idx, cluster_key="cluster"):
    """Copy of `adata` holding only the cells in `idx`.

    Unused cluster categories are dropped. A core that contains none of
    cluster 7 has no Ripley curve for cluster 7, and saying so is more honest
    than emitting an empty one -- and squidpy's per-category loops do not
    tolerate empty categories anyway.
    """
    sub = adata[idx].copy()
    if cluster_key in sub.obs.columns and hasattr(sub.obs[cluster_key], "cat"):
        sub.obs[cluster_key] = sub.obs[cluster_key].cat.remove_unused_categories()
    return sub


def run_per_area(
    fn,
    adata,
    area_ids,
    area_names,
    output_key,
    cluster_key="cluster",
    min_cells=0,
    **kwargs,
):
    """Run a coordinate-based statistic once per independent area.

    Ripley's L and co-occurrence read obsm['spatial'] directly and never
    consult the neighbour graph (verified against squidpy 1.6.6:
    sq.gr.ripley and sq.gr.co_occurrence take no library_key), so making the
    GRAPH block-diagonal does nothing for them. Pooling a TMA's 55 cores into
    one point pattern measures the layout of the array, not the biology of any
    core -- the convex hull spans the whole slide and every inter-core gap
    reads as dispersion.

    Returns {area_label: output} for the areas that produced a result, plus a
    list of (area_label, reason) for those that did not. Areas are processed
    in sorted id order so a run is reproducible.
    """
    results = {}
    skipped = []
    slices = area_slices(area_ids, adata.shape[0])

    # Every helper writes its PNG to one fixed filename (the names are a public
    # contract consumed by the figure exporter), so persisting per area would
    # leave a single file holding whichever area happened to run last -- a plot
    # silently mislabelled as the whole run. The numbers still reach the
    # per-area JSON and CSV.
    kwargs = dict(kwargs)
    kwargs["persist_plots"] = False
    pooled_spatial = kwargs.pop("spatial_data", None)

    for area_id, idx in slices:
        label = area_label(area_names, area_id)
        if idx.size < max(2, int(min_cells)):
            skipped.append((label, "only %d cell(s)" % int(idx.size)))
            continue
        sub = slice_adata_for_area(adata, idx, cluster_key)
        capture = _CapturingTask()
        per_area_kwargs = dict(kwargs)
        if pooled_spatial is not None:
            # co-occurrence takes the coordinates separately; they must be
            # sliced to match or it would measure this area's clusters against
            # the whole cohort's geometry.
            per_area_kwargs["spatial_data"] = np.asarray(pooled_spatial)[idx]
        try:
            fn(sub, capture, cluster_key=cluster_key, **per_area_kwargs)
        except Exception as e:
            logger.warning("Area '%s': %s failed (%s)", label, output_key, e)
            skipped.append((label, str(e)))
            continue
        if output_key in capture.outputs:
            results[label] = capture.outputs[output_key]
        else:
            skipped.append((label, "produced no result"))
    if skipped:
        # Name the count, not every area: a 55-core TMA would otherwise bury
        # the log. Silence here would read as "all areas measured".
        logger.warning(
            "%s: %d of %d area(s) produced no result (first: %s -- %s)",
            output_key,
            len(skipped),
            len(slices),
            skipped[0][0],
            skipped[0][1],
        )
    return results, skipped


def run_ripley(
    adata,
    task,
    cluster_key="cluster",
    n_permutations=1000,
    max_radius=-1.0,
    n_steps=50,
    graph_type="knn",
    plot_dir=None,
    plot_dpi=150,
    persist_plots=True,
    coord_unit="px",
):
    """Compute Ripley K and L per cluster.

    Writes task.outputs["ripley"] as a JSON blob shaped:
      {
        "cluster_names": ["0", "1", ...],
        "radii": [r0, r1, ...],
        "k_values": [[...], ...],       # per-cluster K(r)
        "l_values": [[...], ...],       # per-cluster L(r)
        "poisson_k": [...],             # analytical null K(r)
        "poisson_l": [...],             # zero line
        "p_values": {"0": p0, ...},
        "n_permutations": N,
        "graph_type": "..."
      }

    On failure, logs a warning and does not set task.outputs["ripley"].
    """
    import squidpy as sq

    _, largest = _cluster_sizes(adata, cluster_key)
    if _refuse_if_too_big(
        "Ripley's L (largest cluster has %d cells)" % largest,
        ripley_peak_gb(largest),
        "Re-run with Ripley turned off. Its cost is set by the LARGEST cluster, "
        "because every pairwise distance within a cluster is materialized.",
    ):
        return

    try:
        kwargs = {"cluster_key": cluster_key, "n_simulations": n_permutations}
        if max_radius is not None and max_radius > 0:
            kwargs["max_dist"] = float(max_radius)
        if n_steps is not None and n_steps > 0:
            kwargs["n_steps"] = int(n_steps)

        # Force serial execution (avoids the numba/joblib deadlock on Windows).
        kwargs.update(
            _safe_kwargs(sq.gr.ripley, seed=0, n_jobs=1, show_progress_bar=False)
        )

        # Compute K and L separately - squidpy's mode='K' / mode='L' branches
        # share underlying state via adata.uns['<cluster_key>_ripley_K'] etc.
        # Newer squidpy (RipleyStat) dropped mode='K' (only F/G/L remain); K is
        # optional -- L (the variance-stabilized transform) carries the same
        # clustering-vs-dispersion signal, so skip K if unsupported.
        k_available = True
        try:
            sq.gr.ripley(adata, mode="K", **kwargs)
        except Exception as e:
            logger.warning("Ripley K unavailable in this squidpy (%s); using L only", e)
            k_available = False
        sq.gr.ripley(adata, mode="L", **kwargs)

        k_data = adata.uns.get("%s_ripley_K" % cluster_key, {})
        l_data = adata.uns.get("%s_ripley_L" % cluster_key, {})

        # squidpy returns dict-like with DataFrames; pull per-cluster curves
        cluster_names = sorted(
            set(
                [
                    str(c)
                    for c in adata.obs[cluster_key].cat.categories
                    if cluster_key in adata.obs.columns
                ]
            )
        ) or [str(c) for c in adata.obs[cluster_key].unique()]

        radii = []
        k_curves = []
        l_curves = []
        poisson_k = []
        poisson_l = []
        p_values = {}
        p_value_curves = {}

        # squidpy stores per-cluster results under 'bins' / 'pvalues' / 'sims_stat'
        # but the exact key set varies by version. Read defensively and log
        # which shape matched so the audit log reveals the live squidpy
        # contract on the workstation env (Phase 3 narrowing target).
        k_shape_matched = None
        try:
            bins_df = k_data.get("bins") if isinstance(k_data, dict) else None
            stats_df = k_data.get("stats") if isinstance(k_data, dict) else None
            if bins_df is not None and stats_df is not None:
                radii = [float(r) for r in bins_df]
                for cname in cluster_names:
                    if cname in stats_df:
                        k_curves.append([float(v) for v in stats_df[cname]])
                    else:
                        k_curves.append([0.0] * len(radii))
                k_shape_matched = "dict(bins,stats)"
            else:
                # Fallback for the alternative squidpy shape: DataFrame with
                # 'bins' / 'stats' columns
                if hasattr(k_data, "columns"):
                    cols = list(k_data.columns)
                    if "bins" in cols and "stats" in cols:
                        # group by cluster column if available
                        cluster_col = cluster_key if cluster_key in cols else None
                        if cluster_col:
                            for cname in cluster_names:
                                sub = k_data[k_data[cluster_col] == cname]
                                if not radii:
                                    radii = [float(r) for r in sub["bins"]]
                                k_curves.append([float(v) for v in sub["stats"]])
                            k_shape_matched = "DataFrame(bins,stats,cluster_col)"
                        else:
                            radii = [float(r) for r in k_data["bins"]]
                            k_curves = [[float(v) for v in k_data["stats"]]]
                            k_shape_matched = "DataFrame(bins,stats)"
        except Exception as e:
            logger.warning("Ripley K extraction failed: %s", e)
        if k_shape_matched:
            logger.info(
                "Ripley K shape matched: %s (squidpy=%s)",
                k_shape_matched,
                getattr(sq, "__version__", "?"),
            )
        elif not k_available:
            # This squidpy build dropped mode='K' entirely (only F/G/L remain),
            # so there is no K payload to extract. That is expected, not an
            # error -- continue with L (the variance-stabilized transform), which
            # carries the same clustering-vs-dispersion signal. K curves are
            # zero-padded below and flagged via ripley_k_unavailable.
            task.outputs["ripley_k_unavailable"] = "true"
        else:
            # Extraction genuinely failed: squidpy's Ripley payload shape is
            # unrecognized (its uns layout has changed across versions). Emitting
            # zero-filled curves here would look exactly like a real "complete
            # spatial randomness" result, so the caller could publish a wrong
            # conclusion. Honor the documented contract instead: DO NOT set
            # task.outputs["ripley"]; surface an error the Java side can show.
            observed_keys = (
                list(k_data.keys())
                if isinstance(k_data, dict)
                else (list(k_data.columns) if hasattr(k_data, "columns") else "n/a")
            )
            msg = (
                "Ripley K extraction failed: squidpy (%s) returned an unrecognized "
                "payload (type=%s, keys=%s). No curves were produced -- this is an "
                "error, not a null result."
                % (
                    getattr(sq, "__version__", "?"),
                    type(k_data).__name__,
                    observed_keys,
                )
            )
            logger.error(msg)
            task.outputs["ripley_error"] = msg
            return

        try:
            # squidpy 1.6.6 shape, verified against the installed env:
            #   uns["<cluster_key>_ripley_L"] = {
            #       "L_stat":    DataFrame(bins, <cluster_key>, stats),
            #       "sims_stat": DataFrame(bins, simulations, stats),
            #       "bins":      ndarray(n_steps,),
            #       "pvalues":   ndarray(n_clusters, n_steps),
            #   }
            # The key is "L_stat", not "stats". Looking only for "stats" is why
            # every Ripley run on this squidpy produced an EMPTY payload while
            # logging success.
            stat_key = "%s_stat" % "L"
            if isinstance(l_data, dict) and stat_key in l_data:
                stat_df = l_data[stat_key]
                bins_arr = l_data.get("bins")
                if bins_arr is not None and not radii:
                    radii = [float(r) for r in bins_arr]
                cols = list(getattr(stat_df, "columns", []))
                cluster_col = cluster_key if cluster_key in cols else None
                for cname in cluster_names:
                    if cluster_col is not None:
                        sub = stat_df[stat_df[cluster_col].astype(str) == cname]
                        l_curves.append([float(v) for v in sub["stats"]])
                    else:
                        l_curves.append([0.0] * len(radii))
                if not radii and l_curves and cluster_col is not None:
                    first = stat_df[
                        stat_df[cluster_col].astype(str) == cluster_names[0]
                    ]
                    radii = [float(r) for r in first["bins"]]
                logger.info(
                    "Ripley L shape matched: dict(%s DataFrame) (squidpy=%s)",
                    stat_key,
                    getattr(sq, "__version__", "?"),
                )
                # pvalues is (n_clusters, n_steps) -- a curve per cluster, not a
                # scalar. Emitted as a curve rather than collapsed: min-over-radii
                # would be an uncorrected multiple-comparisons summary, and
                # picking one radius would be arbitrary. Either would be a
                # number we invented rather than one squidpy computed.
                pv_arr = l_data.get("pvalues")
                if pv_arr is not None:
                    try:
                        pv_arr = np.asarray(pv_arr)
                        if pv_arr.ndim == 2 and pv_arr.shape[0] == len(cluster_names):
                            p_value_curves = {
                                cluster_names[i]: [float(v) for v in pv_arr[i]]
                                for i in range(len(cluster_names))
                            }
                    except Exception as e:
                        logger.warning("Ripley p-value curve extraction failed: %s", e)

            l_bins = l_data.get("bins") if isinstance(l_data, dict) else None
            l_stats = l_data.get("stats") if isinstance(l_data, dict) else None
            if l_curves:
                pass
            elif l_bins is not None and l_stats is not None:
                if not radii:
                    radii = [float(r) for r in l_bins]
                for cname in cluster_names:
                    if cname in l_stats:
                        l_curves.append([float(v) for v in l_stats[cname]])
                    else:
                        l_curves.append([0.0] * len(radii))
            elif hasattr(l_data, "columns"):
                cols = list(l_data.columns)
                cluster_col = cluster_key if cluster_key in cols else None
                if cluster_col:
                    if not radii and "bins" in cols:
                        radii = (
                            [
                                float(r)
                                for r in l_data[
                                    l_data[cluster_col] == cluster_names[0]
                                ]["bins"]
                            ]
                            if cluster_names
                            else []
                        )
                    for cname in cluster_names:
                        sub = l_data[l_data[cluster_col] == cname]
                        if "stats" in cols:
                            l_curves.append([float(v) for v in sub["stats"]])
                        else:
                            l_curves.append([0.0] * len(radii))
                elif "stats" in cols:
                    if not radii and "bins" in cols:
                        radii = [float(r) for r in l_data["bins"]]
                    l_curves = [[float(v) for v in l_data["stats"]]]
        except Exception as e:
            logger.warning("Ripley L extraction failed: %s", e)

        # An empty extraction is an ERROR, not a null result. Padding to
        # [0.0] * 0 and emitting the payload anyway is what let every run on
        # squidpy 1.6.6 report "Ripley K/L computed" while returning nothing at
        # all. The K branch already refuses to publish zero-filled curves for
        # exactly this reason; the same rule has to apply here, or the guard is
        # defeated one step later.
        n_r = len(radii)
        if n_r == 0 or not any(curve for curve in l_curves):
            observed = (
                list(l_data.keys())
                if isinstance(l_data, dict)
                else (list(l_data.columns) if hasattr(l_data, "columns") else "n/a")
            )
            msg = (
                "Ripley L extraction produced no curves: squidpy (%s) returned a "
                "payload this build does not recognise (type=%s, keys=%s). No "
                "result was written -- this is an error, not a finding of "
                "complete spatial randomness."
                % (getattr(sq, "__version__", "?"), type(l_data).__name__, observed)
            )
            logger.error(msg)
            task.outputs["ripley_error"] = msg
            return

        # Pad the OTHER curve set only. K is genuinely optional on builds that
        # dropped mode='K'; L is not, and is checked above.
        if not k_curves:
            k_curves = [[0.0] * n_r for _ in cluster_names]

        # Analytical Poisson null: K_poisson(r) = pi * r^2; L_poisson(r) = 0
        poisson_k = [math.pi * (r * r) for r in radii]
        poisson_l = [0.0 for _ in radii]

        # p-values: squidpy attaches them as part of the uns dict in newer versions
        try:
            pv = k_data.get("pvalues") if isinstance(k_data, dict) else None
            if pv is not None:
                for cname in cluster_names:
                    if cname in pv:
                        p_values[cname] = float(pv[cname])
        except Exception:
            pass

        payload = {
            "cluster_names": cluster_names,
            "radii": radii,
            "k_values": k_curves,
            "l_values": l_curves,
            "poisson_k": poisson_k,
            "poisson_l": poisson_l,
            "p_values": p_values,
            # Per-cluster p-value CURVE (one value per radius). squidpy reports
            # significance per radius; collapsing it to one number per cluster
            # would be a summary we invented, so the curve is passed through
            # and p_values stays empty unless a build supplies real scalars.
            "p_value_curves": p_value_curves,
            "n_permutations": int(n_permutations),
            "graph_type": graph_type,
        }
        task.outputs["ripley"] = json.dumps(payload)
        logger.info(
            "Ripley K/L computed for %d clusters (%d radii, %d perms)",
            len(cluster_names),
            n_r,
            n_permutations,
        )

        # Phase 5: matplotlib PNG output for Feature B (batch figure export).
        if _should_persist(plot_dir, persist_plots) and radii:
            try:
                import matplotlib

                matplotlib.use("Agg")
                import matplotlib.pyplot as plt

                fig, (ax_k, ax_l) = plt.subplots(1, 2, figsize=(12, 5))
                n_clusters = len(cluster_names)
                cmap_name = "tab20" if n_clusters > 10 else "tab10"
                cmap = plt.get_cmap(cmap_name, max(n_clusters, 1))

                for idx, cname in enumerate(cluster_names):
                    color = cmap(idx)
                    if idx < len(k_curves):
                        ax_k.plot(
                            radii,
                            k_curves[idx],
                            color=color,
                            label=str(cname),
                            linewidth=1.2,
                        )
                    if idx < len(l_curves):
                        ax_l.plot(
                            radii,
                            l_curves[idx],
                            color=color,
                            label=str(cname),
                            linewidth=1.2,
                        )

                # Poisson null overlays (dashed black for visibility)
                ax_k.plot(
                    radii,
                    poisson_k,
                    "--",
                    color="black",
                    label="Poisson null",
                    linewidth=1.0,
                )
                ax_l.plot(
                    radii,
                    poisson_l,
                    "--",
                    color="black",
                    label="Poisson null",
                    linewidth=1.0,
                )

                ax_k.set_xlabel("Radius (%s)" % coord_unit)
                ax_k.set_ylabel("K(r)")
                ax_k.set_title("Ripley K")
                ax_k.legend(fontsize="small", loc="best")
                ax_k.grid(True, alpha=0.3)

                ax_l.set_xlabel("Radius (%s)" % coord_unit)
                ax_l.set_ylabel("L(r)")
                ax_l.set_title("Ripley L")
                ax_l.legend(fontsize="small", loc="best")
                ax_l.grid(True, alpha=0.3)

                fig.suptitle(
                    "Ripley K and L (graph: %s, perms: %d)"
                    % (graph_type, int(n_permutations))
                )
                out_path = os.path.join(plot_dir, PLOT_FILE_RIPLEY)
                fig.savefig(out_path, dpi=int(plot_dpi), bbox_inches="tight")
                plt.close(fig)
                logger.info("Saved Ripley K/L PNG: %s", out_path)
            except Exception as e:
                logger.warning("Ripley K/L plot failed: %s", e)
    except Exception as e:
        logger.warning("Ripley K/L failed: %s", e)


def run_geary_c(
    adata,
    task,
    n_permutations=1000,
    measurements=None,
    graph_type="knn",
    plot_dir=None,
    plot_dpi=150,
    persist_plots=True,
    coord_unit="px",
):
    """Compute Geary's C per marker.

    Writes task.outputs["geary_c"] as a JSON blob shaped:
      {
        "marker_stats": {"CD3: Mean": {"c": 0.42, "p_value": 0.001}, ...},
        "n_permutations": N,
        "graph_type": "..."
      }
    """
    import squidpy as sq

    try:
        kwargs = {"mode": "geary", "n_perms": int(n_permutations)}
        if measurements:
            kwargs["genes"] = list(measurements)
        # Force serial execution (avoids the numba/joblib deadlock on Windows).
        kwargs.update(
            _safe_kwargs(
                sq.gr.spatial_autocorr, n_jobs=1, show_progress_bar=False, seed=0
            )
        )
        df = sq.gr.spatial_autocorr(adata, **kwargs, copy=True)

        marker_stats = {}
        for marker in df.index:
            row = df.loc[marker]
            c_val = float(row.get("C", row.get("I", float("nan"))))
            # squidpy reports either pval_norm / pval_z_sim / pval_sim depending
            # on version; pick the first available
            p_val = float("nan")
            for col in ("pval_norm", "pval_z_sim", "pval_sim", "pval"):
                if col in row:
                    try:
                        p_val = float(row[col])
                        break
                    except (TypeError, ValueError):
                        continue
            marker_stats[str(marker)] = {"c": c_val, "p_value": p_val}

        payload = {
            "marker_stats": marker_stats,
            "n_permutations": int(n_permutations),
            "graph_type": graph_type,
        }
        task.outputs["geary_c"] = json.dumps(payload)
        logger.info(
            "Geary's C computed for %d markers (%d perms)",
            len(marker_stats),
            n_permutations,
        )

        # Phase 5: matplotlib PNG output for Feature B (batch figure export).
        if _should_persist(plot_dir, persist_plots) and marker_stats:
            try:
                import matplotlib

                matplotlib.use("Agg")
                import matplotlib.pyplot as plt

                markers = list(marker_stats.keys())
                c_vals = [marker_stats[m].get("c", float("nan")) for m in markers]
                # Replace NaNs with 0 for plotting; the bar still appears
                # but at height 0 so the marker name remains visible.
                c_plot = [
                    0.0 if (v is None or math.isnan(v)) else float(v) for v in c_vals
                ]

                n_markers = len(markers)
                width = max(8.0, min(0.4 * n_markers + 2.0, 24.0))
                fig, ax = plt.subplots(figsize=(width, 5))
                xs = np.arange(n_markers)
                ax.bar(xs, c_plot, color="steelblue", edgecolor="black", linewidth=0.4)
                # Null expectation for Geary's C is 1.0 (no autocorrelation).
                ax.axhline(
                    1.0,
                    color="red",
                    linestyle="--",
                    linewidth=1.0,
                    label="Null (C = 1)",
                )
                ax.set_xticks(xs)
                ax.set_xticklabels(markers, rotation=45, ha="right", fontsize="small")
                ax.set_ylabel("Geary's C")
                ax.set_title(
                    "Geary's C per marker (graph: %s, perms: %d)"
                    % (graph_type, int(n_permutations))
                )
                ax.legend(fontsize="small", loc="best")
                ax.grid(True, axis="y", alpha=0.3)

                out_path = os.path.join(plot_dir, PLOT_FILE_GEARY)
                fig.savefig(out_path, dpi=int(plot_dpi), bbox_inches="tight")
                plt.close(fig)
                logger.info("Saved Geary's C PNG: %s", out_path)
            except Exception as e:
                logger.warning("Geary's C plot failed: %s", e)
    except Exception as e:
        logger.warning("Geary's C failed: %s", e)


def run_co_occurrence(
    adata,
    task,
    cluster_key="cluster",
    mode="pairwise",
    min_radius=-1.0,
    max_radius=-1.0,
    n_intervals=50,
    n_permutations=1000,
    spatial_data=None,
    graph_type="knn",
    plot_dir=None,
    plot_dpi=150,
    persist_plots=True,
    coord_unit="px",
):
    """Compute co-occurrence as a function of radius.

    Mode controls the output shape:
      - "pairwise"  -> data[a][b][r] across all cluster pairs
      - "oneVsRest" -> single-cluster vs collapsed-rest comparison

    Writes task.outputs["co_occurrence_pairwise"] or
    task.outputs["co_occurrence_one_vs_rest"] as a JSON blob shaped:
      {
        "mode": "pairwise" | "oneVsRest",
        "cluster_names": [...],
        "intervals": [...],
        "data": [[[...]]] | [[[...]]],
        "n_permutations": N,
        "graph_type": "..."
      }
    """
    import squidpy as sq

    n_clusters, _ = _cluster_sizes(adata, cluster_key)
    n_int = int(n_intervals) if n_intervals and n_intervals > 0 else 50
    if _refuse_if_too_big(
        "co-occurrence on %d cells x %d clusters" % (adata.n_obs, n_clusters),
        co_occurrence_peak_gb(adata.n_obs, n_clusters, n_int),
        "Re-run with co-occurrence turned off, or with fewer clusters -- its "
        "memory grows with the SQUARE of the cluster count. Neighborhood "
        "enrichment answers a similar question far more cheaply.",
    ):
        return

    try:
        kwargs = {"cluster_key": cluster_key, "n_splits": 1}
        if (
            min_radius is not None
            and min_radius > 0
            and max_radius is not None
            and max_radius > 0
        ):
            kwargs["interval"] = np.linspace(min_radius, max_radius, int(n_intervals))
        elif spatial_data is not None and n_intervals > 0:
            # Auto-derive the interval from CELL DENSITY, not the bounding box. A
            # fixed fraction of the bbox diagonal degenerates on thin/elongated or
            # sparse ROIs (every bin empty or saturated). The median nearest-neighbor
            # distance is the natural cell scale.
            coords = spatial_data
            xmin, xmax = float(coords[:, 0].min()), float(coords[:, 0].max())
            ymin, ymax = float(coords[:, 1].min()), float(coords[:, 1].max())
            diag = math.hypot(xmax - xmin, ymax - ymin)
            med_nn = _median_nn_distance(coords)
            if med_nn is not None and med_nn > 0:
                # Span ~1 cell spacing up to ~20 spacings, never past half the ROI.
                r_min = med_nn
                r_max = min(med_nn * 20.0, max(med_nn * 2.0, diag * 0.5))
            else:
                # Fallback: the old bbox-diagonal heuristic.
                r_min = max(1.0, diag * 0.001)
                r_max = diag * 0.1
            kwargs["interval"] = np.linspace(r_min, r_max, int(n_intervals))

        # Force serial execution (avoids the numba/joblib deadlock on Windows).
        kwargs.update(
            _safe_kwargs(sq.gr.co_occurrence, n_jobs=1, show_progress_bar=False)
        )
        sq.gr.co_occurrence(adata, **kwargs)
        cooc = adata.uns.get("%s_co_occurrence" % cluster_key, {})

        ratio = cooc.get("occ") if isinstance(cooc, dict) else None
        intervals = cooc.get("interval") if isinstance(cooc, dict) else None
        cluster_names = [str(c) for c in adata.obs[cluster_key].cat.categories]

        if ratio is None or intervals is None:
            logger.warning("Co-occurrence returned no data")
            return

        ratio_np = np.asarray(ratio, dtype=np.float64)
        intervals_list = [float(v) for v in np.asarray(intervals).ravel()]

        if mode == "oneVsRest":
            # Collapse axis 1: for each cluster A, ratio at "rest" = mean
            # across all other clusters at each radius.
            n_clusters = ratio_np.shape[0]
            collapsed = np.zeros((n_clusters, 1, ratio_np.shape[2]), dtype=np.float64)
            for a in range(n_clusters):
                others = [b for b in range(n_clusters) if b != a]
                if others:
                    collapsed[a, 0, :] = ratio_np[a, others, :].mean(axis=0)
            data_list = collapsed.tolist()
            output_key = "co_occurrence_one_vs_rest"
        else:
            data_list = ratio_np.tolist()
            output_key = "co_occurrence_pairwise"

        # NOTE: squidpy's co_occurrence is a DESCRIPTIVE conditional-probability
        # ratio with no permutation / significance test. n_permutations is NOT
        # passed to squidpy and no null model is computed, so it is deliberately
        # omitted here (advertising it would imply a test that did not run).
        payload = {
            "mode": "oneVsRest" if mode == "oneVsRest" else "pairwise",
            "cluster_names": cluster_names,
            "intervals": intervals_list,
            "data": data_list,
            "graph_type": graph_type,
        }
        task.outputs[output_key] = json.dumps(payload)
        logger.info(
            "Co-occurrence (%s) computed: %d clusters, %d intervals",
            payload["mode"],
            len(cluster_names),
            len(intervals_list),
        )

        # Phase 5: matplotlib PNG output for Feature B (batch figure export).
        # For "pairwise" mode we save a square heatmap averaged across radii;
        # for "oneVsRest" we save a per-cluster vs radius heatmap (which is
        # the natural 2-D view of that collapsed tensor).
        if (
            _should_persist(plot_dir, persist_plots)
            and cluster_names
            and intervals_list
        ):
            try:
                import matplotlib

                matplotlib.use("Agg")
                import matplotlib.pyplot as plt

                if mode == "oneVsRest":
                    # collapsed shape is (n_clusters, 1, n_intervals);
                    # squeeze the middle axis for a (n_clusters x intervals)
                    # heatmap
                    arr = np.asarray(data_list, dtype=np.float64)
                    if arr.ndim == 3 and arr.shape[1] == 1:
                        arr = arr[:, 0, :]
                    fig, ax = plt.subplots(figsize=(10, 6))
                    im = ax.imshow(arr, aspect="auto", cmap="viridis", origin="lower")
                    ax.set_yticks(np.arange(len(cluster_names)))
                    ax.set_yticklabels(cluster_names, fontsize="small")
                    # Sparse x ticks at evenly spaced intervals (max ~10)
                    n_iv = len(intervals_list)
                    step = max(1, n_iv // 10)
                    x_ticks = np.arange(0, n_iv, step)
                    ax.set_xticks(x_ticks)
                    ax.set_xticklabels(
                        ["%.1f" % intervals_list[i] for i in x_ticks],
                        rotation=45,
                        ha="right",
                        fontsize="small",
                    )
                    ax.set_xlabel("Radius (%s)" % coord_unit)
                    ax.set_ylabel("Cluster")
                    ax.set_title(
                        "Co-occurrence (one vs rest, descriptive) - "
                        "graph: %s" % graph_type
                    )
                    fig.colorbar(im, ax=ax, label="Ratio")
                    out_name = PLOT_FILE_COOC_ONE_VS_REST
                else:
                    # Pairwise: average across the radius axis to get a
                    # (n_clusters x n_clusters) square heatmap. The full
                    # per-radius tensor remains in the JSON output for
                    # interactive viewing.
                    arr = np.asarray(data_list, dtype=np.float64)
                    if arr.ndim == 3:
                        heat = arr.mean(axis=2)
                    else:
                        heat = arr
                    fig, ax = plt.subplots(figsize=(8, 7))
                    im = ax.imshow(heat, aspect="equal", cmap="viridis", origin="lower")
                    ax.set_xticks(np.arange(len(cluster_names)))
                    ax.set_yticks(np.arange(len(cluster_names)))
                    ax.set_xticklabels(
                        cluster_names, rotation=45, ha="right", fontsize="small"
                    )
                    ax.set_yticklabels(cluster_names, fontsize="small")
                    ax.set_xlabel("Cluster B")
                    ax.set_ylabel("Cluster A")
                    ax.set_title(
                        "Co-occurrence (pairwise, mean over radius, descriptive) - "
                        "graph: %s" % graph_type
                    )
                    fig.colorbar(im, ax=ax, label="Mean ratio")
                    out_name = PLOT_FILE_COOC_PAIRWISE

                out_path = os.path.join(plot_dir, out_name)
                fig.savefig(out_path, dpi=int(plot_dpi), bbox_inches="tight")
                plt.close(fig)
                logger.info(
                    "Saved co-occurrence (%s) PNG: %s", payload["mode"], out_path
                )
            except Exception as e:
                logger.warning("Co-occurrence (%s) plot failed: %s", mode, e)
    except Exception as e:
        logger.warning("Co-occurrence (%s) failed: %s", mode, e)


def compute_spatial_node_measurements(
    spatial_connectivities, spatial_distances, coords, graph_type, pixel_size_um=1.0
):
    """Return a dict of per-cell measurement arrays + edge-COO triplet.

    Read by ClusteringWorkflow.java to:
      (1) Build PathObjectConnections from the edge COO (rows/cols).
      (2) Write QPCAT spatial: <X> per-cell measurements.
      (3) Compute triangle areas (Delaunay only) and connected components.

    The edge COO triplet is deduped to i < j so each undirected edge is
    listed exactly once. Per-cell measurement arrays are length N_cells;
    triangle_areas is shape (N_cells, 2) of (mean_area, max_area) per
    vertex. component_labels is length N_cells int32 from
    scipy.sparse.csgraph.connected_components on the undirected adjacency.

    Triangle-area columns are only meaningful for graph_type == 'delaunay'
    (the squidpy graph carries edges, not faces); for other graph types
    triangle_areas is None. component_labels is returned for every graph
    type since connected components are well-defined on kNN, Radius, and
    Delaunay graphs alike.

    pixel_size_um scales the distance + triangle-area outputs into microns.
    Java passes PixelCalibration.getAveragedPixelSizeMicrons() when
    PixelCalibration.hasPixelSizeMicrons() is true, otherwise 1.0 (units
    remain pixels for uncalibrated images). Distances scale by
    pixel_size_um; triangle areas scale by pixel_size_um ** 2. Edge COO
    indices and num_neighbors counts are unit-free and unaffected.

    Returns dict with keys: row, col, num_neighbors, mean_distance,
    median_distance, max_distance, min_distance, component_labels, and
    optionally triangle_areas.
    """
    import scipy.sparse as sp
    from scipy.sparse.csgraph import connected_components

    try:
        scale = float(pixel_size_um)
    except (TypeError, ValueError):
        scale = 1.0
    if not np.isfinite(scale) or scale <= 0.0:
        scale = 1.0
    n_cells = spatial_connectivities.shape[0]
    conn_csr = spatial_connectivities.tocsr()
    # Symmetrise just in case (squidpy returns a symmetric matrix for
    # undirected graphs but we want to be safe before the dedup).
    sym_csr = (conn_csr + conn_csr.T).tolil()
    # Boolean mask of edges where i < j (deduped undirected COO triplet)
    row_arr, col_arr = sym_csr.nonzero()
    keep = row_arr < col_arr
    row_kept = np.asarray(row_arr[keep], dtype=np.int64)
    col_kept = np.asarray(col_arr[keep], dtype=np.int64)

    # Per-cell aggregates from the distances CSR.
    dist_csr = spatial_distances.tocsr() if spatial_distances is not None else None
    mean_distance = np.full(n_cells, np.nan, dtype=np.float64)
    median_distance = np.full(n_cells, np.nan, dtype=np.float64)
    max_distance = np.full(n_cells, np.nan, dtype=np.float64)
    min_distance = np.full(n_cells, np.nan, dtype=np.float64)

    # Use CSR adjacency row-pointer for neighbor count -- that mirrors
    # the legacy plugin which counts every connection regardless of
    # whether it carries a distance.
    conn_indptr = conn_csr.indptr
    num_neighbors = np.diff(conn_indptr).astype(np.int32)

    if dist_csr is not None:
        d_indptr = dist_csr.indptr
        d_data = dist_csr.data
        # Squidpy populates explicit-zero entries on the diagonal; filter them so
        # the aggregates are over real edges only. Vectorized over the CSR data:
        # reduceat groups by each NON-EMPTY row's start offset (empty/all-zero
        # rows are excluded from the boundary list so they never steal a value
        # from the preceding row -- the trap a naive indptr-clamp falls into).
        pos_mask = d_data > 0
        data_pos = d_data[pos_mask]
        if data_pos.size > 0:
            orig_counts = np.diff(d_indptr)
            row_id = np.repeat(np.arange(n_cells), orig_counts)
            row_id_pos = row_id[pos_mask]
            surv = np.bincount(row_id_pos, minlength=n_cells)  # survivors / row
            new_indptr = np.zeros(n_cells + 1, dtype=np.int64)
            np.cumsum(surv, out=new_indptr[1:])
            nonempty = np.flatnonzero(surv > 0)
            seg_starts = new_indptr[nonempty]
            cnts = surv[nonempty]
            mean_distance[nonempty] = (
                np.add.reduceat(data_pos, seg_starts) / cnts
            ) * scale
            max_distance[nonempty] = np.maximum.reduceat(data_pos, seg_starts) * scale
            min_distance[nonempty] = np.minimum.reduceat(data_pos, seg_starts) * scale
            # Median has no ragged reducer: sort values within each row via a
            # stable lexsort (row primary, value secondary), then pick the
            # midpoint(s) by segment offset.
            order = np.lexsort((data_pos, row_id_pos))
            data_sorted = data_pos[order]
            lo = seg_starts + (cnts - 1) // 2
            hi = seg_starts + cnts // 2
            median_distance[nonempty] = (
                0.5 * (data_sorted[lo] + data_sorted[hi])
            ) * scale

    # Delaunay-only triangle areas via a fresh scipy Delaunay (squidpy
    # only ships edges, not faces, so we rebuild the triangulation from
    # the coordinates). Degenerate inputs raise QhullError -- guard and
    # fall through to None.
    triangle_areas = None
    if graph_type == "delaunay" and coords is not None and n_cells >= 3:
        try:
            from scipy.spatial import Delaunay, qhull

            tri = Delaunay(np.asarray(coords, dtype=np.float64))
            simplices = tri.simplices  # shape (n_tri, 3)
            # Vectorised shoelace per triangle
            pts = np.asarray(coords, dtype=np.float64)
            p0 = pts[simplices[:, 0]]
            p1 = pts[simplices[:, 1]]
            p2 = pts[simplices[:, 2]]
            areas = 0.5 * np.abs(
                (p1[:, 0] - p0[:, 0]) * (p2[:, 1] - p0[:, 1])
                - (p2[:, 0] - p0[:, 0]) * (p1[:, 1] - p0[:, 1])
            )
            # Aggregate areas per vertex (mean and max) via scatter-add: every
            # vertex appears in multiple triangles. max is seeded with -inf then
            # restored to NaN for never-touched vertices, matching the prior loop.
            flat_vids = simplices.ravel()
            flat_areas = np.repeat(areas, 3)
            sum_areas = np.zeros(n_cells, dtype=np.float64)
            count_areas = np.zeros(n_cells, dtype=np.int32)
            np.add.at(sum_areas, flat_vids, flat_areas)
            np.add.at(count_areas, flat_vids, 1)
            max_areas = np.full(n_cells, -np.inf, dtype=np.float64)
            np.maximum.at(max_areas, flat_vids, flat_areas)
            mean_areas = np.full(n_cells, np.nan, dtype=np.float64)
            nonzero = count_areas > 0
            mean_areas[nonzero] = sum_areas[nonzero] / count_areas[nonzero]
            max_areas[~nonzero] = np.nan
            # Triangle areas scale by pixel_size_um ** 2; NaN entries
            # propagate through the multiply unchanged.
            area_scale = scale * scale
            mean_areas = mean_areas * area_scale
            max_areas = max_areas * area_scale
            triangle_areas = np.column_stack([mean_areas, max_areas])
        except qhull.QhullError as e:
            logger.warning(
                "spatial-stats Delaunay triangle areas skipped (QhullError): %s", e
            )
            triangle_areas = None
        except Exception as e:
            logger.warning("spatial-stats Delaunay triangle areas failed: %s", e)
            triangle_areas = None

    # Connected components on the undirected adjacency.
    try:
        n_components, component_labels = connected_components(
            csgraph=conn_csr, directed=False, return_labels=True
        )
        component_labels = component_labels.astype(np.int32, copy=False)
        logger.info("spatial-stats connected components: %d", n_components)
    except Exception as e:
        logger.warning("spatial-stats connected_components failed: %s", e)
        component_labels = np.zeros(n_cells, dtype=np.int32)

    return {
        "row": row_kept,
        "col": col_kept,
        "num_neighbors": num_neighbors,
        "mean_distance": mean_distance,
        "median_distance": median_distance,
        "max_distance": max_distance,
        "min_distance": min_distance,
        "triangle_areas": triangle_areas,
        "component_labels": component_labels,
    }


def emit_spatial_node_outputs(
    task, payload, graph_type, write_node_measurements, write_component_measurements
):
    """Push the payload from compute_spatial_node_measurements onto task.outputs.

    Edge COO is always emitted (overlay can be rebuilt without measurement
    writes). Per-cell scalar arrays only emitted when
    write_node_measurements is True. Triangle areas only emitted for
    Delaunay graphs. Component labels only emitted when
    write_component_measurements is True (Java-side groupby).
    """
    from appose import NDArray as PyNDArray

    if payload is None:
        return

    row_arr = payload.get("row")
    col_arr = payload.get("col")
    if row_arr is not None and col_arr is not None and row_arr.size > 0:
        row_nd = PyNDArray(dtype="int64", shape=[int(row_arr.size)])
        np.copyto(row_nd.ndarray(), row_arr.astype(np.int64))
        task.outputs["spatial_graph_row"] = row_nd
        col_nd = PyNDArray(dtype="int64", shape=[int(col_arr.size)])
        np.copyto(col_nd.ndarray(), col_arr.astype(np.int64))
        task.outputs["spatial_graph_col"] = col_nd
        logger.info("spatial-stats edge COO emitted: %d edges", int(row_arr.size))

    if write_node_measurements:
        for key in (
            "num_neighbors",
            "mean_distance",
            "median_distance",
            "max_distance",
            "min_distance",
        ):
            arr = payload.get(key)
            if arr is None:
                continue
            out_key = "spatial_" + key
            if key == "num_neighbors":
                nd = PyNDArray(dtype="int32", shape=[int(arr.size)])
                np.copyto(nd.ndarray(), arr.astype(np.int32))
            else:
                nd = PyNDArray(dtype="float64", shape=[int(arr.size)])
                np.copyto(nd.ndarray(), arr.astype(np.float64))
            task.outputs[out_key] = nd
        triangle = payload.get("triangle_areas")
        if triangle is not None and graph_type == "delaunay":
            t_nd = PyNDArray(dtype="float64", shape=[int(triangle.shape[0]), 2])
            np.copyto(t_nd.ndarray(), triangle.astype(np.float64))
            task.outputs["spatial_triangle_areas"] = t_nd
            logger.info("spatial-stats triangle areas emitted")

    if write_component_measurements:
        labels = payload.get("component_labels")
        if labels is not None:
            c_nd = PyNDArray(dtype="int32", shape=[int(labels.size)])
            np.copyto(c_nd.ndarray(), labels.astype(np.int32))
            task.outputs["component_labels"] = c_nd
            logger.info("spatial-stats component labels emitted")


def build_smoothing_adjacency_squidpy(
    spatial_data,
    graph_type="knn",
    k=15,
    radius=-1.0,
    delaunay_max_edge=-1.0,
    area_ids=None,
):
    """Hybrid-graph-reuse smoothing path (Phase 2 contract #2).

    Builds the smoothing adjacency via sq.gr.spatial_neighbors and returns
    a row-normalised pure-A connectivity matrix (no +I diagonal). This is
    the path the smoothing rewrite uses when
    qpcat.spatial.useSquidpyGraphForSmoothing is true.

    With `area_ids`, smoothing never averages a cell's features with those of
    a cell in a different specimen -- which would be a fabricated measurement,
    not a smoothed one.

    The legacy path (run_clustering.py inline) uses (A + I) row-normalised
    on a sklearn kNN graph. Numerical equivalence between the two paths
    is checked at the workstation (see SCRIPTING.md).
    """
    import anndata as ad
    import scipy.sparse as sp

    adata_tmp = ad.AnnData(X=np.zeros((len(spatial_data), 1)))
    adata_tmp.obsm["spatial"] = np.asarray(spatial_data)
    build_spatial_graph(
        adata_tmp,
        graph_type=graph_type,
        k=k,
        radius=radius,
        delaunay_max_edge=delaunay_max_edge,
        area_ids=area_ids,
    )
    conn = adata_tmp.obsp["spatial_connectivities"].astype(np.float64)
    row_sums = np.array(conn.sum(axis=1)).flatten()
    row_sums[row_sums == 0] = 1.0
    return sp.diags(1.0 / row_sums) @ conn


def banksy_k_cap(k_geom, n_block, max_m=1):
    """Largest usable k_geom for a block of n_block cells.

    banksy's generate_spatial_weights_fixed_nbrs asks sklearn for
    `num_neighbours * (m + 1)` neighbours (banksy/main.py:158), so with
    max_m=1 the real constraint is 2*k <= n-1, NOT k <= n-1. The old global
    cap used the latter, which is why a 20-cell run with k_geom=15 raised
    inside sklearn rather than being clamped.

    Returns 0 when the block cannot support a graph at all.
    """
    return max(0, min(int(k_geom), (int(n_block) - 1) // (int(max_m) + 1)))


def build_banksy_weights(
    spatial_data,
    k_geom=15,
    area_ids=None,
    max_m=1,
    nbr_weight_decay="scaled_gaussian",
):
    """Per-area BANKSY neighbour weights, assembled block-diagonally.

    Returns a banksy_dict shaped exactly as initialize_banksy's output --
    {decay: {"weights": {m: csr}}} -- so the rest of the BANKSY pipeline
    (generate_banksy_matrix -> pca_umap -> run_Leiden_partition) runs
    UNCHANGED on the full cell set.

    That is the whole point of cutting the seam here. Running all of BANKSY
    per area would give each area its own Leiden partition, and cluster 3 in
    one core would then be unrelated to cluster 3 in the next -- destroying
    the cross-area comparison the partitioning exists to make possible. Only
    the neighbour graph is per area; clustering stays global.

    It also leaves concatenate_all's z-score global (banksy/main.py:348).
    Reassembling per-area augmented MATRICES instead would z-score each area
    separately, silently regressing out per-core mean expression -- an
    undeclared batch correction that would make "this cluster is CD8-high"
    mean "high relative to its own core".

    A single area yields one block and the identity permutation, so the
    result is what initialize_banksy would have produced on its own.
    """
    import contextlib

    import anndata as ad
    import scipy.sparse as sp
    from banksy.initialize_banksy import initialize_banksy

    coords = np.asarray(spatial_data, dtype=np.float64)
    n = coords.shape[0]
    slices = area_slices(area_ids, n)

    blocks = {m: [] for m in range(int(max_m) + 1)}
    order = []
    reduced = []
    degenerate = []

    for area_id, idx in slices:
        n_area = int(idx.size)
        order.append(idx)
        k_area = banksy_k_cap(k_geom, n_area, max_m)
        if k_area < 1:
            # Too few cells for any neighbour graph. An all-zero block means
            # these cells contribute no neighbourhood term and cluster on
            # their own expression -- the honest answer for a 2-cell core.
            # Dropping them instead would misalign every downstream index,
            # because labels map back positionally on the Java side.
            degenerate.append((area_id, n_area))
            for m in range(int(max_m) + 1):
                dtype = np.float64 if m == 0 else np.complex128
                blocks[m].append(sp.csr_matrix((n_area, n_area), dtype=dtype))
            continue
        if k_area < int(k_geom):
            reduced.append((area_id, n_area, k_area))

        block = ad.AnnData(X=np.zeros((n_area, 1), dtype=np.float32))
        block.obsm["spatial"] = coords[idx]
        block.obs["x"] = coords[idx, 0]
        block.obs["y"] = coords[idx, 1]

        # banksy prints diagnostics to stdout, which is also Appose's protocol
        # channel; on a full pipe that can stall the worker.
        with open(os.devnull, "w") as _devnull, contextlib.redirect_stdout(_devnull):
            banksy_dict = initialize_banksy(
                block,
                ("x", "y", "spatial"),
                num_neighbours=k_area,
                nbr_weight_decay=nbr_weight_decay,
                max_m=int(max_m),
                plt_edge_hist=False,
                plt_nbr_weights=False,
                plt_agf_angles=False,
                plt_theta=False,
            )
        weights = banksy_dict[nbr_weight_decay]["weights"]
        for m in range(int(max_m) + 1):
            blocks[m].append(sp.csr_matrix(weights[m]))

    if reduced:
        smallest = min(reduced, key=lambda r: r[2])
        logger.warning(
            "BANKSY: %d area(s) had k_geom reduced below %d to fit the area size "
            "(smallest: area %d, %d cells, k_geom=%d)",
            len(reduced),
            int(k_geom),
            smallest[0],
            smallest[1],
            smallest[2],
        )
    if degenerate:
        logger.warning(
            "BANKSY: %d area(s) have too few cells for a neighbour graph and were "
            "clustered on their own expression only (smallest has %d cell(s)); "
            "their results are not spatially informed",
            len(degenerate),
            min(d[1] for d in degenerate),
        )

    concatenated = np.concatenate(order) if order else np.zeros(0, dtype=np.int64)
    inverse = np.argsort(concatenated)
    assembled = {}
    for m in range(int(max_m) + 1):
        if len(blocks[m]) == 1:
            assembled[m] = blocks[m][0]
        else:
            assembled[m] = sp.block_diag(blocks[m], format="csr")[inverse, :][
                :, inverse
            ]

    if len(slices) > 1:
        logger.info(
            "BANKSY neighbour graph built per area: %d areas, no edges between them",
            len(slices),
        )
    return {nbr_weight_decay: {"weights": assembled}}


def build_smoothing_adjacency_sklearn(spatial_data, k=15, area_ids=None):
    """Legacy smoothing adjacency: sklearn kNN, (A + I) row-normalised.

    This is the path that is byte-stable with respect to prior QP-CAT
    releases, lifted out of run_clustering.py so it can be tested at all --
    inline script bodies are unreachable by the AST loader the tests use.

    With `area_ids`, neighbours are found within each area only. Smoothing a
    cell's features with those of a cell in a different specimen does not
    produce a smoothed measurement, it produces a fabricated one.

    A single area reproduces the previous behaviour exactly.
    """
    import scipy.sparse as sp
    from sklearn.neighbors import NearestNeighbors

    coords = np.asarray(spatial_data)
    n = coords.shape[0]
    slices = area_slices(area_ids, n)

    rows = []
    cols = []
    reduced = []
    for area_id, idx in slices:
        n_area = idx.size
        if n_area < 2:
            # No neighbours to average with; the (A + I) identity term below
            # leaves the cell's own features untouched, which is the honest
            # answer for an isolated cell.
            continue
        k_area = max(1, min(int(k), n_area - 1))
        if k_area < int(k):
            reduced.append((area_id, n_area, k_area))
        nn = NearestNeighbors(n_neighbors=k_area, metric="euclidean")
        nn.fit(coords[idx])
        _distances, indices = nn.kneighbors(coords[idx])
        # Map block-local neighbour indices back to global cell indices.
        rows.append(np.repeat(idx, k_area))
        cols.append(idx[indices.ravel()])

    if reduced:
        smallest = min(reduced, key=lambda r: r[2])
        logger.warning(
            "Spatial smoothing: %d area(s) had k reduced below %d to fit the "
            "area size (smallest: area %d, %d cells, k=%d)",
            len(reduced),
            int(k),
            smallest[0],
            smallest[1],
            smallest[2],
        )

    if rows:
        row_idx = np.concatenate(rows)
        col_idx = np.concatenate(cols)
    else:
        row_idx = np.zeros(0, dtype=np.int64)
        col_idx = np.zeros(0, dtype=np.int64)

    adj = sp.csr_matrix((np.ones(len(row_idx)), (row_idx, col_idx)), shape=(n, n))
    adj = adj + sp.eye(n)
    row_sums = np.array(adj.sum(axis=1)).flatten()
    row_sums[row_sums == 0] = 1.0
    return sp.diags(1.0 / row_sums) @ adj
