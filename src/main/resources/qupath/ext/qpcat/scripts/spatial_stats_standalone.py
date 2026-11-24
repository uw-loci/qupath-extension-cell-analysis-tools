"""
Standalone post-hoc spatial statistics for QP-CAT.

Runs the spatial-statistics suite (Ripley K/L, co-occurrence, neighborhood
enrichment, Geary's C, Moran's I) over cells that ALREADY carry a categorical
label -- an existing cluster or phenotype classification -- WITHOUT re-running
clustering or embedding. The Java side collects the cells (optionally filtered to
selected annotation ROIs and excluding cells in Ignore/Necrosis regions), reads
their existing PathClass as the label, and injects coordinates + labels here.

This mirrors the spatial section of run_clustering.py but builds the AnnData from
injected labels instead of computing them, then delegates to the same
spatial_stats helper module so the numbers and output contract match exactly.
Emits the same task.outputs keys the Java parsers already consume
(ripley / geary_c / co_occurrence_* / nhood_enrichment / spatial_autocorr).

Inputs (injected by Appose; accessed as variables, NOT task.inputs):
  spatial_coords: NDArray (N x 2, float64) -- centroid X, Y in pixels (required)
  cell_type_labels: list[int] -- categorical index per cell (>=0; -1 allowed and
                    treated as "Unassigned") (required)
  class_names: list[str] -- display name per label index (required)
  feature_matrix: NDArray (N x M, float64) (optional) -- per-cell measurements,
                  needed for Geary's C / Moran's I
  marker_names: list[str] (optional) -- name per feature column
  graph_type: str -- "knn" | "radius" | "delaunay" (default "knn")
  graph_k: int -- kNN neighbors (default 15)
  graph_radius: float -- radius graph distance in pixels; -1 = auto (default -1)
  graph_delaunay_max_edge: float -- prune Delaunay edges longer than this; -1 = keep
  permutations: int -- 0 = adaptive default; positive = fixed override
  enable_ripley / enable_geary / enable_co_occurrence_pairwise /
  enable_co_occurrence_one_vs_rest / enable_nhood_enrichment / enable_moran: bool
  output_dir: str (optional) -- directory for the matplotlib PNGs
  plot_dpi: int (optional, default 150)
  persist_plots: bool (optional, default False)

Outputs (via task.outputs): a subset of
  spatial_graph_type, spatial_n_permutations, ripley, geary_c,
  co_occurrence_pairwise, co_occurrence_one_vs_rest, nhood_enrichment,
  nhood_cluster_names, spatial_autocorr, plot_paths
"""

import logging
import json
import os

logger = logging.getLogger("qpcat.spatial_stats_standalone")

import numpy as np
import pandas as pd


def _flag(name, default=False):
    try:
        return bool(globals()[name])
    except KeyError:
        return default


def drop_untestable_columns(feats, names):
    """Drop columns whose spread is zero or not finite; returns (feats, names, dropped).

    feats and names come back as None when no column is left.
    """
    col_std = np.nanstd(feats, axis=0)
    good = np.isfinite(col_std) & (col_std > 0)
    names = (
        list(names)
        if names is not None
        else ["column %d" % i for i in range(feats.shape[1])]
    )
    dropped = [n for n, keep in zip(names, good) if not keep]
    feats = feats[:, good]
    names = [n for n, keep in zip(names, good) if keep]
    if feats.shape[1] == 0:
        return None, None, dropped
    return feats, names, dropped


def dropped_columns_note(dropped, none_left, max_named=6):
    """The note for measurements Moran's I and Geary's C cannot test."""
    shown = ", ".join(dropped[:max_named])
    if len(dropped) > max_named:
        shown += " and %d more" % (len(dropped) - max_named)
    head = (
        "Every selected measurement"
        if none_left
        else "%d measurement(s)" % len(dropped)
    )
    return (
        "%s left out of Moran's I and Geary's C, because the value does not vary "
        "across these cells (or is not finite), so the statistic is undefined: %s."
        % (head, shown)
    )


def _sanitize(obj):
    if isinstance(obj, dict):
        return {k: _sanitize(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [_sanitize(v) for v in obj]
    if isinstance(obj, float):
        return obj if np.isfinite(obj) else None
    return obj


# 1. Load inputs
report_progress(task, "Loading cell positions and labels...")
coords = spatial_coords.ndarray().astype(np.float64, copy=True)
n_cells = coords.shape[0]
logger.info("Post-hoc spatial stats: %d cells", n_cells)

labels_in = np.asarray(cell_type_labels, dtype=np.int64)
if labels_in.shape[0] != n_cells:
    raise ValueError(
        "cell_type_labels length (%d) != number of cells (%d)"
        % (labels_in.shape[0], n_cells)
    )
names = list(class_names)


# Map each label index to its display name; -1 becomes "Unassigned".
def _name_for(i):
    if i < 0:
        return "Unassigned"
    return names[i] if 0 <= i < len(names) else str(i)


label_strs = [_name_for(int(i)) for i in labels_in]
# Preserve the class_names order for the categories, then append any extras
# (e.g. "Unassigned") so the results tables read in a stable, meaningful order.
present = set(label_strs)
categories = [n for n in names if n in present]
for s in dict.fromkeys(label_strs):
    if s not in categories:
        categories.append(s)
n_label_classes = len(categories)

# Optional feature matrix (for Geary's C / Moran's I). Notes about it are
# collected here and recorded after reset_notes() below, which would erase them.
feature_notes = []
try:
    feats = feature_matrix.ndarray().astype(np.float64, copy=True)
    if feats.shape[0] != n_cells:
        # A QP-CAT defect, not the user's data: the Java side extracts one row
        # per cell. Refuse rather than test measurements against the wrong cells.
        raise ValueError(
            "feature_matrix has %d rows for %d cells" % (feats.shape[0], n_cells)
        )
except NameError:
    feats = None
try:
    mnames = list(marker_names)
except NameError:
    mnames = None
refuse_duplicate_names(mnames)
features_selected = feats is not None and feats.shape[1] > 0

# Drop zero-variance feature columns: Geary's C / Moran's I are undefined (0/0)
# on a constant vector, which is common in small windows or when one class
# dominates. Keep marker names aligned with the surviving columns.
if feats is not None:
    feats, mnames, dropped_names = drop_untestable_columns(feats, mnames)
    if dropped_names:
        feature_notes.append(dropped_columns_note(dropped_names, feats is None))

# Graph parameters.
try:
    g_type = str(graph_type)
except NameError:
    g_type = "knn"
try:
    g_k = int(graph_k)
except NameError:
    g_k = 15
try:
    g_radius = float(graph_radius)
except NameError:
    g_radius = -1.0

# Optional per-cell independent-area ids. The post-hoc workflow normally
# splits into windows BEFORE calling this task, so there is usually one area
# per call and this stays None. It is accepted anyway so that this script and
# run_clustering.py cannot drift into partitioning the graph differently.
try:
    sa_area_ids = list(area_ids)
    if len(sa_area_ids) != n_cells:
        raise ValueError(
            "area_ids length (%d) does not match the cell count (%d)"
            % (len(sa_area_ids), n_cells)
        )
except NameError:
    sa_area_ids = None

try:
    g_delaunay = float(graph_delaunay_max_edge)
except NameError:
    g_delaunay = -1.0
try:
    perm_override = int(permutations)
except NameError:
    perm_override = 0
try:
    autocorr_threshold = float(spatial_autocorr_threshold)
except NameError:
    autocorr_threshold = (
        0.05  # spatial_stats.AUTOCORR_DEFAULT_THRESHOLD, not imported yet
    )
try:
    coord_unit = str(coord_unit)
except NameError:
    coord_unit = "px"

try:
    out_dir = output_dir
except NameError:
    out_dir = None
try:
    dpi = int(plot_dpi)
except NameError:
    dpi = 150
persist = _flag("persist_plots", False) and bool(out_dir)

want_ripley = _flag("enable_ripley")
want_geary = _flag("enable_geary")
want_cooc_pair = _flag("enable_co_occurrence_pairwise")
want_cooc_ovr = _flag("enable_co_occurrence_one_vs_rest")
want_nhood = _flag("enable_nhood_enrichment")
want_moran = _flag("enable_moran")

if n_label_classes < 2:
    raise ValueError(
        "Need at least two distinct cell classes for spatial "
        "statistics; found %d." % n_label_classes
    )

# 2. Build AnnData
report_progress(task, "Building AnnData...")
import anndata as ad
import squidpy as sq
import spatial_stats as _spatial

# Long-lived worker, and runWindows loops over every window in one process, so
# without this a note from one window reappears in the next one's results.
_spatial.reset_notes()
for _note in feature_notes:
    _spatial.note_for_user(_note)

if feats is not None:
    X = feats.astype(np.float32, copy=False)
else:
    X = np.zeros((n_cells, 1), dtype=np.float32)
adata = ad.AnnData(X=X)
if feats is not None and mnames is not None and len(mnames) == X.shape[1]:
    adata.var_names = pd.Index(list(mnames))
adata.obsm["spatial"] = coords
adata.obs["cluster"] = pd.Categorical(label_strs, categories=categories, ordered=False)
logger.info("AnnData built: %d cells, %d classes", n_cells, n_label_classes)

# 3. Spatial neighbor graph
report_progress(task, "Building spatial neighbor graph (%s)..." % g_type)
_spatial.build_spatial_graph(
    adata,
    graph_type=g_type,
    k=g_k,
    radius=g_radius,
    delaunay_max_edge=g_delaunay,
    area_ids=sa_area_ids,
)
task.outputs["spatial_graph_type"] = g_type

# squidpy's numba loops can deadlock inside the Appose worker; serialize them
# for the permutation-heavy stats (same guard as run_clustering.py).
_numba_saved = None
try:
    import numba as _numba

    _numba_saved = _numba.get_num_threads()
    _numba.set_num_threads(1)
except Exception:
    pass


n_perms = _spatial.adaptive_permutations(n_cells, override=perm_override)
_perm_note = _spatial.permutation_count_warning(
    n_perms,
    chosen=perm_override > 0,
    setting="Permutations (0 = adaptive) in the dialog or permutations: in the batch file",
)
if _perm_note:
    _spatial.note_for_user(_perm_note)
plot_paths = {}
any_stat = False

# 4. Neighborhood enrichment + Moran's I (shared with run_clustering)
if want_nhood:
    report_progress(task, "Computing neighborhood enrichment...")
    any_stat |= _spatial.run_nhood_enrichment(
        adata, task, n_perms, area_ids=sa_area_ids
    )
    # The heatmap is what the results window shows: without it the matrix was
    # computed, ticked by default, and never displayed anywhere.
    if out_dir and "cluster_nhood_enrichment" in adata.uns:
        try:
            import matplotlib

            matplotlib.use("Agg")
            import matplotlib.pyplot as plt

            sq.pl.nhood_enrichment(adata, cluster_key="cluster", show=False)
            p = os.path.join(out_dir, "nhood_enrichment.png")
            plt.savefig(p, dpi=dpi, bbox_inches="tight")
            plt.close("all")
            plot_paths["nhood_enrichment"] = p
        except Exception as e:
            _spatial.note_for_user(
                "The neighborhood enrichment heatmap could not be drawn.",
                detail="%s: %s" % (type(e).__name__, e),
            )

if want_moran:
    if feats is None or mnames is None:
        _spatial.note_for_user(
            "Moran's I did not run because no measurement was left to test (see "
            "above), so there is no Spatial Autocorrelation tab."
            if features_selected
            else "Moran's I did not run because no measurements were selected to "
            "test, so there is no Spatial Autocorrelation tab."
        )
    else:
        # Shared with run_clustering.py. This block used to carry its own copy
        # of the squidpy call, and its own copy of that call's two defects.
        report_progress(task, "Computing Moran's I spatial autocorrelation...")
        if _spatial.run_moran_i(
            adata,
            task,
            measurements=list(mnames),
            n_permutations=n_perms,
            area_ids=sa_area_ids,
            threshold=autocorr_threshold,
        ):
            any_stat = True

# 5. v1 expansion stats (Ripley / Geary / co-occurrence)
if want_ripley:
    report_progress(task, "Computing Ripley L (%d permutations)..." % n_perms)
    _spatial.run_ripley(
        adata,
        task,
        cluster_key="cluster",
        n_permutations=n_perms,
        coord_unit=coord_unit,
        plot_dir=out_dir if persist else None,
        plot_dpi=dpi,
        persist_plots=persist,
    )
    any_stat = True
    if persist:
        p = os.path.join(out_dir, _spatial.PLOT_FILE_RIPLEY)
        if os.path.exists(p):
            plot_paths["ripley_l"] = p

if want_geary:
    if feats is None or mnames is None:
        _spatial.note_for_user(
            "Geary's C did not run because no measurement was left to test (see "
            "above), so there is no Geary's C tab."
            if features_selected
            else "Geary's C did not run because no measurements were selected to "
            "test, so there is no Geary's C tab."
        )
    else:
        report_progress(task, "Computing Geary's C (%d permutations)..." % n_perms)
        _spatial.run_geary_c(
            adata,
            task,
            n_permutations=n_perms,
            measurements=list(mnames),
            graph_type=g_type,
            coord_unit=coord_unit,
            plot_dir=out_dir if persist else None,
            plot_dpi=dpi,
            persist_plots=persist,
            area_ids=sa_area_ids,
            threshold=autocorr_threshold,
            marker_ticks=bold_marker_ticks,
        )
        any_stat = True
        if persist:
            p = os.path.join(out_dir, _spatial.PLOT_FILE_GEARY)
            if os.path.exists(p):
                plot_paths["geary_c"] = p

if want_cooc_pair:
    report_progress(task, "Computing co-occurrence (pairwise)...")
    _spatial.run_co_occurrence(
        adata,
        task,
        cluster_key="cluster",
        mode="pairwise",
        n_permutations=n_perms,
        spatial_data=coords,
        coord_unit=coord_unit,
        plot_dir=out_dir if persist else None,
        plot_dpi=dpi,
        persist_plots=persist,
    )
    any_stat = True
    if persist:
        p = os.path.join(out_dir, _spatial.PLOT_FILE_COOC_PAIRWISE)
        if os.path.exists(p):
            plot_paths["cooc_pairwise"] = p
        p = os.path.join(out_dir, _spatial.PLOT_FILE_COOC_CURVES)
        if os.path.exists(p):
            plot_paths["cooc_curves"] = p

if want_cooc_ovr:
    report_progress(task, "Computing co-occurrence (one vs rest)...")
    _spatial.run_co_occurrence(
        adata,
        task,
        cluster_key="cluster",
        mode="oneVsRest",
        n_permutations=n_perms,
        spatial_data=coords,
        coord_unit=coord_unit,
        plot_dir=out_dir if persist else None,
        plot_dpi=dpi,
        persist_plots=persist,
    )
    any_stat = True
    if persist:
        p = os.path.join(out_dir, _spatial.PLOT_FILE_COOC_ONE_VS_REST)
        if os.path.exists(p):
            plot_paths["cooc_one_vs_rest"] = p

# A statistic that was requested and did not arrive leaves no tab, which looks
# exactly like not having asked for it. The Java side also derives this from the
# options, but the note says WHY.
if _spatial.SPATIAL_NOTES:
    task.outputs["spatial_notes"] = json.dumps(list(_spatial.SPATIAL_NOTES))

if any_stat:
    task.outputs["spatial_n_permutations"] = int(n_perms)
if plot_paths:
    task.outputs["plot_paths"] = json.dumps(plot_paths)

# Restore numba threads for the next task in this worker.
if _numba_saved is not None:
    try:
        import numba as _numba

        _numba.set_num_threads(_numba_saved)
    except Exception:
        pass

report_progress(task, "Spatial statistics complete")
logger.info(
    "Post-hoc spatial statistics done (%d cells, %d classes)", n_cells, n_label_classes
)
