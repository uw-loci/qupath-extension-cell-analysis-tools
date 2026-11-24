"""H31: standalone spatial statistics report the measurements they cannot test.

Zero-variance columns were dropped with a log line only, before reset_notes()
would have erased any note, and when every selected measurement was dropped the
results said "no measurements were selected to test", which was false.
"""

import json
import sys

import numpy as np

from conftest import SCRIPTS_DIR, load_script_symbol, requires, worker_globals

SCRIPT = SCRIPTS_DIR / "spatial_stats_standalone.py"


class _Task:
    def __init__(self):
        self.outputs = {}

    def update(self, *args, **kwargs):
        pass


class _Nd:
    def __init__(self, array):
        self._array = array

    def ndarray(self):
        return self._array


def run(feats, names, **flags):
    rng = np.random.default_rng(0)
    n = feats.shape[0]
    task = _Task()
    namespace = {
        "task": task,
        "spatial_coords": _Nd(rng.uniform(0, 1000, (n, 2))),
        "cell_type_labels": [int(v) for v in rng.integers(0, 2, n)],
        "class_names": ["A", "B"],
        "feature_matrix": _Nd(feats),
        "marker_names": names,
        "permutations": 20,
        "bold_marker_ticks": lambda ax: None,
        **worker_globals(),
        **flags,
    }
    sys.modules.pop("spatial_stats", None)
    sys.path.insert(0, str(SCRIPTS_DIR))
    try:
        exec(
            compile(SCRIPT.read_text(encoding="utf-8"), str(SCRIPT), "exec"), namespace
        )
    finally:
        sys.path.remove(str(SCRIPTS_DIR))
        sys.modules.pop("spatial_stats", None)
    return task.outputs


def test_drop_untestable_columns_names_what_it_dropped():
    drop = load_script_symbol(
        "spatial_stats_standalone.py", "drop_untestable_columns", {"np": np}
    )
    feats = np.array([[1.0, 5.0, np.nan], [2.0, 5.0, np.nan], [3.0, 5.0, np.nan]])
    kept, names, dropped = drop(feats, ["CD3", "Flat", "Empty"])
    assert names == ["CD3"] and kept.shape == (3, 1)
    assert dropped == ["Flat", "Empty"]
    assert drop(feats[:, 1:], ["Flat", "Empty"])[:2] == (None, None)


@requires("squidpy")
@requires("appose")
def test_a_constant_measurement_is_named_in_the_results():
    rng = np.random.default_rng(1)
    feats = np.column_stack([rng.normal(size=200), np.full(200, 7.0)])
    out = run(feats, ["Cell: CD3 mean", "Cell: Flat mean"], enable_moran=True)
    notes = json.loads(out["spatial_notes"])
    assert any("Cell: Flat mean" in n and "1 measurement(s)" in n for n in notes)
    payload = json.loads(out["spatial_autocorr"])
    assert list(payload) == ["Cell: CD3 mean"]


@requires("squidpy")
@requires("appose")
def test_all_dropped_is_not_reported_as_nothing_selected():
    feats = np.column_stack([np.full(200, 7.0), np.full(200, 1.0)])
    out = run(
        feats, ["Cell: A mean", "Cell: B mean"], enable_moran=True, enable_geary=True
    )
    notes = json.loads(out["spatial_notes"])
    joined = " ".join(notes)
    assert "Every selected measurement" in joined
    assert "no measurements were selected" not in joined
    assert any(n.startswith("Moran's I did not run") for n in notes)
    assert any(n.startswith("Geary's C did not run") for n in notes)
