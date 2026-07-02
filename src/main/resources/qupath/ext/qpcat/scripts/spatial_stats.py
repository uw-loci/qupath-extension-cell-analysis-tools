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


def build_spatial_graph(
    adata, graph_type="knn", k=15, radius=-1.0, delaunay_max_edge=-1.0
):
    """Build adata.obsp['spatial_connectivities'] via squidpy.

    graph_type is one of "knn", "radius", "delaunay".
    radius < 0 means auto-derive from median NN distance times 5.
    delaunay_max_edge < 0 means do not prune Delaunay edges.

    Returns the resolved (graph_type, effective_param) tuple for audit
    logging. On failure, logs a warning and re-raises so the caller can
    decide whether to fall back.
    """
    import squidpy as sq

    n_cells = adata.shape[0]
    if graph_type == "knn":
        sq.gr.spatial_neighbors(
            adata,
            coord_type="generic",
            n_neighs=min(k, max(1, n_cells - 1)),
            delaunay=False,
        )
        return ("knn", k)
    if graph_type == "radius":
        if radius is None or radius < 0:
            # Auto: median NN distance * 5. We need a one-shot kNN to derive it.
            sq.gr.spatial_neighbors(
                adata, coord_type="generic", n_neighs=2, delaunay=False
            )
            dists = adata.obsp["spatial_distances"]
            try:
                # dists is sparse; the second-nearest distance per row is the
                # 1-NN distance after the self-loop is excluded
                per_row = np.array(dists[dists.nonzero()]).ravel()
                if per_row.size == 0:
                    radius = 50.0
                else:
                    radius = float(np.median(per_row) * 5.0)
            except Exception:
                radius = 50.0
        sq.gr.spatial_neighbors(
            adata, coord_type="generic", radius=radius, delaunay=False
        )
        return ("radius", radius)
    if graph_type == "delaunay":
        sq.gr.spatial_neighbors(adata, coord_type="generic", delaunay=True)
        if delaunay_max_edge is not None and delaunay_max_edge > 0:
            # Prune long edges via the distances matrix
            import scipy.sparse as sp

            dists = adata.obsp["spatial_distances"]
            conn = adata.obsp["spatial_connectivities"]
            mask = dists > delaunay_max_edge
            # Set masked entries to zero in both matrices then prune zeros
            dists = dists.tolil()
            conn = conn.tolil()
            for i, j in zip(*mask.nonzero()):
                dists[i, j] = 0
                conn[i, j] = 0
            adata.obsp["spatial_distances"] = dists.tocsr()
            adata.obsp["spatial_connectivities"] = conn.tocsr()
        return ("delaunay", delaunay_max_edge)
    raise ValueError("Unknown spatial graph type: %s" % graph_type)


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
        sq.gr.ripley(adata, mode="K", **kwargs)
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
        else:
            logger.warning(
                "Ripley K: no shape matched; curves will be zero-filled. "
                "Type=%s, keys=%s",
                type(k_data).__name__,
                (
                    list(k_data.keys())
                    if isinstance(k_data, dict)
                    else (list(k_data.columns) if hasattr(k_data, "columns") else "n/a")
                ),
            )

        try:
            if hasattr(l_data, "columns"):
                cols = list(l_data.columns)
                cluster_col = cluster_key if cluster_key in cols else None
                if cluster_col:
                    for cname in cluster_names:
                        sub = l_data[l_data[cluster_col] == cname]
                        if "stats" in cols:
                            l_curves.append([float(v) for v in sub["stats"]])
                        else:
                            l_curves.append([0.0] * len(radii))
                elif "stats" in cols:
                    l_curves = [[float(v) for v in l_data["stats"]]]
        except Exception as e:
            logger.warning("Ripley L extraction failed: %s", e)

        # Pad missing curves if extraction was partial
        n_r = len(radii)
        if not k_curves:
            k_curves = [[0.0] * n_r for _ in cluster_names]
        if not l_curves:
            l_curves = [[0.0] * n_r for _ in cluster_names]

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

                ax_k.set_xlabel("Radius (px)")
                ax_k.set_ylabel("K(r)")
                ax_k.set_title("Ripley K")
                ax_k.legend(fontsize="small", loc="best")
                ax_k.grid(True, alpha=0.3)

                ax_l.set_xlabel("Radius (px)")
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
            # Auto-derive interval from data extent
            coords = spatial_data
            # max range from bounding box; min from a small fraction of it
            xmin, xmax = float(coords[:, 0].min()), float(coords[:, 0].max())
            ymin, ymax = float(coords[:, 1].min()), float(coords[:, 1].max())
            diag = math.hypot(xmax - xmin, ymax - ymin)
            r_max = diag * 0.1  # 10th-percentile-style cap
            r_min = max(1.0, diag * 0.001)
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
                    ax.set_xlabel("Radius (px)")
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
    spatial_data, graph_type="knn", k=15, radius=-1.0, delaunay_max_edge=-1.0
):
    """Hybrid-graph-reuse smoothing path (Phase 2 contract #2).

    Builds the smoothing adjacency via sq.gr.spatial_neighbors and returns
    a row-normalised pure-A connectivity matrix (no +I diagonal). This is
    the path the smoothing rewrite uses when
    qpcat.spatial.useSquidpyGraphForSmoothing is true.

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
    )
    conn = adata_tmp.obsp["spatial_connectivities"].astype(np.float64)
    row_sums = np.array(conn.sum(axis=1)).flatten()
    row_sums[row_sums == 0] = 1.0
    return sp.diags(1.0 / row_sums) @ conn
