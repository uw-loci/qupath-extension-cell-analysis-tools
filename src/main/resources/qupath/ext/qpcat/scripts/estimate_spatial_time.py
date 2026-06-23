"""
Estimate how long the heavy spatial statistics will take on the full dataset.

Runs the ENABLED spatial permutation statistics on small subsamples (default
100, 1000, 2000 cells), times each, and returns the timings so the Java side can
fit a scaling curve and extrapolate to the full cell count. This lets the user
decide -- with a number in front of them -- whether to run the spatial stats,
skip them, or cancel, before anything touches the objects.

Inputs (injected by Appose 0.10.0):
  measurements: NDArray (N_cells x N_markers, float64)
  marker_names: list[str]
  spatial_coords: NDArray (N_cells x 2, float64)
  normalization: str
  enable_spatial_analysis: bool   -- neighborhood enrichment + Moran's I
  enable_ripley / enable_geary / enable_co_occurrence_pairwise /
  enable_co_occurrence_one_vs_rest: bool
  spatial_graph_type / spatial_graph_k / spatial_graph_radius /
  spatial_graph_delaunay_max_edge: graph params
  spatial_permutations: int (0 = adaptive)
  probe_sizes: list[int] (optional) -- subsample sizes (default [100, 1000, 2000])

Outputs (via task.outputs):
  timings_json: str (JSON) -- [{"size": s, "seconds": t}, ...] for sizes that ran
"""
import json
import logging
import time

logger = logging.getLogger("qpcat.estimate")

import numpy as np
import pandas as pd

# Serialize numba: the spatial stats deadlock multi-threaded inside the Appose
# worker on Windows (same reason as run_clustering). The probe must mirror the
# real single-threaded execution so its timings are representative.
try:
    import numba as _numba_mod
    _numba_mod.set_num_threads(1)
except Exception:
    pass

data = measurements.ndarray().copy()
coords = spatial_coords.ndarray().copy()
n_cells, n_markers = data.shape

try:
    sizes = [int(s) for s in probe_sizes]
except NameError:
    sizes = [100, 1000, 2000]
# Only probe sizes we actually have cells for; always include a near-full point
# capped at the data size so the extrapolation is anchored.
sizes = sorted({s for s in sizes if 0 < s <= n_cells})
if not sizes:
    sizes = [min(n_cells, 100)]


def _pref(name, default):
    try:
        return eval(name)
    except NameError:
        return default


norm = normalization
en_spatial = bool(_pref("enable_spatial_analysis", True))
en_ripley = bool(_pref("enable_ripley", False))
en_geary = bool(_pref("enable_geary", False))
en_cooc_p = bool(_pref("enable_co_occurrence_pairwise", False))
en_cooc_o = bool(_pref("enable_co_occurrence_one_vs_rest", False))
graph_type = _pref("spatial_graph_type", "knn")
graph_k = int(_pref("spatial_graph_k", 15))
graph_radius = float(_pref("spatial_graph_radius", -1.0))
graph_delaunay = float(_pref("spatial_graph_delaunay_max_edge", -1.0))
perms_override = int(_pref("spatial_permutations", 0))

import anndata as ad
import squidpy as sq
import spatial_stats as ss

rng = np.random.RandomState(0)
results = []

for s in sizes:
    try:
        task.update("Testing estimated time (%d cells)..." % s)
        idx = rng.choice(n_cells, size=s, replace=False) if s < n_cells \
            else np.arange(n_cells)
        sub = data[idx]
        sub_coords = coords[idx]
        df = pd.DataFrame(sub, columns=marker_names)
        # Raw values are fine for TIMING (cost depends on cell count / graph /
        # permutations, not on the normalized magnitudes).
        adata = ad.AnnData(X=df.values.astype(np.float64))
        adata.var_names = pd.Index(list(marker_names))
        adata.obsm["spatial"] = sub_coords
        # Nominal cluster labels: timing of nhood / co-occurrence depends on the
        # label COUNT, not the actual assignment.
        k = max(2, min(8, s // 50)) if s >= 100 else 2
        adata.obs["cluster"] = pd.Categorical(
            [str(int(x)) for x in rng.randint(0, k, size=s)])

        n_perms = ss.adaptive_permutations(n_cells, override=perms_override)

        t0 = time.perf_counter()
        ss.build_spatial_graph(adata, graph_type=graph_type, k=graph_k,
                               radius=graph_radius,
                               delaunay_max_edge=graph_delaunay)
        if en_spatial:
            try:
                sq.gr.nhood_enrichment(adata, cluster_key="cluster",
                                       **ss._safe_kwargs(sq.gr.nhood_enrichment,
                                                         numba_parallel=False,
                                                         n_jobs=1,
                                                         show_progress_bar=False,
                                                         seed=0))
            except Exception as e:
                logger.warning("probe nhood failed: %s", e)
            try:
                sq.gr.spatial_autocorr(adata, mode="moran",
                                       **ss._safe_kwargs(sq.gr.spatial_autocorr,
                                                         n_jobs=1,
                                                         show_progress_bar=False,
                                                         seed=0))
            except Exception as e:
                logger.warning("probe moran failed: %s", e)
        if en_ripley:
            try:
                ss.run_ripley(adata, task, cluster_key="cluster",
                              n_permutations=n_perms, graph_type=graph_type,
                              plot_dir=None, persist_plots=False)
            except Exception as e:
                logger.warning("probe ripley failed: %s", e)
        if en_geary:
            try:
                ss.run_geary_c(adata, task, n_permutations=n_perms,
                               measurements=list(marker_names),
                               graph_type=graph_type, plot_dir=None,
                               persist_plots=False)
            except Exception as e:
                logger.warning("probe geary failed: %s", e)
        if en_cooc_p:
            try:
                ss.run_co_occurrence(adata, task, cluster_key="cluster",
                                     mode="pairwise", n_permutations=n_perms,
                                     spatial_data=sub_coords,
                                     graph_type=graph_type, plot_dir=None,
                                     persist_plots=False)
            except Exception as e:
                logger.warning("probe cooc pairwise failed: %s", e)
        if en_cooc_o:
            try:
                ss.run_co_occurrence(adata, task, cluster_key="cluster",
                                     mode="oneVsRest", n_permutations=n_perms,
                                     spatial_data=sub_coords,
                                     graph_type=graph_type, plot_dir=None,
                                     persist_plots=False)
            except Exception as e:
                logger.warning("probe cooc one-vs-rest failed: %s", e)
        dt = time.perf_counter() - t0
        results.append({"size": int(s), "seconds": float(dt)})
        logger.info("probe %d cells -> %.3f s", s, dt)
    except Exception as e:
        logger.warning("probe size %d failed entirely: %s", s, e)

# Extrapolate to the full cell count. Fit a power law (time ~ a * n^b) on the
# probe points in log-log space; fall back to linear scaling from a single point.
estimate_seconds = None
pts = [(r["size"], r["seconds"]) for r in results
       if r["seconds"] > 0 and r["size"] > 0]
if len(pts) >= 2:
    xs = np.log(np.array([p[0] for p in pts], dtype=float))
    ys = np.log(np.array([p[1] for p in pts], dtype=float))
    b, loga = np.polyfit(xs, ys, 1)
    estimate_seconds = float(np.exp(loga) * (float(n_cells) ** b))
elif len(pts) == 1:
    s0, t0 = pts[0]
    estimate_seconds = float(t0 * n_cells / s0)

task.outputs["timings_json"] = json.dumps(results)
task.outputs["estimate_seconds"] = json.dumps(estimate_seconds)
task.outputs["full_cells"] = int(n_cells)
logger.info("Spatial-time probe complete: %d point(s), estimate=%s s for %d cells",
            len(results), str(estimate_seconds), n_cells)
