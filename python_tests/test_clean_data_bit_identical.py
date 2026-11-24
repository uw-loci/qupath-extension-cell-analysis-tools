"""Clean data clusters exactly as it did before the 0.26.0 data-hygiene fixes.

The hashes below were recorded by running this harness against the scripts at
commit e562c3e (v0.26.0-dev.6), before any of the Batch A changes (masked
normalization statistics, the scale check, the reserved-column fix, the fold
change and duplicate-name checks). A fix that changes any number a clean run
produces fails here.
"""

import hashlib
import json
import sys
from pathlib import Path

import numpy as np
import pytest

from conftest import SCRIPTS_DIR, requires

RECORDED = {
    "zscore": "1885c1465e52d794ddd908c7a4dba9fcdde6373724036fa9b3dac54fbd36ac01",
    "minmax": "1431ae213d986057cfb27405c825ad23782d80a1c235aab2350147901e0a1196",
    "percentile": "9fbbafb9037a71eecf44ec4bbf3e7b0fcf399b234bd782da03cb7d8d5d750dcd",
}


class _Task:
    def __init__(self):
        self.outputs = {}
        self.cancel_requested = False

    def update(self, *args, **kwargs):
        pass

    def cancel(self):
        pass


class _Nd:
    def __init__(self, array):
        self._array = array

    def ndarray(self):
        return self._array


def fixed_matrix():
    rng = np.random.default_rng(20261124)
    centers = rng.normal(0, 4, (3, 6))
    rows = [rng.normal(c, 1.0, (80, 6)) for c in centers]
    data = np.vstack(rows) * np.array([1, 10, 100, 0.1, 5, 50]) + 2000
    assert (data > 0).all()  # fold changes are defined on every column
    return data


def run_full_clustering(scripts_dir, data, normalization):
    """Exec model_utils.py then run_clustering.py the way the worker does; return outputs."""
    scripts_dir = Path(scripts_dir)
    namespace = {}
    model_utils = scripts_dir / "model_utils.py"
    exec(
        compile(model_utils.read_text(encoding="utf-8"), str(model_utils), "exec"),
        namespace,
    )
    task = _Task()
    namespace.update(
        {
            "task": task,
            "measurements": _Nd(data.copy()),
            "marker_names": ["Cell: M%d mean" % i for i in range(data.shape[1])],
            "algorithm": "kmeans",
            "algorithm_params": {"n_clusters": 3, "random_state": 7},
            "normalization": normalization,
            "embedding_method": "none",
            "embedding_params": {},
        }
    )
    saved_path = list(sys.path)
    for mod in ("spatial_stats", "knn_cache"):
        sys.modules.pop(mod, None)
    sys.path.insert(0, str(scripts_dir))
    try:
        script = scripts_dir / "run_clustering.py"
        exec(
            compile(script.read_text(encoding="utf-8"), str(script), "exec"), namespace
        )
    finally:
        sys.path[:] = saved_path
        for mod in ("spatial_stats", "knn_cache"):
            sys.modules.pop(mod, None)
    return task.outputs


def digest(outputs):
    h = hashlib.sha256()
    h.update(np.ascontiguousarray(outputs["cluster_labels"].ndarray()).tobytes())
    h.update(np.ascontiguousarray(outputs["cluster_stats"].ndarray()).tobytes())
    for key in ("marker_rankings", "representatives"):
        h.update(json.dumps(json.loads(outputs[key]), sort_keys=True).encode())
    h.update(np.ascontiguousarray(outputs["cluster_confidence"].ndarray()).tobytes())
    return h.hexdigest()


@requires("scanpy")
@requires("appose")
@pytest.mark.parametrize("normalization", sorted(RECORDED))
def test_clean_clustering_is_bit_identical(normalization):
    outputs = run_full_clustering(SCRIPTS_DIR, fixed_matrix(), normalization)
    assert digest(outputs) == RECORDED[normalization]
