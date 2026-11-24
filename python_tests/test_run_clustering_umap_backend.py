"""UMAP on the GPU: the driver's UMAP task, the hand-over, deduplication, the seed
policy and the fall-back to umap-learn."""

import logging
import os
import re
import sys
import textwrap

import numpy as np
import pytest

from conftest import SCRIPTS_DIR, load_script_constant, load_script_symbol

pytest.importorskip("umap")

# The shipped gpu_graph_leiden.py with cuML replaced by a seeded random projection,
# so the driver, the UMAP stage process and the hand-over files run for real without
# a GPU. Markers in the scratch folder's name steer it: UMAPFAIL fails the stage,
# UMAPKILLED kills it without a report, UMAPSHAPE returns one row too few.
_STAND_INS = textwrap.dedent("""
    def versions(libraries=()):
        return {"gpu": "stub GPU", "cuml": "0"}

    def umap_gpu(matrix, n_neighbors, n_components, min_dist, metric, seed):
        if "UMAPFAIL" in os.getcwd():
            raise RuntimeError("umap boom")
        if "UMAPKILLED" in os.getcwd():
            os.kill(os.getpid(), 9)
        rng = np.random.RandomState(seed) if seed is not None else np.random.RandomState()
        emb = np.asarray(matrix, dtype=np.float64) @ rng.normal(size=(matrix.shape[1], n_components))
        if "UMAPSHAPE" in os.getcwd():
            emb = emb[:-1]
        algo = umap_build_algo(matrix.shape[0], metric, seed is not None)
        return emb.astype(np.float32), algo, {"umap": 0.0}

""")


def _stub_source():
    src = (SCRIPTS_DIR / "gpu_graph_leiden.py").read_text(encoding="utf-8")
    marker = '\nif __name__ == "__main__":'
    assert src.count(marker) == 1
    return src.replace(marker, "\n" + _STAND_INS + marker)


STUB = _stub_source()
linux_only = pytest.mark.skipif(
    sys.platform == "win32", reason="the Linux hand-over path"
)


def _symbols():
    import time

    g = {
        "np": np,
        "os": os,
        "re": re,
        "sys": sys,
        "time": time,
        "logger": logging.getLogger("test"),
        "EMBEDDING_FAST_MODE_CELLS": load_script_constant(
            "model_utils.py", "EMBEDDING_FAST_MODE_CELLS"
        ),
        "EMBEDDING_PCA_INIT_CELLS": load_script_constant(
            "model_utils.py", "EMBEDDING_PCA_INIT_CELLS"
        ),
        "FLOAT32_RANGE_REASON": load_script_constant(
            "model_utils.py", "FLOAT32_RANGE_REASON"
        ),
    }
    for n in ("resolve_umap_execution", "require_finite"):
        g[n] = load_script_symbol("model_utils.py", n, g)
    for n in [
        "unique_rows",
        "GpuBridgeError",
        "gpu_bridge_available",
        "gpu_scratch_default",
        "gpu_cuda_path",
        "_gpu_child_env",
        "_wsl_run",
        "_check_wsl_path",
        "_trim_failed_job",
        "_wsl_run_script",
        "_tail",
        "_gpu_job_run",
        "gpu_umap_note",
        "gpu_umap_run",
        "umap_on_gpu",
        "umap_embedding",
    ]:
        g[n] = load_script_symbol("run_clustering.py", n, g)
    return g


def _blobs():
    rng = np.random.RandomState(0)
    return np.vstack([rng.normal(c, 0.3, size=(60, 4)) for c in (0, 3, 6)]).astype(
        np.float32
    )


def _run(s, scratch, x=None, seed=42, n_components=2):
    return s["gpu_umap_run"](
        _blobs() if x is None else x,
        15,
        n_components,
        0.1,
        "euclidean",
        seed,
        sys.executable,
        str(scratch),
        STUB,
    )


def test_the_neighbor_search_follows_the_seed_size_and_metric():
    algo = load_script_symbol(
        "gpu_graph_leiden.py",
        "umap_build_algo",
        {
            "UMAP_BRUTE_FORCE_CELLS": 50_000,
            "UMAP_NN_DESCENT_METRICS": ("euclidean", "l2", "sqeuclidean", "cosine"),
        },
    )
    assert algo(1_000_000, "euclidean", True) == "brute_force_knn"  # seeded: exact
    assert algo(1_000_000, "euclidean", False) == "nn_descent"
    assert algo(1_000_000, "Cosine", False) == "nn_descent"
    assert algo(50_000, "euclidean", False) == "brute_force_knn"  # cuML's own cut
    for metric in ("manhattan", "correlation", "chebyshev"):  # NN-descent lacks them
        assert algo(1_000_000, metric, False) == "brute_force_knn"


def test_the_note_says_whether_the_layout_repeats():
    s = _symbols()
    assert s["gpu_umap_note"]("brute_force_knn", True) == (
        "seeded, exact neighbor search, repeats run to run on one card"
    )
    assert s["gpu_umap_note"]("nn_descent", False) == (
        "unseeded, NN-descent neighbor search, layout not bit-reproducible"
    )


@linux_only
@pytest.mark.parametrize("dims", [2, 3])
def test_round_trip_with_a_stub_process(tmp_path, dims):
    s = _symbols()
    emb, note, seconds = _run(s, tmp_path, n_components=dims)
    assert emb.shape == (180, dims) and emb.dtype == np.float64
    assert note == (
        "gpu (stub GPU on Linux; cuML 0; seeded, exact neighbor search, repeats run "
        "to run on one card)"
    )
    assert seconds["gpu_umap_process"] > 0 and seconds["write"] >= 0
    assert list(tmp_path.iterdir()) == []  # the job folder is removed on success
    again, _, _ = _run(s, tmp_path, n_components=dims)
    assert np.array_equal(emb, again)


@linux_only
def test_unseeded_says_so(tmp_path):
    s = _symbols()
    _, note, _ = _run(s, tmp_path, seed=None)
    assert "unseeded" in note and "not bit-reproducible" in note


@linux_only
def test_a_failing_stage_raises_with_the_reason_and_keeps_only_the_log(tmp_path):
    s = _symbols()
    scratch = tmp_path / "UMAPFAIL"
    with pytest.raises(s["GpuBridgeError"]) as e:
        _run(s, scratch)
    assert "umap boom" in str(e.value) and "the UMAP stage failed" in str(e.value)
    jobs = list(scratch.glob("job_*_umap"))
    assert len(jobs) == 1
    left = sorted(p.name for p in jobs[0].iterdir())
    assert "matrix.npy" not in left and "embedding.npy" not in left
    assert "run.log" in left and "umap boom" in (jobs[0] / "run.log").read_text()


@linux_only
def test_a_killed_stage_is_named(tmp_path):
    s = _symbols()
    with pytest.raises(s["GpuBridgeError"]) as e:
        _run(s, tmp_path / "UMAPKILLED")
    assert "UMAP stage was killed by signal 9" in str(e.value)


@linux_only
def test_an_embedding_of_the_wrong_shape_is_refused(tmp_path):
    s = _symbols()
    with pytest.raises(s["GpuBridgeError"]) as e:
        _run(s, tmp_path / "UMAPSHAPE")
    assert "expected 180 x 2" in str(e.value)


@linux_only
def test_identical_rows_are_embedded_once_and_share_a_point(tmp_path):
    s = _symbols()
    x = _blobs()
    x[:25] = x[0]
    emb, note = s["umap_on_gpu"](
        x, 15, 2, 0.1, "euclidean", 42, sys.executable, str(tmp_path), STUB
    )
    assert emb.shape == (180, 2)
    assert np.unique(emb[:25], axis=0).shape[0] == 1
    assert np.unique(emb, axis=0).shape[0] == 180 - 24
    assert note.endswith("; 25 cells that shared a row placed at that row's point")


def test_too_few_distinct_rows_go_to_the_cpu():
    s = _symbols()
    x = np.repeat(_blobs()[:10], 18, axis=0)
    with pytest.raises(s["GpuBridgeError"]) as e:
        s["umap_on_gpu"](x, 15, 2, 0.1, "euclidean", 42, sys.executable, "", STUB)
    assert "only 10 distinct measurement rows" in str(e.value)


def _embed(s, scratch, backend="gpu", mode="auto", python=sys.executable, x=None):
    return s["umap_embedding"](
        _blobs() if x is None else x,
        15,
        0.1,
        "euclidean",
        2,
        42,
        mode,
        backend,
        python,
        str(scratch),
        STUB,
    )


@linux_only
def test_the_gpu_follows_the_reproducibility_setting(tmp_path):
    s = _symbols()
    emb, note, reproducible, ran, reason = _embed(s, tmp_path, mode="auto")
    assert ran == "gpu" and reason is None and reproducible
    assert note.startswith("gpu (stub GPU") and "seeded, exact" in note
    assert note.endswith("[auto at 180 cells]")
    _, note, reproducible, ran, _ = _embed(s, tmp_path, mode="fast")
    assert ran == "gpu" and not reproducible and "unseeded" in note
    _, note, reproducible, _, _ = _embed(s, tmp_path, mode="reproducible")
    assert reproducible and "seeded, exact" in note and "[auto" not in note


@linux_only
def test_a_failed_gpu_umap_falls_back_to_umap_learn_with_the_reason(tmp_path):
    s = _symbols()
    emb, note, reproducible, ran, reason = _embed(s, tmp_path / "UMAPFAIL")
    assert ran == "cpu" and emb.shape == (180, 2) and np.isfinite(emb).all()
    assert "umap boom" in reason
    assert note.startswith("seeded (reproducible, single-threaded)") and reproducible


def test_no_rapids_python_is_a_reason_not_a_failure(tmp_path):
    s = _symbols()
    _, note, _, ran, reason = _embed(s, tmp_path, python="")
    assert ran == "cpu" and "no RAPIDS Python configured" in reason
    _, _, _, ran, reason = _embed(s, tmp_path, backend="cpu", python="")
    assert ran == "cpu" and reason is None


def test_an_unknown_task_is_reported(tmp_path):
    main = load_script_symbol(
        "gpu_graph_leiden.py",
        "main",
        {
            "json": __import__("json"),
            "os": os,
            "sys": sys,
            "time": __import__("time"),
            "traceback": __import__("traceback"),
            "_write_json": load_script_symbol(
                "gpu_graph_leiden.py", "_write_json", {"json": __import__("json")}
            ),
        },
    )
    (tmp_path / "params.json").write_text('{"task": "tsne"}')
    assert main(str(tmp_path)) == 1
    assert "unknown task 'tsne'" in (tmp_path / "result.json").read_text()
