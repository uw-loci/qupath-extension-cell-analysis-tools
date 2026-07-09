"""Harmony batch-correction orientation tests for run_clustering.py.

Regression coverage for issue #10 (reported by @koopa31): with harmonypy 0.2.0
the script transposed an already cells-by-markers matrix, so every multi-image
run with batch correction enabled died inside the pandas constructor with
"Shape of passed values is (n_markers, n_cells), indices imply ...".

Two layers here:

* ``TestOrientBatchCorrected`` exercises the shipped helper directly and needs
  only numpy.
* ``TestHarmonypyContract`` pins the *dependency's* behaviour -- it is the test
  that would have caught the original break, because the bug was a silent
  orientation change in harmonypy's public ``Z_corr`` property, not in our
  code. It skips when harmonypy is absent.
"""

import numpy as np
import pytest
from conftest import SCRIPTS_DIR, load_script_symbol, requires

N_CELLS = 500
N_MARKERS = 13  # matches the marker count in the issue report


@pytest.fixture(scope="module")
def orient():
    return load_script_symbol(
        "run_clustering.py", "_orient_batch_corrected", {"np": np}
    )


class TestOrientBatchCorrected:
    def test_passes_through_cells_by_markers(self, orient):
        arr = np.zeros((N_CELLS, N_MARKERS))
        out = orient(arr, (N_CELLS, N_MARKERS))
        assert out.shape == (N_CELLS, N_MARKERS)

    def test_transposes_markers_by_cells(self, orient):
        arr = np.zeros((N_MARKERS, N_CELLS))
        out = orient(arr, (N_CELLS, N_MARKERS))
        assert out.shape == (N_CELLS, N_MARKERS)

    def test_transpose_preserves_values(self, orient):
        arr = np.arange(N_MARKERS * N_CELLS).reshape(N_MARKERS, N_CELLS)
        out = orient(arr, (N_CELLS, N_MARKERS))
        np.testing.assert_array_equal(out, arr.T)

    def test_square_input_is_not_transposed(self, orient):
        # Ambiguous shape: prefer the orientation harmonypy >=0.2.0 returns
        # rather than silently flipping the feature matrix.
        arr = np.arange(9).reshape(3, 3)
        out = orient(arr, (3, 3))
        np.testing.assert_array_equal(out, arr)

    def test_incompatible_shape_raises(self, orient):
        arr = np.zeros((7, 5))
        with pytest.raises(ValueError, match="expected .* or its transpose"):
            orient(arr, (N_CELLS, N_MARKERS))

    def test_accepts_non_array_input(self, orient):
        out = orient([[1.0, 2.0], [3.0, 4.0], [5.0, 6.0]], (3, 2))
        assert out.shape == (3, 2)


class TestCallSite:
    """The helper is orientation-tolerant, so a bare ``.T`` at the call site
    would still produce correct output for every non-square matrix and only
    corrupt the pathological ``n_cells == n_markers`` case. Guard the source
    directly rather than relying on shape assertions to notice.
    """

    def test_harmony_block_does_not_transpose_z_corr(self):
        source = (SCRIPTS_DIR / "run_clustering.py").read_text(encoding="utf-8")
        assert "ho.Z_corr.T" not in source
        assert "_orient_batch_corrected(ho.Z_corr, df_norm.shape)" in source


@requires("harmonypy")
@requires("pandas")
class TestHarmonypyContract:
    """Guard against a future harmonypy release flipping Z_corr again."""

    @pytest.fixture(scope="class")
    def harmony_result(self):
        import harmonypy as hm
        import pandas as pd

        rng = np.random.default_rng(0)
        data = rng.normal(size=(N_CELLS, N_MARKERS))
        batch = ["a"] * (N_CELLS // 2) + ["b"] * (N_CELLS - N_CELLS // 2)
        data[N_CELLS // 2 :] += 3.0  # a batch effect worth correcting

        meta_df = pd.DataFrame({"batch": batch})
        ho = hm.run_harmony(data, meta_df, "batch", verbose=False)
        return data, np.asarray(batch), ho

    def test_z_corr_is_cells_by_markers(self, harmony_result):
        data, _, ho = harmony_result
        assert ho.Z_corr.shape == data.shape

    def test_orient_is_a_no_op_on_current_harmonypy(self, orient, harmony_result):
        data, _, ho = harmony_result
        np.testing.assert_array_equal(
            orient(ho.Z_corr, data.shape), np.asarray(ho.Z_corr)
        )

    def test_rows_stay_aligned_with_batch_labels(self, orient, harmony_result):
        """The real failure mode a shape check alone would not catch.

        If rows and columns were swapped, per-batch row means would be
        meaningless. Correction must shrink the gap between batch means.
        """
        data, batch, ho = harmony_result
        corrected = orient(ho.Z_corr, data.shape)

        def batch_gap(matrix):
            mask = batch == "a"
            return np.abs(matrix[mask].mean(axis=0) - matrix[~mask].mean(axis=0)).mean()

        assert batch_gap(corrected) < batch_gap(data)

    def test_frame_construction_matches_the_script(self, orient, harmony_result):
        """End-to-end reproduction of the issue #10 crash site."""
        import pandas as pd

        data, _, ho = harmony_result
        columns = ["M%d" % i for i in range(N_MARKERS)]
        df_norm = pd.DataFrame(data, columns=columns)

        corrected = orient(ho.Z_corr, df_norm.shape)
        out = pd.DataFrame(corrected, columns=df_norm.columns, index=df_norm.index)

        assert out.shape == df_norm.shape
        assert list(out.columns) == columns
        assert out.index.equals(df_norm.index)

        # The pre-fix expression raised ValueError here.
        with pytest.raises(ValueError):
            pd.DataFrame(np.asarray(ho.Z_corr).T, columns=df_norm.columns)
