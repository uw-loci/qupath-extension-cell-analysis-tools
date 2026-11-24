"""PAGA on clusters that no nearest-neighbor edge joins.

python-igraph 1.0 builds the cluster graph's sparse adjacency from an empty edge
list when no kNN edge crosses two clusters, and scipy raises "mismatching number
of index arrays for shape; got 0, expected 2" inside ``sc.tl.paga``. Every
well-separated run lost its PAGA graph that way. ``compute_paga`` records the
edgeless graph instead and says so; a run with edges still goes through scanpy.
"""

import numpy as np
import pandas as pd
import pytest

from conftest import load_script_symbol

sc = pytest.importorskip("scanpy")
ad = pytest.importorskip("anndata")


def _adata(X, labels):
    a = ad.AnnData(X=X)
    order = [str(x) for x in sorted(set(labels))]
    a.obs["cl"] = pd.Categorical([str(x) for x in labels], categories=order)
    sc.pp.neighbors(a, n_neighbors=10, use_rep="X")
    return a


def _compute_paga():
    return load_script_symbol("run_clustering.py", "compute_paga", {"np": np})


def test_separated_clusters_give_an_edgeless_graph_and_a_note():
    rng = np.random.default_rng(1)
    X = np.vstack([rng.normal(i * 100, 1, (40, 4)) for i in range(3)])
    a = _adata(X, np.repeat([0, 1, 2], 40))
    note = _compute_paga()(a, "cl")
    assert note is not None and "no nearest-neighbor edge" in note
    conn = a.uns["paga"]["connectivities"].toarray()
    assert conn.shape == (3, 3)
    assert not conn.any()
    assert a.uns["paga"]["groups"] == "cl"


def test_separated_clusters_still_plot():
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    rng = np.random.default_rng(2)
    X = np.vstack([rng.normal(i * 100, 1, (40, 4)) for i in range(2)])
    a = _adata(X, np.repeat([0, 1], 40))
    _compute_paga()(a, "cl")
    pos_fn = load_script_symbol("run_clustering.py", "edgeless_paga_pos", {"np": np})
    pos = pos_fn(a, 0.05)
    assert pos.shape == (2, 2)
    sc.pl.paga(a, threshold=0.05, pos=pos, show=False)
    plt.close("all")


def test_touching_clusters_go_through_scanpy():
    rng = np.random.default_rng(0)
    X = np.vstack([rng.normal(i * 3, 1, (60, 5)) for i in range(3)])
    a = _adata(X, np.repeat([0, 1, 2], 60))
    assert _compute_paga()(a, "cl") is None
    conn = a.uns["paga"]["connectivities"].toarray()
    assert conn.shape == (3, 3)
    assert conn.any()
    pos_fn = load_script_symbol("run_clustering.py", "edgeless_paga_pos", {"np": np})
    assert pos_fn(a, 0.0) is None


def test_edges_all_under_the_threshold_still_plot():
    """Clusters that touch only weakly leave no edge at the drawing threshold."""
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    a = _adata(*_weakly_touching())
    assert _compute_paga()(a, "cl") is None
    conn = a.uns["paga"]["connectivities"]
    assert conn.count_nonzero() > 0
    pos_fn = load_script_symbol("run_clustering.py", "edgeless_paga_pos", {"np": np})
    above = conn.data.max() * 2
    pos = pos_fn(a, above)
    assert pos is not None
    sc.pl.paga(a, threshold=above, pos=pos, show=False)
    plt.close("all")
    assert pos_fn(a, 0.0) is None


def _weakly_touching():
    rng = np.random.default_rng(0)
    X = np.vstack([rng.normal(i * 3, 1, (100, 5)) for i in range(3)])
    return X, np.repeat([0, 1, 2], 100)
