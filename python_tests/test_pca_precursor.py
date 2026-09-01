"""Tests for the PCA-precursor gating helper in run_clustering.py.

The precursor reduces a high-feature matrix to principal components before the
embedding + clustering step (the canonical scanpy flow). It is driven by a
per-run checkbox and only actually engages when the feature count exceeds the
target component count. That decision is a pure top-level function, so these
exercise the real shipped code through the AST loader.
"""

from conftest import load_script_symbol

resolve = load_script_symbol("run_clustering.py", "resolve_pca_precursor")


def test_enabled_engages_when_features_exceed_components():
    # 442 features (2 markers x 34 compartments style), 50 components -> reduce.
    assert resolve(True, 442, 50, "leiden") is True


def test_enabled_skips_when_features_at_or_below_components():
    # Nothing worth reducing: the target dimensionality is the engage floor.
    assert resolve(True, 50, 50, "leiden") is False
    assert resolve(True, 12, 50, "kmeans") is False


def test_disabled_never_engages_even_when_huge():
    # Checkbox unticked, or a config that predates the option and so records
    # no choice at all -- both arrive here as False.
    assert resolve(False, 442, 50, "leiden") is False


def test_banksy_is_always_exempt():
    # BANKSY runs its own PCA over spatially-augmented features, so a generic
    # precursor would corrupt that neighbourhood construction.
    assert resolve(True, 442, 50, "banksy") is False
    assert resolve(False, 442, 50, "banksy") is False


def test_too_few_features_never_reduces():
    assert resolve(True, 2, 1, "leiden") is False


def test_custom_component_count_moves_the_floor():
    # The component count doubles as the threshold, so lowering it engages sooner.
    assert resolve(True, 30, 20, "kmeans") is True
    assert resolve(True, 30, 30, "kmeans") is False
