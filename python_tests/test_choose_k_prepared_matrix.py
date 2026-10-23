"""Choose k sweeps the matrix a run clusters, prepared by the run's own code.

Until 2026-10-23 the sweep received the raw measurements: no normalization, no
smoothing, no Harmony, no PCA precursor, although its docs said otherwise. The
worker now runs run_clustering.py above its "END OF MATRIX PREPARATION" line
followed by choose_k.py (ApposeClusteringService.preparationOf). These tests
build that script the same way and run it.
"""

import json
import sys
import types

import numpy as np

from conftest import SCRIPTS_DIR, load_script_symbol, requires

MARKER = "# ==== END OF MATRIX PREPARATION ===="


class _Task:
    def __init__(self):
        self.outputs = {}

    def update(self, *args, **kwargs):
        pass


class _Nd:
    def __init__(self, array):
        self._array = array

    def ndarray(self):
        return self._array


def sweep_script():
    run = (SCRIPTS_DIR / "run_clustering.py").read_text(encoding="utf-8")
    assert run.count(MARKER) == 1
    choose = (SCRIPTS_DIR / "choose_k.py").read_text(encoding="utf-8")
    return run[: run.index(MARKER)] + "\n" + choose


def run_sweep(data, **inputs):
    """Run the sweep script on ``data``; return (sweep result, script globals)."""
    if "appose" not in sys.modules:
        stub = types.ModuleType("appose")
        stub.NDArray = object
        sys.modules["appose"] = stub
    task = _Task()
    namespace = {
        "task": task,
        "impute_nonfinite": load_script_symbol(
            "model_utils.py", "impute_nonfinite", {"np": np, "logger": _Log()}
        ),
        "check_cancelled": lambda task_obj: None,
        "measurements": _Nd(data),
        "marker_names": ["m%d" % i for i in range(data.shape[1])],
        "algorithm": "kmeans",
        "algorithm_params": {"n_clusters": 3, "random_state": 7},
        "normalization": "zscore",
        "embedding_method": "umap",
        "embedding_params": {},
        "k_min": 1,
        "k_max": 5,
        "silhouette_max_cells": 0,
        "gap_n_references": 2,
        "sweep_max_cells": 0,
    }
    namespace.update(inputs)
    exec(compile(sweep_script(), "<string>", "exec"), namespace)
    return json.loads(task.outputs["sweep_json"]), namespace


class _Log:
    def __getattr__(self, name):
        return lambda *a, **k: None


def blobs_with_a_loud_marker(seed=0):
    """Three groups told apart by two quiet markers, plus one loud noise marker."""
    rng = np.random.default_rng(seed)
    centers = np.array([[0.0, 0.0], [4.0, 0.0], [0.0, 4.0]])
    group = rng.integers(0, 3, 600)
    quiet = centers[group] + rng.normal(0, 0.5, (600, 2))
    loud = rng.normal(5000.0, 1500.0, (600, 1))
    return np.hstack([quiet, loud])


def test_the_prepared_part_stops_before_the_embedding_and_the_clustering():
    run = (SCRIPTS_DIR / "run_clustering.py").read_text(encoding="utf-8")
    head = run[: run.index(MARKER)]
    assert "cluster_matrix = " in head
    for later in (
        "import knn_cache",
        "import umap",
        "embedding_result = ",
        "labels = ",
    ):
        assert later not in head, later


@requires("sklearn")
@requires("pandas")
def test_the_sweep_runs_on_the_normalized_matrix_and_says_so():
    data = blobs_with_a_loud_marker()
    result, ns = run_sweep(data)
    swept = ns["_mat"]
    expected = (data - data.mean(axis=0)) / data.std(axis=0, ddof=1)
    assert np.allclose(swept, expected)
    assert result["meta"]["prepared"] == ["normalization: zscore"]
    assert result["meta"]["seed"] == 7  # the run's clustering seed


@requires("sklearn")
@requires("pandas")
def test_z_scoring_lets_the_quiet_markers_decide_k():
    # Measured over seeds 0-4: z-scored, the elbow and the silhouette said 3 every
    # time; on raw values the loud noise marker owns the inertia and the elbow
    # said 2 every time, the silhouette 2 in four of five. (The gap said 1 on
    # both.) Sweeping raw values was the defect.
    data = blobs_with_a_loud_marker()
    prepared, _ = run_sweep(data, gap_n_references=5)
    raw, _ = run_sweep(data, normalization="none", gap_n_references=5)
    assert prepared["suggested"]["silhouette"] == 3
    assert raw["suggested"]["silhouette"] != 3


@requires("sklearn")
@requires("pandas")
def test_the_pca_precursor_is_applied_before_the_sweep():
    rng = np.random.default_rng(4)
    data = rng.normal(size=(300, 12))
    result, ns = run_sweep(data, pca_precursor_enabled=True, pca_precursor_n_comps=4)
    assert ns["_mat"].shape == (300, 4)
    assert result["meta"]["n_features"] == 4
    assert result["meta"]["prepared"][-1].startswith("PCA precursor: 12 features -> 4")


@requires("sklearn")
@requires("pandas")
def test_notes_from_java_come_first():
    result, _ = run_sweep(
        blobs_with_a_loud_marker(), sweep_notes=["only the open image"]
    )
    assert result["warnings"][0] == "only the open image"
