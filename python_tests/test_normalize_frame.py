"""One normalization for every script: model_utils.normalize_frame.

run_clustering.py, run_phenotyping.py, embed_3d.py, geosketch_select.py and
compute_thresholds.py each carried their own copy. The three ``legacy_*``
functions below are those copies, verbatim (each was checked bit for bit against
the shipped block before the merge), kept as the reference the shared function
is held to:

* clustering, the VEST embedding and the geosketch selection: identical results;
* phenotyping and its threshold preview: identical, except under z-score for a
  marker with fewer than two finite values (see
  ``test_phenotyping_zscore_with_one_finite_value_is_the_one_change``).
"""

import ast

import numpy as np
import pandas as pd
import pytest

from conftest import SCRIPTS_DIR, normalization_functions

normalization_params, normalize_frame = normalization_functions()

METHODS = ["zscore", "minmax", "percentile", "none"]


def legacy_run_clustering(df, normalization):
    if normalization == "zscore":
        std = df.std().replace(0, 1).fillna(1)
        df_norm = (df - df.mean().fillna(0.0)) / std
    elif normalization == "minmax":
        dmin = df.min()
        dmax = df.max()
        drange = dmax - dmin
        drange[drange == 0] = 1
        df_norm = (df - dmin) / drange
    elif normalization == "percentile":
        p1 = df.quantile(0.01)
        p99 = df.quantile(0.99)
        drange = p99 - p1
        drange[drange == 0] = 1
        df_norm = df.clip(lower=p1, upper=p99, axis=1)
        df_norm = (df_norm - p1) / drange
    else:
        df_norm = df.copy()
    return df_norm


def legacy_run_phenotyping(df, normalization):
    if normalization == "zscore":
        std = df.std()
        std[std == 0] = 1
        df_norm = (df - df.mean()) / std
    elif normalization == "minmax":
        dmin = df.min()
        dmax = df.max()
        drange = dmax - dmin
        drange[drange == 0] = 1
        df_norm = (df - dmin) / drange
    elif normalization == "percentile":
        p1 = df.quantile(0.01)
        p99 = df.quantile(0.99)
        drange = p99 - p1
        drange[drange == 0] = 1
        df_norm = df.clip(lower=p1, upper=p99, axis=1)
        df_norm = (df_norm - p1) / drange
    else:
        df_norm = df.copy()
    return df_norm


def legacy_embed_3d(df, norm, pct_low, pct_high):
    """Also the geosketch_select.py copy: the two were the same text."""
    if norm == "zscore":
        std = df.std().replace(0, 1).fillna(1)
        df_norm = (df - df.mean()) / std
    elif norm == "minmax":
        dmin = df.min()
        drange = (df.max() - dmin).replace(0, 1).fillna(1)
        df_norm = (df - dmin) / drange
    elif norm == "percentile":
        lo = max(0.0, min(pct_low, pct_high))
        hi = min(1.0, max(pct_low, pct_high))
        p1 = df.quantile(lo)
        p99 = df.quantile(hi)
        drange = (p99 - p1).replace(0, 1).fillna(1)
        df_norm = (df.clip(lower=p1, upper=p99, axis=1) - p1) / drange
    else:
        df_norm = df.copy()
    return df_norm


def frames():
    rng = np.random.default_rng(20261122)
    base = pd.DataFrame(rng.normal(3, 2, (400, 4)), columns=list("abcd"))
    constant = base.copy()
    constant["c"] = 7.0
    all_nan = base.copy()
    all_nan["b"] = np.nan
    one_finite = base.copy()
    one_finite["d"] = np.nan
    one_finite.iloc[5, 3] = 2.5
    some_nan = base.copy()
    some_nan.iloc[::7, 0] = np.nan
    return {
        "random": base,
        "constant": constant,
        "all_nan": all_nan,
        "one_finite": one_finite,
        "some_nan": some_nan,
    }


FRAMES = list(frames())


@pytest.mark.parametrize("name", FRAMES)
@pytest.mark.parametrize("method", METHODS)
def test_clustering_results_are_bit_identical(name, method):
    df = frames()[name]
    pd.testing.assert_frame_equal(
        normalize_frame(df, method), legacy_run_clustering(df, method), check_exact=True
    )


def test_clustering_fixed_random_matrix_matches_to_the_last_bit():
    rng = np.random.default_rng(7)
    data = np.concatenate(
        [rng.gamma(2.0, 30.0, (5000, 6)), rng.normal(0, 1e-3, (5000, 2))], axis=1
    )
    data[:, 3] = 12.0  # a constant marker
    df = pd.DataFrame(data, columns=["m%d" % i for i in range(8)])
    for method in METHODS:
        new = normalize_frame(df, method).to_numpy()
        old = legacy_run_clustering(df, method).to_numpy()
        assert new.tobytes() == old.tobytes(), method


@pytest.mark.parametrize("name", FRAMES)
@pytest.mark.parametrize("method", METHODS)
@pytest.mark.parametrize(
    "lo,hi", [(0.01, 0.99), (0.05, 0.9), (0.9, 0.05), (-1.0, 2.0), (0.0, 1.0)]
)
def test_embedding_and_geosketch_results_are_bit_identical(name, method, lo, hi):
    df = frames()[name]
    pd.testing.assert_frame_equal(
        normalize_frame(df, method, lo, hi),
        legacy_embed_3d(df, method, lo, hi),
        check_exact=True,
    )


@pytest.mark.parametrize("name", FRAMES)
@pytest.mark.parametrize("method", METHODS)
def test_phenotyping_results_are_bit_identical_outside_the_one_case(name, method):
    if name == "one_finite" and method == "zscore":
        pytest.skip("the one deliberate change; see the next test")
    df = frames()[name]
    pd.testing.assert_frame_equal(
        normalize_frame(df, method),
        legacy_run_phenotyping(df, method),
        check_exact=True,
    )


def test_phenotyping_zscore_with_one_finite_value_is_the_one_change():
    """A marker measured on exactly one cell has no standard deviation. The old
    phenotyping copy divided by that NaN, so the one cell that HAD a value lost
    it and matched neither a positive nor a negative condition. It now scores
    0.0 (its own mean), like in clustering. Cells with no value stay NaN."""
    df = frames()["one_finite"]
    old = legacy_run_phenotyping(df, "zscore")
    new = normalize_frame(df, "zscore")
    assert np.isnan(old["d"].iloc[5])
    assert new["d"].iloc[5] == 0.0
    others = new["d"].drop(index=5)
    assert others.isna().all()
    pd.testing.assert_frame_equal(new[list("abc")], old[list("abc")], check_exact=True)


def test_a_single_cell_scores_zero_under_zscore():
    df = pd.DataFrame({"a": [4.0], "b": [9.0]})
    assert legacy_run_phenotyping(df, "zscore").isna().all().all()
    assert (normalize_frame(df, "zscore") == 0.0).all().all()


@pytest.mark.parametrize("method", METHODS)
def test_a_constant_column_does_not_divide_by_zero(method):
    out = normalize_frame(frames()["constant"], method)["c"]
    assert np.isfinite(out).all()
    assert out.nunique() == 1
    assert out.iloc[0] == (7.0 if method == "none" else 0.0)


@pytest.mark.parametrize("method", METHODS)
def test_an_all_nan_column_stays_nan_and_leaves_the_others_alone(method):
    df = frames()["all_nan"]
    out = normalize_frame(df, method)
    assert out["b"].isna().all()
    assert np.isfinite(out[list("acd")].to_numpy()).all()


def test_custom_percentiles_clip_to_the_asked_range():
    df = pd.DataFrame({"a": np.arange(101, dtype=float)})
    out = normalize_frame(df, "percentile", 0.10, 0.90)["a"]
    assert out.min() == 0.0 and out.max() == 1.0
    assert (out == 0.0).sum() == 11 and (out == 1.0).sum() == 11
    swapped = normalize_frame(df, "percentile", 0.90, 0.10)["a"]
    pd.testing.assert_series_equal(out, swapped, check_exact=True)


def test_the_default_percentiles_are_1_and_99():
    df = frames()["random"]
    pd.testing.assert_frame_equal(
        normalize_frame(df, "percentile"),
        normalize_frame(df, "percentile", 0.01, 0.99),
        check_exact=True,
    )


def test_none_returns_a_copy_not_the_input():
    df = frames()["random"]
    out = normalize_frame(df, "none")
    assert out is not df
    pd.testing.assert_frame_equal(out, df, check_exact=True)


def test_params_reproduce_the_frame():
    """compute_thresholds.py reports (offset, scale, low, high) to the dialog,
    which draws the histogram in the units the run gates in."""
    for name, df in frames().items():
        for method in METHODS:
            offset, scale, low, high = normalization_params(df, method)
            clipped = df if low is None else df.clip(lower=low, upper=high, axis=1)
            pd.testing.assert_frame_equal(
                (clipped - offset) / scale,
                normalize_frame(df, method),
                check_exact=True,
            )


CALL_SITES = {
    "run_clustering.py": "normalize_frame",
    "run_phenotyping.py": "normalize_frame",
    "embed_3d.py": "normalize_frame",
    "geosketch_select.py": "normalize_frame",
    "compute_thresholds.py": "normalization_params",
}


@pytest.mark.parametrize("script", sorted(CALL_SITES))
def test_every_script_uses_the_shared_function(script):
    source = (SCRIPTS_DIR / script).read_text(encoding="utf-8")
    tree = ast.parse(source)
    called = {
        n.func.id
        for n in ast.walk(tree)
        if isinstance(n, ast.Call) and isinstance(n.func, ast.Name)
    }
    defined = {n.name for n in tree.body if isinstance(n, ast.FunctionDef)}
    assert CALL_SITES[script] in called
    assert not {"normalize_frame", "normalization_params"} & defined
    # No private copy left: none of these scripts computes column statistics
    # of the measurement frame itself.
    assert "df.std()" not in source and "df.quantile(" not in source
