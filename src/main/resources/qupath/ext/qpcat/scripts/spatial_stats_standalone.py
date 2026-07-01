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
from appose import NDArray as PyNDArray


def _update(msg):
    try:
        task.update(msg)
    except Exception:
        pass


def _flag(name, default=False):
    try:
        return bool(globals()[name])
    except KeyError:
        return default


def _sanitize(obj):
    if isinstance(obj, dict):
        return {k: _sanitize(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [_sanitize(v) for v in obj]
    if isinstance(obj, float):
        return obj if np.isfinite(obj) else None
    return obj


# 1. Load inputs
_update("Loading cell positions and labels...")
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

# Optional feature matrix (for Geary's C / Moran's I).
try:
    feats = feature_matrix.ndarray().astype(np.float64, copy=True)
    if feats.shape[0] != n_cells:
        logger.warning(
            "feature_matrix rows (%d) != n_cells (%d); ignoring",
            feats.shape[0],
            n_cells,
        )
        feats = None
except NameError:
    feats = None
try:
    mnames = list(marker_names)
except NameError:
    mnames = None

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
try:
    g_delaunay = float(graph_delaunay_max_edge)
except NameError:
    g_delaunay = -1.0
try:
    perm_override = int(permutations)
except NameError:
    perm_override = 0

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
_update("Building AnnData...")
import anndata as ad
import squidpy as sq
import spatial_stats as _spatial

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
_update("Building spatial neighbor graph (%s)..." % g_type)
_spatial.build_spatial_graph(
    adata, graph_type=g_type, k=g_k, radius=g_radius, delaunay_max_edge=g_delaunay
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


def _supported(fn, **kw):
    import inspect

    try:
        params = inspect.signature(fn).parameters
    except (TypeError, ValueError):
        return {}
    return {k: v for k, v in kw.items() if k in params}


n_perms = _spatial.adaptive_permutations(n_cells, override=perm_override)
plot_paths = {}
any_stat = False

# 4. Neighborhood enrichment + Moran's I (inline, matching run_clustering)
if want_nhood:
    try:
        _update("Computing neighborhood enrichment...")
        sq.gr.nhood_enrichment(
            adata,
            cluster_key="cluster",
            **_supported(
                sq.gr.nhood_enrichment,
                numba_parallel=False,
                n_jobs=1,
                show_progress_bar=False,
                seed=0,
            )
        )
        zscore = adata.uns["cluster_nhood_enrichment"]["zscore"]
        nhood_nd = PyNDArray(dtype="float64", shape=list(zscore.shape))
        np.copyto(nhood_nd.ndarray(), zscore.astype(np.float64))
        task.outputs["nhood_enrichment"] = nhood_nd
        task.outputs["nhood_cluster_names"] = json.dumps(
            list(adata.obs["cluster"].cat.categories)
        )
        any_stat = True
        logger.info(
            "Neighborhood enrichment computed (%d x %d)",
            zscore.shape[0],
            zscore.shape[1],
        )
    except Exception as e:
        logger.warning("Neighborhood enrichment failed: %s", e)

if want_moran:
    if feats is None or mnames is None:
        logger.warning("Moran's I requested but no feature matrix supplied; skipping.")
    else:
        try:
            _update("Computing Moran's I spatial autocorrelation...")
            df = sq.gr.spatial_autocorr(
                adata,
                mode="moran",
                **_supported(
                    sq.gr.spatial_autocorr, n_jobs=1, show_progress_bar=False, seed=0
                )
            )
            autocorr = {}
            for marker in mnames:
                if marker in df.index:
                    row = df.loc[marker]
                    autocorr[marker] = {
                        "I": float(row["I"]),
                        "pval": float(
                            row.get("pval_norm", row.get("pval_z_sim", float("nan")))
                        ),
                    }
            task.outputs["spatial_autocorr"] = json.dumps(_sanitize(autocorr))
            any_stat = True
            logger.info("Moran's I computed for %d markers", len(autocorr))
        except Exception as e:
            logger.warning("Moran's I failed: %s", e)

# 5. v1 expansion stats (Ripley / Geary / co-occurrence)
if want_ripley:
    _update("Computing Ripley K and L (%d permutations)..." % n_perms)
    _spatial.run_ripley(
        adata,
        task,
        cluster_key="cluster",
        n_permutations=n_perms,
        graph_type=g_type,
        plot_dir=out_dir if persist else None,
        plot_dpi=dpi,
        persist_plots=persist,
    )
    any_stat = True
    if persist:
        p = os.path.join(out_dir, _spatial.PLOT_FILE_RIPLEY)
        if os.path.exists(p):
            plot_paths["ripley_k"] = p
            plot_paths["ripley_l"] = p

if want_geary:
    if feats is None or mnames is None:
        logger.warning("Geary's C requested but no feature matrix supplied; skipping.")
    else:
        _update("Computing Geary's C (%d permutations)..." % n_perms)
        _spatial.run_geary_c(
            adata,
            task,
            n_permutations=n_perms,
            measurements=list(mnames),
            graph_type=g_type,
            plot_dir=out_dir if persist else None,
            plot_dpi=dpi,
            persist_plots=persist,
        )
        any_stat = True
        if persist:
            p = os.path.join(out_dir, _spatial.PLOT_FILE_GEARY)
            if os.path.exists(p):
                plot_paths["geary_c"] = p

if want_cooc_pair:
    _update("Computing co-occurrence (pairwise)...")
    _spatial.run_co_occurrence(
        adata,
        task,
        cluster_key="cluster",
        mode="pairwise",
        n_permutations=n_perms,
        spatial_data=coords,
        graph_type=g_type,
        plot_dir=out_dir if persist else None,
        plot_dpi=dpi,
        persist_plots=persist,
    )
    any_stat = True
    if persist:
        p = os.path.join(out_dir, _spatial.PLOT_FILE_COOC_PAIRWISE)
        if os.path.exists(p):
            plot_paths["cooc_pairwise"] = p

if want_cooc_ovr:
    _update("Computing co-occurrence (one vs rest)...")
    _spatial.run_co_occurrence(
        adata,
        task,
        cluster_key="cluster",
        mode="oneVsRest",
        n_permutations=n_perms,
        spatial_data=coords,
        graph_type=g_type,
        plot_dir=out_dir if persist else None,
        plot_dpi=dpi,
        persist_plots=persist,
    )
    any_stat = True
    if persist:
        p = os.path.join(out_dir, _spatial.PLOT_FILE_COOC_ONE_VS_REST)
        if os.path.exists(p):
            plot_paths["cooc_one_vs_rest"] = p

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

_update("Spatial statistics complete")
logger.info(
    "Post-hoc spatial statistics done (%d cells, %d classes)", n_cells, n_label_classes
)
