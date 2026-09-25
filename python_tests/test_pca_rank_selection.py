"""Adaptive PCA rank selection for the clustering precursor.

The precursor used a fixed 50 components, which is Seurat/scanpy's default for
thousands of genes. On a 58-feature morphology panel that reduced 58 to 50 at
99.8% variance retained -- it did nothing, while the dialog was separately
warning that those same features were redundant across compartments.

Neither test is right at every scale, so the choice is made by feature count:
permutation is affordable and appropriate for tens of features, the
random-matrix edge for thousands.
"""

import logging

import numpy as np
import pytest
from conftest import load_script_symbol

SCRIPT = "run_clustering.py"


def _load(symbol, extra=None):
    g = {"np": np, "logger": logging.getLogger("test.pca")}
    g.update(extra or {})
    return load_script_symbol(SCRIPT, symbol, extra_globals=g)


@pytest.fixture(scope="module")
def parallel():
    return _load("pca_rank_parallel_analysis")


@pytest.fixture(scope="module")
def mp():
    return _load("pca_rank_marchenko_pastur")


def _planted(n, p, rank, noise=1.0, seed=0):
    """n x p matrix whose real structure is exactly `rank` dimensions."""
    rng = np.random.RandomState(seed)
    scores = rng.normal(size=(n, rank)) * np.linspace(6.0, 3.0, rank)
    loads = rng.normal(size=(rank, p))
    return scores @ loads + rng.normal(scale=noise, size=(n, p))


def test_parallel_analysis_finds_planted_structure(parallel):
    X = _planted(1500, 30, rank=4)
    assert parallel(X, max_comps=25) == pytest.approx(4, abs=1)


def test_parallel_analysis_rejects_pure_noise(parallel):
    rng = np.random.RandomState(1)
    X = rng.normal(size=(1200, 25))
    # Nothing but noise: it must not claim a large structure. Two is the floor.
    # Measured: 5 permutations leave the 95th percentile noisy enough to keep 3-5
    # components here, which is why the default is 20.
    assert parallel(X, max_comps=20) <= 3


def test_parallel_analysis_sees_through_duplicated_columns(parallel):
    """The real case: one marker measured in three compartments."""
    base = _planted(1200, 6, rank=3)
    X = np.hstack([base, base + 0.01, base - 0.01])  # 18 columns, still 3-ish dims
    assert parallel(X, max_comps=17) < 10


def test_marchenko_pastur_finds_planted_structure(mp):
    X = _planted(800, 300, rank=5)
    assert mp(X, max_comps=100) == pytest.approx(5, abs=2)


def test_marchenko_pastur_rejects_pure_noise(mp):
    rng = np.random.RandomState(2)
    X = rng.normal(size=(600, 400))
    assert mp(X, max_comps=100) <= 5


def test_both_respect_the_cap_and_the_floor(parallel, mp):
    X = _planted(500, 40, rank=8)
    assert parallel(X, max_comps=3, n_perm=3) <= 3
    assert mp(X, max_comps=3) <= 3
    tiny = np.random.RandomState(3).normal(size=(40, 2))
    assert parallel(tiny, max_comps=10, n_perm=2) >= 2
    assert mp(tiny, max_comps=10) >= 2


def test_dispatch_picks_the_method_by_feature_count():
    calls = {}

    def fake_parallel(X, max_comps, n_perm=5, seed=0):
        calls["method"] = "parallel"
        return 7

    def fake_mp(X, max_comps, iters=5):
        calls["method"] = "mp"
        return 11

    choose = _load(
        "choose_pca_rank",
        {"pca_rank_parallel_analysis": fake_parallel, "pca_rank_marchenko_pastur": fake_mp},
    )

    narrow = np.zeros((100, 58))
    assert choose(narrow, 50)[0] == 7
    assert calls["method"] == "parallel"

    wide = np.zeros((100, 5000))
    n, name = choose(wide, 50)
    assert n == 11
    assert calls["method"] == "mp"
    assert "Marchenko" in name


def test_dispatch_subsamples_rows_for_the_decision():
    seen = {}

    def fake_parallel(X, max_comps, n_perm=5, seed=0):
        seen["rows"] = X.shape[0]
        return 5

    choose = _load(
        "choose_pca_rank",
        {"pca_rank_parallel_analysis": fake_parallel, "pca_rank_marchenko_pastur": None},
    )
    choose(np.zeros((132109, 58)), 50, row_cap=20000)
    assert seen["rows"] == 20000
