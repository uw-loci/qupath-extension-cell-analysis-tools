"""Co-occurrence: one radius per bin, and which bin the matrix is drawn at.

squidpy returns one fewer bin than it is given interval points
(``_co_occurrence_helper`` sets ``l_val = len(interval) - 1`` and squares
``interval[1:]`` for its thresholds), so bin ``i`` is the disc of radius
``interval[i+1]``. Storing the raw edges labelled every table row and CSV line
with the radius of the bin before it and wrote a trailing all-NaN row.
"""

import pytest

from conftest import load_script_symbol

SCRIPT = "spatial_stats.py"


@pytest.fixture(scope="module")
def pick_radius_bin():
    return load_script_symbol(SCRIPT, "pick_radius_bin")


@pytest.fixture(scope="module")
def radius_caption():
    return load_script_symbol(SCRIPT, "radius_caption")


def test_requested_radius_picks_the_nearest_bin(pick_radius_bin):
    radii = [10.0, 20.0, 30.0, 40.0, 50.0]
    assert pick_radius_bin(radii, 31.0, 7.0) == (2, 30.0)
    assert pick_radius_bin(radii, 10.0, 7.0) == (0, 10.0)
    # Out of range clamps to the nearest end rather than failing.
    assert pick_radius_bin(radii, 1000.0, 7.0) == (4, 50.0)
    assert pick_radius_bin(radii, 0.5, None) == (0, 10.0)


def test_the_default_is_short_range_not_the_middle(pick_radius_bin):
    # Five median nearest-neighbour distances: the near end of the profile,
    # where the ratio still discriminates. A midpoint default would sit where
    # cumulative discs have already decayed toward 1.0.
    radii = [10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0]
    idx, r = pick_radius_bin(radii, -1, 6.0)
    assert r == 30.0
    assert idx == 2


def test_without_a_cell_scale_it_falls_back_to_the_middle(pick_radius_bin):
    radii = [10.0, 20.0, 30.0, 40.0, 50.0]
    assert pick_radius_bin(radii, -1, None) == (2, 30.0)
    assert pick_radius_bin(radii, -1, 0.0) == (2, 30.0)


def test_no_bins_is_reported_not_guessed(pick_radius_bin):
    assert pick_radius_bin([], -1, 5.0) == (None, None)
    assert pick_radius_bin(None, 10.0, 5.0) == (None, None)


def test_caption_counts_bins_not_edges(radius_caption):
    # 49 bin radii -> "49 bins", and the low bound is the first BIN, which is
    # interval[1]: the first edge is not a bin radius at all.
    radii = [float(10 + i) for i in range(49)]
    caption = radius_caption(radii, "um")
    assert "49 bins" in caption
    assert "10.0 to 58.0 um" in caption


def test_caption_survives_no_data(radius_caption):
    assert radius_caption([], "um") == "radius bins unavailable"
    assert radius_caption(None, "px") == "radius bins unavailable"
