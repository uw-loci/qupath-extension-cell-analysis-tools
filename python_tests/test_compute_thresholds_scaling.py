"""compute_thresholds: the scaling it reports is the normalization the run applies,
and the GMM components it reports give the threshold it reports."""

import numpy as np
import pandas as pd
import pytest

from conftest import load_script_symbol, requires

params = load_script_symbol("compute_thresholds.py", "normalization_params", {"pd": pd})


def frame():
    rng = np.random.default_rng(0)
    df = pd.DataFrame({"a": rng.normal(5, 2, 500), "b": rng.gamma(2, 3, 500)})
    df.iloc[3, 0] = np.nan
    return df


def run_phenotyping_norm(df, normalization):
    """The normalization block of run_phenotyping.py, copied verbatim."""
    if normalization == "zscore":
        std = df.std()
        std[std == 0] = 1
        return (df - df.mean()) / std
    if normalization == "minmax":
        dmin = df.min()
        dmax = df.max()
        drange = dmax - dmin
        drange[drange == 0] = 1
        return (df - dmin) / drange
    if normalization == "percentile":
        p1 = df.quantile(0.01)
        p99 = df.quantile(0.99)
        drange = p99 - p1
        drange[drange == 0] = 1
        out = df.clip(lower=p1, upper=p99, axis=1)
        return (out - p1) / drange
    return df.copy()


@pytest.mark.parametrize("normalization", ["zscore", "minmax", "percentile", "none"])
def test_the_scaling_reproduces_the_run_exactly(normalization):
    df = frame()
    offset, scale, low, high = params(df, normalization)
    clipped = df if low is None else df.clip(lower=low, upper=high, axis=1)
    pd.testing.assert_frame_equal(
        (clipped - offset) / scale, run_phenotyping_norm(df, normalization), check_exact=True
    )


def test_a_constant_column_does_not_divide_by_zero():
    df = pd.DataFrame({"a": [3.0, 3.0, 3.0]})
    _, scale, _, _ = params(df, "zscore")
    assert scale["a"] == 1


@requires("sklearn")
def test_the_threshold_comes_from_the_reported_components():
    crossing = load_script_symbol("compute_thresholds.py", "gmm_crossing", {"math": __import__("math")})
    interior = load_script_symbol("compute_thresholds.py", "interior_values", {"np": np})
    fit_fn = load_script_symbol(
        "compute_thresholds.py", "gmm_fit", {"np": np, "interior_values": interior}
    )
    threshold = load_script_symbol(
        "compute_thresholds.py", "gmm_threshold", {"gmm_crossing": crossing}
    )
    rng = np.random.default_rng(0)
    values = np.concatenate([rng.normal(0, 1, 800), rng.normal(6, 1, 200)])
    fit = fit_fn(values)
    assert fit["means"][0] < fit["means"][1]
    assert fit["n_fit"] == 1000
    assert sum(fit["weights"]) == pytest.approx(1.0)
    assert 2.0 < threshold(fit) < 5.0
