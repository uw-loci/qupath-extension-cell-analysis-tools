"""A neighbour count is checked before scikit-learn sees it.

The reported failure was an editable JavaFX Spinner committing 0 -- its min/max
constrain the arrows, not typed text -- and the run then died with

    InvalidParameterError: The 'n_neighbors' parameter of KNeighborsTransformer
    must be an int in the range [1, inf) or None. Got 0 instead.

which names neither the control nor the dialog, and arrives only after the whole
measurement extraction has been paid for.
"""

import numpy as np
import pytest
from conftest import load_script_symbol


class _Log:
    def warning(self, *a, **k):
        pass


validate = load_script_symbol(
    "run_clustering.py", "validate_n_neighbors", {"np": np, "logger": _Log()}
)


def test_a_sensible_request_passes_through():
    assert validate(15, 10_000) == 15


def test_zero_is_refused_and_says_where_to_look():
    with pytest.raises(ValueError) as e:
        validate(0, 10_000)
    assert "n_neighbors" in str(e.value)
    assert "Clustering Algorithm" in str(e.value)


def test_negative_is_refused():
    with pytest.raises(ValueError, match="at least 1"):
        validate(-5, 10_000)


def test_too_few_cells_names_the_selection_as_the_likely_cause():
    # The single-selected-cell case: the run silently narrows to the selection.
    with pytest.raises(ValueError) as e:
        validate(15, 1)
    assert "selected" in str(e.value)
    assert "clear the selection" in str(e.value).lower()


def test_more_neighbours_than_cells_is_reduced_not_refused():
    # The user asked for a graph; the biggest one 8 cells can support is still
    # a graph. scanpy counts each cell as its own first neighbour, so n-1.
    assert validate(50, 8) == 7


def test_the_boundary_is_not_off_by_one():
    assert validate(9, 10) == 9  # exactly n-1 is allowed
    assert validate(10, 10) == 9  # n is not


def test_non_numeric_is_refused_rather_than_crashing_later():
    with pytest.raises(ValueError, match="whole number"):
        validate("fifty", 1000)
