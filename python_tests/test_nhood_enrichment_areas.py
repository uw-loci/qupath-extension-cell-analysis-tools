"""Neighbourhood enrichment over several independent areas (spatial_stats.py).

The neighbour graph is built per area, but squidpy's permutation null shuffled
cluster labels across ALL cells unless ``library_key`` is given. Two areas of
different composition then make every within-area pair look enriched: the null
hands a cell neighbours from a cluster that only exists in the other area. The
edge counts behind each z-score were also discarded.
"""

import json
import sys
import types

import numpy as np
import pytest

from conftest import SCRIPTS_DIR, requires

SCRIPT = SCRIPTS_DIR / "spatial_stats.py"


class _Task:
    def __init__(self):
        self.outputs = {}

    def update(self, *args, **kwargs):
        pass


class _FakeNd:
    def __init__(self, dtype, shape):
        self._array = np.zeros(shape, dtype=dtype)

    def ndarray(self):
        return self._array


@pytest.fixture
def spatial(monkeypatch):
    """The whole module, with a stand-in for appose.NDArray."""
    import importlib.util

    monkeypatch.setitem(sys.modules, "appose", types.SimpleNamespace(NDArray=_FakeNd))
    spec = importlib.util.spec_from_file_location("qpcat_spatial_nhood_test", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _two_area_adata(seed=3):
    """Area 0: clusters A and B, randomly mixed. Area 1: cluster C only.

    Both areas sit on the same coordinates, as images do when QP-CAT pools them.
    """
    import anndata as ad
    import pandas as pd

    rs = np.random.RandomState(seed)
    n = 300
    coords = np.vstack([rs.rand(n, 2) * 100, rs.rand(n, 2) * 100])
    labels = list(rs.choice(["A", "B"], size=n)) + ["C"] * n
    adata = ad.AnnData(X=np.zeros((2 * n, 1), dtype=np.float32))
    adata.obsm["spatial"] = coords
    adata.obs["cluster"] = pd.Categorical(labels)
    return adata, [0] * n + [1] * n


@requires("squidpy")
@requires("anndata")
def test_pinned_squidpy_accepts_library_key():
    import inspect

    import squidpy as sq

    assert "library_key" in inspect.signature(sq.gr.nhood_enrichment).parameters


@requires("squidpy")
@requires("anndata")
def test_random_mixing_inside_an_area_is_not_called_enriched(spatial):
    adata, area_ids = _two_area_adata()
    spatial.build_spatial_graph(adata, graph_type="knn", k=8, area_ids=area_ids)
    task = _Task()
    assert spatial.run_nhood_enrichment(
        adata, task, area_ids=area_ids, n_permutations=200
    )

    names = json.loads(task.outputs["nhood_cluster_names"])
    z = task.outputs["nhood_enrichment"].ndarray()
    a = names.index("A")
    # A and B are mixed at random within their area, so A next to A is chance.
    # With labels shuffled across both areas this read z = 15.8.
    assert abs(z[a, a]) < 3.0, z[a, a]
    assert task.outputs["nhood_null"] == (
        "200 permutations; cluster labels shuffled within each of 2 areas"
    )


@requires("squidpy")
@requires("anndata")
def test_edge_counts_travel_with_the_z_scores(spatial):
    adata, area_ids = _two_area_adata()
    spatial.build_spatial_graph(adata, graph_type="knn", k=8, area_ids=area_ids)
    task = _Task()
    spatial.run_nhood_enrichment(adata, task, area_ids=area_ids, n_permutations=50)

    counts = task.outputs["nhood_counts"].ndarray()
    names = json.loads(task.outputs["nhood_cluster_names"])
    c = names.index("C")
    assert counts.shape == task.outputs["nhood_enrichment"].ndarray().shape
    assert counts.sum() == adata.obsp["spatial_connectivities"].nnz
    # No edge crosses areas, so C (area 1 only) never neighbours A or B.
    assert counts[c].sum() == counts[c, c]


@requires("anndata")
def test_one_area_needs_no_library_key(spatial):
    adata, _ = _two_area_adata()
    assert spatial.nhood_library_key(adata, None) is None
    assert spatial.nhood_library_key(adata, [4] * adata.n_obs) is None
    assert spatial.describe_nhood_null(1000, 1) == (
        "1000 permutations; cluster labels shuffled across all cells"
    )
