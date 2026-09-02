"""Validation of externally-supplied cluster labels.

`algorithm="existing"` analyses the classifications already on the objects rather
than computing new ones, so the labels arrive from Java instead of from an
algorithm in this script. Everything downstream was written against algorithm
output, and three of its assumptions are load-bearing -- length, non-negativity,
and a dense 0..k-1 range. This refuses at the door rather than failing deep in
the analysis after the expensive work is done.
"""

import numpy as np
import pytest
from conftest import load_script_symbol

validate = load_script_symbol("run_clustering.py", "validate_supplied_labels", {"np": np})


def test_dense_labels_pass_and_come_back_int32():
    out = validate(np.array([0, 1, 2, 1, 0]), 5)
    assert out.dtype == np.int32
    assert out.tolist() == [0, 1, 2, 1, 0]


def test_a_single_class_is_allowed():
    # Analysing one class is legitimate; the script's own "only ONE cluster"
    # warning covers whether it was a good idea.
    assert validate(np.zeros(4, dtype=int), 4).tolist() == [0, 0, 0, 0]


def test_missing_input_names_itself():
    with pytest.raises(ValueError, match="supplied_labels"):
        validate(None, 10)


def test_length_mismatch_reports_both_numbers():
    with pytest.raises(ValueError) as e:
        validate(np.array([0, 1, 0]), 7)
    assert "3" in str(e.value) and "7" in str(e.value)


def test_empty_is_refused():
    with pytest.raises(ValueError, match="empty"):
        validate(np.array([], dtype=int), 0)


def test_negative_labels_are_refused_because_negative_means_noise():
    with pytest.raises(ValueError) as e:
        validate(np.array([0, 1, -1]), 3)
    assert "-1" in str(e.value)


def test_a_gap_is_refused_and_names_the_gap():
    # {0, 2, 5}: n_clusters_found would be 6 while cluster_means has 3 rows, and
    # the representative-cells loop would index cluster_means[5] -> IndexError.
    with pytest.raises(ValueError) as e:
        validate(np.array([0, 2, 5]), 3)
    msg = str(e.value)
    assert "dense" in msg
    assert "1" in msg and "3" in msg and "4" in msg  # the unused indices


def test_the_refused_gap_is_exactly_what_breaks_cluster_means_alignment():
    """Tie the refusal to the reason, so the check cannot be relaxed by accident.

    cluster_means is a groupby over values PRESENT; n_clusters_found is max + 1.
    For a gapped set those disagree, and the disagreement is silent until an
    IndexError deep in the run. Assert the arithmetic here so the validator's
    rule and its justification stay attached.
    """
    labels = np.array([0, 2, 5])
    n_clusters_found = int(labels.max() + 1)          # 6, as the script computes
    n_mean_rows = int(np.unique(labels).size)         # 3, what groupby produces
    assert n_clusters_found != n_mean_rows
    with pytest.raises(ValueError):
        validate(labels, labels.size)

    dense = np.array([0, 1, 2])
    assert int(dense.max() + 1) == int(np.unique(dense).size)
    validate(dense, dense.size)  # must not raise
