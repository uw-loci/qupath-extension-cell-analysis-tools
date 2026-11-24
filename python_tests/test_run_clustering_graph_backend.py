"""The GPU graph/Leiden bridge: CPU unchanged, hand-over files round-trip, failures fall back."""

import json
import logging
import os
import re
import sys
import textwrap

import numpy as np
import pytest

from conftest import SCRIPTS_DIR, load_script_symbol

sc = pytest.importorskip("scanpy")
ig = pytest.importorskip("igraph")

# The shipped gpu_graph_leiden.py with its GPU calls replaced, so the driver, both
# stage processes, the hand-over files and the retry run for real without a GPU:
# the graph is a symmetrized sklearn kNN graph, Leiden is igraph's. Markers in the scratch folder's name
# steer it: GRAPHFAIL fails the graph stage, KILLED kills it without a report,
# OOM makes the device-memory Leiden stage run out of card memory, WSL makes the
# driver think it runs inside WSL2, LEIDENFAIL fails Leiden for another reason,
# DEGEN marks resolution 0.5 degenerate.
_STAND_INS = textwrap.dedent("""
    def versions():
        return {"gpu": "stub GPU", "cuml": "0", "cugraph": "0", "rapids_singlecell": "0"}

    def is_wsl():
        return "WSL" in os.getcwd()

    def use_managed_memory():
        print("stub: managed memory", flush=True)

    def knn_graph_gpu(matrix, n_neighbors, seed, brute_force_cells):
        from sklearn.neighbors import kneighbors_graph
        if "GRAPHFAIL" in os.getcwd():
            raise RuntimeError("boom")
        if "KILLED" in os.getcwd():
            os.kill(os.getpid(), 9)
        g = kneighbors_graph(np.asarray(matrix), n_neighbors - 1)
        conn = sp.csr_matrix((g + g.T) > 0, dtype=np.float32)
        return conn, "stub", {"knn": 0.0, "fuzzy": 0.0}

    def cugraph_graph(csr):
        return csr

    def leiden_gpu(graph, n_cells, resolution, seed):
        import random
        import igraph as ig
        if "OOM" in os.getcwd() and sys.argv[-1] == "device":
            raise MemoryError("std::bad_alloc: out_of_memory: CUDA error at: rmm")
        if "LEIDENFAIL" in os.getcwd():
            raise ValueError("not a memory problem")
        up = sp.triu(graph, k=1).tocoo()
        g = ig.Graph(n=n_cells, edges=list(zip(up.row.tolist(), up.col.tolist())))
        random.seed(seed)
        part = g.community_leiden(objective_function="modularity", weights=up.data.tolist(),
                                  resolution=resolution, n_iterations=-1)
        lab = np.asarray(part.membership, dtype=np.int32)
        degenerate = "DEGEN" in os.getcwd() and resolution == 0.5
        return lab, 0.5, int(len(set(lab))), degenerate

""")


def _stub_source():
    src = (SCRIPTS_DIR / "gpu_graph_leiden.py").read_text(encoding="utf-8")
    marker = '\nif __name__ == "__main__":'
    assert src.count(marker) == 1
    return src.replace(marker, "\n" + _STAND_INS + marker)


STUB = _stub_source()


def _symbols():
    g = {
        "np": np,
        "os": os,
        "re": re,
        "sys": sys,
        "logger": logging.getLogger("test"),
        "GPU_BRUTE_FORCE_CELLS": 200_000,
    }
    import time

    g["time"] = time
    for n in [
        "knn_graph",
        "leiden_igraph",
        "leiden_partition",
        "leiden_labels",
        "CpuLeidenEngine",
        "GpuBridgeError",
        "gpu_bridge_available",
        "gpu_scratch_default",
        "gpu_cuda_path",
        "_gpu_child_env",
        "_wsl_run",
        "_check_wsl_path",
        "_trim_failed_job",
        "_tail",
        "gpu_memory_note",
        "gpu_bridge_run",
    ]:
        g[n] = load_script_symbol("run_clustering.py", n, g)
    return g


def _blobs():
    rng = np.random.RandomState(0)
    return np.vstack([rng.normal(c, 0.3, size=(60, 4)) for c in (0, 3, 6)]).astype(
        np.float32
    )


def test_cpu_is_the_default_and_gpu_needs_a_python():
    s = _symbols()
    assert s["gpu_bridge_available"]("cpu", "") == ("cpu", None)
    assert s["gpu_bridge_available"]("gpu", "/env/.pixi/envs/default/bin/python") == (
        "gpu",
        None,
    )
    backend, reason = s["gpu_bridge_available"]("gpu", "")
    assert backend == "cpu" and "Preferences" in reason


def test_cpu_engine_matches_leiden_labels():
    s = _symbols()
    conn = s["knn_graph"](_blobs(), 10, 42)
    a = s["CpuLeidenEngine"](conn).partition(1.0, 42)
    b = s["leiden_labels"](conn, 1.0, 42)
    assert np.array_equal(a, b)
    assert len(set(a.tolist())) >= 3


def test_scratch_default_prefers_tmpdir_then_the_env(monkeypatch):
    s = _symbols()
    monkeypatch.setenv("TMPDIR", "/scratch/node")
    assert s["gpu_scratch_default"]("/x/.pixi/envs/default/bin/python") == os.path.join(
        "/scratch/node", "qpcat-gpu"
    )
    monkeypatch.delenv("TMPDIR")
    assert (
        s["gpu_scratch_default"]("/data/rapids/.pixi/envs/default/bin/python")
        == "/data/rapids/qpcat-gpu-scratch"
    )


def test_child_env_is_isolated(monkeypatch, tmp_path):
    s = _symbols()
    lib = tmp_path / "env" / "lib"
    lib.mkdir(parents=True)
    cuda = tmp_path / "nv"
    cuda.mkdir()
    (cuda / "libcuda.so.1").write_text("")
    other = tmp_path / "torchlibs"
    other.mkdir()
    monkeypatch.setenv("LD_LIBRARY_PATH", os.pathsep.join([str(other), str(cuda)]))
    monkeypatch.setenv("PYTHONPATH", "/home/user/.local/lib")
    env = s["_gpu_child_env"](str(tmp_path / "env" / "bin" / "python"))
    assert env["PYTHONNOUSERSITE"] == "1"
    assert "PYTHONPATH" not in env
    assert env["LD_LIBRARY_PATH"] == os.pathsep.join([str(lib), str(cuda)])


@pytest.mark.skipif(sys.platform == "win32", reason="the Linux hand-over path")
def test_bridge_round_trip_with_a_stub_process(tmp_path):
    s = _symbols()
    x = _blobs()
    conn, labels, note, seconds = s["gpu_bridge_run"](
        x, 10, 42, [1.0, 0.5], sys.executable, str(tmp_path), STUB
    )
    assert conn.shape == (x.shape[0], x.shape[0]) and conn.nnz > 0
    assert set(labels) == {1.0, 0.5} and len(labels[1.0]) == x.shape[0]
    assert note.startswith("gpu (stub GPU on Linux") and note.endswith("device memory)")
    assert seconds["write"] >= 0 and seconds["read"] >= 0 and "total" in seconds
    # two stage processes, each timed
    assert seconds["gpu_graph_process"] > 0 and seconds["gpu_leiden_process"] > 0
    assert list(tmp_path.iterdir()) == []  # the job folder is removed on success


@pytest.mark.skipif(sys.platform == "win32", reason="the Linux hand-over path")
def test_bridge_failure_raises_with_the_reason_and_keeps_the_log(tmp_path):
    s = _symbols()
    scratch = tmp_path / "GRAPHFAIL"
    scratch.mkdir()
    with pytest.raises(s["GpuBridgeError"]) as e:
        s["gpu_bridge_run"](_blobs(), 10, 42, [1.0], sys.executable, str(scratch), STUB)
    assert "boom" in str(e.value) and "stub GPU" in str(e.value)
    assert any(p.is_dir() for p in scratch.iterdir())  # kept for diagnosis


@pytest.mark.skipif(sys.platform == "win32", reason="the Linux hand-over path")
def test_degenerate_resolution_is_left_to_the_worker(tmp_path):
    s = _symbols()
    scratch = tmp_path / "DEGEN"
    scratch.mkdir()
    conn, labels, note, _ = s["gpu_bridge_run"](
        _blobs(), 10, 42, [1.0, 0.5], sys.executable, str(scratch), STUB
    )
    assert (
        set(labels) == {1.0}
        and "every cell as its own cluster at resolution 0.5" in note
    )
    assert s["CpuLeidenEngine"](conn).partition(0.5, 42).shape == (conn.shape[0],)


@pytest.mark.skipif(sys.platform == "win32", reason="the Linux hand-over path")
def test_an_unwritable_scratch_folder_is_a_bridge_error_not_a_crash():
    s = _symbols()
    with pytest.raises(s["GpuBridgeError"]) as e:
        s["gpu_bridge_run"](
            _blobs(), 10, 42, [1.0], sys.executable, "/nonexistent/qpcat", STUB
        )
    assert "scratch folder" in str(e.value)


def test_a_missing_wsl_is_a_clear_bridge_error(monkeypatch):
    import subprocess as sp

    s = _symbols()

    def no_wsl(*a, **k):
        raise FileNotFoundError(
            2, "The system cannot find the file specified", "wsl.exe"
        )

    monkeypatch.setattr(sp, "run", no_wsl)
    with pytest.raises(s["GpuBridgeError"]) as e:
        s["_wsl_run"](["echo", "~"])
    assert "Windows Subsystem for Linux is not installed" in str(e.value)


def test_wsl_without_a_distribution_says_so(monkeypatch):
    import subprocess as sp

    s = _symbols()

    class R:
        returncode = 1
        stdout = "W\x00i\x00n\x00d\x00o\x00w\x00s\x00 Subsystem for Linux has no installed distributions."
        stderr = ""

    monkeypatch.setattr(sp, "run", lambda *a, **k: R())
    with pytest.raises(s["GpuBridgeError"]) as e:
        s["_wsl_run"](["echo", "~"])
    assert "has no Linux distribution" in str(e.value)


def test_windows_paths_must_be_plain_absolute_linux_paths():
    s = _symbols()
    ok = "/home/me/rapids/.pixi/envs/default/bin/python"
    s["_check_wsl_path"](ok, "x")  # no raise
    for bad in (
        "C:\\scratch",
        "/home/me/my scratch",
        "/home/me/$HOME",
        "",
        "relative/path",
    ):
        with pytest.raises(s["GpuBridgeError"]) as e:
            s["_check_wsl_path"](bad, "GPU graph and Leiden: scratch folder")
        assert "GPU graph and Leiden: scratch folder" in str(e.value)


def test_a_failed_job_keeps_only_the_small_files(tmp_path):
    s = _symbols()
    for name in (
        "matrix.npy",
        "connectivities.npz",
        "labels_0.npy",
        "run.log",
        "params.json",
        "result.json",
    ):
        (tmp_path / name).write_bytes(b"x")
    s["_trim_failed_job"](str(tmp_path))
    assert sorted(p.name for p in tmp_path.iterdir()) == [
        "params.json",
        "result.json",
        "run.log",
    ]


@pytest.mark.skipif(sys.platform == "win32", reason="the Linux hand-over path")
def test_a_failing_gpu_process_leaves_no_matrix_behind(tmp_path):
    s = _symbols()
    scratch = tmp_path / "GRAPHFAIL"
    with pytest.raises(s["GpuBridgeError"]):
        s["gpu_bridge_run"](_blobs(), 10, 42, [1.0], sys.executable, str(scratch), STUB)
    jobs = list(scratch.glob("job_*"))
    assert len(jobs) == 1
    left = sorted(p.name for p in jobs[0].iterdir())
    assert "matrix.npy" not in left and "run.log" in left


def _job(scratch):
    jobs = list(scratch.glob("job_*"))
    assert len(jobs) == 1
    return jobs[0]


@pytest.mark.skipif(sys.platform == "win32", reason="the Linux hand-over path")
def test_out_of_memory_leiden_is_retried_once_with_managed_memory(tmp_path):
    s = _symbols()
    x = _blobs()
    conn, labels, note, seconds = s["gpu_bridge_run"](
        x, 10, 42, [1.0], sys.executable, str(tmp_path / "OOM"), STUB
    )
    assert set(labels) == {1.0} and len(labels[1.0]) == x.shape[0]
    assert "managed memory, after the card ran out with device memory" in note


@pytest.mark.skipif(sys.platform == "win32", reason="the Linux hand-over path")
def test_out_of_memory_leiden_on_wsl2_falls_back_without_a_retry(tmp_path):
    s = _symbols()
    scratch = tmp_path / "OOM_WSL"
    with pytest.raises(s["GpuBridgeError"]) as e:
        s["gpu_bridge_run"](_blobs(), 10, 42, [1.0], sys.executable, str(scratch), STUB)
    assert "WSL2 does not support the managed-memory retry" in str(e.value)
    log = (_job(scratch) / "run.log").read_text()
    assert "stub: managed memory" not in log and "out_of_memory" in log


@pytest.mark.skipif(sys.platform == "win32", reason="the Linux hand-over path")
def test_a_leiden_failure_that_is_not_memory_is_not_retried(tmp_path):
    s = _symbols()
    scratch = tmp_path / "LEIDENFAIL"
    with pytest.raises(s["GpuBridgeError"]) as e:
        s["gpu_bridge_run"](_blobs(), 10, 42, [1.0], sys.executable, str(scratch), STUB)
    assert "not a memory problem" in str(e.value)
    assert "retrying" not in (_job(scratch) / "run.log").read_text()


@pytest.mark.skipif(sys.platform == "win32", reason="the Linux hand-over path")
def test_a_graph_stage_killed_without_a_report_is_named(tmp_path):
    s = _symbols()
    scratch = tmp_path / "KILLED"
    with pytest.raises(s["GpuBridgeError"]) as e:
        s["gpu_bridge_run"](_blobs(), 10, 42, [1.0], sys.executable, str(scratch), STUB)
    assert "graph stage was killed by signal 9" in str(e.value)
