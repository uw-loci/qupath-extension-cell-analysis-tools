"""Tests for the shared non-finite measurement guard (model_utils.impute_nonfinite).

Background (issue #8 triage). MeasurementExtractor.extractDataMatrix used to fill
a measurement QuPath could not compute with 0.0; it now leaves it as NaN so the
Python side can impute per-column. That change (commit 17fb511) landed while
only four of the nine consumers of the measurements matrix sanitized their input.

The dangerous consumers were the autoencoders: numpy's mean/std are not
NaN-aware and the usual `std[std == 0] = 1` guard does not catch NaN, so a
single missing measurement turned a whole column of normalization statistics
into NaN and trained the model on NaN **without ever raising**. These tests pin
both the helper's semantics and the fact that each consumer calls it.
"""

import ast
import logging
import warnings

import numpy as np
import pytest
from conftest import SCRIPTS_DIR, load_script_symbol

N_CELLS = 200
N_MARKERS = 6


@pytest.fixture(scope="module")
def impute():
    # model_utils.py defines `logger` and imports numpy/warnings at module level;
    # load_script_symbol executes only the function, so supply its globals here.
    return load_script_symbol(
        "model_utils.py",
        "impute_nonfinite",
        {"np": np, "warnings": warnings, "logger": logging.getLogger("test.impute")},
    )


class TestImputeNonfinite:
    def test_clean_input_is_unchanged(self, impute):
        arr = np.arange(12, dtype=np.float64).reshape(4, 3)
        out, n = impute(arr)
        assert n == 0
        np.testing.assert_array_equal(out, arr)

    def test_clean_input_is_copied_not_aliased(self, impute):
        """The caller passes a view into Appose shared memory, freed after the task."""
        arr = np.ones((4, 3))
        out, _ = impute(arr)
        out[0, 0] = 99.0
        assert arr[0, 0] == 1.0

    def test_nan_becomes_column_median(self, impute):
        arr = np.array([[1.0, 10.0], [3.0, 20.0], [np.nan, 30.0]])
        out, n = impute(arr)
        assert n == 1
        assert out[2, 0] == 2.0  # median of [1, 3]
        np.testing.assert_array_equal(out[:, 1], [10.0, 20.0, 30.0])

    def test_inf_is_treated_as_missing(self, impute):
        arr = np.array([[1.0], [3.0], [np.inf], [-np.inf]])
        out, n = impute(arr)
        assert n == 2
        assert np.isfinite(out).all()
        assert out[2, 0] == 2.0 and out[3, 0] == 2.0

    def test_all_nan_column_becomes_zero(self, impute):
        arr = np.array([[np.nan, 5.0], [np.nan, 7.0]])
        out, n = impute(arr)
        assert n == 2
        np.testing.assert_array_equal(out[:, 0], [0.0, 0.0])

    def test_all_nan_column_emits_no_runtime_warning(self, impute):
        arr = np.full((3, 2), np.nan)
        with warnings_as_errors():
            out, _ = impute(arr)
        assert np.isfinite(out).all()

    def test_rows_are_never_dropped(self, impute):
        """Callers index cells by row against spatial coords / CellRefs."""
        arr = np.random.RandomState(0).normal(size=(N_CELLS, N_MARKERS))
        arr[7, 2] = np.nan
        out, _ = impute(arr)
        assert out.shape == (N_CELLS, N_MARKERS)

    def test_each_column_imputes_independently(self, impute):
        arr = np.array([[np.nan, np.nan], [2.0, 100.0], [4.0, 300.0]])
        out, _ = impute(arr)
        assert out[0, 0] == 3.0  # median of [2, 4]
        assert out[0, 1] == 200.0  # median of [100, 300]

    def test_output_is_float64_even_for_int_input(self, impute):
        out, n = impute(np.ones((2, 2), dtype=np.int32))
        assert out.dtype == np.float64 and n == 0

    def test_non_2d_input_raises(self, impute):
        with pytest.raises(ValueError, match="2-D"):
            impute(np.zeros(5))

    def test_defeats_the_silent_nan_statistics_bug(self, impute):
        """The exact failure mode: np.mean/np.std are not NaN-aware."""
        arr = np.random.RandomState(1).normal(size=(N_CELLS, N_MARKERS))
        arr[0, 0] = np.nan

        std = arr.std(axis=0)
        std[std == 0] = 1  # the guard that does NOT catch NaN
        assert np.isnan(arr.mean(axis=0)[0]) and np.isnan(std[0])

        clean, _ = impute(arr)
        std_clean = clean.std(axis=0)
        std_clean[std_clean == 0] = 1
        assert np.isfinite(clean.mean(axis=0)).all()
        assert np.isfinite(std_clean).all()


class TestConsumersAreGuarded:
    """Every script fed extraction.getData() must sanitize or deliberately opt out."""

    IMPUTING = [
        "run_clustering.py",
        "embed_3d.py",
        "geosketch_select.py",
        "train_autoencoder.py",
        "infer_autoencoder.py",
        "estimate_spatial_time.py",
    ]
    # These two consume NaN intentionally; see the comments at their call sites.
    OPTED_OUT = ["export_anndata.py", "run_phenotyping.py", "compute_thresholds.py"]

    @pytest.mark.parametrize("script", IMPUTING)
    def test_impute_is_called(self, script):
        source = (SCRIPTS_DIR / script).read_text(encoding="utf-8")
        assert "impute_nonfinite(" in source, "%s must sanitize measurements" % script

    @pytest.mark.parametrize("script", IMPUTING)
    def test_no_raw_ndarray_read_bypasses_the_guard(self, script):
        """A bare `measurements.ndarray()` outside impute_nonfinite(...) is a bypass."""
        source = (SCRIPTS_DIR / script).read_text(encoding="utf-8")
        for line in source.splitlines():
            stripped = line.strip()
            if "measurements.ndarray()" not in stripped or stripped.startswith("#"):
                continue
            assert "impute_nonfinite(" in stripped or stripped.endswith(
                ('context="measurement"', 'context="tile measurement"')
            ), "unsanitized read in %s: %s" % (script, stripped)

    @pytest.mark.parametrize("script", OPTED_OUT)
    def test_opt_out_is_documented(self, script):
        source = (SCRIPTS_DIR / script).read_text(encoding="utf-8")
        assert "NOT imputed" in source or "np.isfinite" in source, (
            "%s consumes NaN without explaining why" % script
        )


class TestModelRegistry:
    """Guard the issue #8 root cause: a transformers repo in a timm registry."""

    def test_no_known_transformers_repos(self):
        source = (SCRIPTS_DIR / "model_utils.py").read_text(encoding="utf-8")
        tree = ast.parse(source)
        registry = None
        for node in ast.walk(tree):
            if isinstance(node, ast.Assign) and any(
                getattr(t, "id", None) == "FOUNDATION_MODELS" for t in node.targets
            ):
                registry = ast.literal_eval(node.value)
        assert registry, "FOUNDATION_MODELS not found"

        # facebook/dinov2-large and kaiko-ai/midnight are transformers repos:
        # their config.json has "architectures", not "architecture", so
        # timm.create_model raises KeyError: 'architecture'.
        for name, (timm_id, _dim, _lic) in registry.items():
            assert "facebook/dinov2-large" not in timm_id, name
            assert "kaiko-ai/midnight" not in timm_id, name

    def test_extract_features_translates_the_keyerror(self):
        source = (SCRIPTS_DIR / "extract_features.py").read_text(encoding="utf-8")
        assert "except KeyError" in source
        assert "not loadable by timm" in source


def warnings_as_errors():
    """Turn RuntimeWarning into an error, so an unsuppressed all-NaN nanmedian fails."""
    ctx = warnings.catch_warnings()
    ctx.__enter__()
    warnings.simplefilter("error", RuntimeWarning)

    class _Guard:
        def __enter__(self):
            return self

        def __exit__(self, *exc):
            ctx.__exit__(*exc)
            return False

    return _Guard()
