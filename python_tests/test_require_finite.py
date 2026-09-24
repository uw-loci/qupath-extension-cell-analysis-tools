"""The named finiteness check in run_clustering.

A NaN introduced by an intermediate step used to travel silently until the
embedding, where sklearn reported "ValueError: Input contains NaN" from inside
umap -- naming neither the step nor the column, and leaving the user to guess
which option to switch off.
"""

import numpy as np
import pytest
from conftest import load_script_symbol


@pytest.fixture(scope="module")
def require_finite():
    return load_script_symbol("run_clustering.py", "require_finite", {"np": np})


def test_finite_input_passes_quietly(require_finite):
    require_finite(np.arange(12, dtype=float).reshape(4, 3), "Normalization", ["a", "b", "c"])


def test_the_message_names_the_step_and_the_column(require_finite):
    arr = np.ones((4, 3))
    arr[2, 1] = np.nan
    with pytest.raises(ValueError) as err:
        require_finite(arr, "Harmony batch correction", ["CD3", "CD8", "CD20"])
    msg = str(err.value)
    assert "Harmony batch correction" in msg
    assert "CD8" in msg
    assert "CD3" not in msg


def test_infinity_counts_too(require_finite):
    arr = np.ones((2, 2))
    arr[0, 0] = np.inf
    with pytest.raises(ValueError):
        require_finite(arr, "Spatial feature smoothing", ["x", "y"])


def test_many_bad_columns_are_summarised(require_finite):
    arr = np.full((3, 10), np.nan)
    names = ["m%d" % i for i in range(10)]
    with pytest.raises(ValueError) as err:
        require_finite(arr, "Normalization", names)
    assert "and 4 more" in str(err.value)


def test_it_works_without_column_names(require_finite):
    arr = np.ones((2, 2))
    arr[1, 1] = np.nan
    with pytest.raises(ValueError) as err:
        require_finite(arr, "Normalization")
    assert "1" in str(err.value)
