"""QP-CAT's neighbor graph and Leiden on the GPU, as its own processes in a RAPIDS environment.

Usage: python gpu_graph_leiden.py <job dir>
       python gpu_graph_leiden.py --stage graph <job dir>
       python gpu_graph_leiden.py --stage leiden <job dir> <device|managed>

The first form is the driver: it runs the graph stage and then the Leiden stage,
each in a fresh child process of this same script, so the neighbor search's card
memory is released before Leiden starts. Based on the GPU pipeline benchmarked by
Clement Cazorla (INSERM).

Reads from the job folder:
  matrix.npy    cells x features, float32, already normalized by the worker
  params.json   {"n_neighbors": int, "seed": int, "resolutions": [float, ...],
                 "brute_force_cells": int}
Writes there:
  connectivities.npz  the cells x cells connectivities (scipy CSR, int32 indices,
                      float32 weights), the graph Leiden partitioned
  graph_meta.json     the graph stage's report
  leiden_meta.json    the Leiden stage's report (the last attempt)
  labels_<i>.npy      one int32 label per cell for resolutions[i]
  result.json   {"ok": true, "n_cells", "nnz", "algorithm", "memory", "attempts",
                 "versions", "resolutions": [...], "seconds": {...}}
                or {"ok": false, "error": "...", "versions": {...}}

This is not an Appose script: run_clustering.py (gpu_bridge_run) starts the driver
with stdout and stderr sent to run.log in the job folder; the stages inherit them.

Graph: the neighbor search runs on the GPU (cuML's exact brute force up to
brute_force_cells, cuVS NN-descent above, both through rapids-singlecell's
KNN_ALGORITHMS); umap-learn turns the neighbors into the fuzzy connectivities scanpy
would have made, on the CPU. Leiden: the CSR goes to cuGraph (pylibcugraph) as CSR.
A Leiden stage that runs out of card memory is retried once with RMM managed memory
on native Linux; WSL2 does not support managed oversubscription, so there the run
fails with the reason and the worker runs Leiden on the CPU. cuGraph 26.10 returns
every cell as its own cluster on graphs with hub nodes; such a result is reported as
"degenerate" and the worker partitions the returned graph with igraph instead.
"""

import json
import os
import platform
import re
import subprocess
import sys
import time
import traceback

import numpy as np
from scipy import sparse as sp

# Text of a CUDA out-of-memory failure from cupy, RMM, RAFT or cuGraph.
_OOM = re.compile(
    r"out[_ ]of[_ ]memory|OutOfMemoryError|cudaErrorMemoryAllocation|bad_alloc",
    re.IGNORECASE,
)


def is_wsl():
    """Whether this process runs inside WSL2 (managed memory cannot oversubscribe there)."""
    return "microsoft" in platform.uname().release.lower()


def is_cuda_oom(text):
    """Whether an error text is a CUDA out-of-memory failure."""
    return bool(_OOM.search(text or ""))


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
    for name in ("cuml", "cugraph", "rapids_singlecell"):
        try:
            out[name] = __import__(name).__version__
        except Exception as e:
            out[name + "_error"] = "%s: %s" % (type(e).__name__, e)
    return out


def check_knn(idx, dist, n_cells, k, self_first):
    """Refuse a neighbor result that is not n_cells x k of valid indices and finite distances.

    :param self_first: also require every cell to be its own first neighbor (NN-descent
        puts it there by construction; checked on the first 10,000 cells)
    :raises ValueError: naming what is wrong
    """
    if idx.shape != (n_cells, k) or dist.shape != (n_cells, k):
        raise ValueError(
            "neighbor search returned %s indices and %s distances, expected (%d, %d)"
            % (idx.shape, dist.shape, n_cells, k)
        )
    head = min(n_cells, 10000)
    if self_first and not np.array_equal(idx[:head, 0], np.arange(head)):
        raise ValueError("neighbor search: a cell is not its own first neighbor")
    if idx.min() < 0 or idx.max() >= n_cells:
        raise ValueError("neighbor search returned an index outside 0..%d" % n_cells)
    if not np.isfinite(dist).all():
        raise ValueError("neighbor search returned a non-finite distance")


def knn_graph_gpu(matrix, n_neighbors, seed, brute_force_cells):
    """The connectivities scanpy would build, with the neighbor search on the GPU.

    :return: (CSR with int32 indices and float32 weights, search name, seconds dict)
    """
    import cupy as cp
    from rapids_singlecell.preprocessing._neighbors._neighbors import KNN_ALGORITHMS
    from umap.umap_ import fuzzy_simplicial_set

    n_cells = int(matrix.shape[0])
    if n_neighbors >= n_cells:
        raise ValueError(
            "n_neighbors %d is not below the %d cells" % (n_neighbors, n_cells)
        )
    algorithm = "brute" if n_cells <= brute_force_cells else "nn_descent"
    seconds = {}
    t0 = time.monotonic()
    gx = cp.ascontiguousarray(cp.asarray(matrix, dtype=cp.float32))
    idx_g, dist_g = KNN_ALGORITHMS[algorithm](
        gx, gx, k=n_neighbors, metric="euclidean", metric_kwds={}, algorithm_kwds={}
    )
    cp.cuda.get_current_stream().synchronize()
    knn_indices = cp.asnumpy(idx_g).astype(np.int32, copy=False)
    knn_dists = cp.asnumpy(dist_g).astype(np.float32, copy=False)
    del gx, idx_g, dist_g
    cp.get_default_memory_pool().free_all_blocks()
    seconds["knn"] = round(time.monotonic() - t0, 3)
    check_knn(knn_indices, knn_dists, n_cells, n_neighbors, algorithm == "nn_descent")
    t0 = time.monotonic()
    # scanpy: _connectivity.umap(knn_indices, knn_dists, n_obs, n_neighbors) with
    # umap's defaults (set_op_mix_ratio=1.0, local_connectivity=1.0).
    placeholder = sp.coo_matrix(([], ([], [])), shape=(n_cells, 1))
    conn, _sigmas, _rhos = fuzzy_simplicial_set(
        placeholder,
        n_neighbors,
        np.random.RandomState(seed),
        "euclidean",
        knn_indices=knn_indices,
        knn_dists=knn_dists,
        set_op_mix_ratio=1.0,
        local_connectivity=1.0,
    )
    del knn_indices, knn_dists
    conn = sp.csr_matrix(conn, dtype=np.float32)
    conn.sum_duplicates()
    conn.eliminate_zeros()
    conn.sort_indices()
    if conn.nnz >= 2**31:
        raise ValueError(
            "the graph has %d nonzeros; cuGraph's int32 CSR holds fewer than 2**31"
            % conn.nnz
        )
    conn.indptr = conn.indptr.astype(np.int32, copy=False)
    conn.indices = conn.indices.astype(np.int32, copy=False)
    seconds["fuzzy"] = round(time.monotonic() - t0, 3)
    return conn, algorithm, seconds


def modularity(csr, labels, resolution):
    """Newman modularity of a partition, recomputed on the CSR (both directions stored).

    An independent check on cuGraph's own value, in float64, 20,000 rows at a time.
    cuGraph 26.10 sometimes reports more than its returned partition has (0.962
    against 0.901 on the synthetic set, k=50, resolution 1.0), whichever way the
    graph was built.
    """
    _, ids = np.unique(np.asarray(labels), return_inverse=True)
    ids = ids.ravel()
    degree = np.asarray(csr.sum(axis=1, dtype=np.float64)).ravel()
    total = float(degree.sum())
    if total <= 0:
        return 0.0
    internal = 0.0
    for start in range(0, ids.size, 20000):
        end = min(start + 20000, ids.size)
        lo, hi = csr.indptr[start], csr.indptr[end]
        row_ids = np.repeat(ids[start:end], np.diff(csr.indptr[start : end + 1]))
        same = row_ids == ids[csr.indices[lo:hi]]
        internal += float(np.sum(csr.data[lo:hi][same], dtype=np.float64))
    volumes = np.bincount(ids, weights=degree)
    return internal / total - float(resolution) * float(np.sum((volumes / total) ** 2))


def cugraph_graph(csr):
    """The CSR as a cuGraph undirected graph, built straight from its three arrays.

    pylibcugraph copies the arrays into the graph, so they are freed here. Through
    cugraph.Graph.from_cudf_adjlist the same graph kept the adjacency list alive
    beside it: 57 B per nonzero at the Leiden peak against 37 B this way (2M cells,
    k=50, 2026-11-24).

    :return: (resource handle, pylibcugraph SGGraph)
    """
    import cupy as cp
    import pylibcugraph as plc

    handle = plc.ResourceHandle()
    offsets = cp.asarray(csr.indptr, dtype=cp.int32)
    indices = cp.asarray(csr.indices, dtype=cp.int32)
    weights = cp.asarray(csr.data, dtype=cp.float32)
    graph = plc.SGGraph(
        handle,
        plc.GraphProperties(is_symmetric=True, is_multigraph=False),
        offsets,
        indices,
        weight_array=weights,
        store_transposed=False,
        renumber=False,
        do_expensive_check=False,
        input_array_format="CSR",
    )
    del offsets, indices, weights
    cp.get_default_memory_pool().free_all_blocks()
    return handle, graph


def leiden_gpu(graph, n_cells, resolution, seed):
    """cuGraph Leiden (what cugraph.leiden calls, max_iter 100, theta 1.0).

    :param graph: (handle, SGGraph) from cugraph_graph
    :return: (int32 labels, cuGraph's modularity, cluster count, degenerate)
    """
    import cupy as cp
    import pylibcugraph as plc

    handle, sg = graph
    vertex, partition, q_native = plc.leiden(
        handle, int(seed), sg, 100, float(resolution), 1.0, False
    )
    vertex = cp.asnumpy(vertex).astype(np.int64, copy=False)
    if (
        vertex.size != n_cells
        or np.unique(vertex).size != n_cells
        or vertex.min() != 0
        or vertex.max() != n_cells - 1
    ):
        raise ValueError(
            "cuGraph Leiden labeled %d of %d vertices" % (vertex.size, n_cells)
        )
    labels = np.empty(n_cells, dtype=np.int32)
    labels[vertex] = cp.asnumpy(partition)
    n_clusters = int(np.unique(labels).size)
    degenerate = n_clusters >= n_cells or q_native < 0
    return labels, float(q_native), n_clusters, degenerate


def use_managed_memory():
    """Route RMM and cupy allocations to CUDA managed memory (before any allocation)."""
    import cupy as cp
    import rmm
    from rmm.allocators.cupy import rmm_cupy_allocator

    rmm.reinitialize(managed_memory=True, pool_allocator=False)
    cp.cuda.set_allocator(rmm_cupy_allocator)


def _write_json(path, data):
    with open(path, "w") as f:
        json.dump(data, f)


def graph_stage(job):
    """Neighbor search and connectivities; writes connectivities.npz and graph_meta.json."""
    meta = {"ok": False, "versions": versions(), "seconds": {}}
    try:
        params = json.load(open(os.path.join(job, "params.json")))
        t0 = time.monotonic()
        matrix = np.load(os.path.join(job, "matrix.npy"))
        meta["seconds"]["load"] = round(time.monotonic() - t0, 3)
        conn, algorithm, secs = knn_graph_gpu(
            matrix,
            int(params["n_neighbors"]),
            int(params["seed"]),
            int(params.get("brute_force_cells", 0)),
        )
        meta["seconds"].update(secs)
        del matrix
        t0 = time.monotonic()
        sp.save_npz(os.path.join(job, "connectivities.npz"), conn, compressed=False)
        meta["seconds"]["save_graph"] = round(time.monotonic() - t0, 3)
        meta.update(
            {
                "ok": True,
                "n_cells": int(conn.shape[0]),
                "nnz": int(conn.nnz),
                "algorithm": algorithm,
            }
        )
        print(
            "graph stage: %d cells, nnz %d, %s search, seconds %s"
            % (conn.shape[0], conn.nnz, algorithm, json.dumps(meta["seconds"])),
            flush=True,
        )
    except Exception:
        meta["error"] = traceback.format_exc()
        print(meta["error"], file=sys.stderr, flush=True)
    _write_json(os.path.join(job, "graph_meta.json"), meta)
    return 0 if meta["ok"] else 1


def leiden_stage(job, memory):
    """cuGraph Leiden at every resolution; writes labels_<i>.npy and leiden_meta.json.

    :param memory: "device" (RMM's default allocator) or "managed" (CUDA managed
        memory, which can oversubscribe the card into host RAM on native Linux)
    """
    meta = {"ok": False, "memory": memory, "seconds": {}}
    try:
        if memory == "managed":
            use_managed_memory()
        params = json.load(open(os.path.join(job, "params.json")))
        seed = int(params["seed"])
        t0 = time.monotonic()
        csr = sp.load_npz(os.path.join(job, "connectivities.npz")).tocsr()
        if csr.dtype != np.float32:
            csr = csr.astype(np.float32)
        n_cells = int(csr.shape[0])
        meta["seconds"]["load_graph"] = round(time.monotonic() - t0, 3)
        t0 = time.monotonic()
        graph = cugraph_graph(csr)
        meta["seconds"]["to_cugraph"] = round(time.monotonic() - t0, 3)
        rows = []
        leiden_s = check_s = 0.0
        for i, res in enumerate(params["resolutions"]):
            t0 = time.monotonic()
            labels, q_native, n_clusters, degenerate = leiden_gpu(
                graph, n_cells, float(res), seed
            )
            leiden_s += time.monotonic() - t0
            name = "labels_%d.npy" % i
            np.save(os.path.join(job, name), labels)
            t0 = time.monotonic()
            q_check = modularity(csr, labels, float(res))
            check_s += time.monotonic() - t0
            rows.append(
                {
                    "resolution": float(res),
                    "file": name,
                    "n_clusters": n_clusters,
                    "modularity": q_native,
                    "modularity_check": q_check,
                    "degenerate": bool(degenerate),
                }
            )
            print(
                "leiden stage (%s memory): resolution %g, %d clusters, modularity "
                "%.6f (cuGraph) %.6f (recomputed)"
                % (memory, float(res), n_clusters, q_native, q_check),
                flush=True,
            )
        meta["seconds"]["leiden"] = round(leiden_s, 3)
        meta["seconds"]["modularity_check"] = round(check_s, 3)
        meta.update({"ok": True, "resolutions": rows})
    except Exception:
        meta["error"] = traceback.format_exc()
        meta["oom"] = is_cuda_oom(meta["error"])
        print(meta["error"], file=sys.stderr, flush=True)
    _write_json(os.path.join(job, "leiden_meta.json"), meta)
    return 0 if meta["ok"] else 1


def _run_stage(job, stage, *extra):
    """One stage in a fresh process of this script; its output joins this one's log.

    :return: (exit code, wall seconds)
    """
    meta = os.path.join(job, stage + "_meta.json")
    if os.path.exists(meta):
        os.remove(meta)
    t0 = time.monotonic()
    r = subprocess.run(
        [sys.executable, os.path.abspath(__file__), "--stage", stage, job]
        + list(extra),
        cwd=job,
    )
    return r.returncode, round(time.monotonic() - t0, 3)


def _read_meta(job, name):
    path = os.path.join(job, name)
    if not os.path.exists(path):
        return None
    with open(path) as f:
        return json.load(f)


def _stage_failure(stage, code, meta):
    if meta is None:
        if code == -9:
            how = "was killed by signal 9 (often the system running out of host RAM)"
        elif code < 0:
            how = "was killed by signal %d" % -code
        else:
            how = "exited %d" % code
        return "the %s stage %s without a report" % (stage, how)
    return "the %s stage failed: %s" % (stage, str(meta.get("error", ""))[-800:])


def main(job):
    """Run the graph stage, then the Leiden stage, and write result.json."""
    t_start = time.monotonic()
    result = {"ok": False, "versions": {}, "seconds": {}}
    try:
        code, secs = _run_stage(job, "graph")
        result["seconds"]["graph_process"] = secs
        graph = _read_meta(job, "graph_meta.json")
        if graph is not None:
            result["versions"] = graph.get("versions", {})
            result["seconds"].update(graph.get("seconds", {}))
        if code != 0 or graph is None or not graph.get("ok"):
            raise RuntimeError(_stage_failure("graph", code, graph))
        attempts = []
        for memory in ("device", "managed"):
            code, secs = _run_stage(job, "leiden", memory)
            leiden = _read_meta(job, "leiden_meta.json")
            ok = code == 0 and leiden is not None and leiden.get("ok")
            attempts.append({"memory": memory, "exit": code, "seconds": secs})
            if ok:
                break
            failure = _stage_failure("Leiden", code, leiden)
            if not (leiden or {}).get("oom"):
                raise RuntimeError(failure)
            if memory == "managed":
                raise RuntimeError(
                    "out of GPU memory with managed memory too: " + failure
                )
            if is_wsl():
                raise RuntimeError(
                    "Leiden ran out of GPU memory, and WSL2 does not support the "
                    "managed-memory retry: " + failure
                )
            print(
                "Leiden ran out of GPU memory; retrying once with RMM managed memory",
                flush=True,
            )
        result["attempts"] = attempts
        result["seconds"]["leiden_process"] = round(
            sum(a["seconds"] for a in attempts), 3
        )
        result["seconds"].update(leiden.get("seconds", {}))
        result.update(
            {
                "ok": True,
                "n_cells": graph["n_cells"],
                "nnz": graph["nnz"],
                "algorithm": graph["algorithm"],
                "memory": leiden["memory"],
                "resolutions": leiden["resolutions"],
            }
        )
    except Exception as e:
        result["error"] = (
            str(e) if isinstance(e, RuntimeError) else traceback.format_exc()
        )
    result["seconds"]["total"] = round(time.monotonic() - t_start, 3)
    _write_json(os.path.join(job, "result.json"), result)
    print(json.dumps({k: v for k, v in result.items() if k != "error"}), flush=True)
    if not result["ok"]:
        print(result["error"], file=sys.stderr, flush=True)
        return 1
    return 0


if __name__ == "__main__":
    if len(sys.argv) >= 4 and sys.argv[1] == "--stage":
        if sys.argv[2] == "graph":
            sys.exit(graph_stage(sys.argv[3]))
        sys.exit(leiden_stage(sys.argv[3], sys.argv[4]))
    sys.exit(main(sys.argv[1]))
