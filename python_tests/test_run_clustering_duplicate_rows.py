"""Cells with identical measurement rows are searched and partitioned once per row.

What must hold: unique_rows rebuilds the matrix exactly and counts the cells that
share a row; a matrix without duplicates passes through untouched, so the graph is
the one built before this existed; every cell of a shared row gets the same Leiden
label, on the CPU path, through the GPU bridge, and in the stability replicates;
and the graph cache keys on the deduplicated matrix.
"""

import importlib
import logging
import os
import sys

import numpy as np
import pytest

from conftest import SCRIPTS_DIR, load_script_symbol

sc = pytest.importorskip("scanpy")
ig = pytest.importorskip("igraph")

sys.path.insert(0, str(SCRIPTS_DIR))
knn_cache = importlib.import_module("knn_cache")
sys.path.remove(str(SCRIPTS_DIR))


def _symbols():
    import time

    g = {
        "np": np,
        "os": os,
        "sys": sys,
        "time": time,
        "logger": logging.getLogger("test"),
        "GPU_BRUTE_FORCE_CELLS": 200_000,
    }
    for n in [
        "knn_graph",
        "unique_rows",
        "leiden_igraph",
        "leiden_partition",
        "leiden_labels",
        "CpuLeidenEngine",
        "GpuBridgeError",
        "_tail",
        "gpu_scratch_default",
        "gpu_cuda_path",
        "_gpu_child_env",
        "gpu_memory_note",
        "gpu_bridge_run",
        "fit_seeded",
    ]:
        g[n] = load_script_symbol("run_clustering.py", n, g)
    return g


def _blobs_with_duplicates(seed=0):
    """Three blobs of 60 cells; the first 25 cells of the first blob share one
    row, and ten more cells repeat ten rows of the third blob once each."""
    rng = np.random.RandomState(seed)
    x = np.vstack([rng.normal(c, 0.3, size=(60, 4)) for c in (0, 3, 6)]).astype(
        np.float32
    )
    x[:25] = x[0]
    x = np.vstack([x, x[120:130]])
    return x


def test_unique_rows_rebuilds_the_matrix_and_counts_shared_cells():
    s = _symbols()
    x = _blobs_with_duplicates()
    rows, row_of_cell, shared = s["unique_rows"](x)
    assert rows.shape == (190 - 24 - 10, 4)
    assert np.array_equal(rows[row_of_cell], x)
    assert shared == 25 + 20  # 25 identical cells, plus 10 pairs
    assert len(np.unique(row_of_cell[:25])) == 1


def test_a_matrix_without_duplicates_passes_through_untouched():
    s = _symbols()
    x = np.ascontiguousarray(np.random.RandomState(1).normal(size=(50, 3)))
    rows, row_of_cell, shared = s["unique_rows"](x)
    assert rows is x and shared == 0
    assert np.array_equal(row_of_cell, np.arange(50))


def test_unique_rows_handles_fortran_order_float64_and_tiny_inputs():
    s = _symbols()
    x = np.asfortranarray(np.repeat(np.arange(6.0).reshape(2, 3), 3, axis=0))
    rows, row_of_cell, shared = s["unique_rows"](x)
    assert rows.shape == (2, 3) and shared == 6
    assert np.array_equal(rows[row_of_cell], x)
    one, idx, shared = s["unique_rows"](np.zeros((1, 3)))
    assert one.shape == (1, 3) and shared == 0 and idx.tolist() == [0]


def test_bitwise_comparison_separates_values_that_print_alike():
    s = _symbols()
    x = np.zeros((4, 2), dtype=np.float32)
    x[2, 0] = np.float32(1e-9)
    rows, _, shared = s["unique_rows"](x)
    assert rows.shape[0] == 2 and shared == 3


def test_every_cell_of_a_shared_row_gets_the_same_leiden_label():
    s = _symbols()
    x = _blobs_with_duplicates()
    rows, row_of_cell, _ = s["unique_rows"](x)
    conn = s["knn_graph"](rows, 10, 42)
    assert conn.shape == (rows.shape[0], rows.shape[0])
    labels = s["CpuLeidenEngine"](conn).partition(1.0, 42)[row_of_cell]
    assert labels.shape == (x.shape[0],)
    assert len(set(labels[:25].tolist())) == 1
    assert np.array_equal(labels[120:130], labels[180:190])
    assert len(set(labels.tolist())) >= 3


def test_stability_replicates_cluster_the_distinct_rows_too():
    s = _symbols()
    x = _blobs_with_duplicates()
    labels, graph = s["fit_seeded"](
        "leiden", {"n_neighbors": 10, "resolution": 1.0}, x, 7
    )
    assert labels.shape == (x.shape[0],)
    assert graph.shape == (156, 156)
    assert len(set(labels[:25].tolist())) == 1


def test_the_graph_key_names_the_deduplicated_matrix():
    s = _symbols()
    x = _blobs_with_duplicates()
    rows, _, _ = s["unique_rows"](x)
    full = knn_cache.matrix_digest(x)
    key = knn_cache.make_key(
        full, n_neighbors=10, seed=42, backend="cpu", rows=knn_cache.matrix_digest(rows)
    )
    # keep_only_matrix frees on the full digest, so the key keeps it as prefix...
    assert key.startswith(full + " ")
    # ...and a key built over the undeduplicated matrix is another key.
    assert key != knn_cache.make_key(
        full, n_neighbors=10, seed=42, backend="cpu", rows=full
    )
    knn_cache.release("test setup")
    conn = s["knn_graph"](rows, 10, 42)
    assert knn_cache.put("graph", conn, key, 64 * 1024 * 1024)
    hit = knn_cache.get("graph", key)
    assert hit is not None and hit[0].shape == (rows.shape[0], rows.shape[0])
    knn_cache.release("test teardown")


@pytest.mark.skipif(sys.platform == "win32", reason="the Linux hand-over path")
def test_gpu_bridge_labels_come_back_per_row_and_map_to_every_cell(tmp_path):
    from test_run_clustering_graph_backend import STUB

    s = _symbols()
    x = _blobs_with_duplicates()
    rows, row_of_cell, _ = s["unique_rows"](x)
    conn, labels, note, _ = s["gpu_bridge_run"](
        rows, 10, 42, [1.0], sys.executable, str(tmp_path), STUB
    )
    assert conn.shape == (rows.shape[0], rows.shape[0])
    assert labels[1.0].shape == (rows.shape[0],)
    per_cell = np.asarray(labels[1.0])[row_of_cell]
    assert per_cell.shape == (x.shape[0],)
    assert len(set(per_cell[:25].tolist())) == 1
