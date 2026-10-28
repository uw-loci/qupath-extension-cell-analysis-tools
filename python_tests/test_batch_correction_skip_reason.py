"""run_clustering._batch_correction_skip_reason: a requested Harmony run with
one batch is reported instead of silently clustering uncorrected values."""

from conftest import load_script_symbol

skip = load_script_symbol("run_clustering.py", "_batch_correction_skip_reason")


def test_not_requested_is_silent():
    assert skip(False, None) is None
    assert skip(False, [0, 1]) is None


def test_no_labels_is_reported():
    assert "NOT applied" in skip(True, None)


def test_one_batch_is_reported():
    assert "NOT applied" in skip(True, [0, 0, 0])


def test_two_batches_run():
    assert skip(True, [0, 1, 1]) is None
