"""Stacked violin layout: taller rows, no blank strip, angled labels, bold markers.

Asked for after the 2026-10-19 check of a 34-measurement violin: the rows were too
flat to read a shape, an inch of the figure above the plot was empty, and the
vertical labels made the marker hard to find among "Nucleus:" and "std dev".
"""

import numpy as np

from conftest import load_script_symbol, requires

split = load_script_symbol("run_clustering.py", "split_measurement_name")
bold = load_script_symbol(
    "run_clustering.py",
    "bold_marker_label",
    {"split_measurement_name": split},
)


def test_splits_compartment_marker_and_statistic():
    assert split("Nucleus: CD3 std dev") == ("Nucleus: ", "CD3", " std dev")
    assert split("Cytoplasm: PanCK mean") == ("Cytoplasm: ", "PanCK", " mean")
    assert split("Cell: Opal 570 Median") == ("Cell: ", "Opal 570", " Median")
    assert split("Cell: Area") == ("Cell: ", "Area", "")
    assert split("QPCAT spatial: Mean distance") == (
        "QPCAT spatial: ",
        "Mean distance",
        "",
    )
    assert split("CD3") == ("", "CD3", "")


def test_a_name_that_is_only_a_statistic_keeps_it_as_the_marker():
    assert split("Cell: mean") == ("Cell: ", "mean", "")


def test_bolds_only_the_marker():
    assert bold("Nucleus: CD3 std dev") == r"Nucleus: $\mathbf{CD3}$ std dev"
    assert bold("Cell: Opal 570 mean") == r"Cell: $\mathbf{Opal\ 570}$ mean"


def test_leaves_names_mathtext_would_misread_alone():
    for name in (
        "Cell: CD3_x mean",
        "Cell: 50% mean",
        "Cell: $CD3 mean",
        "Cell: a^b mean",
    ):
        assert bold(name) == name


@requires("scanpy")
def test_rows_are_twice_as_tall_with_no_blank_strip_and_labels_render():
    import anndata as ad
    import matplotlib

    matplotlib.use("Agg")
    import scanpy as sc

    size = load_script_symbol("run_clustering.py", "size_stacked_violin")
    angle = load_script_symbol(
        "run_clustering.py", "angle_marker_labels", {"bold_marker_label": bold}
    )

    rng = np.random.default_rng(0)
    names = ["Nucleus: %s mean" % m for m in ("DAPI", "CD3", "CD8", "PanCK")] + [
        "Cell: Area"
    ]
    n_clusters = 6
    labels = rng.integers(0, n_clusters, 600)
    x = rng.gamma(0.6, 2.0, size=(600, len(names))).astype(np.float32)
    adata = ad.AnnData(x)
    adata.var_names = names
    adata.obs["cluster"] = [str(v) for v in labels]
    adata.obs["cluster"] = adata.obs["cluster"].astype("category")

    sv = sc.pl.stacked_violin(
        adata, names, groupby="cluster", show=False, return_fig=True, dodge=False
    )
    row_height = sv.DEFAULT_CATEGORY_HEIGHT
    size(sv)
    sv.make_figure()
    angle(sv)
    sv.fig.canvas.draw()  # mathtext errors surface here

    assert sv.fig.get_size_inches()[1] == n_clusters * row_height * 2
    # Nothing above the rows: the plot reaches the top of the subplot area, where
    # scanpy's default layout stops an inch short of it.
    default = sc.pl.stacked_violin(
        adata, names, groupby="cluster", show=False, return_fig=True, dodge=False
    )
    default.make_figure()
    top_default = default.ax_dict["mainplot_ax"].get_position().y1
    top_now = sv.ax_dict["mainplot_ax"].get_position().y1
    assert top_now == sv.fig.subplotpars.top
    assert top_default < top_now - 0.1
    ticks = sv.ax_dict["mainplot_ax"].get_xticklabels()
    assert {t.get_rotation() for t in ticks} == {45.0}
    assert ticks[1].get_text() == r"Nucleus: $\mathbf{CD3}$ mean"
