"""A class absent from a neighborhood is absent, not a -19 log2 depletion.

The enrichment used log2((fraction + 1e-6) / (frequency + 1e-6)), so a class with
no cell in a neighborhood scored about -19 against a 50 % class, far past any real
depletion, and that one value set the heatmap's color range. It is now NaN: null in
the JSON, gray in the heatmap, outside the color scale.
"""

import json
import logging

import numpy as np
import pytest

from conftest import load_script_symbol, requires

enrich = load_script_symbol("cellular_neighborhoods.py", "cn_enrichment", {"np": np})
nan_to_none = load_script_symbol("cellular_neighborhoods.py", "nan_to_none", {"np": np})


def test_absent_is_nan_and_present_is_the_plain_log2_ratio():
    mean_comp = np.array([[0.75, 0.25, 0.0], [0.25, 0.25, 0.5]])
    freq = np.array([0.5, 0.25, 0.25])
    e = enrich(mean_comp, freq)
    assert e[0, 0] == pytest.approx(np.log2(1.5))
    assert e[0, 1] == pytest.approx(0.0)
    assert np.isnan(e[0, 2])
    assert e[1, 2] == pytest.approx(1.0)
    assert np.nanmin(e) > -2  # no -19 from the guard value


def test_a_class_with_no_cell_anywhere_is_nan_too():
    e = enrich(np.array([[1.0, 0.0]]), np.array([1.0, 0.0]))
    assert e[0, 0] == pytest.approx(0.0)
    assert np.isnan(e[0, 1])


def test_json_has_null_for_absent():
    text = json.dumps(nan_to_none(np.array([[1.0, np.nan], [np.inf, -0.5]])))
    assert json.loads(text) == [[1.0, None], [None, -0.5]]
    assert "NaN" not in text and "Infinity" not in text


@requires("matplotlib")
def test_heatmap_draws_absent_gray_with_a_caption(tmp_path):
    import os

    import matplotlib

    matplotlib.use("Agg")
    notes = []
    save = load_script_symbol(
        "cellular_neighborhoods.py",
        "_save_heatmap",
        {
            "np": np,
            "os": os,
            "out_dir": str(tmp_path),
            "logger": logging.getLogger("test"),
            "cn_notes": notes,
        },
    )
    m = enrich(np.array([[0.75, 0.25, 0.0]]), np.array([0.5, 0.25, 0.25]))
    path = save(
        m,
        ["CN 0"],
        ["A", "B", "C"],
        "t",
        "log2",
        "RdBu_r",
        -1,
        1,
        "x.png",
        missing_caption="Gray: absent",
    )
    assert path and os.path.exists(path) and notes == []
    import matplotlib.image as mpimg

    img = mpimg.imread(path)
    gray = np.array([0xBD, 0xBD, 0xBD]) / 255.0
    assert np.any(np.all(np.abs(img[..., :3] - gray) < 0.02, axis=-1))
