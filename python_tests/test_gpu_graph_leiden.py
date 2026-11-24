"""gpu_graph_leiden.py helpers that run without a GPU: neighbor checks, the
out-of-memory classifier and the independent modularity check."""

import importlib
import sys

import numpy as np
import pytest
from scipy import sparse as sp

from conftest import SCRIPTS_DIR

sys.path.insert(0, str(SCRIPTS_DIR))
ggl = importlib.import_module("gpu_graph_leiden")
sys.path.remove(str(SCRIPTS_DIR))


def _knn(n=20, k=4):
    idx = np.array([[i] + [(i + j) % n for j in range(1, k)] for i in range(n)])
    dist = np.tile(np.arange(k, dtype=np.float32), (n, 1))
    return idx, dist


def _set(a, where, value):
    a = a.copy()
    a[where] = value
    return a


def test_a_valid_neighbor_result_passes():
    idx, dist = _knn()
    ggl.check_knn(idx, dist, 20, 4, self_first=True)


@pytest.mark.parametrize(
    "spoil, message",
    [
        (lambda i, d: (i[:, :3], d[:, :3]), r"expected \(20, 4\)"),
        (lambda i, d: (np.roll(i, 1, axis=1), d), "its own first neighbor"),
        (lambda i, d: (_set(i, (3, 2), 20), d), "outside"),
        (lambda i, d: (i, np.where(d == 2, np.nan, d)), "non-finite"),
    ],
)
def test_a_broken_neighbor_result_is_refused(spoil, message):
    idx, dist = spoil(*_knn())
    with pytest.raises(ValueError, match=message):
        ggl.check_knn(idx, dist, 20, 4, self_first=True)


def test_brute_force_need_not_put_self_first():
    idx, dist = _knn()
    ggl.check_knn(np.roll(idx, 1, axis=1), dist, 20, 4, self_first=False)


@pytest.mark.parametrize(
    "text",
    [
        "cupy.cuda.memory.OutOfMemoryError: Out of memory allocating 1,024 bytes",
        "MemoryError: std::bad_alloc: out_of_memory: CUDA error at: rmm/mr/device",
        "RuntimeError: RAFT failure: cudaErrorMemoryAllocation",
    ],
)
def test_cuda_out_of_memory_texts_are_recognized(text):
    assert ggl.is_cuda_oom(text)


@pytest.mark.parametrize(
    "text",
    [
        "ValueError: cuGraph holds 9 vertices for a 10-cell graph",
        "numpy.core._exceptions._ArrayMemoryError: Unable to allocate 3 GiB",
        "",
        None,
    ],
)
def test_other_failures_are_not_out_of_memory(text):
    assert not ggl.is_cuda_oom(text)


def test_modularity_matches_igraph():
    ig = pytest.importorskip("igraph")
    rng = np.random.RandomState(1)
    n = 300
    dense = sp.random(n, n, density=0.03, random_state=rng, dtype=np.float32)
    upper = sp.triu(dense, k=1).tocsr()
    sym = (upper + upper.T).tocsr()
    labels = rng.randint(0, 5, size=n)
    coo = upper.tocoo()
    g = ig.Graph(n=n, edges=list(zip(coo.row.tolist(), coo.col.tolist())))
    for res in (0.5, 1.0):
        want = g.modularity(labels.tolist(), weights=coo.data.tolist(), resolution=res)
        assert ggl.modularity(sym, labels, res) == pytest.approx(want, abs=1e-6)
