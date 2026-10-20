"""Marker names in bold, the same way in every figure.

A measurement name such as "Nucleus: CD3 std dev" draws its marker ("CD3") bold
and the compartment and statistic regular. The splitter lives in model_utils.py,
which the worker loads into every task script, and the Java MeasurementNames
splits the same way: both read the same case list, so they cannot drift.
"""

import json
from pathlib import Path

import pytest

from conftest import load_script_symbol, requires

CASES = json.loads(
    (
        Path(__file__).resolve().parent.parent
        / "src/test/resources/qupath/ext/qpcat/measurement_name_cases.json"
    ).read_text(encoding="utf-8")
)["cases"]

split = load_script_symbol("model_utils.py", "split_measurement_name")
bold = load_script_symbol(
    "model_utils.py", "bold_marker_label", {"split_measurement_name": split}
)
ticks = load_script_symbol(
    "model_utils.py", "bold_marker_ticks", {"bold_marker_label": bold}
)


@pytest.mark.parametrize("case", CASES, ids=lambda c: c[0] or "<empty>")
def test_split_matches_the_shared_cases(case):
    name, before, marker, after = case
    assert split(name) == (before, marker, after)


def test_the_parts_always_join_back_to_the_name():
    for name, *_ in CASES:
        assert "".join(split(name)) == name


def test_bolds_only_the_marker():
    assert bold("Nucleus: CD3 std dev") == r"Nucleus: $\mathbf{CD3}$ std dev"
    assert bold("Cell: Opal 570 mean") == r"Cell: $\mathbf{Opal\ 570}$ mean"
    assert bold("CD3: Nucleus: Mean") == r"$\mathbf{CD3}$: Nucleus: Mean"


def test_characters_mathtext_would_redraw_are_swapped_for_look_alikes():
    # In mathtext a hyphen is a MINUS with operator spacing ("Ki - 67"), an
    # apostrophe is a prime, and + and , pick up spacing as well.
    assert bold("Nucleus: Ki-67 mean") == "Nucleus: $\\mathbf{Ki\u201067}$ mean"
    assert bold("Cell: PD-L1 (SP263) mean") == (
        "Cell: $\\mathbf{PD\u2010L1\\ (SP263)}$ mean"
    )
    assert bold("Cell: CD3+ mean") == r"Cell: $\mathbf{CD3{+}}$ mean"
    assert bold("Cell: a,b mean") == r"Cell: $\mathbf{a{,}b}$ mean"
    assert bold("Cell: O'Brien mean") == "Cell: $\\mathbf{O\u2019Brien}$ mean"
    assert bold("Membrane: 18_Ki-67 mean") == (
        "Membrane: $\\mathbf{18\\_Ki\u201067}$ mean"
    )
    assert bold("Cell: 50% mean") == r"Cell: $\mathbf{50\%}$ mean"


def test_leaves_names_mathtext_would_misread_alone():
    for name in ("Cell: $CD3 mean", "Cell: a^b mean", "Cell: x{y} mean", ""):
        assert bold(name) == name


@requires("matplotlib")
def test_every_label_renders():
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    names = [c[0] for c in CASES] + [
        "Cell: PD-L1 (SP263) mean",
        "Cell: CD3+ mean",
        "Cell: O'Brien mean",
        "Cell: 50% mean",
        "Cell: a^b mean",
    ]
    fig, ax = plt.subplots()
    for i, name in enumerate(names):
        ax.text(0, i, bold(name))
    fig.canvas.draw()  # a mathtext parse error surfaces here
    plt.close(fig)


@requires("matplotlib")
def test_tick_relabel_keeps_rotation_and_alignment():
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    fig, ax = plt.subplots()
    names = ["Nucleus: CD3 mean", "Nucleus: Ki-67 mean", "Cell: Area"]
    ax.bar(range(3), [1, 2, 3])
    ax.set_xticks(range(3))
    ax.set_xticklabels(names, rotation=90, ha="center", fontsize=7)
    ticks(ax)
    fig.canvas.draw()
    labels = ax.get_xticklabels()
    assert [t.get_text() for t in labels] == [bold(n) for n in names]
    assert {t.get_rotation() for t in labels} == {90.0}
    assert {t.get_ha() for t in labels} == {"center"}
    assert {t.get_fontsize() for t in labels} == {7.0}
    plt.close(fig)


@requires("scanpy")
@pytest.mark.parametrize("kind", ["dotplot", "matrixplot"])
def test_dotplot_and_matrixplot_bold_their_markers(kind):
    import anndata as ad
    import matplotlib
    import numpy as np

    matplotlib.use("Agg")
    import scanpy as sc

    axis = load_script_symbol(
        "run_clustering.py", "bold_marker_axis", {"bold_marker_ticks": ticks}
    )
    rng = np.random.default_rng(0)
    names = ["Nucleus: %s mean" % m for m in ("DAPI", "CD3", "Ki-67")] + ["Cell: Area"]
    adata = ad.AnnData(rng.gamma(0.6, 2.0, size=(300, 4)).astype(np.float32))
    adata.var_names = names
    adata.obs["cluster"] = [str(v) for v in rng.integers(0, 4, 300)]
    adata.obs["cluster"] = adata.obs["cluster"].astype("category")

    plot = getattr(sc.pl, kind)(
        adata, names, groupby="cluster", show=False, return_fig=True
    )
    plot.make_figure()
    before = {t.get_rotation() for t in plot.ax_dict["mainplot_ax"].get_xticklabels()}
    axis(plot)
    plot.fig.canvas.draw()
    labels = plot.ax_dict["mainplot_ax"].get_xticklabels()
    assert [t.get_text() for t in labels] == [bold(n) for n in names]
    assert {t.get_rotation() for t in labels} == before
