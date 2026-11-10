"""system_info.thread_pool_lines: what System Info says about thread pools (issue #24)."""

from conftest import load_script_symbol

VARS = (
    "OMP_NUM_THREADS",
    "MKL_NUM_THREADS",
    "OPENBLAS_NUM_THREADS",
    "NUMBA_NUM_THREADS",
)
thread_pool_lines = load_script_symbol(
    "system_info.py", "thread_pool_lines", {"THREAD_ENV_VARS": VARS}
)


def test_lists_each_pool_and_every_variable():
    pools = [
        {
            "user_api": "openmp",
            "internal_api": "openmp",
            "num_threads": 8,
            "prefix": "libomp",
            "filepath": "/env/lib/libomp.so",
            "version": None,
        },
        {
            "user_api": "blas",
            "internal_api": "mkl",
            "num_threads": 8,
            "prefix": "libmkl_rt",
            "filepath": "/env/lib/libmkl_rt.so.3",
            "version": "2026.1-Product",
            "threading_layer": "gnu",
        },
    ]
    lines = thread_pool_lines(pools, {"OMP_NUM_THREADS": "1"})
    assert lines[0] == "=== Thread pools (threadpoolctl) ==="
    assert lines[1] == "  openmp: openmp, 8 threads, /env/lib/libomp.so"
    assert lines[2] == "  blas: mkl (gnu), 8 threads, /env/lib/libmkl_rt.so.3"
    assert "  OMP_NUM_THREADS: 1" in lines
    assert "  MKL_NUM_THREADS: (unset)" in lines
    assert sum(
        1 for line in lines if line.startswith("  ") and "_NUM_THREADS" in line
    ) == len(VARS)


def test_missing_and_empty_are_said_plainly():
    assert "  threadpoolctl not installed" in thread_pool_lines(None, {})
    assert "  no BLAS or OpenMP library loaded" in thread_pool_lines([], {})
