"""Contract test for the seaborn behaviour that made every violin a hairline.

A 20-cluster x 34-feature stacked violin came out as a grid of vertical lines,
readable at no zoom level, because the PNG genuinely contained hairlines: each
violin was about 2 px wide inside a 40 px column.

The cause is in scanpy's wrapper, not in the figure size. ``StackedViolin``
calls ``seaborn.violinplot(x=<var>, hue=<the same var>, ...)`` so it can give
each column its own colour, and seaborn's ``dodge="auto"`` then splits the slot
between the hue levels -- so with 34 features each violin gets 1/34 of its
column. Passing ``dodge=False`` restores the full width.

The two tests below MEASURE the drawn width rather than asserting the keyword is
present, so they pin the defect as well as the fix: if a future seaborn stops
dodging a redundant hue, the first test goes red and the keyword can be dropped.
"""

import numpy as np
import pytest

from conftest import SCRIPTS_DIR, requires


def _widest_violin(fig):
    """Widest violin body in the figure, in data units of its own axes."""
    from matplotlib.collections import PolyCollection

    widest = 0.0
    for ax in fig.axes:
        for coll in ax.collections:
            if not isinstance(coll, PolyCollection):
                continue
            for path in coll.get_paths():
                v = path.vertices
                if len(v) < 3:
                    continue
                widest = max(widest, float(v[:, 0].max() - v[:, 0].min()))
    return widest


def _make_adata(n_vars=12, n_clusters=6, n_cells=600):
    import anndata as ad

    rng = np.random.default_rng(0)
    x = rng.gamma(0.6, 2.0, size=(n_cells, n_vars)).astype(np.float32)
    labels = rng.integers(0, n_clusters, n_cells)
    for c in range(n_clusters):
        x[labels == c, c % n_vars] += 8.0
    var_names = ["M%02d" % i for i in range(n_vars)]
    adata = ad.AnnData(x)
    adata.var_names = var_names
    adata.obs["cluster"] = [str(v) for v in labels]
    adata.obs["cluster"] = adata.obs["cluster"].astype("category")
    return adata, var_names


def _violin_fig(dodge):
    import matplotlib

    matplotlib.use("Agg")
    import scanpy as sc

    adata, var_names = _make_adata()
    kwds = {} if dodge is None else {"dodge": dodge}
    plot = sc.pl.stacked_violin(
        adata,
        var_names=var_names,
        groupby="cluster",
        dendrogram=False,
        show=False,
        return_fig=True,
        **kwds
    )
    plot.make_figure()
    return plot.fig


@requires("scanpy")
@requires("anndata")
def test_default_violins_are_hairlines():
    """The defect itself. Red here means seaborn stopped dodging a redundant hue."""
    import matplotlib.pyplot as plt

    fig = _violin_fig(dodge=None)
    try:
        width = _widest_violin(fig)
    finally:
        plt.close("all")
    assert width > 0, "no violin bodies were drawn; the measurement is not valid"
    # A full-width violin is ~0.8 data units. Dodging 12 features squeezes it
    # below a tenth of that.
    assert width < 0.2, (
        "scanpy's default stacked_violin no longer draws hairlines (widest %.3f "
        "data units); drop the dodge=False workaround in run_clustering.py" % width
    )


@requires("scanpy")
@requires("anndata")
def test_dodge_false_restores_full_width_violins():
    import matplotlib.pyplot as plt

    fig = _violin_fig(dodge=False)
    try:
        width = _widest_violin(fig)
    finally:
        plt.close("all")
    assert width > 0.5, (
        "dodge=False no longer widens the violins (widest %.3f data units)" % width
    )


def test_shipped_script_passes_dodge_false():
    """The call site keeps the keyword, whatever else it grows."""
    source = (SCRIPTS_DIR / "run_clustering.py").read_text(encoding="utf-8")
    call = source.split("sc.pl.stacked_violin(", 1)
    assert len(call) == 2, "run_clustering.py no longer calls sc.pl.stacked_violin"
    body = call[1].split(")", 1)[0]
    assert "dodge=False" in body, "stacked_violin lost dodge=False: violins go back to hairlines"
