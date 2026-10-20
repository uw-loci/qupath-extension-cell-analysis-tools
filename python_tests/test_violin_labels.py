"""Stacked violin layout: taller rows, no blank strip, angled labels, bold markers.

Asked for after the 2026-10-19 check of a 34-measurement violin: the rows were too
flat to read a shape, an inch of the figure above the plot was empty, and the
vertical labels made the marker hard to find among "Nucleus:" and "std dev".
"""

import numpy as np

from conftest import load_script_symbol, requires

split = load_script_symbol("model_utils.py", "split_measurement_name")
bold = load_script_symbol(
    "model_utils.py",
    "bold_marker_label",
    {"split_measurement_name": split},
)


@requires("scanpy")
def test_rows_are_twice_as_tall_with_no_blank_strip_and_labels_render():
    import anndata as ad
    import matplotlib

    matplotlib.use("Agg")
    import scanpy as sc

    size = load_script_symbol("run_clustering.py", "size_without_top_strip")
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
    size(sv, row_scale=2.0)
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


@requires("scanpy")
def test_the_colour_scale_sits_under_the_dendrogram_and_the_figure_narrows():
    import anndata as ad
    import matplotlib

    matplotlib.use("Agg")
    import scanpy as sc

    legend = load_script_symbol("run_clustering.py", "legend_under_dendrogram")
    rng = np.random.default_rng(1)
    names = ["Cell: M%d mean" % i for i in range(12)]
    adata = ad.AnnData(rng.gamma(0.6, 2.0, size=(600, 12)).astype(np.float32))
    adata.var_names = names
    adata.obs["cluster"] = [str(v) for v in rng.integers(0, 5, 600)]
    adata.obs["cluster"] = adata.obs["cluster"].astype("category")

    def drawn(move):
        sv = sc.pl.stacked_violin(
            adata,
            names,
            groupby="cluster",
            dendrogram=True,
            show=False,
            return_fig=True,
            dodge=False,
        )
        sv.make_figure()
        if move:
            legend(sv)
        renderer = sv.fig.canvas.get_renderer()
        return sv, sv.fig.get_tightbbox(renderer).width

    sv, moved_width = drawn(True)
    _, default_width = drawn(False)
    tree = sv.ax_dict["group_extra_ax"].get_position()
    scale = sv.ax_dict["color_legend_ax"].get_position()
    main = sv.ax_dict["mainplot_ax"].get_position()
    assert scale.x0 == tree.x0
    assert scale.y1 < main.y0
    assert moved_width < default_width - 0.5  # inches


@requires("scanpy")
def test_the_dotplot_has_no_blank_strip_above_it_either():
    import anndata as ad
    import matplotlib

    matplotlib.use("Agg")
    import scanpy as sc

    size = load_script_symbol("run_clustering.py", "size_without_top_strip")
    rng = np.random.default_rng(2)
    names = ["Cell: M%d mean" % i for i in range(10)]
    adata = ad.AnnData(rng.gamma(0.6, 2.0, size=(500, 10)).astype(np.float32))
    adata.var_names = names
    adata.obs["cluster"] = [str(v) for v in rng.integers(0, 6, 500)]
    adata.obs["cluster"] = adata.obs["cluster"].astype("category")

    def top(sized):
        dp = sc.pl.dotplot(
            adata,
            names,
            groupby="cluster",
            standard_scale="var",
            show=False,
            return_fig=True,
        )
        if sized:
            size(dp)
        dp.make_figure()
        return dp.ax_dict["mainplot_ax"].get_position().y1, dp.fig.subplotpars.top

    sized_top, frame_top = top(True)
    default_top, _ = top(False)
    assert sized_top == frame_top
    assert default_top < sized_top - 0.1
