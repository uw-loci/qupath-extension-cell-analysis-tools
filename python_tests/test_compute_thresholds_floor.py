"""Auto-thresholds on a marker with a floor of zeros (compute_thresholds.py).

Multiplex intensities put most marker-negative cells at exactly 0. Fitted with
that spike included, GMM put one component on the spike and its threshold at
the spike's edge, and Triangle took the first bin past it: on the synthetic TME
image tme_01 (CD3, percentile scaling) both called 37-38% of cells positive
against 24% T cells. The spike is now left out of both fits.
"""

import json

import numpy as np
import pytest

from conftest import SCRIPTS_DIR, load_script_symbol, requires

SCRIPT = SCRIPTS_DIR / "compute_thresholds.py"


def spike_and_tail(seed=0):
    """55% exact zeros, 20% dim shoulder, 25% positive bump; returns (values, is_pos)."""
    rng = np.random.default_rng(seed)
    zeros = np.zeros(1100)
    shoulder = rng.exponential(3.0, 400)
    positive = np.clip(rng.normal(60.0, 12.0, 500), 25.0, None)
    values = np.concatenate([zeros, shoulder, positive])
    is_pos = np.concatenate([np.zeros(1500, bool), np.ones(500, bool)])
    return values, is_pos


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


def run_script(values, normalization):
    task = _Task()
    names = ["Cell: CD3 mean"]
    namespace = {
        "task": task,
        "measurements": _Nd(values.reshape(-1, 1)),
        "marker_names": names,
        "normalization": normalization,
    }
    exec(compile(SCRIPT.read_text(encoding="utf-8"), str(SCRIPT), "exec"), namespace)
    result = json.loads(task.outputs["histograms_json"])[names[0]]
    scaled = namespace["df_norm"].iloc[:, 0].to_numpy()
    return result, scaled


def test_interior_values_drops_floor_and_clip_masses():
    interior_values = load_script_symbol(
        "compute_thresholds.py", "interior_values", {"np": np}
    )
    values = np.concatenate([np.zeros(50), np.linspace(1, 9, 40), np.full(10, 10.0)])
    kept = interior_values(values)
    assert kept.min() == 1.0
    assert kept.max() == 9.0
    assert len(kept) == 40


def test_interior_values_leaves_continuous_data_alone():
    interior_values = load_script_symbol(
        "compute_thresholds.py", "interior_values", {"np": np}
    )
    values = np.random.default_rng(1).normal(size=500)
    assert len(interior_values(values)) == 500


def test_interior_values_keeps_everything_when_too_little_is_left():
    interior_values = load_script_symbol(
        "compute_thresholds.py", "interior_values", {"np": np}
    )
    values = np.concatenate([np.zeros(95), np.arange(1, 6, dtype=float)])
    assert len(interior_values(values)) == 100


@requires("pandas")
@requires("sklearn")
@requires("skimage")
@requires("scipy")
@pytest.mark.parametrize("normalization", ["percentile", "minmax", "zscore", "none"])
def test_gmm_and_triangle_do_not_sit_on_the_zero_spike(normalization):
    values, is_pos = spike_and_tail()
    result, scaled = run_script(values, normalization)
    thresholds = result["thresholds"]
    truth = 100.0 * is_pos.mean()

    gmm_pct = 100.0 * np.mean(scaled >= thresholds["gmm"])
    tri_pct = 100.0 * np.mean(scaled >= thresholds["triangle"])

    # Before the fix: GMM 43-45% and Triangle 39-40% positive on this data.
    assert abs(gmm_pct - truth) < 2.0, gmm_pct
    assert abs(tri_pct - truth) < 5.0, tri_pct


@requires("pandas")
@requires("sklearn")
@requires("skimage")
@requires("scipy")
def test_quantiles_reproduce_the_positive_fraction():
    values, _ = spike_and_tail()
    result, scaled = run_script(values, "percentile")
    quantiles = np.asarray(result["quantiles"])
    assert len(quantiles) == 1001
    assert np.all(np.diff(quantiles) >= 0)
    assert quantiles[0] == scaled.min()
    assert quantiles[-1] == scaled.max()
