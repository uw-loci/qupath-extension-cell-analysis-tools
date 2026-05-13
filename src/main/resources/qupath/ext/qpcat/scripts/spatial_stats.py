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

Function contract: every callable below either populates `task.outputs`
with a JSON or NDArray-backed entry, or logs a warning and returns
without setting outputs. Failures never bubble out of the helper - the
Java side checks `task.outputs.containsKey(...)` per the existing
`hasSpatialAutocorr` / `hasNhoodEnrichment` pattern.

ASCII-only logging and error messages per the QPSC encoding policy
(Windows cp1252 production).
"""
import json
import logging
import math

import numpy as np

logger = logging.getLogger("qpcat.spatial_stats")


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


def build_spatial_graph(adata, graph_type="knn", k=15, radius=-1.0,
                         delaunay_max_edge=-1.0):
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
            adata, coord_type="generic", n_neighs=min(k, max(1, n_cells - 1)),
            delaunay=False)
        return ("knn", k)
    if graph_type == "radius":
        if radius is None or radius < 0:
            # Auto: median NN distance * 5. We need a one-shot kNN to derive it.
            sq.gr.spatial_neighbors(adata, coord_type="generic", n_neighs=2,
                                     delaunay=False)
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
        sq.gr.spatial_neighbors(adata, coord_type="generic", radius=radius,
                                 delaunay=False)
        return ("radius", radius)
    if graph_type == "delaunay":
        sq.gr.spatial_neighbors(adata, coord_type="generic", delaunay=True)
        if delaunay_max_edge is not None and delaunay_max_edge > 0:
            # Prune long edges via the distances matrix
            import scipy.sparse as sp
            dists = adata.obsp["spatial_distances"]
            conn = adata.obsp["spatial_connectivities"]
            mask = (dists > delaunay_max_edge)
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


def run_ripley(adata, task, cluster_key="cluster", n_permutations=1000,
                max_radius=-1.0, n_steps=50, graph_type="knn"):
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

        # Compute K and L separately - squidpy's mode='K' / mode='L' branches
        # share underlying state via adata.uns['<cluster_key>_ripley_K'] etc.
        sq.gr.ripley(adata, mode="K", **kwargs)
        sq.gr.ripley(adata, mode="L", **kwargs)

        k_data = adata.uns.get("%s_ripley_K" % cluster_key, {})
        l_data = adata.uns.get("%s_ripley_L" % cluster_key, {})

        # squidpy returns dict-like with DataFrames; pull per-cluster curves
        cluster_names = sorted(set(
            [str(c) for c in adata.obs[cluster_key].cat.categories
             if cluster_key in adata.obs.columns]
        )) or [str(c) for c in adata.obs[cluster_key].unique()]

        radii = []
        k_curves = []
        l_curves = []
        poisson_k = []
        poisson_l = []
        p_values = {}

        # squidpy stores per-cluster results under 'bins' / 'pvalues' / 'sims_stat'
        # but the exact key set varies by version. Read defensively.
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
                        else:
                            radii = [float(r) for r in k_data["bins"]]
                            k_curves = [[float(v) for v in k_data["stats"]]]
        except Exception as e:
            logger.warning("Ripley K extraction failed: %s", e)

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
        logger.info("Ripley K/L computed for %d clusters (%d radii, %d perms)",
                    len(cluster_names), n_r, n_permutations)
    except Exception as e:
        logger.warning("Ripley K/L failed: %s", e)


def run_geary_c(adata, task, n_permutations=1000, measurements=None,
                 graph_type="knn"):
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
        logger.info("Geary's C computed for %d markers (%d perms)",
                    len(marker_stats), n_permutations)
    except Exception as e:
        logger.warning("Geary's C failed: %s", e)


def run_co_occurrence(adata, task, cluster_key="cluster", mode="pairwise",
                       min_radius=-1.0, max_radius=-1.0, n_intervals=50,
                       n_permutations=1000, spatial_data=None,
                       graph_type="knn"):
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
        if min_radius is not None and min_radius > 0 \
                and max_radius is not None and max_radius > 0:
            kwargs["interval"] = np.linspace(min_radius, max_radius,
                                              int(n_intervals))
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
            collapsed = np.zeros((n_clusters, 1, ratio_np.shape[2]),
                                  dtype=np.float64)
            for a in range(n_clusters):
                others = [b for b in range(n_clusters) if b != a]
                if others:
                    collapsed[a, 0, :] = ratio_np[a, others, :].mean(axis=0)
            data_list = collapsed.tolist()
            output_key = "co_occurrence_one_vs_rest"
        else:
            data_list = ratio_np.tolist()
            output_key = "co_occurrence_pairwise"

        payload = {
            "mode": "oneVsRest" if mode == "oneVsRest" else "pairwise",
            "cluster_names": cluster_names,
            "intervals": intervals_list,
            "data": data_list,
            "n_permutations": int(n_permutations),
            "graph_type": graph_type,
        }
        task.outputs[output_key] = json.dumps(payload)
        logger.info("Co-occurrence (%s) computed: %d clusters, %d intervals",
                    payload["mode"], len(cluster_names), len(intervals_list))
    except Exception as e:
        logger.warning("Co-occurrence (%s) failed: %s", mode, e)


def build_smoothing_adjacency_squidpy(spatial_data, graph_type="knn", k=15,
                                       radius=-1.0, delaunay_max_edge=-1.0):
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
    build_spatial_graph(adata_tmp, graph_type=graph_type, k=k, radius=radius,
                         delaunay_max_edge=delaunay_max_edge)
    conn = adata_tmp.obsp["spatial_connectivities"].astype(np.float64)
    row_sums = np.array(conn.sum(axis=1)).flatten()
    row_sums[row_sums == 0] = 1.0
    return sp.diags(1.0 / row_sums) @ conn
