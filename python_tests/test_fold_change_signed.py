"""H18: a measurement with negative values gets no log2 fold change, and a note.

The fold change is a ratio of raw means. Negatives used to be clipped to 0, so an
imported z-score or a PCA axis showed log2(eps/eps) = 0 or +-30 with no warning.
"""

import json

import numpy as np
import pytest

from conftest import SCRIPTS_DIR, load_script_symbol, requires
from test_clean_data_bit_identical import fixed_matrix, run_full_clustering

fold_change_input = load_script_symbol(
    "run_clustering.py", "fold_change_input", {"np": np}
)
signed_fold_change_note = load_script_symbol(
    "run_clustering.py", "signed_fold_change_note"
)


def test_signed_columns_are_flagged_and_the_rest_untouched():
    raw = np.array([[1.0, -2.0, 0.0], [3.0, 4.0, 5.0]])
    clipped, signed = fold_change_input(raw)
    assert signed.tolist() == [False, True, False]
    np.testing.assert_array_equal(clipped[:, [0, 2]], raw[:, [0, 2]])


def test_the_note_names_the_measurements():
    note = signed_fold_change_note(
        ["CD3", "PC1", "CD8"], np.array([False, True, False])
    )
    assert "1 measurement(s)" in note and "PC1" in note and "CD3" not in note


@requires("scanpy")
@requires("appose")
def test_a_signed_column_gets_null_fold_changes_in_a_full_run():
    data = fixed_matrix()
    data[:, 2] = data[:, 2] - data[:, 2].mean()  # centered: negative values
    out = run_full_clustering(SCRIPTS_DIR, data, "zscore")
    rankings = json.loads(out["marker_rankings"])
    signed_name = "Cell: M2 mean"
    seen = 0
    for markers in rankings.values():
        for m in markers:
            if m["name"] == signed_name:
                assert m["logfoldchange"] is None
                seen += 1
            else:
                assert m["logfoldchange"] is not None
    assert seen > 0
    warnings = json.loads(out["quality_warnings"])
    assert any("negative values" in w and signed_name in w for w in warnings)
