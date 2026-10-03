"""The colour map a figure uses is chosen, but only within the right family.

A diverging map promises that its pale midpoint means something. Co-occurrence is
a ratio with a null of 1.0, so when the data straddles 1.0 that promise is real
and a diverging map is correct; when the data never crosses it, the midpoint
would land on an arbitrary value. The FAMILY therefore follows the data and only
the map WITHIN a family is the caller's choice -- these tests hold that line,
because handing the caller the family is exactly how a false statement gets made
in colour.
"""

import numpy as np
import pytest

from conftest import load_script_symbol


@pytest.fixture
def ratio_colour_scale():
    return load_script_symbol("spatial_stats.py", "ratio_colour_scale")


def test_data_straddling_the_null_gets_the_diverging_map(ratio_colour_scale):
    name, norm = ratio_colour_scale(np.array([0.5, 1.0, 2.0]))
    assert name == "RdBu_r"
    assert norm is not None
    assert norm.vcenter == 1.0


def test_data_entirely_above_the_null_gets_the_sequential_map(ratio_colour_scale):
    # TwoSlopeNorm requires vmin < vcenter < vmax, and more importantly there is
    # no midpoint to diverge about.
    name, norm = ratio_colour_scale(np.array([1.5, 2.0, 3.0]))
    assert name == "viridis"
    assert norm is None


def test_data_entirely_below_the_null_gets_the_sequential_map(ratio_colour_scale):
    name, norm = ratio_colour_scale(np.array([0.1, 0.4, 0.9]))
    assert name == "viridis"
    assert norm is None


def test_the_caller_chooses_the_map_within_each_family(ratio_colour_scale):
    straddles = np.array([0.5, 1.0, 2.0])
    one_sided = np.array([1.5, 2.0, 3.0])

    name, norm = ratio_colour_scale(
        straddles, diverging_cmap="PuOr_r", sequential_cmap="magma"
    )
    assert name == "PuOr_r"
    assert norm is not None

    name, norm = ratio_colour_scale(
        one_sided, diverging_cmap="PuOr_r", sequential_cmap="magma"
    )
    assert name == "magma"
    assert norm is None


def test_the_caller_cannot_force_a_diverging_map_onto_one_sided_data(ratio_colour_scale):
    # The whole point. There is no parameter that overrides the family.
    name, _ = ratio_colour_scale(
        np.array([1.5, 2.0, 3.0]), diverging_cmap="RdBu_r", sequential_cmap="viridis"
    )
    assert name != "RdBu_r"


def test_a_none_choice_falls_back_to_the_historical_default(ratio_colour_scale):
    # The Java side omits the input when the chosen map has no matplotlib
    # equivalent, and a stored empty string must behave the same way.
    assert ratio_colour_scale(
        np.array([0.5, 1.0, 2.0]), diverging_cmap=None
    )[0] == "RdBu_r"
    assert ratio_colour_scale(
        np.array([1.5, 2.0]), sequential_cmap=None
    )[0] == "viridis"
    assert ratio_colour_scale(
        np.array([1.5, 2.0]), sequential_cmap=""
    )[0] == "viridis"


def test_all_non_finite_data_still_returns_a_usable_map(ratio_colour_scale):
    name, norm = ratio_colour_scale(np.array([np.nan, np.inf]))
    assert name == "viridis"
    assert norm is None


def test_a_custom_null_is_honoured(ratio_colour_scale):
    # Not every ratio has a null of 1.0; the centre is a parameter.
    name, norm = ratio_colour_scale(np.array([-1.0, 0.0, 1.0]), center=0.0)
    assert name == "RdBu_r"
    assert norm.vcenter == 0.0
