"""run_clustering._harmony_device: Harmony leaves the GPU when it would not fit."""

from conftest import load_script_symbol

pick = load_script_symbol(
    "run_clustering.py",
    "_harmony_device",
    {"HARMONY_GPU_MATRICES": 7, "HARMONY_GPU_HEADROOM": 0.8},
)
GIB = 1024**3


def test_no_gpu_runs_on_cpu_silently():
    assert pick(5_000_000, None) == ("cpu", None)


def test_a_run_that_fits_uses_the_gpu():
    # 2,000,000 cells measured at 5.18 GiB peak.
    assert pick(2_000_000, 20 * GIB) == ("cuda", None)


def test_a_run_that_does_not_fit_falls_back_with_a_note():
    device, note = pick(4_879_263, 8 * GIB)
    assert device == "cpu"
    assert "12.7 GiB" in note and "8.0 GiB free" in note
