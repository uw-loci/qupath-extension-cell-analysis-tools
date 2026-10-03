"""Choosing k: three statistics, and the one case that tells them apart.

QP-CAT's KMeans note recommended elbow / silhouette / gap without providing any
of them. These tests cover the sweep that does, and the assertions were written
from measurements rather than from expectation.

The measurement that matters, five seeds each:

    PURE NOISE (400 cells x 5 features, no clusters exist)
      elbow      said 3 or 4, every run
      silhouette said 6 to 8, every run
      gap        said k = 1, 5 of 5 runs      <- the only one that can

    THREE CLEAN BLOBS
      all three agreed on k = 3, 5 of 5 runs

So the elbow and the silhouette will confidently name a k for data with no
structure in it, and only the gap statistic can report "nothing here". That is
the fact the dialog leads with, and `test_only_the_gap_can_say_there_is_no_structure`
is what holds it.
"""

import json

import numpy as np
import pytest

from conftest import load_script_symbol, requires

SCRIPT = "choose_k.py"


def _script_namespace():
    """Every top-level helper, loaded into ONE shared namespace.

    `load_script_symbol` compiles a single `def` with the globals it is given, so
    a helper loaded on its own cannot see its siblings -- `build_sweep_result`
    calls `elbow_suggestion` and would raise NameError. Loading them all into the
    same dict, adding each as it goes, mirrors how the module actually runs.
    """
    ns = {"np": np, "json": json, "logger": __import__("logging").getLogger("t")}
    for name in (
        "subsample_rows",
        "elbow_suggestion",
        "gap_suggestion",
        "reference_box",
        "build_sweep_result",
        "sweep_k",
    ):
        ns[name] = load_script_symbol(SCRIPT, name, ns)
    return ns


def _load(symbol):
    return _script_namespace()[symbol]


@pytest.fixture
def subsample_rows():
    return _load("subsample_rows")


@pytest.fixture
def elbow_suggestion():
    return _load("elbow_suggestion")


@pytest.fixture
def gap_suggestion():
    return _load("gap_suggestion")


@pytest.fixture
def reference_box():
    return _load("reference_box")


@pytest.fixture
def sweep_k():
    return _load("sweep_k")


# ---- subsample_rows ----


def test_a_small_matrix_is_not_subsampled(subsample_rows):
    m = np.arange(40, dtype=float).reshape(10, 4)
    rows, n, was = subsample_rows(m, 100, 0)
    assert n == 10
    assert was is False
    assert rows.shape == (10, 4)


def test_subsampling_is_seeded_so_the_statistic_is_reproducible(subsample_rows):
    m = np.arange(2000, dtype=float).reshape(500, 4)
    a, na, wa = subsample_rows(m, 50, 7)
    b, _, _ = subsample_rows(m, 50, 7)
    c, _, _ = subsample_rows(m, 50, 8)
    assert wa is True and na == 50
    assert np.array_equal(a, b)
    assert not np.array_equal(a, c)


def test_a_zero_cap_means_no_cap(subsample_rows):
    m = np.zeros((300, 2))
    for cap in (0, None, -1):
        _, n, was = subsample_rows(m, cap, 0)
        assert n == 300 and was is False


# ---- elbow_suggestion ----


def test_the_elbow_is_the_point_farthest_from_the_chord(elbow_suggestion):
    # A curve with a deliberate kink at k = 4.
    ks = [2, 3, 4, 5, 6, 7]
    inertias = [100.0, 60.0, 30.0, 28.0, 26.5, 25.0]
    assert elbow_suggestion(ks, inertias) == 4


def test_the_elbow_does_not_depend_on_the_units_of_the_data(elbow_suggestion):
    # Both axes are normalised before the chord distance, so a run on raw
    # intensities and the same run z-scored must elbow at the same k. Without
    # that, the answer would change with the normalization.
    ks = [2, 3, 4, 5, 6]
    inertias = [100.0, 55.0, 30.0, 27.0, 25.0]
    assert elbow_suggestion(ks, inertias) == elbow_suggestion(
        ks, [v * 1e6 for v in inertias]
    )


def test_a_curve_too_short_to_have_a_kink_declines_to_guess(elbow_suggestion):
    assert elbow_suggestion([2, 3], [10.0, 5.0]) is None
    assert elbow_suggestion([], []) is None


def test_a_flat_curve_declines_rather_than_picking_the_first_k(elbow_suggestion):
    # Nothing to see. Returning k_min would read as a real finding.
    assert elbow_suggestion([2, 3, 4, 5], [10.0, 10.0, 10.0, 10.0]) == 2


def test_non_finite_inertias_are_skipped(elbow_suggestion):
    ks = [2, 3, 4, 5, 6]
    inertias = [100.0, float("nan"), 30.0, 28.0, 26.0]
    assert elbow_suggestion(ks, inertias) in (4, 5)


# ---- gap_suggestion: Tibshirani's rule, not the argmax ----


def test_the_gap_rule_is_the_smallest_sufficient_k_not_the_argmax(gap_suggestion):
    # The gap keeps rising to k = 5, so argmax would say 5. Tibshirani's rule
    # takes the first k that reaches the next one minus its error -- here k = 3.
    ks = [1, 2, 3, 4, 5]
    gaps = [0.1, 0.5, 1.00, 1.05, 1.10]
    errs = [0.02, 0.02, 0.02, 0.10, 0.10]
    assert gap_suggestion(ks, gaps, errs) == 3
    assert int(np.argmax(gaps)) == 4  # what the naive answer would have been


def test_the_gap_can_report_a_single_cluster(gap_suggestion):
    # The one answer no other statistic here can give.
    ks = [1, 2, 3, 4]
    gaps = [1.0, 0.9, 0.8, 0.7]
    errs = [0.05, 0.05, 0.05, 0.05]
    assert gap_suggestion(ks, gaps, errs) == 1


def test_a_still_rising_gap_declines_rather_than_naming_k_max(gap_suggestion):
    # The range was too small. Returning k_max would present a boundary artifact
    # as a result.
    ks = [2, 3, 4]
    gaps = [0.2, 0.6, 1.4]
    errs = [0.01, 0.01, 0.01]
    assert gap_suggestion(ks, gaps, errs) is None


def test_non_finite_gaps_are_skipped(gap_suggestion):
    ks = [2, 3, 4, 5]
    gaps = [float("nan"), 1.0, 1.02, 1.03]
    errs = [float("nan"), 0.01, 0.05, 0.05]
    assert gap_suggestion(ks, gaps, errs) == 3


# ---- reference_box ----


def test_the_reference_null_covers_the_data_and_nothing_much_more(reference_box):
    rng = np.random.default_rng(0)
    data = rng.normal(size=(300, 3)) * [5.0, 1.0, 0.2]
    ref = reference_box(data, 1)
    assert ref.shape == data.shape
    assert np.isfinite(ref).all()
    # A PCA-aligned box around an anisotropic cloud should have a comparable
    # total spread, not an order of magnitude more -- that is the whole reason
    # Tibshirani prefers it to the raw bounding box.
    assert ref.std(axis=0).sum() < data.std(axis=0).sum() * 3


def test_the_reference_is_seeded(reference_box):
    data = np.random.default_rng(0).normal(size=(100, 2))
    assert np.array_equal(reference_box(data, 5), reference_box(data, 5))
    assert not np.array_equal(reference_box(data, 5), reference_box(data, 6))


# ---- sweep_k, against real sklearn ----


@requires("sklearn")
def test_three_clean_blobs_make_all_three_statistics_agree(sweep_k):
    rng = np.random.default_rng(100)
    X = np.vstack([rng.normal(c, 0.4, (200, 5)) for c in (-5, 0, 5)])
    r = json.loads(sweep_k(X, 1, 8, seed=0, silhouette_max_cells=0, gap_n_references=10))

    assert r["ks"] == list(range(1, 9))
    assert r["suggested"]["elbow"] == 3
    assert r["suggested"]["silhouette"] == 3
    assert r["suggested"]["gap"] == 3


@requires("sklearn")
def test_only_the_gap_can_say_there_is_no_structure(sweep_k):
    # The headline measurement. On data with no clusters at all, the elbow and
    # the silhouette both confidently name a k; the gap says 1. Measured over
    # five seeds: gap = 1 in 5 of 5, elbow in 3-4, silhouette in 6-8.
    rng = np.random.default_rng(0)
    Y = rng.normal(size=(400, 5))
    r = json.loads(sweep_k(Y, 1, 8, seed=0, silhouette_max_cells=0, gap_n_references=10))

    assert r["suggested"]["gap"] == 1
    assert r["suggested"]["elbow"] not in (None, 1)
    assert r["suggested"]["silhouette"] not in (None, 1)


@requires("sklearn")
def test_k_equals_one_has_no_silhouette_but_does_have_a_gap(sweep_k):
    rng = np.random.default_rng(1)
    X = rng.normal(size=(150, 3))
    r = json.loads(sweep_k(X, 1, 3, seed=0, silhouette_max_cells=0, gap_n_references=5))
    assert r["silhouette"][0] is None      # undefined: no other cluster exists
    assert r["gap"][0] is not None         # defined, and the point of including it
    assert r["inertia"][0] is not None


@requires("sklearn")
def test_inertia_falls_monotonically_which_is_why_the_elbow_is_weak(sweep_k):
    # Pinned because it is the argument for the caveat: a curve with no optimum
    # cannot be argmaxed, so the "elbow" is a kink read off a shape.
    rng = np.random.default_rng(2)
    X = np.vstack([rng.normal(c, 0.5, (100, 4)) for c in (-3, 3)])
    r = json.loads(sweep_k(X, 1, 6, seed=0, silhouette_max_cells=0, gap_n_references=0))
    inertias = r["inertia"]
    for a, b in zip(inertias, inertias[1:]):
        assert b <= a + 1e-6


@requires("sklearn")
def test_skipping_the_gap_is_honoured_and_reported(sweep_k):
    rng = np.random.default_rng(3)
    X = rng.normal(size=(120, 3))
    r = json.loads(sweep_k(X, 2, 5, seed=0, silhouette_max_cells=0, gap_n_references=0))
    assert all(v is None for v in r["gap"])
    assert r["suggested"]["gap"] is None
    assert r["meta"]["gap_n_references"] == 0


@requires("sklearn")
def test_subsampling_the_silhouette_is_reported_in_the_warnings(sweep_k):
    rng = np.random.default_rng(4)
    X = np.vstack([rng.normal(c, 0.4, (150, 3)) for c in (-4, 4)])
    r = json.loads(sweep_k(X, 2, 4, seed=0, silhouette_max_cells=100, gap_n_references=0))
    assert r["meta"]["silhouette_subsampled"] is True
    assert r["meta"]["silhouette_cells"] == 100
    assert any("subsample" in w for w in r["warnings"])
    # The GB figure in the warning is the reason for the cap, so it must be there.
    assert any("GB" in w for w in r["warnings"])


@requires("sklearn")
def test_starting_above_one_warns_that_no_structure_cannot_be_detected(sweep_k):
    rng = np.random.default_rng(5)
    X = rng.normal(size=(120, 3))
    r = json.loads(sweep_k(X, 2, 4, seed=0, silhouette_max_cells=0, gap_n_references=5))
    assert any("k = 1" in w for w in r["warnings"])


@requires("sklearn")
def test_k_max_above_the_cell_count_is_clamped_and_said(sweep_k):
    rng = np.random.default_rng(6)
    X = rng.normal(size=(12, 2))
    r = json.loads(sweep_k(X, 2, 50, seed=0, silhouette_max_cells=0, gap_n_references=0))
    assert r["meta"]["k_max"] == 11
    assert any("reduced" in w for w in r["warnings"])


@requires("sklearn")
def test_the_sweep_gives_the_same_ANSWER_at_a_fixed_seed(sweep_k):
    """Same seed, same suggested k.

    Deliberately not a string comparison of the two JSON blobs. The shipped
    module pins BLAS and numba to one thread at import time, so in the Appose
    worker the floats are bit-identical -- but these tests AST-load one function
    at a time and never execute that module-level setup, so sklearn runs
    multi-threaded here and the reduction order of a sum varies in the last
    couple of digits. Asserting byte equality would therefore pass or fail on
    the test harness's threading rather than on the code, which is worse than
    useless. What a user needs is that the ANSWER does not move, and that the
    curves agree to far better than the precision anyone reads off them.
    """
    rng = np.random.default_rng(7)
    X = np.vstack([rng.normal(c, 0.5, (100, 4)) for c in (-3, 0, 3)])
    a = json.loads(sweep_k(X, 2, 5, seed=11, silhouette_max_cells=0, gap_n_references=5))
    b = json.loads(sweep_k(X, 2, 5, seed=11, silhouette_max_cells=0, gap_n_references=5))

    assert a["ks"] == b["ks"]
    assert a["suggested"] == b["suggested"]
    assert a["meta"] == b["meta"]
    for key in ("inertia", "silhouette", "gap", "gap_std_error"):
        assert a[key] == pytest.approx(b[key], rel=1e-6), key


@requires("sklearn")
def test_a_different_seed_can_move_the_curves_but_not_a_clear_answer(sweep_k):
    # The companion claim: the seed is worth changing as a stability check, and
    # on well-separated data the answer survives it. This is what the dialog
    # tells the user to do.
    rng = np.random.default_rng(9)
    X = np.vstack([rng.normal(c, 0.4, (120, 4)) for c in (-6, 0, 6)])
    answers = {
        json.loads(
            sweep_k(X, 2, 6, seed=s, silhouette_max_cells=0, gap_n_references=5)
        )["suggested"]["silhouette"]
        for s in (1, 2, 3)
    }
    assert answers == {3}


@requires("sklearn")
def test_progress_is_reported_and_reaches_one(sweep_k):
    seen = []
    rng = np.random.default_rng(8)
    X = rng.normal(size=(80, 3))
    sweep_k(
        X, 2, 4, seed=0, silhouette_max_cells=0, gap_n_references=0,
        progress=lambda f, m: seen.append((f, m)),
    )
    assert seen
    assert seen[-1][0] == 1.0
    assert all(0.0 <= f <= 1.0 for f, _ in seen)
