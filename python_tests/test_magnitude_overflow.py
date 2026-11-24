"""H08 and H46: values too large for the arithmetic are refused, naming the measurement.

H08: a column with |x| near 1e154 has std = inf under z-score, so every cell
normalized to -0.0 and the marker silently dropped out of the clustering.
H46: a float32 cast turns a value above 3.4e38 into Inf (BANKSY, the GPU
hand-over, the autoencoders) and the run trained or clustered on it.
"""

import ast

import numpy as np
import pandas as pd
import pytest

from conftest import (
    SCRIPTS_DIR,
    load_script_constant,
    load_script_symbol,
    normalization_functions,
)

normalization_params, normalize_frame = normalization_functions()
require_finite = load_script_symbol("model_utils.py", "require_finite", {"np": np})
FLOAT32_RANGE_REASON = load_script_constant("model_utils.py", "FLOAT32_RANGE_REASON")


def frame():
    rng = np.random.default_rng(3)
    return pd.DataFrame(
        {"Cell: CD3 mean": rng.normal(5, 1, 100), "Cell: Odd": rng.normal(0, 1, 100)}
    )


@pytest.mark.parametrize(
    "method, value",
    [
        ("zscore", 1e200),
        ("zscore", -1e200),
        ("minmax", 1.7e308),
        ("percentile", 1.7e308),
    ],
)
def test_overflowing_statistics_are_refused_with_the_name(method, value):
    df = frame()
    df.iloc[:50, 1] = value
    df.iloc[50:, 1] = -value if method != "zscore" else 1.0
    with pytest.raises(ValueError) as e:
        normalization_params(df, method)
    msg = str(e.value)
    assert "'Cell: Odd'" in msg and "CD3" not in msg
    assert method in msg and "NaN" in msg


def test_large_but_representable_values_still_normalize():
    df = frame()
    df.iloc[:, 1] = df.iloc[:, 1] * 1e150
    out = normalize_frame(df, "zscore")
    assert np.isfinite(out.to_numpy()).all()
    assert out.iloc[:, 1].std() == pytest.approx(1.0)


def test_an_all_missing_column_is_not_refused():
    df = frame()
    df.iloc[:, 1] = np.nan
    for method in ("zscore", "minmax", "percentile"):
        normalization_params(df, method)


def test_float32_overflow_names_the_measurement_and_blames_the_data():
    x = np.array([[1.0, 2.0], [3.0, 1e300]]).astype(np.float32)
    with pytest.raises(ValueError) as e:
        require_finite(
            x,
            "The float32 conversion for BANKSY",
            ["CD3", "CD8"],
            reason=FLOAT32_RANGE_REASON,
        )
    msg = str(e.value)
    assert "CD8" in msg and "CD3" not in msg
    assert "3.4e38" in msg and "QP-CAT defect" not in msg


@pytest.mark.parametrize(
    "script, step",
    [
        ("run_clustering.py", "The float32 hand-over to the GPU graph"),
        ("run_clustering.py", "The float32 conversion for BANKSY"),
        ("train_autoencoder.py", "The float32 conversion of the measurements"),
        ("train_autoencoder.py", "The float32 conversion of the tile measurements"),
        ("infer_autoencoder.py", "The float32 conversion of the measurements"),
        ("infer_autoencoder.py", "The float32 conversion of the tile measurements"),
    ],
)
def test_every_float32_cast_of_user_values_is_checked(script, step):
    source = (SCRIPTS_DIR / script).read_text(encoding="utf-8")
    tree = ast.parse(source)
    calls = [
        n
        for n in ast.walk(tree)
        if isinstance(n, ast.Call)
        and isinstance(n.func, ast.Name)
        and n.func.id == "require_finite"
        and n.args
        and len(n.args) > 1
        and isinstance(n.args[1], ast.Constant)
        and n.args[1].value == step
    ]
    assert len(calls) == 1
    assert any(k.arg == "reason" for k in calls[0].keywords)
