"""spatial_stats.permutation_count_warning: a chosen count too small to report is flagged."""

from conftest import load_script_symbol

warn = load_script_symbol(
    "spatial_stats.py",
    "permutation_count_warning",
    {
        "FEW_PERMUTATIONS": 100,
        "PERMUTATION_PREFERENCE": "Edit > Preferences > QP-CAT: Run Clustering > Spatial Stats Permutations",
    },
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


def test_the_note_names_the_preference_by_default():
    assert (
        "Edit > Preferences > QP-CAT: Run Clustering > Spatial Stats Permutations"
        in warn(50, chosen=True)
    )


def test_the_note_names_the_setting_the_count_came_from():
    for n in (10, 50):
        note = warn(n, chosen=True, setting="Permutations (0 = adaptive) in the dialog")
        assert "Permutations (0 = adaptive) in the dialog" in note
        assert "Preferences" not in note
