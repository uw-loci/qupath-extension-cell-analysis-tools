"""Reusing the neighbour graph and embedding between runs (knn_cache.py).

Asked for so Leiden resolutions can be explored without rebuilding the graph.
What must hold: a reused graph gives exactly the labels a rebuilt one gives,
anything that changes the graph changes the key, and the cache never holds
more than its budget or keeps memory after it is freed.
"""

import importlib
import sys

import numpy as np
import pytest

from conftest import SCRIPTS_DIR, load_script_symbol, requires

sys.path.insert(0, str(SCRIPTS_DIR))
knn_cache = importlib.import_module("knn_cache")
sys.path.remove(str(SCRIPTS_DIR))

MB = 1024 * 1024


@pytest.fixture(autouse=True)
def empty_cache():
    knn_cache.release("test setup")
    yield
    knn_cache.release("test teardown")


def blobs(n=600, seed=0):
    rng = np.random.default_rng(seed)
    centers = rng.normal(0, 4, (5, 6))
    return centers[rng.integers(0, 5, n)] + rng.normal(0, 1, (n, 6))


def test_the_digest_changes_with_cell_order_one_value_and_dtype():
    x = blobs()
    d = knn_cache.matrix_digest(x)
    assert knn_cache.matrix_digest(x.copy()) == d
    assert knn_cache.matrix_digest(x[::-1]) != d  # order changes approximate kNN
    y = x.copy()
    y[123, 4] += 1e-9
    assert knn_cache.matrix_digest(y) != d
    assert knn_cache.matrix_digest(x.astype(np.float32)) != d
    assert knn_cache.matrix_digest(x.reshape(300, 12)) != d


def test_the_key_ignores_parameter_order_and_tracks_every_value():
    a = knn_cache.make_key("d", n_neighbors=15, seed=42)
    assert a == knn_cache.make_key("d", seed=42, n_neighbors=15)
    assert a != knn_cache.make_key("d", n_neighbors=16, seed=42)
    assert a != knn_cache.make_key("d", n_neighbors=15, seed=43)
    assert a != knn_cache.make_key("e", n_neighbors=15, seed=42)


def test_a_slot_returns_only_what_was_stored_under_its_key():
    v = np.arange(10.0)
    assert knn_cache.put("embedding", v, "k1", 10 * MB, meta={"m": 1})
    value, built, meta = knn_cache.get("embedding", "k1")
    assert value is v and meta == {"m": 1} and len(built) == 8
    assert knn_cache.get("embedding", "k2") is None
    assert knn_cache.get("graph", "k1") is None


def test_stored_arrays_are_read_only_and_copy_leaves_the_caller_writable():
    v = np.zeros(4)
    knn_cache.put("embedding", v, "k", MB, copy=True)
    v[0] = 1.0  # the caller's array is untouched by storing a copy
    stored = knn_cache.get("embedding", "k")[0]
    assert stored[0] == 0.0
    with pytest.raises(ValueError):
        stored[0] = 2.0


def test_nothing_is_kept_beyond_the_budget_or_with_a_zero_budget():
    assert not knn_cache.put("graph", np.zeros(1000), "k", 0)
    assert knn_cache.held_bytes() == 0
    assert knn_cache.put("embedding", np.zeros(1000), "e", 12000)  # 8000 bytes
    assert not knn_cache.put("graph", np.zeros(1000), "g", 12000)  # 16000 total
    assert knn_cache.held_bytes() == 8000


def test_storing_replaces_the_slot_and_frees_the_old_value_first():
    knn_cache.put("graph", np.zeros(1000), "old", 9000)
    # Old and new together would be 16000 > 9000: storing must not count the old.
    assert knn_cache.put("graph", np.zeros(1000), "new", 9000)
    assert knn_cache.get("graph", "old") is None
    assert knn_cache.held_bytes() == 8000


def test_keep_only_matrix_frees_what_another_matrix_built():
    knn_cache.put("graph", np.zeros(10), knn_cache.make_key("aaa", k=1), MB)
    knn_cache.put("embedding", np.zeros(10), knn_cache.make_key("bbb", k=1), MB)
    assert knn_cache.keep_only_matrix("aaa", "test") == 80
    assert knn_cache.get("graph", knn_cache.make_key("aaa", k=1)) is not None
    assert knn_cache.held_bytes() == 80


@pytest.mark.skipif(not sys.platform.startswith("linux"), reason="reads /proc")
def test_releasing_returns_the_memory_to_the_system():
    def rss():
        with open("/proc/self/statm") as f:
            return int(f.read().split()[1]) * 4096

    big = np.ones(64 * MB // 8)
    knn_cache.put("graph", big, "k", 128 * MB)
    del big
    before = rss()
    assert knn_cache.release("test") == 64 * MB
    assert before - rss() > 48 * MB


@requires("scanpy")
@requires("igraph")
def test_leiden_on_a_kept_graph_gives_the_labels_of_a_rebuilt_one():
    graph_of = load_script_symbol("run_clustering.py", "knn_graph", {"np": np})
    leiden = load_script_symbol("run_clustering.py", "leiden_labels", {"np": np})
    # Above 4,096 cells, where scanpy's search is approximate and seeded.
    x = blobs(n=5000, seed=3)
    built = graph_of(x, 15, 42)
    knn_cache.put("graph", built, "k", 512 * MB)
    kept = knn_cache.get("graph", "k")[0]
    assert not kept.data.flags.writeable  # Leiden must cope with a read-only graph
    rebuilt = graph_of(x, 15, 42)
    assert (kept != rebuilt).nnz == 0
    for resolution in (0.3, 1.0):
        assert np.array_equal(
            leiden(kept, resolution, 42), leiden(rebuilt, resolution, 42)
        )


@requires("scanpy")
@requires("igraph")
def test_leiden_labels_match_the_previous_single_call_path():
    """leiden_labels(knn_graph(...)) is what fit_seeded did in one AnnData."""
    import anndata as ad
    import scanpy as sc

    graph_of = load_script_symbol("run_clustering.py", "knn_graph", {"np": np})
    leiden = load_script_symbol("run_clustering.py", "leiden_labels", {"np": np})
    x = blobs(n=800, seed=5)
    adata = ad.AnnData(X=x)
    sc.pp.neighbors(adata, n_neighbors=15, use_rep="X", random_state=7)
    sc.tl.leiden(
        adata, resolution=0.8, flavor="igraph", n_iterations=-1, random_state=7
    )
    before = adata.obs["leiden"].astype(int).values
    assert np.array_equal(leiden(graph_of(x, 15, 7), 0.8, 7), before)
