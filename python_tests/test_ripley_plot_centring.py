"""The exported Ripley PNG must draw the same null as the in-app chart.

Until 0.14.11 it did not. The chart plotted each curve relative to that
cluster's own simulated-CSR median; the PNG plotted the raw curve against the
analytical Poisson diagonal. So a figure exported for a paper disagreed with the
screen it was exported from -- and the diagonal is the weaker of the two
references, assuming an unbounded plane with no edge correction, which is why
the simulated envelope was added in the first place.

The centring is one subtraction, but it lives inside a broad try/except whose
failure mode is a silently missing PNG, so it is worth pinning on its own.
"""

import numpy as np
from conftest import load_script_symbol

centre = load_script_symbol("spatial_stats.py", "centre_on_median", {"np": np})


def test_a_curve_on_its_median_is_flat_zero():
    # A cluster indistinguishable from random must sit ON the zero line, which
    # is the whole reading the centred plot offers.
    med = [0.0, 1.0, 2.5, 4.0]
    assert centre(list(med), med) == [0.0, 0.0, 0.0, 0.0]


def test_clustering_reads_positive_and_dispersion_negative():
    med = [1.0, 2.0, 3.0]
    assert centre([1.5, 3.0, 5.0], med) == [0.5, 1.0, 2.0]
    assert centre([0.5, 1.0, 1.0], med) == [-0.5, -1.0, -2.0]


def test_the_diagonal_is_removed_not_merely_offset():
    # L(r) is dominated by r itself. Centring has to take that slope out, or
    # the exported figure has the same unreadability the chart fix addressed:
    # every curve climbing together with the signal a few percent of the height.
    radii = np.linspace(0, 1000, 50)
    median = list(radii * 0.7)  # a null that tracks r
    observed = [m + 3.0 for m in median]  # constant excess over it
    out = centre(observed, median)
    assert max(out) - min(out) < 1e-9
    assert abs(out[0] - 3.0) < 1e-9


def test_a_ragged_pair_truncates_instead_of_raising():
    # A partial envelope should cost the tail of the curve, not the whole plot.
    assert centre([1.0, 2.0, 3.0], [0.5, 1.0]) == [0.5, 1.0]
    assert centre([1.0, 2.0], [0.5, 1.0, 9.9]) == [0.5, 1.0]


def test_empty_input_is_not_an_error():
    assert centre([], []) == []
    assert centre([1.0], []) == []
