"""The Leiden graph/partition backend seam: CPU unchanged, GPU falls back when absent."""

import numpy as np
import pytest

from conftest import load_script_symbol, requires

sc = pytest.importorskip("scanpy")
ig = pytest.importorskip("igraph")


def _symbols():
    import logging

    g = {
        "np": np,
        "logger": logging.getLogger("test"),
        "GPU_BRUTE_FORCE_CELLS": 200_000,
    }
    names = [
        "graph_backend_available",
        "knn_graph",
        "leiden_igraph",
        "leiden_partition",
        "leiden_labels",
        "CpuLeidenEngine",
        "GpuLeidenEngine",
        "leiden_engine",
    ]
    out = {}
    for n in names:
        out[n] = load_script_symbol("run_clustering.py", n, g)
        g[n] = out[n]
    return out


def test_cpu_is_the_default_and_never_reports_a_reason():
    s = _symbols()
    assert s["graph_backend_available"]("cpu") == ("cpu", None)


def test_gpu_falls_back_with_a_reason_when_rapids_is_absent():
    s = _symbols()
    try:
        import cuml, cugraph  # noqa: F401

        pytest.skip("RAPIDS is installed here; the fallback cannot be exercised")
    except Exception:
        pass
    backend, reason = s["graph_backend_available"]("gpu")
    assert backend == "cpu"
    assert reason and ("ImportError" in reason or "ModuleNotFoundError" in reason)


def test_cpu_engine_matches_leiden_labels():
    s = _symbols()
    rng = np.random.RandomState(0)
    x = np.vstack([rng.normal(c, 0.3, size=(60, 4)) for c in (0, 3, 6)]).astype(
        np.float32
    )
    conn = s["knn_graph"](x, 10, 42)
    engine = s["leiden_engine"](conn, "cpu")
    assert engine.name == "cpu"
    a = engine.partition(1.0, 42)
    b = s["leiden_labels"](conn, 1.0, 42)
    assert np.array_equal(a, b)
    assert len(set(a.tolist())) >= 3
