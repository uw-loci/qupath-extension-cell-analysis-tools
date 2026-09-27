"""Contract test for the seaborn behaviour that made every violin a hairline.

A 20-cluster x 34-feature stacked violin came out as a grid of vertical lines,
readable at no zoom level, because the PNG genuinely contained hairlines: each
violin was about 2 px wide inside a 62 px column. Not a resolution problem.

The cause is in scanpy's wrapper. ``StackedViolin`` calls
``seaborn.violinplot(x=<var>, hue=<the same var>, ...)`` so it can colour each
column, and seaborn 0.13's ``dodge="auto"`` asks ``_dodge_needed()``, which
compares ``df[[x]].value_counts().size`` with ``df[[x, hue]].value_counts().size``.
When that column is a pandas Categorical the second counts every unobserved
combination too -- 12 against 144 for a 12-feature panel -- so seaborn concludes
the hue overlaps and splits each slot n ways, although the hue is redundant.

That count is pandas-version-dependent. Measured on the same scanpy 1.11.5 /
seaborn 0.13.2: pandas 3.0.5 (the shipped Appose env) dodges, pandas 2.3.3 (this
project's CI runner) does not. ``dodge=False`` removes the dependence, which is
the real reason to pass it: the figure stops varying with the pandas a user's
environment happens to resolve.

The tests MEASURE the drawn width rather than asserting the keyword is present.
The first two hold in either environment; the third records which way this one
goes, so a reader of a CI log can see it.
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


def _measure(dodge):
    import matplotlib.pyplot as plt

    fig = _violin_fig(dodge=dodge)
    try:
        return _widest_violin(fig)
    finally:
        plt.close("all")


@requires("scanpy")
@requires("anndata")
def test_dodge_false_draws_full_width_violins():
    """The fix. A full-width violin is ~0.8 data units wide."""
    width = _measure(dodge=False)
    assert width > 0, "no violin bodies were drawn; the measurement is not valid"
    assert width > 0.5, (
        "dodge=False no longer widens the violins (widest %.3f data units)" % width
    )


@requires("scanpy")
@requires("anndata")
def test_dodge_false_is_never_narrower_than_the_default():
    """Holds whether or not this environment dodges, so it is the one to gate on."""
    assert _measure(dodge=False) >= _measure(dodge=None) - 1e-9


@requires("scanpy")
@requires("anndata")
def test_report_whether_this_environment_dodges(capsys):
    """Not an assertion about which way it goes -- a record of which way it went."""
    import pandas as pd
    import seaborn as sns

    default = _measure(dodge=None)
    with capsys.disabled():
        print(
            "\nstacked_violin default widest violin: %.3f data units "
            "(seaborn %s, pandas %s) -- %s"
            % (
                default,
                sns.__version__,
                pd.__version__,
                "DODGES, dodge=False is load-bearing here"
                if default < 0.5
                else "does not dodge; dodge=False is a no-op here",
            )
        )
    assert default > 0


def test_shipped_script_passes_dodge_false():
    """The call site keeps the keyword, whatever else it grows.

    Parsed rather than grepped: the call spans a comment that itself contains
    brackets, so any text-slicing check reads the wrong span.
    """
    import ast

    source = (SCRIPTS_DIR / "run_clustering.py").read_text(encoding="utf-8")
    calls = [
        node
        for node in ast.walk(ast.parse(source))
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and node.func.attr == "stacked_violin"
    ]
    assert calls, "run_clustering.py no longer calls sc.pl.stacked_violin"
    for call in calls:
        kwds = {k.arg: k.value for k in call.keywords}
        assert "dodge" in kwds, (
            "stacked_violin lost dodge=False: violins go back to hairlines"
        )
        assert isinstance(kwds["dodge"], ast.Constant) and kwds["dodge"].value is False
