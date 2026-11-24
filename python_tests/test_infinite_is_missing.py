"""H07: an infinite measurement value is missing, never data.

One +Inf under z-score used to make every finite value -Inf (0 of 500 cells
positive), and compute_thresholds.py dropped the marker from its output with a
log line only. The statistics now ignore +-Inf, phenotyping gates such a cell
negative and says so, and compute_thresholds notes it per marker.
"""

import json

import numpy as np
import pandas as pd
import pytest

from conftest import SCRIPTS_DIR, normalization_functions, requires, worker_globals

normalization_params, normalize_frame = normalization_functions()


def frame(seed=0):
    rng = np.random.default_rng(seed)
    return pd.DataFrame(
        {
            "a": rng.normal(5, 2, 500),
            "b": rng.gamma(2, 3, 500),
            "c": rng.normal(0, 1, 500),
        }
    )


@pytest.mark.parametrize("method", ["zscore", "minmax", "percentile"])
@pytest.mark.parametrize("inf", [np.inf, -np.inf])
def test_statistics_ignore_infinity(method, inf):
    clean = frame()
    dirty = clean.copy()
    dirty.iloc[7, 0] = inf
    masked = clean.copy()
    masked.iloc[7, 0] = np.nan
    got = normalization_params(dirty, method)
    want = normalization_params(masked, method)
    for g, w in zip(got, want):
        if w is None:
            assert g is None
        else:
            pd.testing.assert_series_equal(g, w, check_exact=True)
    # The finite cells normalize as if the infinite one were missing.
    out = normalize_frame(dirty, method)
    ref = normalize_frame(masked, method)
    finite = np.isfinite(dirty.to_numpy())
    np.testing.assert_array_equal(out.to_numpy()[finite], ref.to_numpy()[finite])


class _Nd:
    def __init__(self, array):
        self._array = array

    def ndarray(self):
        return self._array


class _Task:
    def __init__(self):
        self.outputs = {}

    def update(self, *args, **kwargs):
        pass


def _exec(script, **inputs):
    task = _Task()
    namespace = {"task": task, **worker_globals(), **inputs}
    path = SCRIPTS_DIR / script
    exec(compile(path.read_text(encoding="utf-8"), str(path), "exec"), namespace)
    return task, namespace


def _bimodal(n=500, seed=1):
    rng = np.random.default_rng(seed)
    return np.concatenate([rng.normal(10, 2, n // 2), rng.normal(60, 5, n - n // 2)])


@requires("sklearn")
@pytest.mark.parametrize("normalization", ["zscore", "minmax", "percentile"])
def test_thresholds_keep_a_marker_with_infinity_and_note_it(normalization):
    values = _bimodal()
    values[3] = np.inf
    task, _ = _exec(
        "compute_thresholds.py",
        measurements=_Nd(values.reshape(-1, 1)),
        marker_names=["Cell: CD3 mean"],
        normalization=normalization,
    )
    marker = json.loads(task.outputs["histograms_json"])["Cell: CD3 mean"]
    assert marker["thresholds"], "a marker with one infinite value is still fitted"
    assert sum(marker["counts"]) == 499
    assert any("infinite" in n for n in marker["notes"])


def test_thresholds_report_a_marker_too_small_to_fit():
    values = np.array([1.0, 2.0, np.inf, np.nan, 3.0])
    task, _ = _exec(
        "compute_thresholds.py",
        measurements=_Nd(values.reshape(-1, 1)),
        marker_names=["Cell: CD8 mean"],
        normalization="zscore",
        min_valid_values=10,
    )
    marker = json.loads(task.outputs["histograms_json"])["Cell: CD8 mean"]
    assert marker["skipped"] is True
    assert "counts" not in marker
    joined = " ".join(marker["notes"])
    assert "infinite" in joined and "only 3" in joined


def test_thresholds_note_a_constant_marker():
    values = np.full(50, 4.0)
    task, _ = _exec(
        "compute_thresholds.py",
        measurements=_Nd(values.reshape(-1, 1)),
        marker_names=["Cell: CD20 mean"],
        normalization="none",
    )
    marker = json.loads(task.outputs["histograms_json"])["Cell: CD20 mean"]
    assert marker["thresholds"] == {}
    assert any("same value" in n for n in marker["notes"])


@requires("appose")
@pytest.mark.parametrize("inf", [np.inf, -np.inf])
def test_phenotyping_gates_infinity_negative_and_says_so(inf):
    values = _bimodal()
    expected_pos = int(np.sum(values > 35))
    values[0] = inf  # cell 0 was negative (first half)
    rules = [{"cellType": "CD3+", "Cell: CD3 mean": "pos"}]
    task, ns = _exec(
        "run_phenotyping.py",
        measurements=_Nd(values.reshape(-1, 1)),
        marker_names=["Cell: CD3 mean"],
        normalization="zscore",
        phenotype_rules=json.dumps(rules),
        gates_json=json.dumps({"Cell: CD3 mean": 0.0}),
    )
    counts = json.loads(task.outputs["phenotype_counts"])
    assert counts["CD3+"] == expected_pos
    assert ns["labels"][0] == 1, "the infinite cell is Unknown, not CD3+"
    warnings = json.loads(task.outputs["warnings"])
    assert len(warnings) == 1 and "missing or infinite" in warnings[0]
    assert "Cell: CD3 mean (1)" in warnings[0]


@requires("appose")
def test_phenotyping_clean_data_has_no_warnings():
    task, _ = _exec(
        "run_phenotyping.py",
        measurements=_Nd(_bimodal().reshape(-1, 1)),
        marker_names=["Cell: CD3 mean"],
        normalization="zscore",
        phenotype_rules=json.dumps([{"cellType": "CD3+", "Cell: CD3 mean": "pos"}]),
        gates_json=json.dumps({}),
    )
    assert json.loads(task.outputs["warnings"]) == []
