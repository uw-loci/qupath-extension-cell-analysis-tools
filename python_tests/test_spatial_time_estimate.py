"""Extrapolation of the spatial-statistics run-time probe.

The estimate decides whether the user is warned before a long run, so an
UNDER-estimate is the dangerous direction. On one 132k-cell run the probes were
(100, 17.3s), (1000, 5.6s), (2000, 6.5s) -- the smallest was the slowest,
because the first probe pays imports and numba JIT. A plain log-log fit through
those points has a negative exponent ("bigger is faster") and extrapolated to
1.18 s; the dialog logged "running without prompting" and the spatial statistics
then ran for about 11 minutes.
"""

import logging

import numpy as np
import pytest
from conftest import load_script_symbol


@pytest.fixture(scope="module")
def extrapolate():
    return load_script_symbol(
        "estimate_spatial_time.py",
        "extrapolate_seconds",
        {"np": np, "logger": logging.getLogger("test.estimate")},
    )


def test_the_warm_up_point_no_longer_inverts_the_fit(extrapolate):
    est = extrapolate([(100, 17.261), (1000, 5.586), (2000, 6.547)], 132109)
    assert est is not None
    # Must never come back below the slowest probe already measured.
    assert est > 17.261
    # And must not be the ~1 second that suppressed the warning.
    assert est > 60


def test_a_clean_superlinear_probe_is_fitted(extrapolate):
    # time ~ n^1.5 with no warm-up distortion; 8000 cells is 2x the largest probe.
    est = extrapolate([(1000, 1.0), (2000, 2.83), (4000, 8.0)], 8000)
    assert est == pytest.approx(22.6, rel=0.2)


def test_a_fit_implying_bigger_is_faster_falls_back_to_linear(extrapolate):
    est = extrapolate([(1000, 10.0), (2000, 5.0)], 10000)
    # Linear from the largest probe: 5 s at 2000 -> 25 s at 10000.
    assert est == pytest.approx(25.0, rel=0.01)


def test_never_reports_less_than_the_slowest_probe(extrapolate):
    assert extrapolate([(1000, 30.0), (2000, 31.0)], 2000) >= 31.0


def test_a_single_point_scales_linearly(extrapolate):
    assert extrapolate([(1000, 2.0)], 10000) == pytest.approx(20.0)


def test_no_usable_points(extrapolate):
    assert extrapolate([], 1000) is None
    assert extrapolate([(0, 0.0)], 1000) is None
