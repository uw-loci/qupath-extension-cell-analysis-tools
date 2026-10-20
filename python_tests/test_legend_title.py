"""The colour-scale titles name the units the figures actually draw.

scanpy's defaults ("Mean expression in group", "Median expression in group")
never said that the dotplot, matrix plot and stacked violin draw the run's
NORMALIZED matrix, nor that the first two rescale each marker to 0-1, so a
violin scale running to 2 read as raw expression.
"""

import ast
import json
from pathlib import Path

import pytest

from conftest import SCRIPTS_DIR, load_script_symbol, requires

title = load_script_symbol("model_utils.py", "legend_title")
UNITS = json.loads(
    (
        Path(__file__).resolve().parent.parent
        / "src/test/resources/qupath/ext/qpcat/normalization_units.json"
    ).read_text(encoding="utf-8")
)["units"]


@pytest.mark.parametrize("normalization", sorted(UNITS))
def test_violin_title_names_the_normalization(normalization):
    assert (
        title("Median", normalization)
        == "Median in cluster\n(%s)" % UNITS[normalization]
    )


def test_titles_per_normalization():
    assert title("Median", "zscore") == "Median in cluster\n(z-score)"
    assert title("Median", "minmax") == "Median in cluster\n(min-max 0-1)"
    assert title("Median", "percentile") == "Median in cluster\n(p1-p99 scaled 0-1)"
    assert title("Median", "none") == "Median in cluster\n(raw values)"


def test_a_per_marker_rescaled_figure_says_so_whatever_the_normalization():
    for normalization in UNITS:
        assert title("Mean", normalization, per_marker_scaled=True) == (
            "Mean in cluster\n(each marker scaled 0-1)"
        )


def test_all_three_expression_figures_pass_a_title():
    tree = ast.parse((SCRIPTS_DIR / "run_clustering.py").read_text(encoding="utf-8"))
    titled = {}
    for node in ast.walk(tree):
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
            if node.func.attr in ("dotplot", "matrixplot", "stacked_violin"):
                kw = {k.arg: k.value for k in node.keywords}
                titled[node.func.attr] = ast.unparse(
                    kw.get("colorbar_title", ast.Constant(None))
                )
    assert titled == {
        "dotplot": "legend_title('Mean', normalization, per_marker_scaled=True)",
        "matrixplot": "legend_title('Mean', normalization, per_marker_scaled=True)",
        "stacked_violin": "legend_title('Median', normalization)",
    }


def test_the_dot_size_title_says_above_what_not_expressing():
    size = load_script_symbol("run_clustering.py", "dot_size_title")
    assert size(0.0) == "% of cluster above 0\n(see note below)"
    tree = ast.parse((SCRIPTS_DIR / "run_clustering.py").read_text(encoding="utf-8"))
    calls = [
        n
        for n in ast.walk(tree)
        if isinstance(n, ast.Call)
        and isinstance(n.func, ast.Attribute)
        and n.func.attr == "dotplot"
    ]
    assert [
        ast.unparse(k.value) for c in calls for k in c.keywords if k.arg == "size_title"
    ] == ["dot_size_title(_dot_cutoff)"]


@requires("scanpy")
@pytest.mark.parametrize("kind", ["dotplot", "matrixplot", "stacked_violin"])
def test_scanpy_draws_the_title_it_is_given(kind):
    import anndata as ad
    import matplotlib
    import numpy as np

    matplotlib.use("Agg")
    import scanpy as sc

    rng = np.random.default_rng(0)
    names = ["Nucleus: %s mean" % m for m in ("DAPI", "CD3", "CD8")]
    adata = ad.AnnData(rng.gamma(0.6, 2.0, size=(300, 3)).astype(np.float32))
    adata.var_names = names
    adata.obs["cluster"] = [str(v) for v in rng.integers(0, 4, 300)]
    adata.obs["cluster"] = adata.obs["cluster"].astype("category")
    want = title("Median", "zscore")
    plot = getattr(sc.pl, kind)(
        adata,
        names,
        groupby="cluster",
        colorbar_title=want,
        show=False,
        return_fig=True,
    )
    plot.make_figure()
    texts = [t.get_text() for ax in plot.fig.axes for t in [ax.title, ax.xaxis.label]]
    assert want in texts
