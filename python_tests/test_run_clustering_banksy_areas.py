"""BANKSY neighbour weights are built per area; everything after stays global.

Running the whole BANKSY pipeline per area would be the obvious implementation
and the wrong one: independent Leiden partitions give incomparable label
spaces, so cluster 3 in one TMA core would be unrelated to cluster 3 in the
next -- destroying exactly the cross-core comparison that motivated
partitioning. So the seam is cut one step earlier. Only
`initialize_banksy`'s output is per area; `generate_banksy_matrix`,
`pca_umap` and `run_Leiden_partition` all run unchanged on the full cell set.

That makes the forged `banksy_dict` the entire contract surface with
pybanksy, and these tests pin it. They also pin the two upstream facts the
design rests on:

  * `num_neighbours` is multiplied by `(m + 1)` (banksy/main.py:158), so the
    real bound is 2*k <= n-1 with max_m=1 -- not k <= n-1, which is what the
    old global cap assumed and why a 20-cell run with k_geom=15 raised inside
    sklearn.
  * weights are row-normalised (banksy/main.py:229), which is why per-area
    blocks are comparable without any renormalisation before the global PCA.

pybanksy is installed in CI so none of this silently skips.
"""

import os

import numpy as np

from conftest import load_script_symbol, requires

SCRIPT = "spatial_stats.py"


class _Logger:
    def __init__(self):
        self.warnings = []

    def warning(self, msg, *args):
        self.warnings.append(msg % args if args else msg)

    def info(self, msg, *args):
        pass


def _resolve(logger=None):
    ns = {
        "np": np,
        "os": os,
        "logger": logger or _Logger(),
        "area_slices": load_script_symbol(SCRIPT, "area_slices", {"np": np}),
        "banksy_k_cap": load_script_symbol(SCRIPT, "banksy_k_cap", {"np": np}),
    }
    return load_script_symbol(SCRIPT, "build_banksy_weights", ns)


def _cap():
    return load_script_symbol(SCRIPT, "banksy_k_cap", {"np": np})


_RS = np.random.RandomState(42)
_BLOB_A = _RS.rand(40, 2) * 50
_BLOB_B = _RS.rand(40, 2) * 50 + 5000.0
_COORDS = np.vstack([_BLOB_A, _BLOB_B])
_IDS = [0] * 40 + [1] * 40
_N_MARKERS = 6


def _cross_count(matrix, ids):
    dense = matrix.toarray()
    ids = np.asarray(ids)
    return sum(
        1
        for i in range(len(ids))
        for j in range(len(ids))
        if ids[i] != ids[j] and dense[i, j] != 0
    )


def _init_banksy(coords, k):
    """initialize_banksy on one frame, stdout silenced as in production."""
    import contextlib

    import anndata as ad
    from banksy.initialize_banksy import initialize_banksy

    a = ad.AnnData(X=np.zeros((len(coords), 1), dtype=np.float32))
    a.obsm["spatial"] = coords
    a.obs["x"] = coords[:, 0]
    a.obs["y"] = coords[:, 1]
    with open(os.devnull, "w") as devnull, contextlib.redirect_stdout(devnull):
        return initialize_banksy(
            a,
            ("x", "y", "spatial"),
            num_neighbours=k,
            nbr_weight_decay="scaled_gaussian",
            max_m=1,
            plt_edge_hist=False,
            plt_nbr_weights=False,
            plt_agf_angles=False,
            plt_theta=False,
        )


# --- Pure logic ----------------------------------------------------------


def test_k_cap_accounts_for_the_m_plus_one_multiplier():
    cap = _cap()
    assert cap(15, 100, 1) == 15
    assert cap(15, 21, 1) == 10
    # The case that used to raise inside sklearn under the old k <= n-1 cap.
    assert cap(15, 20, 1) == 9
    assert cap(15, 3, 1) == 1
    assert cap(15, 2, 1) == 0
    assert cap(15, 1, 1) == 0


# --- pybanksy contract ---------------------------------------------------


@requires("banksy")
def test_initialize_banksy_returns_the_structure_we_forge():
    weights = _init_banksy(_BLOB_A, 5)["scaled_gaussian"]["weights"]
    assert set(weights.keys()) == {0, 1}
    assert weights[0].shape == (40, 40)
    assert np.iscomplexobj(weights[1].data)


@requires("banksy")
def test_num_neighbours_is_multiplied_by_m_plus_one():
    """The per-area cap formula is derived from this. If upstream drops the
    doubling the cap becomes silently over-conservative and nothing else
    notices."""
    weights = _init_banksy(_BLOB_A, 5)["scaled_gaussian"]["weights"]
    assert set(weights[0].getnnz(axis=1)) == {5}
    assert set(weights[1].getnnz(axis=1)) == {10}


@requires("banksy")
def test_weights_are_row_normalised():
    """Why no per-area renormalisation is needed: each neighbour block is a
    weighted MEAN of neighbour expression, in expression units, whatever the
    area's cell density."""
    built = _resolve()(_BLOB_A, k_geom=5, area_ids=None, max_m=1)
    sums = np.asarray(built["scaled_gaussian"]["weights"][0].sum(axis=1)).ravel()
    np.testing.assert_allclose(sums, 1.0, rtol=1e-9, atol=1e-9)


# --- The partition -------------------------------------------------------


@requires("banksy")
def test_no_weight_joins_two_areas_in_either_block():
    built = _resolve()(_COORDS, k_geom=8, area_ids=_IDS, max_m=1)
    for m in (0, 1):
        assert _cross_count(built["scaled_gaussian"]["weights"][m], _IDS) == 0


@requires("banksy")
def test_partitioning_removes_cross_area_neighbours_that_really_existed():
    """Teeth. Uneven, close-together areas so an un-partitioned kNN genuinely
    reaches across -- otherwise the assertion above would hold for a broken
    implementation too."""
    small = np.random.RandomState(7).rand(6, 2) * 2
    big = np.random.RandomState(8).rand(40, 2) * 2 + 3.0
    coords = np.vstack([small, big])
    ids = [0] * 6 + [1] * 40
    build = _resolve()

    joined = build(coords, k_geom=8, area_ids=None, max_m=1)
    split = build(coords, k_geom=8, area_ids=ids, max_m=1)

    assert _cross_count(joined["scaled_gaussian"]["weights"][0], ids) > 0
    assert _cross_count(split["scaled_gaussian"]["weights"][0], ids) == 0


@requires("banksy")
def test_a_single_area_is_exactly_initialize_banksy_alone():
    """No parallel code path: the partitioned builder IS the unpartitioned one
    with a single block and the identity permutation."""
    direct = _init_banksy(_BLOB_A, 6)["scaled_gaussian"]["weights"]
    ours = _resolve()(_BLOB_A, k_geom=6, area_ids=None, max_m=1)["scaled_gaussian"][
        "weights"
    ]
    for m in (0, 1):
        np.testing.assert_array_equal(direct[m].toarray(), ours[m].toarray())


@requires("banksy")
def test_generate_banksy_matrix_accepts_the_spliced_dict():
    """THE test. Everything rests on generate_banksy_matrix reading the
    weights we hand it rather than recomputing the graph."""
    import contextlib

    import anndata as ad
    from banksy.embed_banksy import generate_banksy_matrix

    adata = ad.AnnData(
        X=np.random.RandomState(1).rand(80, _N_MARKERS).astype(np.float32)
    )
    adata.var_names = ["m%d" % i for i in range(_N_MARKERS)]
    adata.obsm["spatial"] = _COORDS
    built = _resolve()(_COORDS, k_geom=8, area_ids=_IDS, max_m=1)

    with open(os.devnull, "w") as devnull, contextlib.redirect_stdout(devnull):
        out_dict, _matrix = generate_banksy_matrix(
            adata, built, [0.2], 1, verbose=False
        )

    augmented = out_dict["scaled_gaussian"][0.2]["adata"]
    # (max_m + 2) blocks of n_markers columns.
    assert augmented.X.shape == (80, 3 * _N_MARKERS)
    assert np.isfinite(np.asarray(augmented.X)).all()


@requires("banksy")
def test_a_too_small_area_becomes_a_zero_block_and_cells_are_kept():
    """Never drop cells: labels map back positionally on the Java side, so a
    shortened array is a silent misalignment rather than a clean failure."""
    log = _Logger()
    coords = np.vstack([_BLOB_A, [[9999.0, 9999.0]]])
    ids = [0] * 40 + [1]
    built = _resolve(log)(coords, k_geom=6, area_ids=ids, max_m=1)

    weights = built["scaled_gaussian"]["weights"][0]
    assert weights.shape == (41, 41)
    assert weights.toarray()[40].sum() == 0
    assert any("too few cells" in w for w in log.warnings)


@requires("banksy")
def test_lambda_must_be_a_float_upstream():
    """Documents why run_clustering casts. run_Leiden_partition skips any
    lambda key that is not a float, so an int yields an empty results_df and
    surfaces one step later as 'BANKSY did not produce cluster labels'."""
    import inspect

    from banksy.cluster_methods import run_Leiden_partition

    source = inspect.getsource(run_Leiden_partition)
    assert "isinstance" in source and "float" in source
