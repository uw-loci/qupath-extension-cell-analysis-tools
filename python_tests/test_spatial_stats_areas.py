"""Independent areas: a spatial graph edge must never cross an area boundary.

QP-CAT built one neighbour graph over one flat coordinate array. Cells in two
TMA cores, two tissue sections on one slide, or two different images became
neighbours whenever their pixel coordinates happened to be close. Across
images the failure was total -- centroids are concatenated with no offset, so
(100,100) in image A sits exactly on top of (100,100) in image B.

The invariant these tests exist to hold is a single sentence: for any area
assignment, the built adjacency has ZERO edges between different areas. That
is decisive in a way "the numbers look plausible" is not -- a graph joining
two specimens produces confident, well-formed, wrong statistics, and nothing
downstream can detect it.

The assembly logic (slice -> per-area block -> block_diag -> permute back) is
tested with a stub block builder so it runs on numpy + scipy alone. The real
squidpy-backed path is covered separately, and squidpy is installed in CI
precisely so those do not silently skip.
"""

import numpy as np
import pytest
import scipy.sparse as sp

from conftest import load_script_symbol, requires

SCRIPT = "spatial_stats.py"


# --- Loading -------------------------------------------------------------


def _pure(symbol):
    """Load a helper that needs nothing but numpy."""
    return load_script_symbol(SCRIPT, symbol, {"np": np})


class _Logger:
    """Collects warnings so a test can assert the user was actually told."""

    def __init__(self):
        self.warnings = []
        self.infos = []

    def warning(self, msg, *args):
        self.warnings.append(msg % args if args else msg)

    def info(self, msg, *args):
        self.infos.append(msg % args if args else msg)


class _FakeAdata:
    """The three attributes build_spatial_graph touches."""

    def __init__(self, coords):
        self.shape = (len(coords), 1)
        self.obsm = {"spatial": np.asarray(coords, dtype=np.float64)}
        self.obsp = {}
        self.uns = {}


def _fully_connected_block(coords, graph_type, k, radius, delaunay_max_edge):
    """Stub block builder: every cell in the block joined to every other.

    Deliberately maximal. Any zero inside a block, or any non-zero outside
    one, is then unambiguous evidence of a slicing or permutation bug rather
    than of a neighbour-count coincidence.
    """
    n = len(coords)
    dense = np.ones((n, n), dtype=np.float64) - np.eye(n)
    return sp.csr_matrix(dense), sp.csr_matrix(dense)


def _graph_builder(logger=None, block_builder=None):
    """build_spatial_graph with its collaborators injected."""
    ns = {
        "np": np,
        "logger": logger or _Logger(),
        "area_slices": _pure("area_slices"),
        "_knn_cap": _pure("_knn_cap"),
        "_build_graph_block": block_builder or _fully_connected_block,
        "_auto_radius": lambda coords, slices: 42.0,
    }
    return load_script_symbol(SCRIPT, "build_spatial_graph", ns)


# --- area_slices ---------------------------------------------------------


def test_no_area_ids_yields_one_slice_over_every_cell():
    area_slices = _pure("area_slices")
    slices = area_slices(None, 5)
    assert len(slices) == 1
    assert slices[0][0] == 0
    np.testing.assert_array_equal(slices[0][1], np.arange(5))


def test_slices_are_sorted_by_area_id_not_by_first_appearance():
    # The block assembly depends on this order matching the permutation it
    # builds, and BANKSY reuses it. Insertion order would make a run
    # irreproducible for no visible reason.
    area_slices = _pure("area_slices")
    slices = area_slices([2, 0, 1, 0, 2], 5)
    assert [s[0] for s in slices] == [0, 1, 2]
    np.testing.assert_array_equal(slices[0][1], [1, 3])
    np.testing.assert_array_equal(slices[1][1], [2])
    np.testing.assert_array_equal(slices[2][1], [0, 4])


def test_length_mismatch_raises_rather_than_silently_ignoring_the_ids():
    # cellular_neighborhoods.py degrades to None on a mismatch; here that
    # would silently rebuild the joined graph this feature exists to prevent.
    area_slices = _pure("area_slices")
    with pytest.raises(ValueError, match="does not match"):
        area_slices([0, 0, 1], 5)


def test_knn_cap_never_exceeds_what_the_block_can_supply():
    knn_cap = _pure("_knn_cap")
    assert knn_cap(15, 100) == 15
    assert knn_cap(15, 10) == 9  # sklearn raises above n-1
    assert knn_cap(15, 2) == 1
    assert knn_cap(15, 1) == 0  # a lone cell has no neighbours
    assert knn_cap(15, 0) == 0


# --- The decisive invariant ----------------------------------------------


def _assert_block_diagonal(conn, area_ids):
    dense = conn.toarray()
    ids = np.asarray(area_ids)
    for i in range(len(ids)):
        for j in range(len(ids)):
            if i == j:
                continue
            same_area = ids[i] == ids[j]
            assert (
                dense[i, j] != 0
            ) == same_area, "edge (%d,%d) across areas %s/%s" % (i, j, ids[i], ids[j])


def test_no_edge_ever_joins_two_areas():
    area_ids = [0, 1, 0, 1, 2, 0]
    adata = _FakeAdata(np.random.RandomState(0).rand(len(area_ids), 2))
    _graph_builder()(adata, graph_type="knn", k=5, area_ids=area_ids)
    _assert_block_diagonal(adata.obsp["spatial_connectivities"], area_ids)


def test_cells_are_not_reordered_by_the_block_assembly():
    # Areas interleaved in the input. block_diag concatenates them contiguously,
    # so without the inverse permutation every downstream index -- cluster
    # labels, centroids, the write-back to QuPath -- would refer to a different
    # cell. This is the failure that would look like a subtle numerical bug.
    area_ids = [1, 0, 1, 0]
    coords = np.array([[0.0, 0.0], [100.0, 0.0], [1.0, 0.0], [101.0, 0.0]])
    adata = _FakeAdata(coords)
    _graph_builder()(adata, graph_type="knn", k=3, area_ids=area_ids)

    dense = adata.obsp["spatial_connectivities"].toarray()
    # Cell 0 is in area 1 with cell 2; it must NOT be joined to 1 or 3.
    assert dense[0, 2] != 0
    assert dense[0, 1] == 0
    assert dense[0, 3] == 0
    assert dense[1, 3] != 0


def test_overlapping_coordinate_frames_stay_separate():
    # The multi-image case: every image starts near (0,0), so cells from
    # different images are literally coincident. Distance cannot separate
    # them -- only the area id can.
    coords = np.array([[0.0, 0.0], [1.0, 1.0], [0.0, 0.0], [1.0, 1.0]])
    area_ids = [0, 0, 1, 1]
    adata = _FakeAdata(coords)
    _graph_builder()(adata, graph_type="knn", k=3, area_ids=area_ids)
    _assert_block_diagonal(adata.obsp["spatial_connectivities"], area_ids)


def test_a_single_area_is_the_unpartitioned_graph():
    coords = np.random.RandomState(1).rand(6, 2)
    with_ids = _FakeAdata(coords)
    without_ids = _FakeAdata(coords)
    _graph_builder()(with_ids, graph_type="knn", k=3, area_ids=[0] * 6)
    _graph_builder()(without_ids, graph_type="knn", k=3, area_ids=None)

    np.testing.assert_array_equal(
        with_ids.obsp["spatial_connectivities"].toarray(),
        without_ids.obsp["spatial_connectivities"].toarray(),
    )


def test_area_count_is_recorded_for_the_audit_trail():
    adata = _FakeAdata(np.random.RandomState(2).rand(6, 2))
    _graph_builder()(adata, graph_type="knn", k=3, area_ids=[0, 0, 1, 1, 2, 2])
    assert adata.uns["spatial_neighbors"]["params"]["qpcat_n_areas"] == 3


# --- Small areas ---------------------------------------------------------


def test_a_small_area_reduces_k_instead_of_taking_the_run_down():
    log = _Logger()
    # Area 1 has 3 cells; k=15 would raise inside sklearn.
    area_ids = [0] * 20 + [1, 1, 1]
    adata = _FakeAdata(np.random.RandomState(3).rand(len(area_ids), 2))
    _graph_builder(logger=log)(adata, graph_type="knn", k=15, area_ids=area_ids)

    assert any("k reduced" in w for w in log.warnings)
    # One line with a count, not one per area -- a 55-core TMA would bury the log.
    assert len([w for w in log.warnings if "k reduced" in w]) == 1


def test_reduced_k_warning_names_the_smallest_area():
    log = _Logger()
    area_ids = [0] * 20 + [1, 1, 1] + [2] * 8
    adata = _FakeAdata(np.random.RandomState(4).rand(len(area_ids), 2))
    _graph_builder(logger=log)(adata, graph_type="knn", k=15, area_ids=area_ids)

    warning = next(w for w in log.warnings if "k reduced" in w)
    assert "2 area(s)" in warning
    assert "3 cells" in warning  # the smallest, not just the first


# --- Real squidpy path ---------------------------------------------------
#
# squidpy is installed in CI specifically so these run. A @requires that
# always skips is not a test.


@requires("squidpy")
def test_squidpy_spatial_neighbors_still_accepts_library_key():
    # We do not use library_key (per-area k capping needs our own loop), but
    # the block-diagonal-plus-permutation trick this module uses is copied
    # from squidpy's own implementation. If that disappears upstream, the
    # assumption is worth re-checking.
    import inspect

    import squidpy as sq

    assert "library_key" in inspect.signature(sq.gr.spatial_neighbors).parameters


@requires("squidpy")
def test_ripley_and_co_occurrence_still_have_no_library_key():
    """They read obsm['spatial'] directly, bypassing the graph entirely, so
    QP-CAT must loop them per area itself. If either grows a library_key,
    delete our loop and use it."""
    import inspect

    import squidpy as sq

    assert "library_key" not in inspect.signature(sq.gr.ripley).parameters
    assert "library_key" not in inspect.signature(sq.gr.co_occurrence).parameters


@requires("squidpy")
@requires("anndata")
def test_real_knn_graph_has_no_cross_area_edges():
    import anndata as ad

    build_spatial_graph = _real_builder()

    # Two blobs 1000 units apart, k large enough that a joined graph WOULD
    # bridge them (each blob has only 5 cells, so k=8 forces cross-links).
    blob_a = np.random.RandomState(5).rand(5, 2)
    blob_b = np.random.RandomState(6).rand(5, 2) + 1000.0
    coords = np.vstack([blob_a, blob_b])
    area_ids = [0] * 5 + [1] * 5

    adata = ad.AnnData(X=np.zeros((10, 1), dtype=np.float32))
    adata.obsm["spatial"] = coords
    build_spatial_graph(adata, graph_type="knn", k=8, area_ids=area_ids)
    _assert_no_cross_edges(adata.obsp["spatial_connectivities"], area_ids)


@requires("squidpy")
@requires("anndata")
def test_without_areas_the_same_data_does_bridge_the_gap():
    """The teeth check. If an un-partitioned graph did NOT join the two blobs,
    the test above would pass for a correct AND an incorrect implementation."""
    import anndata as ad

    build_spatial_graph = _real_builder()

    blob_a = np.random.RandomState(5).rand(5, 2)
    blob_b = np.random.RandomState(6).rand(5, 2) + 1000.0
    coords = np.vstack([blob_a, blob_b])
    area_ids = [0] * 5 + [1] * 5

    adata = ad.AnnData(X=np.zeros((10, 1), dtype=np.float32))
    adata.obsm["spatial"] = coords
    build_spatial_graph(adata, graph_type="knn", k=8, area_ids=None)

    dense = adata.obsp["spatial_connectivities"].toarray()
    ids = np.asarray(area_ids)
    cross = sum(
        1 for i in range(10) for j in range(10) if ids[i] != ids[j] and dense[i, j] != 0
    )
    assert cross > 0, "un-partitioned graph should bridge the blobs"


@requires("squidpy")
@requires("anndata")
def test_real_single_area_matches_the_unpartitioned_graph_exactly():
    import anndata as ad

    build_spatial_graph = _real_builder()
    coords = np.random.RandomState(7).rand(30, 2) * 100

    grouped = ad.AnnData(X=np.zeros((30, 1), dtype=np.float32))
    grouped.obsm["spatial"] = coords
    build_spatial_graph(grouped, graph_type="knn", k=5, area_ids=[0] * 30)

    plain = ad.AnnData(X=np.zeros((30, 1), dtype=np.float32))
    plain.obsm["spatial"] = coords
    build_spatial_graph(plain, graph_type="knn", k=5, area_ids=None)

    np.testing.assert_array_equal(
        grouped.obsp["spatial_connectivities"].toarray(),
        plain.obsp["spatial_connectivities"].toarray(),
    )


@requires("squidpy")
@requires("anndata")
def test_a_one_cell_area_yields_an_empty_block_rather_than_raising():
    import anndata as ad

    build_spatial_graph = _real_builder()
    coords = np.vstack([np.random.RandomState(8).rand(6, 2), [[500.0, 500.0]]])
    area_ids = [0] * 6 + [1]

    adata = ad.AnnData(X=np.zeros((7, 1), dtype=np.float32))
    adata.obsm["spatial"] = coords
    build_spatial_graph(adata, graph_type="knn", k=4, area_ids=area_ids)

    dense = adata.obsp["spatial_connectivities"].toarray()
    assert dense[6].sum() == 0  # the lone cell borrows no neighbour
    _assert_no_cross_edges(adata.obsp["spatial_connectivities"], area_ids)


def _real_builder():
    """build_spatial_graph with the REAL block builder and helpers."""
    median_nn = load_script_symbol(SCRIPT, "_median_nn_distance", {"np": np})
    ns = {
        "np": np,
        "logger": _Logger(),
        "area_slices": _pure("area_slices"),
        "_knn_cap": _pure("_knn_cap"),
        "_median_nn_distance": median_nn,
    }
    ns["_build_graph_block"] = load_script_symbol(SCRIPT, "_build_graph_block", ns)
    ns["_auto_radius"] = load_script_symbol(SCRIPT, "_auto_radius", ns)
    return load_script_symbol(SCRIPT, "build_spatial_graph", ns)


def _assert_no_cross_edges(conn, area_ids):
    dense = conn.toarray()
    ids = np.asarray(area_ids)
    for i in range(len(ids)):
        for j in range(len(ids)):
            if ids[i] != ids[j]:
                assert dense[i, j] == 0, "edge (%d,%d) crosses an area" % (i, j)
