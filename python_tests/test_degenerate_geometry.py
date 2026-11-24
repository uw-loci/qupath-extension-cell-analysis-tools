"""H56: degenerate geometry refuses instead of inventing a length; outputs are valid JSON.

The automatic radius used to be 50 in whatever unit the coordinates were in
when no two cells were apart, and co-occurrence fell back to a fraction of the
bounding box. Both are lengths nobody measured.
"""

import json
import math

import numpy as np
import pytest

from conftest import SCRIPTS_DIR, load_script_symbol, requires

finite_json = load_script_symbol("model_utils.py", "finite_json", {"np": np})


def _strict_loads(text):
    def refuse(token):
        raise ValueError("bare %s token in JSON" % token)

    return json.loads(text, parse_constant=refuse)


def test_finite_json_nulls_every_non_finite_float():
    tree = {
        "a": float("nan"),
        "b": [1.0, float("inf"), np.float32("nan"), np.float64(2.5)],
        "c": ({"d": -float("inf")},),
        "e": "NaN",
        "f": 3,
    }
    out = finite_json(tree)
    assert out == {
        "a": None,
        "b": [1.0, None, None, 2.5],
        "c": [{"d": None}],
        "e": "NaN",
        "f": 3,
    }
    _strict_loads(json.dumps(out))


def _auto_radius():
    median_nn = load_script_symbol(
        "spatial_stats.py", "_median_nn_distance", {"np": np}
    )
    return load_script_symbol(
        "spatial_stats.py",
        "_auto_radius",
        {"np": np, "_median_nn_distance": median_nn},
    )


@requires("scipy")
def test_auto_radius_refuses_when_no_two_cells_are_apart():
    coords = np.zeros((6, 2))
    slices = [(0, np.arange(3)), (1, np.arange(3, 6))]
    with pytest.raises(ValueError) as e:
        _auto_radius()(coords, slices)
    assert "Set the radius by hand" in str(e.value)


@requires("scipy")
def test_auto_radius_is_unchanged_on_ordinary_data():
    rng = np.random.default_rng(0)
    coords = rng.uniform(0, 100, (50, 2))
    r = _auto_radius()(coords, [(0, np.arange(50))])
    assert math.isfinite(r) and r > 0


class _Task:
    def __init__(self):
        self.outputs = {}

    def update(self, *args, **kwargs):
        pass


@requires("squidpy")
@requires("anndata")
def test_co_occurrence_on_coincident_cells_is_a_note_not_a_made_up_interval():
    import importlib.util
    import sys
    from pathlib import Path

    import anndata as ad
    import pandas as pd

    spec = importlib.util.spec_from_file_location(
        "qpcat_spatial_stats_degenerate", Path(SCRIPTS_DIR) / "spatial_stats.py"
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    module.reset_notes()
    coords = np.full((20, 2), 5.0)
    adata = ad.AnnData(X=np.zeros((20, 1), dtype=np.float32))
    adata.obsm["spatial"] = coords
    adata.obs["cluster"] = pd.Categorical(["a", "b"] * 10)
    task = _Task()
    module.run_co_occurrence(
        adata,
        task,
        cluster_key="cluster",
        mode="pairwise",
        n_permutations=5,
        spatial_data=coords,
        persist_plots=False,
    )
    assert "co_occurrence_pairwise" not in task.outputs
    assert len(module.SPATIAL_NOTES) == 1
    assert "share one position" in module.SPATIAL_NOTES[0]
