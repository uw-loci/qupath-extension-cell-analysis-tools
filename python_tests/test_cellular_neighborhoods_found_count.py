"""H10: k-means that finds fewer neighborhoods than requested reports what it found.

Two cell types in windows of 5 give at most 6 distinct compositions; with the
default 10 neighborhoods the empty ids were reported as real rows with zero
composition and an enrichment of about -19 for every class, which also set the
heatmap's color range so the real rows read as white.
"""

import json

import numpy as np

from conftest import SCRIPTS_DIR, load_script_symbol, requires, worker_globals

SCRIPT = SCRIPTS_DIR / "cellular_neighborhoods.py"


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


def run(n_types, n_neighborhoods, k=5, n=400, seed=0):
    rng = np.random.default_rng(seed)
    task = _Task()
    namespace = {
        "task": task,
        "spatial_coords": _Nd(rng.uniform(0, 1000, (n, 2))),
        "cell_type_labels": [int(v) for v in rng.integers(0, n_types, n)],
        "class_names": ["T%d" % i for i in range(n_types)],
        "k_neighbors": k,
        "n_neighborhoods": n_neighborhoods,
        **worker_globals(),
    }
    exec(compile(SCRIPT.read_text(encoding="utf-8"), str(SCRIPT), "exec"), namespace)
    return task.outputs


def test_compact_labels_renumbers_in_order():
    compact = load_script_symbol(
        "cellular_neighborhoods.py", "compact_labels", {"np": np}
    )
    labels, m = compact(np.array([7, 2, 7, 4, 2]))
    assert m == 3
    assert labels.tolist() == [2, 0, 2, 1, 0]


@requires("sklearn")
@requires("appose")
def test_fewer_distinct_windows_than_requested_reports_the_found_count():
    out = run(n_types=2, n_neighborhoods=10)
    found = out["n_neighborhoods"]
    assert found <= 6
    labels = out["neighborhood_labels"].ndarray()
    assert sorted(np.unique(labels).tolist()) == list(range(found))
    counts = json.loads(out["neighborhood_counts"])
    assert len(counts) == found and min(counts.values()) > 0
    enrichment = json.loads(out["enrichment_json"])["log2_enrichment"]
    assert len(enrichment) == found
    # An empty neighborhood had every class at about -19 (log2 of eps over the
    # class frequency); a real one has at least one class it is enriched in.
    # An absent class is null, never a large negative number.
    assert all(max(v for v in row if v is not None) > -10 for row in enrichment)
    assert all(v is None or v > -10 for row in enrichment for v in row)
    notes = json.loads(out["notes"])
    assert len(notes) == 1
    assert (
        "not the 10 requested" in notes[0] and "%d neighborhood(s)" % found in notes[0]
    )


@requires("sklearn")
@requires("appose")
def test_enough_distinct_windows_gives_the_requested_count_and_no_note():
    out = run(n_types=5, n_neighborhoods=4, k=10)
    assert out["n_neighborhoods"] == 4
    assert json.loads(out["notes"]) == []
