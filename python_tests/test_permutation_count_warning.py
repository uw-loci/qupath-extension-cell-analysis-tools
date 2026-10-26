"""spatial_stats.permutation_count_warning: a chosen count too small to report is flagged."""

from conftest import load_script_symbol

warn = load_script_symbol(
    "spatial_stats.py", "permutation_count_warning", {"FEW_PERMUTATIONS": 100}
)


def test_the_adaptive_default_is_never_flagged():
    assert warn(50, chosen=False) is None


def test_a_count_that_cannot_reach_005_says_so():
    note = warn(10, chosen=True)
    assert "none can reach 0.05" in note and "0.091" in note


def test_nineteen_still_cannot_reach_005():
    assert "none can reach 0.05" in warn(19, chosen=True)


def test_a_small_but_usable_count_names_its_floor():
    note = warn(50, chosen=True)
    assert "0.020" in note and "none can reach" not in note


def test_enough_permutations_pass():
    assert warn(100, chosen=True) is None
    assert warn(1000, chosen=True) is None
