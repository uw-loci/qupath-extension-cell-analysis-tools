"""The GMM threshold's BIC note (compute_thresholds.py).

The GMM method always fits two components and gates where they cross, so a
marker with one population (no positive cells) is split in two with the gate
inside it, and three populations (negative / dim / bright) are absorbed by
two. gmm_fit now also fits 1 and 3 components and compares the three by BIC;
gmm_note says when 2 is not the best fit. The gate itself does not move.
"""

import json
import math

import numpy as np
import pytest

from conftest import SCRIPTS_DIR, load_script_symbol, requires, worker_globals

SCRIPT = SCRIPTS_DIR / "compute_thresholds.py"

pytestmark = requires("sklearn")


def helpers(**overrides):
    """The script's GMM helpers, sharing one namespace; overrides replace the
    module constants (BIC_SAMPLE, BIC_MARGIN) before the functions are loaded,
    since load_script_symbol copies the globals it is given."""
    ns = {"np": np, "math": math, "BIC_SAMPLE": 50000, "BIC_MARGIN": 6.0, **overrides}
    for name in (
        "interior_values",
        "gmm_crossing",
        "gmm_fit",
        "gmm_threshold",
        "gmm_note",
    ):
        ns[name] = load_script_symbol("compute_thresholds.py", name, ns)
    return ns


def fit_and_note(values):
    h = helpers()
    fit = h["gmm_fit"](values)
    return fit, h["gmm_note"](fit, h["gmm_threshold"](fit))


def one_population(n=3000, seed=0):
    return np.random.default_rng(seed).normal(0.3, 0.05, n)


def two_populations(n=3000, seed=0):
    rng = np.random.default_rng(seed)
    return np.concatenate(
        [rng.normal(0.1, 0.04, n * 3 // 4), rng.normal(0.7, 0.08, n // 4)]
    )


def three_populations(n=3000, seed=0):
    rng = np.random.default_rng(seed)
    return np.concatenate(
        [
            rng.normal(0.05, 0.02, n // 2),
            rng.normal(0.35, 0.04, n // 4),
            rng.normal(0.8, 0.06, n // 4),
        ]
    )


def test_one_population_gets_the_no_positive_cells_note():
    fit, note = fit_and_note(one_population())
    assert fit["bic_components"] == 1
    assert "one population by BIC" in note
    assert "no positive cells" in note
    assert "means3" not in fit


def test_one_population_past_a_zero_floor_says_so():
    values = np.concatenate([np.zeros(2000), one_population()])
    fit, note = fit_and_note(values)
    assert fit["bic_components"] == 1
    assert fit["n_fit"] == 3000 and fit["n_values"] == 5000
    assert "among the 3000 cells outside the spike" in note
    assert "all be positive" in note


def test_two_populations_get_no_note():
    fit, note = fit_and_note(two_populations())
    assert fit["bic_components"] == 2
    assert note is None
    assert "means3" not in fit


def test_three_populations_name_the_pair_the_gate_separates():
    fit, note = fit_and_note(three_populations())
    assert fit["bic_components"] == 3
    m1, m2, m3 = fit["means3"]
    assert abs(m1 - 0.05) < 0.02 and abs(m2 - 0.35) < 0.03 and abs(m3 - 0.8) < 0.03
    assert note.startswith("three populations by BIC (means ")
    for m in (m1, m2, m3):
        assert "%.3g" % m in note
    gate = helpers()["gmm_threshold"](fit)
    if gate < m2:
        assert "separates the negative group from the dim and bright groups" in note
        assert "between the dim and bright groups" in note
    else:
        assert "separates the negative and dim groups from the bright group" in note
        assert "between the negative and dim groups" in note


def test_the_gate_is_still_the_two_component_crossing():
    # Existing behavior and saved rule sets must not change: the note is
    # advisory and the threshold comes from the 2-component fit alone.
    h = helpers()
    fit = h["gmm_fit"](three_populations())
    m1, m2 = fit["means"]
    s1, s2 = fit["stds"]
    w1, w2 = fit["weights"]
    assert h["gmm_threshold"](fit) == pytest.approx(
        h["gmm_crossing"](m1, s1, w1, m2, s2, w2)
    )


def test_the_comparison_subsamples_large_markers():
    h = helpers(BIC_SAMPLE=500)
    fit = h["gmm_fit"](three_populations(n=4000))
    assert fit["n_fit"] == 4000
    assert fit["bic_components"] == 3


def test_a_small_margin_leaves_two_components():
    # Two populations whose BIC gap to 1 or 3 is within the margin stay at 2.
    h = helpers(BIC_MARGIN=1e9)
    fit = h["gmm_fit"](three_populations())
    assert fit["bic_components"] == 2
    assert h["gmm_note"](fit, h["gmm_threshold"](fit)) is None


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


@requires("pandas")
@requires("skimage")
@requires("scipy")
def test_the_script_ships_the_note_in_the_gmm_entry():
    task = _Task()
    names = ["Cell: CD20 mean", "Cell: CD3 mean"]
    data = np.column_stack([one_population(), two_populations()])
    namespace = {
        "task": task,
        "measurements": _Nd(data),
        "marker_names": names,
        "normalization": "none",
        **worker_globals(),
    }
    exec(compile(SCRIPT.read_text(encoding="utf-8"), str(SCRIPT), "exec"), namespace)
    result = json.loads(task.outputs["histograms_json"])
    assert result[names[0]]["gmm"]["bic_components"] == 1
    assert "no positive cells" in result[names[0]]["gmm"]["note"]
    assert result[names[1]]["gmm"]["bic_components"] == 2
    assert "note" not in result[names[1]]["gmm"]
    assert "gmm" in result[names[0]]["thresholds"]
