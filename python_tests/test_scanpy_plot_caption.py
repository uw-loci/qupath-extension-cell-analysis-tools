"""Contract test for the scanpy behaviour save_scanpy_plot works around.

The per-feature plots stamp "showing N of M features" onto the figure. The
obvious way to do that -- set a caption, then call ``plot_obj.savefig(path)`` --
silently loses it, because scanpy's ``BasePlot.savefig`` calls ``make_figure()``
itself and so REBUILDS the figure before writing. So we render, stamp the built
figure, and save that figure object.

This pins the two facts that reasoning rests on. If a future scanpy stops
rebuilding in savefig, or renames make_figure/fig, this goes red and the
workaround can be simplified rather than quietly becoming wrong.
"""

from conftest import requires


@requires("scanpy")
def test_baseplot_exposes_make_figure_and_fig():
    import scanpy as sc

    base = sc.pl._baseplot_class.BasePlot
    assert hasattr(base, "make_figure"), "save_scanpy_plot calls make_figure()"
    assert hasattr(base, "savefig")


@requires("scanpy")
def test_savefig_rebuilds_the_figure():
    """savefig() re-renders, which is why a caption cannot be set before it."""
    import inspect

    import scanpy as sc

    src = inspect.getsource(sc.pl._baseplot_class.BasePlot.savefig)
    assert "make_figure()" in src, (
        "savefig no longer re-renders; save_scanpy_plot's render-then-stamp "
        "dance may no longer be necessary"
    )


@requires("scanpy")
@requires("matplotlib")
def test_caption_survives_the_render_then_stamp_order(tmp_path):
    """End to end: text added after make_figure() reaches the written PNG."""
    import matplotlib

    matplotlib.use("Agg")
    import anndata as ad
    import numpy as np
    import pandas as pd

    rng = np.random.default_rng(0)
    n, m = 60, 6
    adata = ad.AnnData(X=rng.normal(size=(n, m)).astype("float32"))
    adata.var_names = pd.Index(["m%d" % i for i in range(m)])
    adata.obs["cluster"] = pd.Categorical(["0"] * (n // 2) + ["1"] * (n - n // 2))

    import scanpy as sc

    dp = sc.pl.dotplot(
        adata,
        var_names=list(adata.var_names),
        groupby="cluster",
        show=False,
        return_fig=True,
    )
    dp.make_figure()
    fig = dp.fig
    fig.text(0.5, -0.02, "showing 3 of 442 features", ha="center", fontsize=8)
    out = tmp_path / "dotplot.png"
    fig.savefig(out, dpi=72, bbox_inches="tight")

    assert out.exists() and out.stat().st_size > 0
    # The caption is a child of the figure, not of any axes, so it is still
    # attached after the save -- the property save_scanpy_plot relies on.
    assert any("showing 3 of 442" in t.get_text() for t in fig.texts)

    # Negative control: the obvious order LOSES it. Without this, the assertion
    # above would still pass if savefig stopped re-rendering, and nothing would
    # flag that save_scanpy_plot had become unnecessary ceremony. Verified
    # against scanpy 1.11.5, where this comes back False.
    dp2 = sc.pl.dotplot(
        adata,
        var_names=list(adata.var_names),
        groupby="cluster",
        show=False,
        return_fig=True,
    )
    dp2.make_figure()
    dp2.fig.text(0.5, -0.02, "should vanish", ha="center")
    dp2.savefig(str(tmp_path / "naive.png"), dpi=72)
    assert not any("should vanish" in t.get_text() for t in dp2.fig.texts), (
        "savefig no longer discards pre-set figure text; save_scanpy_plot's "
        "render-then-stamp order may be simplifiable"
    )
