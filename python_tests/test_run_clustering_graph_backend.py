"""The GPU graph/Leiden bridge: CPU unchanged, hand-over files round-trip, failures fall back."""

import json
import logging
import os
import sys
import textwrap

import numpy as np
import pytest

from conftest import load_script_symbol

sc = pytest.importorskip("scanpy")
ig = pytest.importorskip("igraph")

# A stand-in for gpu_graph_leiden.py that needs no GPU: same files in, same files out,
# graph built with scanpy. "FAIL" in the job name makes it report an error; "DEGEN"
# marks the last resolution degenerate.
STUB = textwrap.dedent("""
    import json, os, sys
    import numpy as np
    from scipy import sparse as sp
    import anndata as ad, scanpy as sc
    job = sys.argv[1]
    params = json.load(open(os.path.join(job, "params.json")))
    x = np.load(os.path.join(job, "matrix.npy"))
    if "FAIL" in job:
        json.dump({"ok": False, "error": "boom", "versions": {"gpu": "none"}}, open(os.path.join(job, "result.json"), "w"))
        sys.exit(1)
    a = ad.AnnData(X=x); sc.pp.neighbors(a, n_neighbors=params["n_neighbors"], use_rep="X", random_state=params["seed"])
    conn = sp.csr_matrix(a.obsp["connectivities"]); sp.save_npz(os.path.join(job, "graph.npz"), conn, compressed=False)
    rows = []
    for i, res in enumerate(params["resolutions"]):
        sc.tl.leiden(a, resolution=res, flavor="igraph", n_iterations=-1, random_state=params["seed"], key_added="l")
        lab = a.obs["l"].astype(int).to_numpy().astype(np.int32)
        degenerate = "DEGEN" in job and i == len(params["resolutions"]) - 1
        np.save(os.path.join(job, "labels_%d.npy" % i), lab)
        rows.append({"resolution": res, "file": "labels_%d.npy" % i, "n_clusters": int(len(set(lab))), "modularity": 0.5, "degenerate": degenerate})
    json.dump({"ok": True, "n_cells": int(x.shape[0]), "nnz": int(conn.nnz), "algorithm": "stub",
               "versions": {"gpu": "stub GPU", "cuml": "0", "cugraph": "0"}, "resolutions": rows, "seconds": {"total": 0.1}},
              open(os.path.join(job, "result.json"), "w"))
    """)


def _symbols():
    g = {
        "np": np,
        "os": os,
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
        "_tail",
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
    assert note.startswith("gpu (stub GPU on Linux")
    assert seconds["write"] >= 0 and seconds["read"] >= 0 and "total" in seconds
    assert list(tmp_path.iterdir()) == []  # the job folder is removed on success


@pytest.mark.skipif(sys.platform == "win32", reason="the Linux hand-over path")
def test_bridge_failure_raises_with_the_reason_and_keeps_the_log(tmp_path):
    s = _symbols()
    scratch = tmp_path / "FAIL"
    scratch.mkdir()
    with pytest.raises(s["GpuBridgeError"]) as e:
        s["gpu_bridge_run"](_blobs(), 10, 42, [1.0], sys.executable, str(scratch), STUB)
    assert "boom" in str(e.value) and "none" in str(e.value)
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
