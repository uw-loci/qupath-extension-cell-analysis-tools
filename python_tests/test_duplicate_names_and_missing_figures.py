"""H21: duplicate measurement names are refused; a figure that fails says so.

Results are keyed by name, so a repeated name overwrote the first column's fold
change, gate or threshold, and scanpy refused three figures with an error that
reached only the log: the results window simply lacked them.
"""

import ast
import json
import tempfile

import numpy as np
import pytest

from conftest import SCRIPTS_DIR, load_script_symbol, requires
from test_clean_data_bit_identical import fixed_matrix, run_full_clustering

refuse = load_script_symbol("model_utils.py", "refuse_duplicate_names")
figure_failed_note = load_script_symbol("run_clustering.py", "figure_failed_note")


@pytest.mark.parametrize(
    "names",
    [
        ["CD3", "CD8", "CD3"],
        ["CD3", "cd3"],
        ["CD3", " CD3 "],
        ["Cell: CD3 mean", "Cell: cd3 MEAN"],
    ],
)
def test_duplicates_are_refused_with_both_names(names):
    with pytest.raises(ValueError) as e:
        refuse(names)
    assert "'%s'" % names[0] in str(e.value)


def test_distinct_names_and_none_pass():
    refuse(["CD3", "CD8", "Cell: CD3 mean"])
    refuse(None)


SCRIPTS_WITH_MARKER_NAMES = [
    "run_clustering.py",
    "run_phenotyping.py",
    "compute_thresholds.py",
    "export_anndata.py",
    "geosketch_select.py",
    "estimate_spatial_time.py",
    "embed_3d.py",
    "train_autoencoder.py",
    "infer_autoencoder.py",
    "spatial_stats_standalone.py",
]


@pytest.mark.parametrize("script", SCRIPTS_WITH_MARKER_NAMES)
def test_every_script_given_names_checks_them(script):
    tree = ast.parse((SCRIPTS_DIR / script).read_text(encoding="utf-8"))
    calls = [
        n
        for n in ast.walk(tree)
        if isinstance(n, ast.Call)
        and isinstance(n.func, ast.Name)
        and n.func.id == "refuse_duplicate_names"
    ]
    assert len(calls) == 1, script


def test_the_figure_note_names_the_figure_and_the_error():
    note = figure_failed_note(
        "Dotplot", ValueError("var_names contains duplicated items")
    )
    assert note.startswith("Dotplot: not drawn (ValueError: var_names")
    assert len(figure_failed_note("X", RuntimeError("y" * 500))) < 260


@requires("scanpy")
@requires("appose")
def test_a_failed_figure_reaches_omitted_outputs():
    # PAGA is computed and drawn when plots are on; whatever it does in this
    # environment, its figure either exists or is listed with a reason.
    out = run_full_clustering(
        SCRIPTS_DIR,
        fixed_matrix(),
        "zscore",
        embedding_method="umap",
        generate_plots=True,
        output_dir=tempfile.mkdtemp(),
    )
    plots = json.loads(out["plot_paths"])
    omitted = json.loads(out.get("omitted_outputs", "[]"))
    for key, figure in (
        ("dotplot", "Dotplot"),
        ("matrixplot", "Matrix Plot"),
        ("paga", "PAGA graph"),
        ("stacked_violin", "Stacked violin"),
        ("embedding", "Embedding plot"),
    ):
        assert key in plots or any(o.startswith(figure) for o in omitted), (
            key,
            omitted,
        )
