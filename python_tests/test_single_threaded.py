"""model_utils.single_threaded: one thread inside, the old counts back outside.

choose_k.py used to call threadpool_limits(1) at module level, which left the whole
worker single-threaded for every later clustering run (threadpoolctl restores only
on __exit__). The scoped context is what both choose_k and compute_thresholds use now.
"""

import contextlib

import numpy  # noqa: F401 - loads a BLAS so there is a pool to limit
import pytest

from conftest import load_script_symbol

threadpoolctl = pytest.importorskip("threadpoolctl")

single_threaded = load_script_symbol(
    "model_utils.py", "single_threaded", {"contextlib": contextlib}
)


def _counts():
    return [p["num_threads"] for p in threadpoolctl.threadpool_info()]


def test_one_thread_inside_and_restored_after():
    before = _counts()
    if not before:
        pytest.skip("no BLAS or OpenMP library loaded in this interpreter")
    with single_threaded():
        assert all(n == 1 for n in _counts())
    assert _counts() == before


def test_restored_when_the_body_raises():
    before = _counts()
    with pytest.raises(RuntimeError):
        with single_threaded():
            raise RuntimeError("boom")
    assert _counts() == before
