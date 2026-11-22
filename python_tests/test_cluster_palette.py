"""The cluster palette is one list kept in two places.

ClusterPalette.java seeds the "Cluster N" class colors and the interactive plots;
model_utils.py carries the same colors for every script (run_clustering.py hands
them to scanpy and the spatial scatter, regenerate_plots.py falls back to them
when no custom color arrives). The Python cannot read the Java, so this test is
what makes the two copies one palette. Cluster 00 and Cluster 20 were both blue
until the list grew past 20, which is also pinned here.
"""

import ast
import re
from pathlib import Path

import pytest

from conftest import SCRIPTS_DIR, load_script_symbol

JAVA_PALETTE = (
    SCRIPTS_DIR.parents[4]
    / "java"
    / "qupath"
    / "ext"
    / "qpcat"
    / "service"
    / "ClusterPalette.java"
)

TAB20 = [
    "#1F77B4", "#FF7F0E", "#2CA02C", "#D62728", "#9467BD",
    "#8C564B", "#E377C2", "#7F7F7F", "#BCBD22", "#17BECF",
    "#AEC7E8", "#FFBB78", "#98DF8A", "#FF9896", "#C5B0D5",
    "#C49C94", "#F7B6D2", "#C7C7C7", "#DBDB8D", "#9EDAE5",
]  # fmt: skip


def script_constant(script_name, name):
    """The literal value of a module-level assignment in a shipped script."""
    tree = ast.parse((SCRIPTS_DIR / script_name).read_text(encoding="utf-8"))
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(
            isinstance(t, ast.Name) and t.id == name for t in node.targets
        ):
            return ast.literal_eval(node.value)
    raise LookupError("No module-level %s in %s" % (name, script_name))


def java_palette():
    """Every packRGB(r, g, b) literal in ClusterPalette.java, as "#RRGGBB"."""
    text = JAVA_PALETTE.read_text(encoding="utf-8")
    triples = re.findall(r"packRGB\((\d+),\s*(\d+),\s*(\d+)\)", text)
    return ["#%02X%02X%02X" % tuple(int(v) for v in t) for t in triples]


@pytest.mark.parametrize("script", ["model_utils.py"])
def test_script_palette_matches_the_java_palette(script):
    assert script_constant(script, "CLUSTER_PALETTE_HEX") == java_palette()


def test_palette_has_forty_colors_and_keeps_tab20_first():
    palette = java_palette()
    assert len(palette) == 40
    assert palette[:20] == TAB20
    assert len(set(palette)) == 40, "a color repeats inside the first 40"


@pytest.mark.parametrize("script", ["model_utils.py"])
def test_cluster_palette_hex_wraps_past_forty(script):
    fn = load_script_symbol(
        script,
        "cluster_palette_hex",
        {"CLUSTER_PALETTE_HEX": script_constant(script, "CLUSTER_PALETTE_HEX")},
    )
    assert fn(0) == "#1F77B4"
    assert fn(20) != fn(0), "Cluster 20 was Cluster 0's blue"
    assert fn(40) == fn(0)
    assert fn("7") == "#7F7F7F"
