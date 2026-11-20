"""QP-CAT's neighbor graph and Leiden on the GPU, as its own process in a RAPIDS environment.

Usage: python gpu_graph_leiden.py <job dir>

Reads from the job folder:
  matrix.npy    cells x features, float32, already normalized by the worker
  params.json   {"n_neighbors": int, "seed": int, "resolutions": [float, ...],
                 "brute_force_cells": int}
Writes there:
  graph.npz     the cells x cells connectivities (scipy CSR), the graph Leiden partitioned
  labels_<i>.npy  one int32 label per cell for resolutions[i]
  result.json   {"ok": true, "n_cells", "nnz", "algorithm", "versions", "resolutions": [...],
                 "seconds": {...}} or {"ok": false, "error": "..."}

This is not an Appose script: it is started by run_clustering.py (gpu_bridge_run) with
stdout and stderr sent to a log file in the job folder. The neighbour search runs on cuML
(exact brute force up to brute_force_cells, IVF-flat above); umap-learn turns the
neighbours into the fuzzy connectivities scanpy would have made, so only the search
differs from QP-CAT's CPU graph; cuGraph partitions the graph. cuGraph 26.10 returns every
cell as its own cluster on graphs with hub nodes (exact duplicate cells); such a result is
reported as "degenerate" and the worker partitions the returned graph with igraph instead.
"""

import json
import os
import sys
import time
import traceback

import numpy as np
from scipy import sparse as sp


def versions():
    out = {"python": sys.version.split()[0]}
    try:
        import cupy

        out["cupy"] = cupy.__version__
        out["cuda_runtime"] = int(cupy.cuda.runtime.runtimeGetVersion())
        out["cuda_driver"] = int(cupy.cuda.runtime.driverGetVersion())
        props = cupy.cuda.runtime.getDeviceProperties(0)
        out["gpu"] = (
            props["name"].decode()
            if isinstance(props["name"], bytes)
            else str(props["name"])
        )
        out["gpu_total_mib"] = int(props["totalGlobalMem"] // 2**20)
    except Exception as e:  # reported, not fatal: the error that follows names it
        out["cupy_error"] = "%s: %s" % (type(e).__name__, e)
    for name in ("cuml", "cugraph"):
        try:
            out[name] = __import__(name).__version__
        except Exception as e:
            out[name + "_error"] = "%s: %s" % (type(e).__name__, e)
    return out


def knn_graph_gpu(matrix, n_neighbors, seed, brute_force_cells):
    """The connectivities scanpy would build, with the neighbour search on the GPU."""
    import cupy as cp
    from cuml.neighbors import NearestNeighbors
    from umap.umap_ import fuzzy_simplicial_set

    x = cp.asarray(np.ascontiguousarray(matrix, dtype=np.float32))
    n_cells = x.shape[0]
    # cuML 26.10 offers brute, ivfflat and ivfpq here (no nn_descent).
    algorithm = "brute" if n_cells <= brute_force_cells else "ivfflat"
    nn = NearestNeighbors(
        n_neighbors=n_neighbors, metric="euclidean", algorithm=algorithm
    )
    nn.fit(x)
    dists, idx = nn.kneighbors(x)
    knn_dists = cp.asnumpy(dists).astype(np.float32)
    knn_indices = cp.asnumpy(idx).astype(np.int64)
    del x, dists, idx
    # scanpy: _connectivity.umap(knn_indices, knn_dists, n_obs, n_neighbors) with
    # umap's defaults (set_op_mix_ratio=1.0, local_connectivity=1.0).
    placeholder = sp.coo_matrix(([], ([], [])), shape=(n_cells, 1))
    connectivities, _sigmas, _rhos = fuzzy_simplicial_set(
        placeholder,
        n_neighbors,
        np.random.RandomState(seed),
        "euclidean",
        knn_indices=knn_indices,
        knn_dists=knn_dists,
        set_op_mix_ratio=1.0,
        local_connectivity=1.0,
    )
    return sp.csr_matrix(connectivities), algorithm


def cugraph_graph(connectivities):
    import cudf
    import cugraph

    coo = sp.triu(connectivities.tocsr(), k=1).tocoo()
    edges = cudf.DataFrame(
        {
            "src": coo.row.astype(np.int32),
            "dst": coo.col.astype(np.int32),
            "w": coo.data.astype(np.float32),
        }
    )
    graph = cugraph.Graph(directed=False)
    graph.from_cudf_edgelist(
        edges, source="src", destination="dst", edge_attr="w", renumber=False
    )
    return graph


def leiden_gpu(graph, n_cells, resolution, seed):
    import cugraph

    parts, modularity = cugraph.leiden(
        graph, max_iter=100, resolution=resolution, random_state=int(seed)
    )
    parts = parts.to_pandas()
    labels = np.zeros(n_cells, dtype=np.int32)
    labels[parts["vertex"].to_numpy()] = parts["partition"].to_numpy()
    n_clusters = int(np.unique(labels).size)
    degenerate = n_clusters >= n_cells or modularity < 0
    return labels, float(modularity), n_clusters, degenerate


def main(job):
    t_start = time.monotonic()
    result = {"ok": False, "versions": versions()}
    try:
        params = json.load(open(os.path.join(job, "params.json")))
        t0 = time.monotonic()
        matrix = np.load(os.path.join(job, "matrix.npy"))
        result["seconds"] = {"load": round(time.monotonic() - t0, 3)}
        n_cells = int(matrix.shape[0])
        t0 = time.monotonic()
        conn, algorithm = knn_graph_gpu(
            matrix,
            int(params["n_neighbors"]),
            int(params["seed"]),
            int(params.get("brute_force_cells", 200000)),
        )
        del matrix
        result["seconds"]["graph"] = round(time.monotonic() - t0, 3)
        t0 = time.monotonic()
        sp.save_npz(os.path.join(job, "graph.npz"), conn, compressed=False)
        result["seconds"]["save_graph"] = round(time.monotonic() - t0, 3)
        t0 = time.monotonic()
        graph = cugraph_graph(conn)
        rows = []
        for i, res in enumerate(params["resolutions"]):
            labels, modularity, n_clusters, degenerate = leiden_gpu(
                graph, n_cells, float(res), params["seed"]
            )
            name = "labels_%d.npy" % i
            np.save(os.path.join(job, name), labels)
            rows.append(
                {
                    "resolution": float(res),
                    "file": name,
                    "n_clusters": n_clusters,
                    "modularity": modularity,
                    "degenerate": bool(degenerate),
                }
            )
        result["seconds"]["leiden"] = round(time.monotonic() - t0, 3)
        result.update(
            {
                "ok": True,
                "n_cells": n_cells,
                "nnz": int(conn.nnz),
                "algorithm": algorithm,
                "resolutions": rows,
            }
        )
    except Exception:
        result["error"] = traceback.format_exc()
    result.setdefault("seconds", {})["total"] = round(time.monotonic() - t_start, 3)
    with open(os.path.join(job, "result.json"), "w") as f:
        json.dump(result, f)
    print(json.dumps({k: v for k, v in result.items() if k != "error"}))
    if not result["ok"]:
        print(result["error"], file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
