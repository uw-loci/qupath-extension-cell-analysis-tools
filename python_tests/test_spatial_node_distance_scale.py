"""A micron output without a pixel size is an error, never a silent 1.0.

``spatial_stats.node_distance_scale`` decides the factor for the per-cell
``QPCAT spatial:`` distance and triangle-area columns, and
``compute_spatial_node_measurements`` takes that factor with no default.
"""

import ast
import logging
import math

import numpy as np
import pytest

from conftest import SCRIPTS_DIR, load_script_symbol, requires

scale = load_script_symbol("spatial_stats.py", "node_distance_scale", {"np": np})


def test_pixel_coordinates_need_no_pixel_size():
    assert scale("px", None) == 1.0
    # A pixel size that arrives with pixel coordinates is not applied either.
    assert scale("px", 0.5) == 1.0


def test_micron_coordinates_take_the_supplied_factor():
    assert scale("um", 1.0) == 1.0
    assert scale("um", "0.5") == 0.5


@pytest.mark.parametrize(
    "missing", [None, "", "abc", float("nan"), float("inf"), 0, -0.5]
)
def test_micron_coordinates_without_a_usable_pixel_size_raise(missing):
    with pytest.raises(ValueError, match="pixel size"):
        scale("um", missing)


def _source(name):
    return (SCRIPTS_DIR / name).read_text(encoding="utf-8")


def test_the_node_measurement_function_has_no_default_scale():
    tree = ast.parse(_source("spatial_stats.py"))
    fn = next(
        n
        for n in tree.body
        if isinstance(n, ast.FunctionDef)
        and n.name == "compute_spatial_node_measurements"
    )
    assert [a.arg for a in fn.args.args][-1] == "distance_scale"
    assert fn.args.defaults == []


def test_run_clustering_has_no_stand_in_pixel_size():
    src = _source("run_clustering.py")
    assert "pref_spatial_pixel_size_um = 1.0" not in src
    assert "pref_spatial_pixel_size_um = None" in src
    # The check runs before the try that turns node-measurement failures into warnings.
    assert src.index("_qpcat_spatial.node_distance_scale(") < src.index(
        "_qpcat_spatial.compute_spatial_node_measurements("
    )


@requires("scipy")
def test_distances_and_areas_scale_and_a_bad_scale_raises():
    import scipy.sparse as sp

    compute = load_script_symbol(
        "spatial_stats.py",
        "compute_spatial_node_measurements",
        {"np": np, "logger": logging.getLogger("test")},
    )
    coords = np.array([[0.0, 0.0], [3.0, 0.0], [0.0, 4.0], [3.0, 4.0]])
    rows = [0, 0, 1, 2, 0]
    cols = [1, 2, 3, 3, 3]
    dist = [3.0, 4.0, 4.0, 3.0, 5.0]
    conn = sp.csr_matrix((np.ones(5), (rows, cols)), shape=(4, 4))
    conn = conn + conn.T
    d = sp.csr_matrix((dist, (rows, cols)), shape=(4, 4))
    d = d + d.T

    one = compute(conn, d, coords, "delaunay", 1.0)
    half = compute(conn, d, coords, "delaunay", 0.5)
    assert math.isclose(one["max_distance"][0], 5.0)
    assert np.allclose(half["mean_distance"], 0.5 * one["mean_distance"])
    assert np.allclose(half["max_distance"], 0.5 * one["max_distance"])
    if one.get("triangle_areas") is not None:
        assert np.allclose(
            half["triangle_areas"], 0.25 * one["triangle_areas"], equal_nan=True
        )
    assert np.array_equal(half["num_neighbors"], one["num_neighbors"])

    for bad in (None, float("nan"), 0.0, -1.0):
        with pytest.raises((ValueError, TypeError)):
            compute(conn, d, coords, "delaunay", bad)
