"""The Python half of the scale guard, and its agreement with the Java half.

Ripley's L and co-occurrence are gated in Python rather than Java because their
cost depends on the cluster labels, which do not exist until clustering has run.
That means the SAME cost model is written twice, in two languages -- so the real
risk is not that one is wrong, but that they drift apart. These tests pin the
Python side to the same measurements ScalingLimitsTest.java pins the Java side
to, and assert the two agree numerically.

Measurements (QP-CAT Appose env, 16 cores, 20 features, 20 clusters, peak RSS
from /proc, 6 GB cap):

    co-occurrence   20,000 cells -> 2.27 GB    50,000 -> 4.47 GB   100,000 -> OOM
    Ripley L       100,000 cells -> 1.50 GB   250,000 -> 5.19 GB
"""

import numpy as np
import pytest

from conftest import load_script_symbol

TOL = 0.35  # GB -- order-of-magnitude fidelity is what matters, not decimals


# load_script_symbol execs only the function def, so module-level constants have
# to be handed in. Injecting the SHIPPED value (not a literal) keeps the tests
# honest if it is ever retuned.
_BASE_GB = 0.2


@pytest.fixture(scope="module")
def cooc_peak():
    return load_script_symbol(
        "spatial_stats.py",
        "co_occurrence_peak_gb",
        extra_globals={"_BASE_GB": _BASE_GB},
    )


@pytest.fixture(scope="module")
def ripley_peak():
    return load_script_symbol(
        "spatial_stats.py", "ripley_peak_gb", extra_globals={"_BASE_GB": _BASE_GB}
    )


def test_injected_base_matches_the_shipped_constant():
    """The fixtures inject _BASE_GB; make sure that value is the real one."""
    from conftest import SCRIPTS_DIR

    src = (SCRIPTS_DIR / "spatial_stats.py").read_text(encoding="utf-8")
    assert "_BASE_GB = %s" % _BASE_GB in src


@pytest.fixture(scope="module")
def cluster_sizes():
    return load_script_symbol(
        "spatial_stats.py", "_cluster_sizes", extra_globals={"np": np}
    )


# ---- Calibration ----------------------------------------------------------


def test_co_occurrence_matches_measured_peaks(cooc_peak):
    assert cooc_peak(20_000, 20, 50) == pytest.approx(2.27, abs=TOL)
    assert cooc_peak(50_000, 20, 50) == pytest.approx(4.47, abs=TOL)


def test_co_occurrence_is_quadratic_in_cluster_count(cooc_peak):
    # The trap: a config that fit at 10 clusters needs 4x as much at 20.
    base = 0.95
    k10 = cooc_peak(50_000, 10, 50) - base
    k20 = cooc_peak(50_000, 20, 50) - base
    assert k20 / k10 == pytest.approx(4.0, rel=0.01)


def test_ripley_matches_measured_peaks(ripley_peak):
    assert ripley_peak(5_100) == pytest.approx(1.50, abs=TOL)
    assert ripley_peak(12_800) == pytest.approx(5.19, abs=TOL)


def test_ripley_is_driven_by_the_largest_cluster(ripley_peak):
    # 1M cells split 20 ways is survivable; 1M cells with one 400k cluster is
    # not, and the difference is ~64x. A model keyed on total N would miss this
    # entirely -- which is the reason this check lives in Python at all.
    balanced = ripley_peak(50_000)
    skewed = ripley_peak(400_000)
    assert skewed > balanced * 60


# ---- Agreement with the Java model ----------------------------------------


def test_python_and_java_models_agree(cooc_peak, ripley_peak):
    """Same formulas as ScalingLimits.java. If someone retunes one side only,
    this is the test that says so."""
    # ScalingLimits.coOccurrencePeakGb: BASE 0.2 + 0.75 + n*l*k^2*4 / 2^30
    for n, k, ell in [
        (20_000, 20, 50),
        (50_000, 20, 50),
        (1_000_000, 20, 50),
        (100_000, 40, 25),
    ]:
        java = 0.2 + 0.75 + (n * ell * k * k * 4.0) / 1024.0**3
        assert cooc_peak(n, k, ell) == pytest.approx(java, rel=1e-9)

    # ScalingLimits.ripleyPeakGb: BASE 0.2 + 0.6 + 2.68e-8 * m^2
    for m in [5_000, 12_800, 50_000, 400_000]:
        java = 0.2 + 0.6 + 2.68e-8 * m * m
        assert ripley_peak(m) == pytest.approx(java, rel=1e-9)


# ---- The label-derived inputs ---------------------------------------------


def test_cluster_sizes_reports_count_and_largest(cluster_sizes):
    class FakeAdata:
        obs = {"cluster": np.array([0, 0, 0, 1, 1, 2])}

    n_clusters, largest = cluster_sizes(FakeAdata(), "cluster")
    assert n_clusters == 3
    assert largest == 3


def test_cluster_sizes_survives_a_missing_key(cluster_sizes):
    # Guard code must never be the thing that breaks the run it is protecting.
    class FakeAdata:
        obs = {}

    assert cluster_sizes(FakeAdata(), "cluster") == (0, 0)


def test_cluster_sizes_handles_empty_labels(cluster_sizes):
    class FakeAdata:
        obs = {"cluster": np.array([])}

    assert cluster_sizes(FakeAdata(), "cluster") == (0, 0)


def test_cluster_sizes_handles_string_labels(cluster_sizes):
    # Labels arrive as pandas Categorical of str in the real pipeline.
    class FakeAdata:
        obs = {"cluster": np.array(["a", "b", "b", "b"])}

    assert cluster_sizes(FakeAdata(), "cluster") == (2, 3)


# ---- The decision ---------------------------------------------------------


def test_refusal_is_relative_to_the_machine():
    refuse = load_script_symbol(
        "spatial_stats.py",
        "_refuse_if_too_big",
        extra_globals={
            "logger": _NullLogger(),
            "_total_ram_gb": lambda: 16.0,
            "_BLOCK_RAM_FRACTION": 0.85,
        },
    )
    assert refuse("x", 2.0, "remedy") is False  # comfortably under
    assert refuse("x", 9.0, "remedy") is False  # warns, but proceeds
    assert refuse("x", 20.0, "remedy") is True  # over 85% of 16 GB


def test_a_big_machine_admits_what_a_laptop_refuses():
    for ram, expect in [(16.0, True), (512.0, False)]:
        refuse = load_script_symbol(
            "spatial_stats.py",
            "_refuse_if_too_big",
            extra_globals={
                "logger": _NullLogger(),
                "_total_ram_gb": lambda: ram,
                "_BLOCK_RAM_FRACTION": 0.85,
            },
        )
        assert refuse("co-occurrence", 75.0, "remedy") is expect


def test_total_ram_is_plausible():
    total = load_script_symbol(
        "spatial_stats.py", "_total_ram_gb", extra_globals={"os": __import__("os")}
    )
    assert total() > 0.5


class _NullLogger:
    def warning(self, *a, **k):
        pass

    def info(self, *a, **k):
        pass
