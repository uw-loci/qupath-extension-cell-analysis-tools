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
PLOT_FILE_COOC_CURVES = "co_occurrence_curves.png"


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


def area_type(area_types, area_id):
    """Type label for an area id, defaulting to "Image".

    Absent types mean the run was not partitioned below the image level, which
    is exactly when every area IS an image.
    """
    if area_types is not None:
        try:
            value = list(area_types)[int(area_id)]
            if value:
                return str(value)
        except (IndexError, ValueError, TypeError):
            pass
    return "Image"


def _csv_cell(value):
    """Minimal CSV quoting. Area labels contain ' | ' and can contain commas."""
    text = "" if value is None else str(value)
    if any(ch in text for ch in [",", '"', "\n", "\r"]):
        return '"' + text.replace('"', '""') + '"'
    return text


def _csv_row(values):
    return ",".join(_csv_cell(v) for v in values)


def build_area_summary_csv(
    area_ids, area_names, cluster_labels, cluster_names=None, area_types=None
):
    """Wide per-area summary: one row per area, one column per cluster.

    This is the file a user actually opens. It answers "what is in each core"
    -- the question that only becomes askable once areas exist -- and it is
    deliberately separate from the cluster-level output: a cluster's marker
    profile is a property of the CLUSTER, identical in every area, and
    repeating it 55 times would be noise.

    Columns: area, type, n_cells, n_clusters_present, then one fraction column
    per cluster, then the matching count columns.

    `type` says WHAT each area is -- "Image", "TMA Core", "Annotation-Tumor" --
    because a name alone is not self-describing once a project mixes cores and
    annotations, and "A-1" tells a reader nothing on its own.
    """
    ids = np.asarray(area_ids, dtype=np.int64).ravel()
    labels = np.asarray(cluster_labels).ravel()
    if ids.shape[0] != labels.shape[0]:
        raise ValueError(
            "area_ids length (%d) does not match cluster label count (%d)"
            % (ids.shape[0], labels.shape[0])
        )

    present = sorted(set(int(v) for v in labels))

    # Negative labels are NOISE (HDBSCAN), not a cluster: those cells are left
    # unclassified in the viewer, so a "Cluster -1" column names a population
    # the reader cannot go and look at. Name it, and keep it out of the
    # cluster count.
    def _name_of(c):
        if c < 0:
            return "Noise"
        if cluster_names is None:
            return "Cluster %d" % c
        listed = list(cluster_names)
        return listed[c] if 0 <= c < len(listed) else "Cluster %d" % c

    names = {c: _name_of(c) for c in present}

    header = ["area", "type", "n_cells", "n_clusters_present"]
    header += ["%s_frac" % names[c] for c in present]
    header += ["%s_count" % names[c] for c in present]
    rows = [_csv_row(header)]

    for area_id, idx in area_slices(ids, ids.shape[0]):
        area_labels = labels[idx]
        n = int(area_labels.shape[0])
        counts = {c: int(np.count_nonzero(area_labels == c)) for c in present}
        n_present = sum(1 for c in present if c >= 0 and counts[c] > 0)
        row = [
            area_label(area_names, area_id),
            area_type(area_types, area_id),
            n,
            n_present,
        ]
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


# CSR realisations per cluster for the Ripley envelope. 99 gives a 2.5/97.5 band
# with a resolution of about one percentile, and costs well under a second for a
# few thousand points.
RIPLEY_ENVELOPE_SIMS = 99


def ripley_l_observed(coords, radii):
    """Ripley's L for one point pattern, normalised by ITS OWN intensity.

    ``K(r) = (A / n^2) * #{ordered pairs closer than r}``, then
    ``L(r) = sqrt(K(r) / pi)``.

    WHY WE DO NOT USE squidpy's L. squidpy's ``_ripley.py`` computes each
    cluster's pairwise distances from that cluster's points but passes the
    GLOBAL cell count to the estimator:

        distances = pdist(coord_c)                     # cluster i only
        _l_function(distances, support, N, area)       # N = ALL cells

    so every cluster's K is divided by N^2 instead of n_c^2 and its L comes out
    scaled by n_c / N. Measured on complete spatial randomness -- where the
    answer must be L(r) = r -- squidpy returned 0.10 * r for a cluster holding
    15% of the points. Every cluster therefore plots far below the diagonal
    whatever its real pattern, ordered by cluster SIZE rather than by
    clustering, which is what a 7-cluster run looked like: all seven below the
    null, reading as universal dispersion.

    squidpy's own docstring gives the right formula -- ``K(t) = (1/lambda) *
    sum_{i != j} I(d_ij < t) / n`` -- so this is an implementation slip, not a
    difference of definition.

    :param coords: (n, 2) array of point coordinates
    :param radii: increasing array of radii to evaluate at
    :return: L(r) array, same length as radii; zeros when n < 2
    """
    import numpy as np
    from scipy.spatial import ConvexHull
    from scipy.spatial.distance import pdist

    coords = np.asarray(coords, dtype=float)
    n = coords.shape[0]
    radii = np.asarray(radii, dtype=float)
    if n < 2:
        return np.zeros(len(radii))
    try:
        area = float(ConvexHull(coords).volume)
    except Exception:
        # Degenerate (collinear / coincident) points have no hull.
        return np.zeros(len(radii))
    if area <= 0:
        return np.zeros(len(radii))

    d = pdist(coords)
    counts = (d < radii.reshape(-1, 1)).sum(axis=1)
    k = (2.0 * counts) * area / float(n * n)
    return np.sqrt(np.maximum(k, 0.0) / math.pi)


def ripley_csr_envelope(
    hull_points, n_points, radii, n_sim=99, seed=0, lo=2.5, hi=97.5
):
    """Monte-Carlo envelope of L(r) under complete spatial randomness.

    THE ENVELOPE IS THE NULL, not the analytical diagonal. ``L(r) = r`` is the
    expectation of an UNBIASED, edge-corrected estimator. This estimator has no
    edge correction, so near the domain boundary part of every disc of radius r
    falls outside the study region and the pair count is systematically short --
    increasingly so as r grows. Measured on complete spatial randomness with the
    correct per-cluster intensity, L still came out at 0.70 * r at the largest
    radius. Judged against the diagonal, random points look dispersed.

    Simulating CSR with the SAME point count, in the SAME hull, through the SAME
    estimator absorbs that bias: whatever the estimator does to random data, it
    does to the envelope too. A curve is only meaningful where it leaves the
    band.

    :param hull_points: points whose convex hull defines the study region
    :param n_points: how many points to scatter per simulation (the cluster's n)
    :param radii: radii to evaluate at
    :param n_sim: number of CSR realisations
    :param seed: RNG seed, so a run reproduces
    :param lo: lower percentile of the band
    :param hi: upper percentile of the band
    :return: (low, median, high) arrays, each the length of radii
    """
    import numpy as np
    from scipy.spatial import ConvexHull, Delaunay

    radii = np.asarray(radii, dtype=float)
    zeros = np.zeros(len(radii))
    hull_points = np.asarray(hull_points, dtype=float)
    if n_points < 2 or hull_points.shape[0] < 3:
        return zeros, zeros, zeros
    try:
        hull = ConvexHull(hull_points)
        verts = hull.points[hull.vertices]
        tri = Delaunay(verts)
    except Exception:
        return zeros, zeros, zeros

    lo_xy = verts.min(axis=0)
    hi_xy = verts.max(axis=0)
    rng = np.random.default_rng(seed)

    curves = np.empty((n_sim, len(radii)))
    for i in range(n_sim):
        # Rejection-sample uniformly inside the hull via its bounding box.
        got = np.empty((0, 2))
        guard = 0
        while got.shape[0] < n_points and guard < 100:
            need = n_points - got.shape[0]
            cand = rng.uniform(lo_xy, hi_xy, size=(max(need * 2, 32), 2))
            inside = cand[tri.find_simplex(cand) >= 0]
            got = np.vstack([got, inside]) if got.size else inside
            guard += 1
        if got.shape[0] < n_points:
            return zeros, zeros, zeros
        curves[i] = ripley_l_observed(got[:n_points], radii)

    return (
        np.percentile(curves, lo, axis=0),
        np.percentile(curves, 50.0, axis=0),
        np.percentile(curves, hi, axis=0),
    )


def run_ripley(
    adata,
    task,
    cluster_key="cluster",
    n_permutations=1000,
    max_radius=-1.0,
    n_steps=50,
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
        "poisson_l": [...],             # analytical L(r) = r, NOT the null used
        "envelope_low":    [[...], ...],  # per-cluster CSR band, low percentile
        "envelope_median": [[...], ...],  # per-cluster CSR band, median
        "envelope_high":   [[...], ...],  # per-cluster CSR band, high percentile
        "p_values": {"0": p0, ...},
        "n_permutations": N
      }

    Takes no graph argument: sq.gr.ripley reads spatial coordinates, so the
    kNN/Delaunay choice cannot reach it.

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

        # L only. squidpy's RipleyStat dropped mode='K' (F, G and L remain), and
        # both shipped environments pin squidpy >= 1.6.6, so the K call could
        # only ever raise -- and it did, logging a warning on every single run
        # about a statistic nobody asked for. L is the variance-stabilized
        # transform and carries the same clustering-vs-dispersion signal.
        # k_available stays False so the extraction below reports K as absent
        # rather than emitting zeros that would read as a measured result.
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

        # RECOMPUTE L, and build a simulated null. squidpy's curves are kept only
        # for the K panel; its L is scaled by n_cluster / N (see
        # ripley_l_observed) and cannot be compared with anything. Both the
        # observed curve and its null are produced here by the same estimator, so
        # the comparison is valid whatever that estimator's edge bias.
        poisson_k = [math.pi * (r * r) for r in radii]
        envelope_low = []
        envelope_high = []
        envelope_median = []
        try:
            import numpy as _np

            coords_all = _np.asarray(adata.obsm["spatial"], dtype=float)
            radii_arr = _np.asarray(radii, dtype=float)
            labels_all = [str(c) for c in adata.obs[cluster_key].values]
            recomputed = []
            for cname in cluster_names:
                mask = _np.array([lab == cname for lab in labels_all], dtype=bool)
                pts_c = coords_all[mask]
                recomputed.append(
                    [float(v) for v in ripley_l_observed(pts_c, radii_arr)]
                )
                lo, med, hi = ripley_csr_envelope(
                    coords_all,
                    int(mask.sum()),
                    radii_arr,
                    n_sim=RIPLEY_ENVELOPE_SIMS,
                    seed=0,
                )
                envelope_low.append([float(v) for v in lo])
                envelope_median.append([float(v) for v in med])
                envelope_high.append([float(v) for v in hi])
            l_curves = recomputed
            logger.info(
                "Ripley L recomputed per cluster with its own intensity, plus a "
                "%d-run CSR envelope per cluster",
                RIPLEY_ENVELOPE_SIMS,
            )
        except Exception as e:
            # Refuse rather than fall back to squidpy's mis-scaled L: a curve that
            # cannot be compared to its null is worse than no curve.
            msg = (
                "Ripley L could not be recomputed (%s). No result was written: "
                "squidpy's own L is normalised by the total cell count rather "
                "than each cluster's, so it cannot be read against any null." % e
            )
            logger.error(msg)
            task.outputs["ripley_error"] = msg
            return

        # Kept for the payload's shape; the envelope is what the chart compares
        # against now.
        poisson_l = [float(r) for r in radii]

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
            "envelope_low": envelope_low,
            "envelope_median": envelope_median,
            "envelope_high": envelope_high,
            "envelope_sims": RIPLEY_ENVELOPE_SIMS,
            "p_values": p_values,
            # Per-cluster p-value CURVE (one value per radius). squidpy reports
            # significance per radius; collapsing it to one number per cluster
            # would be a summary we invented, so the curve is passed through
            # and p_values stays empty unless a build supplies real scalars.
            "p_value_curves": p_value_curves,
            "n_permutations": int(n_permutations),
            # True when this squidpy build dropped mode='K'. The k_values above
            # are then ZERO PADDING, not a measurement, and a chart of zeros
            # reads exactly like a real "no clustering at any radius" result --
            # so the consumer must be told rather than left to plot it.
            "k_unavailable": not k_available,
        }
        task.outputs["ripley"] = json.dumps(payload)
        logger.info(
            "Ripley L computed for %d clusters (%d radii, %d perms)",
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

                if k_available:
                    fig, (ax_k, ax_l) = plt.subplots(1, 2, figsize=(12, 5))
                else:
                    # No K from this squidpy build; k_curves are zero padding.
                    # Plotting them would show every cluster flat on zero, which
                    # reads as a measured result. Draw L alone instead.
                    fig, ax_l = plt.subplots(1, 1, figsize=(7, 5))
                    ax_k = None

                # Centre on each cluster's OWN simulated-random median, which is
                # what the in-app Ripley L tab plots. Until now this PNG drew the
                # analytical Poisson diagonal instead, so a figure exported for a
                # paper disagreed with the chart it was exported from -- and the
                # diagonal is the weaker reference: it assumes an unbounded plane
                # with no edge correction, which is why the simulated envelope
                # was added in the first place.
                centred = bool(envelope_median) and len(envelope_median) == len(
                    l_curves
                )
                n_clusters = len(cluster_names)
                cmap_name = "tab20" if n_clusters > 10 else "tab10"
                cmap = plt.get_cmap(cmap_name, max(n_clusters, 1))

                for idx, cname in enumerate(cluster_names):
                    color = cmap(idx)
                    if ax_k is not None and idx < len(k_curves):
                        ax_k.plot(
                            radii,
                            k_curves[idx],
                            color=color,
                            label=str(cname),
                            linewidth=1.2,
                        )
                    if idx < len(l_curves):
                        curve = l_curves[idx]
                        if centred:
                            curve = centre_on_median(curve, envelope_median[idx])
                        ax_l.plot(
                            radii[: len(curve)],
                            curve,
                            color=color,
                            label=str(cname),
                            linewidth=1.2,
                        )
                        # Each cluster's band in its own colour, so "is this
                        # curve outside ITS band" is answerable with several
                        # drawn at once.
                        if (
                            centred
                            and idx < len(envelope_low)
                            and idx < len(envelope_high)
                        ):
                            med = envelope_median[idx]
                            for edge in (envelope_low[idx], envelope_high[idx]):
                                band = centre_on_median(edge, med)
                                n_e = min(len(band), len(radii))
                                ax_l.plot(
                                    radii[:n_e],
                                    band[:n_e],
                                    "--",
                                    color=color,
                                    linewidth=0.8,
                                    alpha=0.7,
                                )

                # Poisson null overlays (dashed black for visibility)
                if ax_k is not None:
                    ax_k.plot(
                        radii,
                        poisson_k,
                        "--",
                        color="black",
                        label="Poisson null",
                        linewidth=1.0,
                    )
                if centred:
                    # Randomness is a flat line at zero once centred.
                    ax_l.axhline(
                        0.0,
                        linestyle="--",
                        color="black",
                        linewidth=1.0,
                        label="Random (simulated)",
                    )
                else:
                    # Pre-envelope fallback: the analytical diagonal is all there
                    # is. Named so it is not read as the simulated null.
                    ax_l.plot(
                        radii,
                        poisson_l,
                        "--",
                        color="black",
                        label="Poisson null (analytical)",
                        linewidth=1.0,
                    )

                if ax_k is not None:
                    ax_k.set_xlabel("Radius (%s)" % coord_unit)
                    ax_k.set_ylabel("K(r)")
                    ax_k.set_title("Ripley K")
                    ax_k.legend(fontsize="small", loc="best")
                    ax_k.grid(True, alpha=0.3)

                ax_l.set_xlabel("Radius (%s)" % coord_unit)
                ax_l.set_ylabel("L(r) - random" if centred else "L(r)")
                if ax_k is not None:
                    ax_l.set_title(
                        "Ripley L(r), relative to random" if centred else "Ripley L(r)"
                    )
                ax_l.legend(fontsize="small", loc="best")
                ax_l.grid(True, alpha=0.3)

                # Parameters belong under the axis title on a one-panel figure;
                # a suptitle there just repeats "Ripley L" above itself.
                params = "perms: %d%s" % (
                    int(n_permutations),
                    ", %d CSR sims" % RIPLEY_ENVELOPE_SIMS if centred else "",
                )
                if ax_k is not None:
                    fig.suptitle("Ripley K and L (%s)" % params)
                else:
                    ax_l.set_title(
                        "%s\n%s"
                        % (
                            (
                                "Ripley L(r), relative to random"
                                if centred
                                else "Ripley L(r)"
                            ),
                            params,
                        ),
                        fontsize="medium",
                    )
                out_path = os.path.join(plot_dir, PLOT_FILE_RIPLEY)
                fig.savefig(out_path, dpi=int(plot_dpi), bbox_inches="tight")
                plt.close(fig)
                logger.info("Saved Ripley L PNG: %s", out_path)
            except Exception as e:
                logger.warning("Ripley L plot failed: %s", e)
    except Exception as e:
        logger.warning("Ripley L failed: %s", e)


def centre_on_median(curve, median):
    """Subtract a cluster's own simulated-random median from its L curve.

    This is what makes zero mean "random" on the Ripley plot. Both the in-app
    chart and the exported PNG draw the centred form; they disagreed until
    0.14.11, when the PNG still drew the analytical Poisson diagonal.

    Truncates to the shorter of the two rather than raising: a ragged pair means
    a partial envelope, and half a curve drawn correctly beats no plot at all.

    :param curve: L(r) for one cluster
    :param median: that cluster's simulated-CSR median, same r-axis
    :return: list of curve - median, length min(len(curve), len(median))
    """
    n = min(len(curve), len(median))
    return [curve[j] - median[j] for j in range(n)]


# ---------------------------------------------------------------------------
# Moran's I and Geary's C: one squidpy call, two modes, one p-value contract.
#
# Both statistics are sq.gr.spatial_autocorr with a different `mode`. They used
# to be written out twice -- Geary here, Moran inline in run_clustering.py AND
# again inline in spatial_stats_standalone.py -- and the same two defects were
# in both Moran copies: `genes` omitted, so squidpy indexed with
# `adata.var_names.values`, which under the pinned pandas 3.0.5 is an
# ArrowStringArray that anndata rejects with a bare IndexError; and `copy` left
# at False, so the function returns None and writing `df.index` raises. Either
# alone meant the Spatial Autocorrelation tab could never appear, and a bare
# `except Exception` turned both into a log line.
#
# So the call lives in one place now. `genes` and `copy=True` are not optional
# arguments here, they are the contract, and test_spatial_autocorr_contract.py
# pins them against the installed squidpy.
# ---------------------------------------------------------------------------

# Which of squidpy's p-value columns to REPORT. It returns up to nine, and the
# previous Geary code picked "pval_norm" first -- the analytic normal-theory
# value -- so the permutations it had just paid for were computed and discarded,
# and the Benjamini-Hochberg column squidpy produces by default
# (corr_method="fdr_bh") was never read at all.
#
# The corrected simulated p leads, because the tab shows a whole marker panel at
# once and the question put to it is "which markers", which is a multiple
# comparison. Measured on 34 markers x 2000 cells of i.i.d. noise, where no
# spatial structure exists: pval_norm flagged 3 markers at < 0.05 (smallest
# 0.0024) and pval_sim flagged 9, while either FDR column flagged none.
AUTOCORR_P_PREFERENCE = (
    "pval_sim_fdr_bh",
    "pval_z_sim_fdr_bh",
    "pval_norm_fdr_bh",
    "pval_sim",
    "pval_z_sim",
    "pval_norm",
    "pval",
)

# The uncorrected counterpart, kept alongside so a value can be cross-referenced
# against a published or previously-reported number.
AUTOCORR_P_RAW_PREFERENCE = ("pval_sim", "pval_z_sim", "pval_norm", "pval")

# Outputs that were requested and did not arrive, with a reason a user can act
# on. A failed statistic leaves no trace in the results window -- the tab is
# simply absent, which looks exactly like not having asked for it. These are
# NOT quality warnings: the result stands, one tab is missing. run_clustering.py
# emits them under their own key (omitted_outputs) after the spatial section,
# and the Java side shows them in a separate, neutral banner. Sharing the
# quality channel put them under "This result may not be usable", over a note
# saying the opposite.
SPATIAL_NOTES = []


def reset_notes():
    """Clear the per-run note list.

    The Appose worker is long-lived and this module stays imported between
    tasks, so without this a note from one run reappears in the next one's
    results window. Every entry point calls it before doing any spatial work.
    """
    del SPATIAL_NOTES[:]


def note_for_user(message, detail=None):
    """Record a message the results window should show, and log it.

    :param message: what the user reads; plain words, no exception text
    :param detail: for the log only -- exception type and message, if any
    """
    SPATIAL_NOTES.append(message)
    if detail:
        logger.warning("Spatial: %s [%s]", message, detail)
    else:
        logger.warning("Spatial: %s", message)


def _json_number(v):
    """NaN / inf -> None, so the emitted JSON is valid and nulls render blank."""
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return f if math.isfinite(f) else None


def pick_p_column(row, preference):
    """First finite p-value in `preference` that `row` actually has.

    :param row: one row of the sq.gr.spatial_autocorr DataFrame
    :param preference: column names, best first
    :return: (value, column_name), or (nan, None) when none is usable
    """
    for col in preference:
        if col not in row:
            continue
        try:
            val = float(row[col])
        except (TypeError, ValueError):
            continue
        if not math.isnan(val):
            return val, col
    return float("nan"), None


def compute_autocorr(
    adata, mode, measurements, n_permutations=0, value_key="I"
):
    """Run sq.gr.spatial_autocorr for one mode and shape the per-marker stats.

    :param adata: AnnData carrying a connectivity graph
    :param mode: "moran" or "geary"
    :param measurements: marker names to test; REQUIRED, see the note above
    :param n_permutations: 0 leaves squidpy at its analytic-only default
    :param value_key: "I" for Moran, "C" for Geary
    :return: (stats, p_method) where stats maps marker -> dict with the value,
             "p" (reported), "p_raw" (uncorrected) and p_method names the column
    :raises ValueError: when `measurements` is empty
    """
    import squidpy as sq

    genes = [str(m) for m in (measurements or [])]
    if not genes:
        # Why an explicit list is required, for whoever reads this: with
        # genes=None squidpy indexes AnnData with var_names.values, which
        # pandas 3 returns as an ArrowStringArray and anndata rejects.
        raise ValueError("no measurements were selected to test")

    kwargs = {"mode": mode, "genes": genes}
    if n_permutations and int(n_permutations) > 0:
        kwargs["n_perms"] = int(n_permutations)
    # Force serial execution (avoids the numba/joblib deadlock on Windows).
    kwargs.update(
        _safe_kwargs(sq.gr.spatial_autocorr, n_jobs=1, show_progress_bar=False, seed=0)
    )
    # copy=True or the return value is None and the results land in adata.uns.
    df = sq.gr.spatial_autocorr(adata, copy=True, **kwargs)

    stats = {}
    p_method = None
    for marker in df.index:
        row = df.loc[marker]
        value = float(row.get(value_key, row.get("I", float("nan"))))
        p_val, p_col = pick_p_column(row, AUTOCORR_P_PREFERENCE)
        p_raw, _ = pick_p_column(row, AUTOCORR_P_RAW_PREFERENCE)
        if p_col is not None and p_method is None:
            p_method = p_col
        stats[str(marker)] = {"value": value, "p": p_val, "p_raw": p_raw}
    return stats, p_method


def describe_p_method(p_method, n_markers, n_permutations):
    """One line naming exactly which p-value a table is showing."""
    if not p_method:
        return "no p-value column was available"
    parts = []
    if "sim" in p_method:
        parts.append("%d permutations" % int(n_permutations or 0))
    else:
        parts.append("normal-theory approximation")
    if p_method.endswith("_fdr_bh"):
        parts.append("Benjamini-Hochberg across %d markers" % int(n_markers))
    else:
        parts.append("NOT corrected for multiple markers")
    return "%s (%s)" % (p_method, ", ".join(parts))


def run_moran_i(
    adata,
    task,
    measurements,
    n_permutations=0,
    output_key="spatial_autocorr",
):
    """Compute Moran's I per measurement and write task.outputs[output_key].

    :param measurements: marker names to test
    :param n_permutations: permutation count; 0 for squidpy's analytic default
    :param output_key: task output key the Java side reads
    :return: number of markers reported (0 when nothing was produced)
    """
    try:
        stats, p_method = compute_autocorr(
            adata, "moran", measurements,
            n_permutations=n_permutations, value_key="I",
        )
        payload = {}
        for marker, st in stats.items():
            payload[marker] = {
                "I": _json_number(st["value"]),
                "pval": _json_number(st["p"]),
                "pval_uncorrected": _json_number(st["p_raw"]),
            }
        # The payload stays strictly marker -> numbers, because the Java side
        # parses it as Map<String, Map<String, Double>> and renders one table
        # row per key. The method line travels as its own output.
        task.outputs[output_key] = json.dumps(payload)
        task.outputs[output_key + "_p_method"] = describe_p_method(
            p_method, len(stats), n_permutations
        )
        logger.info(
            "Moran's I computed for %d markers (p from %s)", len(stats), p_method
        )
        return len(stats)
    except Exception as e:
        # The user reads the first sentence; the exception goes to the log.
        # Rendering it inline gave "(IndexError: )" for the defect that hid this
        # tab for months, and a 228-character developer sentence for the guard.
        note_for_user(
            "Moran's I did not run, so there is no Spatial Autocorrelation tab. "
            "Everything else in this result stands. The QuPath log has the "
            "error; re-running the statistic from Explore & spatial > Spatial "
            "statistics on existing clusters... is the quickest retry.",
            detail="%s: %s" % (type(e).__name__, e) if str(e) else type(e).__name__,
        )
        return 0


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
        "marker_stats": {"CD3: Mean": {"c": 0.42, "p_value": 0.001,
                                       "p_value_uncorrected": 0.0004}, ...},
        "n_permutations": N,
        "graph_type": "...",
        "p_value_method": "pval_sim_fdr_bh (1000 permutations, ...)"
      }

    Shares its squidpy call with Moran's I via compute_autocorr, which is also
    what fixes the p-value: this used to prefer "pval_norm" and so discarded
    both the permutation p it paid for and squidpy's Benjamini-Hochberg column.
    """
    try:
        stats, p_method = compute_autocorr(
            adata, "geary", measurements,
            n_permutations=n_permutations, value_key="C",
        )
        marker_stats = {}
        for marker, st in stats.items():
            marker_stats[marker] = {
                "c": _json_number(st["value"]),
                "p_value": _json_number(st["p"]),
                "p_value_uncorrected": _json_number(st["p_raw"]),
            }

        payload = {
            "marker_stats": marker_stats,
            "n_permutations": int(n_permutations),
            "graph_type": graph_type,
            "p_value_method": describe_p_method(
                p_method, len(marker_stats), n_permutations
            ),
        }
        task.outputs["geary_c"] = json.dumps(payload)
        logger.info(
            "Geary's C computed for %d markers (%d perms, p from %s)",
            len(marker_stats),
            n_permutations,
            p_method,
        )

        # Phase 5: matplotlib PNG output for Feature B (batch figure export).
        if _should_persist(plot_dir, persist_plots) and marker_stats:
            try:
                import matplotlib

                matplotlib.use("Agg")
                import matplotlib.pyplot as plt

                # A marker with no C is OMITTED, not drawn at zero. The null
                # for this statistic is C = 1, so a zero bar is the tallest
                # "positive autocorrelation" bar on the chart -- it reads as the
                # strongest result rather than as a missing one. The caption
                # says how many were left out.
                measured = [
                    (m, float(marker_stats[m]["c"]))
                    for m in marker_stats
                    if marker_stats[m].get("c") is not None
                    and math.isfinite(float(marker_stats[m]["c"]))
                ]
                n_omitted = len(marker_stats) - len(measured)
                if not measured:
                    raise ValueError(
                        "no marker produced a finite Geary's C, so there is "
                        "nothing to plot"
                    )
                markers = [m for m, _ in measured]
                c_plot = [c for _, c in measured]

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
                _title = "Geary's C per marker (graph: %s, perms: %d)" % (
                    graph_type,
                    int(n_permutations),
                )
                if n_omitted:
                    _title += "\n%d marker(s) omitted: no finite C" % n_omitted
                ax.set_title(_title)
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


# Panels in the curves figure. Past this the grid is taller than it is useful
# and the per-panel legend stops being readable; the caption says it truncated.
MAX_COOC_CURVE_PANELS = 12

def pick_radius_bin(intervals, requested, median_nn=None, nn_multiple=5.0):
    """Which radius bin a cluster-by-cluster matrix should be drawn at.

    The bins are cumulative discs, so a matrix has to name one radius rather
    than average them: the mean is dominated by the large-radius bins, where the
    ratio has already decayed toward 1.0.

    :param intervals: bin radii, ascending, one per data bin
    :param requested: radius asked for in coord units, or <= 0 for the default
    :param median_nn: median nearest-neighbour distance, or None
    :param nn_multiple: default radius as a multiple of median_nn -- "about five
        cell diameters", the near end of the profile, because the ratio decays
        toward 1.0 as the disc grows
    :return: (index, radius) of the chosen bin, or (None, None) if there are none
    """
    if intervals is None or len(intervals) == 0:
        return None, None
    radii = [float(v) for v in intervals]
    if requested is not None and float(requested) > 0:
        target = float(requested)
    elif median_nn is not None and float(median_nn) > 0:
        target = float(median_nn) * float(nn_multiple)
    else:
        idx = len(radii) // 2
        return idx, radii[idx]
    idx = min(range(len(radii)), key=lambda i: abs(radii[i] - target))
    return idx, radii[idx]


def radius_caption(intervals, coord_unit):
    """One line naming the radius bins a co-occurrence figure actually used.

    :param intervals: radius bin positions, ascending
    :param coord_unit: unit the spatial coordinates carry
    :return: e.g. "radius 12.4 to 248.6 px, 50 bins"
    """
    if intervals is None or len(intervals) == 0:
        return "radius bins unavailable"
    return "radius %.1f to %.1f %s, %d bins" % (
        float(min(intervals)),
        float(max(intervals)),
        coord_unit,
        len(intervals),
    )


def ratio_colour_scale(
    values, center=1.0, diverging_cmap="RdBu_r", sequential_cmap="viridis"
):
    """Colour mapping for a ratio whose null value is meaningful.

    Co-occurrence is a ratio with a null of 1.0, so the colour scale has to put
    the null in the middle: on a sequential map the eye cannot tell enrichment
    from depletion, only "more" from "less". Returns a diverging map centred on
    the null, and falls back to the sequential one when the data lies entirely on
    one side of it (TwoSlopeNorm requires vmin < vcenter < vmax).

    The FAMILY is chosen from the data, not by the caller -- data that never
    crosses the null has no midpoint to diverge about, and painting it diverging
    would put the pale middle on an arbitrary value. The caller only says WHICH
    map to use within each family.

    :param values: the array being drawn
    :param center: the null value
    :param diverging_cmap: matplotlib name to use when the data straddles center
    :param sequential_cmap: matplotlib name to use when it does not
    :return: (cmap_name, norm or None)
    """
    diverging_cmap = diverging_cmap or "RdBu_r"
    sequential_cmap = sequential_cmap or "viridis"
    try:
        import numpy as _np
        from matplotlib.colors import TwoSlopeNorm

        finite = _np.asarray(values, dtype=float)
        finite = finite[_np.isfinite(finite)]
        if finite.size == 0:
            return sequential_cmap, None
        vmin = float(finite.min())
        vmax = float(finite.max())
        if vmin < center < vmax:
            return diverging_cmap, TwoSlopeNorm(vmin=vmin, vcenter=center, vmax=vmax)
        return sequential_cmap, None
    except Exception:
        return sequential_cmap, None


def _save_co_occurrence_curves(
    plt, arr, intervals, cluster_names, coord_unit, plot_dir, plot_dpi
):
    """Score against distance, one panel per reference cluster.

    This is how squidpy presents co-occurrence (`squidpy.pl.co_occurrence`, a
    lineplot per cluster) and how its tutorials read it -- conclusions are quoted
    at a distance, not as a single summary. Drawn here rather than by calling
    squidpy's plotting function so the panel count, the figure size and the DPI
    match the rest of QP-CAT's figures.

    :param plt: the pyplot module (already configured for Agg)
    :param arr: pairwise ratio tensor, (clusters x clusters x bins)
    :param intervals: bin radii, one per bin
    :param cluster_names: display names, index-aligned with arr
    :param coord_unit: unit the radii carry
    :param plot_dir: directory to write into
    :param plot_dpi: output resolution
    """
    try:
        if arr.ndim != 3 or not intervals or not cluster_names:
            return
        n = min(arr.shape[0], len(cluster_names))
        shown = min(n, MAX_COOC_CURVE_PANELS)
        if shown == 0:
            return
        ncols = min(3, shown)
        nrows = int(math.ceil(shown / float(ncols)))
        fig, axes = plt.subplots(
            nrows,
            ncols,
            figsize=(4.2 * ncols, 3.2 * nrows),
            squeeze=False,
            sharex=True,
        )
        r = list(intervals)[: arr.shape[2]]
        for pos in range(nrows * ncols):
            ax = axes[pos // ncols][pos % ncols]
            if pos >= shown:
                ax.axis("off")
                continue
            for b in range(n):
                ax.plot(
                    r,
                    arr[pos, b, : len(r)],
                    linewidth=1.2,
                    label=cluster_names[b],
                )
            # 1.0 is the null: the partner is as common near this cluster as it
            # is anywhere. Without the line the eye has no reference.
            ax.axhline(1.0, color="0.4", linestyle="--", linewidth=0.8)
            ax.set_title("around %s" % cluster_names[pos], fontsize="small")
            ax.set_xlabel("Radius (%s)" % coord_unit)
            ax.set_ylabel("Ratio")
            ax.tick_params(labelsize="small")
        handles, labels = axes[0][0].get_legend_handles_labels()
        fig.legend(
            handles,
            labels,
            loc="center left",
            bbox_to_anchor=(1.0, 0.5),
            fontsize="small",
            title="Neighbour",
        )
        caption = "Co-occurrence vs distance (descriptive; 1.0 = no association)"
        if shown < n:
            caption += "\nfirst %d of %d clusters" % (shown, n)
        fig.suptitle(caption)
        fig.tight_layout()
        out_path = os.path.join(plot_dir, PLOT_FILE_COOC_CURVES)
        fig.savefig(out_path, dpi=int(plot_dpi), bbox_inches="tight")
        plt.close(fig)
        logger.info("Saved co-occurrence curves PNG: %s", out_path)
    except Exception as e:
        logger.warning("Co-occurrence curves plot failed: %s", e)


def run_co_occurrence(
    adata,
    task,
    cluster_key="cluster",
    mode="pairwise",
    min_radius=-1.0,
    max_radius=-1.0,
    n_intervals=50,
    matrix_radius=-1.0,
    n_permutations=1000,
    spatial_data=None,
    plot_dir=None,
    plot_dpi=150,
    persist_plots=True,
    coord_unit="px",
    sequential_cmap="viridis",
    diverging_cmap="RdBu_r",
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
        "coord_unit": "px" | "um"
      }

    Takes no graph argument: sq.gr.co_occurrence reads spatial coordinates
    and distance bins, so the kNN/Delaunay choice cannot reach it.
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

    median_nn = None
    try:
        kwargs = {"cluster_key": cluster_key, "n_splits": 1}
        if (
            min_radius is not None
            and min_radius > 0
            and max_radius is not None
            and max_radius > 0
        ):
            kwargs["interval"] = np.linspace(min_radius, max_radius, int(n_intervals))
            if spatial_data is not None:
                median_nn = _median_nn_distance(np.asarray(spatial_data))
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
            median_nn = med_nn
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
        # squidpy returns one fewer bin than it was given interval points:
        # _co_occurrence_helper sets l_val = len(interval) - 1 and squares
        # interval[1:] for its thresholds, so bin i is the disc of radius
        # interval[i+1]. Store THOSE radii, one per bin, so every consumer --
        # the caption, the heatmap ticks, the results table, the CSV -- zips
        # 1:1 with the data instead of each labelling a bin with the radius of
        # the one before it and emitting a trailing all-NaN row.
        edges = [float(v) for v in np.asarray(intervals).ravel()]
        n_bins = int(ratio_np.shape[2]) if ratio_np.ndim == 3 else len(edges)
        intervals_list = edges[1:] if len(edges) == n_bins + 1 else edges

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
            "coord_unit": coord_unit,
        }
        task.outputs[output_key] = json.dumps(payload)
        logger.info(
            "Co-occurrence (%s) computed: %d clusters, %d radius bin(s) from %.1f to %.1f %s",
            payload["mode"],
            len(cluster_names),
            len(intervals_list),
            intervals_list[0] if intervals_list else float("nan"),
            intervals_list[-1] if intervals_list else float("nan"),
            coord_unit,
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
                    cmap_name, norm = ratio_colour_scale(
                        arr,
                        diverging_cmap=diverging_cmap,
                        sequential_cmap=sequential_cmap,
                    )
                    im = ax.imshow(
                        arr, aspect="auto", cmap=cmap_name, norm=norm, origin="lower"
                    )
                    ax.set_yticks(np.arange(len(cluster_names)))
                    ax.set_yticklabels(cluster_names, fontsize="small")
                    # Sparse x ticks at evenly spaced bins (max ~10). intervals_list
                    # is now one radius per column, so tick i labels column i.
                    n_iv = min(len(intervals_list), arr.shape[1])
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
                        "Co-occurrence (one vs rest, descriptive)\n%s"
                        % radius_caption(intervals_list, coord_unit)
                    )
                    fig.colorbar(im, ax=ax, label="Ratio")
                    out_name = PLOT_FILE_COOC_ONE_VS_REST
                else:
                    # Pairwise: a square heatmap AT ONE RADIUS, not averaged over
                    # them. The bins are cumulative discs, so a mean is dominated
                    # by the large-radius bins where the ratio has already decayed
                    # toward 1.0 -- measured on synthetic tissue, a short-range
                    # ratio of 1.75 averaged to 1.13. A named radius is also the
                    # form a published co-occurrence value takes ("within 30 um"),
                    # so it can be compared. The full per-radius tensor stays in
                    # the JSON for the table.
                    arr = np.asarray(data_list, dtype=np.float64)
                    bin_idx, bin_r = pick_radius_bin(
                        intervals_list, matrix_radius, median_nn
                    )
                    if arr.ndim == 3 and bin_idx is not None:
                        heat = arr[:, :, bin_idx]
                    else:
                        heat = arr
                    fig, ax = plt.subplots(figsize=(8, 7))
                    cmap_name, norm = ratio_colour_scale(
                        heat,
                        diverging_cmap=diverging_cmap,
                        sequential_cmap=sequential_cmap,
                    )
                    im = ax.imshow(
                        heat, aspect="equal", cmap=cmap_name, norm=norm, origin="lower"
                    )
                    ax.set_xticks(np.arange(len(cluster_names)))
                    ax.set_yticks(np.arange(len(cluster_names)))
                    ax.set_xticklabels(
                        cluster_names, rotation=45, ha="right", fontsize="small"
                    )
                    ax.set_yticklabels(cluster_names, fontsize="small")
                    ax.set_xlabel("Cluster B")
                    ax.set_ylabel("Cluster A")
                    if bin_idx is None:
                        ax.set_title("Co-occurrence (pairwise, descriptive)")
                    else:
                        ax.set_title(
                            "Co-occurrence (pairwise, descriptive)\n"
                            "within r = %.1f %s  (bin %d of %d; 1.0 = no association)"
                            % (
                                bin_r,
                                coord_unit,
                                bin_idx + 1,
                                len(intervals_list),
                            )
                        )
                    fig.colorbar(im, ax=ax, label="Ratio at r")
                    out_name = PLOT_FILE_COOC_PAIRWISE

                out_path = os.path.join(plot_dir, out_name)
                fig.savefig(out_path, dpi=int(plot_dpi), bbox_inches="tight")
                plt.close(fig)
                logger.info(
                    "Saved co-occurrence (%s) PNG: %s", payload["mode"], out_path
                )

                if mode != "oneVsRest":
                    _save_co_occurrence_curves(
                        plt,
                        np.asarray(data_list, dtype=np.float64),
                        intervals_list,
                        cluster_names,
                        coord_unit,
                        plot_dir,
                        plot_dpi,
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
