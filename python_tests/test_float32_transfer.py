"""Pin the float32 measurement-transfer contract on the Python side.

The Java side now ships the cell-by-marker matrix as float32 when every value
round-trips exactly, which is the normal case because QuPath stores detection
measurements as float32 already. That halves the shared segment. It is only safe
if Python widens back to float64 BEFORE doing any arithmetic -- normalization and
the 1-D distribution fits are sensitive to the difference, and doing the maths in
float32 is the change that was measured to flip up to 28% of cluster labels when
it was tried in the 2026-06-26 perf pass.

Scripts that go through ``impute_nonfinite`` get the widening for free. Three did
NOT: run_phenotyping, compute_thresholds, and export_anndata each did a bare
``measurements.ndarray().copy()``, which would have silently inherited float32.

These are the tests for that, and they exist because the phantom-dataset smoke
test **cannot** reach those three scripts -- the YAML batch reports
"phenotyping dispatch deferred to v1.1 (headless entry point pending)", so
phenotyping and threshold computation are GUI-only paths with no headless entry.
A sample dataset does not help where there is no way to run the code.
"""

import re
import warnings

import numpy as np
import pytest

from conftest import SCRIPTS_DIR, load_script_symbol

# Scripts that read the measurement matrix directly rather than via
# impute_nonfinite, and therefore have to widen it themselves.
DIRECT_READERS = ["run_phenotyping.py", "compute_thresholds.py", "export_anndata.py"]

# Scripts that go through impute_nonfinite, which already forces float64.
IMPUTE_READERS = [
    "run_clustering.py",
    "embed_3d.py",
    "geosketch_select.py",
    "infer_autoencoder.py",
]


def _source(name):
    return (SCRIPTS_DIR / name).read_text(encoding="utf-8")


@pytest.mark.parametrize("script", DIRECT_READERS)
def test_direct_readers_widen_to_float64(script):
    """A bare .copy() would inherit float32 and silently change the numbers."""
    src = _source(script)
    assert "measurements.ndarray().copy()" not in src, (
        "%s reads the measurement matrix without widening it. The Java side may "
        "ship float32, so this must be np.array(..., dtype=np.float64, copy=True)."
        % script
    )
    assert re.search(
        r"np\.array\(\s*measurements\.ndarray\(\)\s*,\s*dtype=np\.float64\s*,\s*copy=True\s*\)",
        src,
    ), (
        "%s must widen to float64 with an explicit copy" % script
    )


@pytest.mark.parametrize("script", DIRECT_READERS)
def test_direct_readers_copy_rather_than_alias(script):
    """copy=True is load-bearing, not stylistic.

    Appose frees the shared segment when the task returns. ``np.asarray`` would
    return a VIEW when the input is already float64, leaving the script reading
    freed memory.
    """
    src = _source(script)
    assert "np.asarray(measurements.ndarray()" not in src, (
        "%s must not alias the Appose shared segment -- it is freed when the "
        "task returns. Use np.array(..., copy=True)." % script
    )


@pytest.mark.parametrize("script", IMPUTE_READERS)
def test_impute_readers_still_route_through_impute_nonfinite(script):
    """These get the widening from impute_nonfinite; make sure that stays true."""
    src = _source(script)
    assert "impute_nonfinite(" in src, (
        "%s no longer routes through impute_nonfinite -- it must widen the "
        "measurement matrix to float64 itself." % script
    )


def test_impute_nonfinite_widens_float32_input():
    """The single point that protects the majority of the scripts."""
    impute = load_script_symbol(
        "model_utils.py",
        "impute_nonfinite",
        extra_globals={"np": np, "logger": _NullLogger(), "warnings": warnings},
    )
    f32 = np.array([[1.5, 2.25], [3.0, np.nan]], dtype=np.float32)
    out, n = impute(f32, context="test")
    assert out.dtype == np.float64, "must widen float32 input to float64"
    assert n == 1
    # And it must be a copy -- the shared segment goes away.
    assert not np.shares_memory(out, f32)


def test_float32_transfer_is_value_preserving():
    """The premise: values that came out of QuPath are float32-exact, so the
    narrowing on the Java side and the widening here compose to identity."""
    rng = np.random.default_rng(0)
    original = rng.normal(size=(500, 12)) * 1000.0
    # What QuPath actually stores (FloatList casts to float on write).
    as_quantised = original.astype(np.float32).astype(np.float64)
    # What the float32 transfer does to it.
    round_tripped = as_quantised.astype(np.float32).astype(np.float64)
    assert np.array_equal(
        as_quantised, round_tripped
    ), "narrowing an already-float32 value must be lossless"


class _NullLogger:
    def info(self, *a, **k):
        pass

    def warning(self, *a, **k):
        pass
