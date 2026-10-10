"""Moran's I could never appear, and only running it showed that.

Two defects, in both call sites, each sufficient on its own:

1. ``copy`` was left at its default ``False``, so ``sq.gr.spatial_autocorr``
   returns ``None`` and writes to ``adata.uns``. The next line read
   ``df.index``. Present from the day the feature was written.

2. ``genes`` was omitted, so squidpy does ``genes = adata.var_names.values``
   and indexes AnnData with it. Under the pinned pandas 3.0.5 that is an
   ``ArrowStringArray``, which anndata's ``_normalize_index`` does not
   recognise, so it raises a bare ``IndexError`` with no message.

Both were swallowed by ``except Exception`` and logged, so the Spatial
Autocorrelation tab was simply absent -- indistinguishable from not having
ticked the box. Twelve saved results across four projects carried
``nhoodEnrichment`` (computed a few lines above, under the same gate) and not
one carried ``spatialAutocorrJson``.

These tests pin the LIBRARY CONTRACT, not our wrapper, because that is what
moved. A future pandas or anndata bump that changes it fails here instead of
silently removing a tab.
"""

import json

import numpy as np
import pytest

from conftest import load_script_symbol

SCRIPT = "spatial_stats.py"

sq = pytest.importorskip("squidpy", reason="needs the QP-CAT analysis env")
ad = pytest.importorskip("anndata")
pd = pytest.importorskip("pandas")

MARKERS = ["Nucleus: DAPI mean", "Nucleus: PanCK mean", "Nucleus: CD3 mean"]


def _adata(n=300, seed=0):
    """An AnnData built exactly the way run_clustering.py builds it."""
    rng = np.random.default_rng(seed)
    a = ad.AnnData(X=rng.standard_normal((n, len(MARKERS))).astype(np.float32))
    a.var_names = pd.Index(list(MARKERS))
    a.obsm["spatial"] = rng.uniform(0, 1000, (n, 2))
    sq.gr.spatial_neighbors(a, coord_type="generic", n_neighs=15)
    return a


class _Task:
    def __init__(self):
        self.outputs = {}

    def update(self, *args, **kwargs):
        pass


# --- the two library contracts the defects rested on ---------------------


def test_var_names_values_is_not_indexable_so_genes_must_be_explicit():
    """Defect 2. Reproduces the exact failure, so the fix cannot be undone."""
    a = _adata()
    with pytest.raises(IndexError):
        # What squidpy does internally when genes=None.
        _ = a[:, a.var_names.values]
    # A plain list is accepted, which is why passing genes= fixes it.
    assert a[:, list(a.var_names)].shape[1] == len(MARKERS)


def test_spatial_autocorr_returns_none_without_copy():
    """Defect 1. The results go to adata.uns and the return value is None."""
    a = _adata()
    returned = sq.gr.spatial_autocorr(
        a, mode="moran", genes=list(a.var_names),
        n_jobs=1, show_progress_bar=False, seed=0,
    )
    assert returned is None
    assert "moranI" in a.uns
    with pytest.raises(AttributeError):
        _ = returned.index


def test_spatial_autocorr_with_copy_returns_a_frame_with_fdr_columns():
    """What the fixed call relies on: a DataFrame, and a corrected p column."""
    a = _adata()
    df = sq.gr.spatial_autocorr(
        a, mode="moran", genes=list(a.var_names), n_perms=99,
        n_jobs=1, show_progress_bar=False, seed=0, copy=True,
    )
    assert df is not None
    assert list(df.index) and set(df.index) <= set(MARKERS)
    assert "I" in df.columns
    # squidpy's corr_method defaults to fdr_bh; the old code read neither this
    # nor pval_sim, so the permutations it paid for were discarded.
    assert "pval_norm" in df.columns
    assert "pval_sim" in df.columns
    assert "pval_sim_fdr_bh" in df.columns


# --- our wrapper ---------------------------------------------------------


_NOTES = []


def _const(name):
    """Read a module-level constant out of the shipped script."""
    import ast
    import pathlib

    from conftest import SCRIPTS_DIR

    tree = ast.parse(pathlib.Path(SCRIPTS_DIR / SCRIPT).read_text(encoding="utf-8"))
    for node in tree.body:
        if isinstance(node, ast.Assign):
            for target in node.targets:
                if isinstance(target, ast.Name) and target.id == name:
                    return ast.literal_eval(node.value)
    raise AssertionError("%s not found in %s" % (name, SCRIPT))


def _base_globals():
    """Everything these helpers reference, since the AST loader injects nothing.

    The chain is run_moran_i -> compute_autocorr -> pick_p_column, plus
    describe_p_method, _json_number and _safe_kwargs. Loading them in dependency
    order and sharing one dict means each sees the others.
    """
    import logging
    import math as _math

    g = {
        "json": json,
        "math": _math,
        "logger": logging.getLogger("test.spatial_stats"),
        "AUTOCORR_P_PREFERENCE": _const("AUTOCORR_P_PREFERENCE"),
        "AUTOCORR_P_RAW_PREFERENCE": _const("AUTOCORR_P_RAW_PREFERENCE"),
        "note_for_user": _NOTES.append,
    }
    for name in ("_safe_kwargs", "_json_number", "pick_p_column",
                 "describe_p_method", "compute_autocorr", "run_moran_i"):
        g[name] = load_script_symbol(SCRIPT, name, extra_globals=g)
    return g


def _pick_p():
    return _base_globals()["pick_p_column"]


def _run_moran():
    return _base_globals()["run_moran_i"]


def test_run_moran_i_produces_a_payload_per_marker():
    _NOTES.clear()
    task = _Task()
    n = _run_moran()(_adata(), task, measurements=MARKERS, n_permutations=99)
    assert n == len(MARKERS), _NOTES
    payload = json.loads(task.outputs["spatial_autocorr"])
    assert set(payload) == set(MARKERS)
    for marker, stats in payload.items():
        assert set(stats) == {"I", "pval", "pval_uncorrected"}
        assert stats["I"] is not None
    assert not _NOTES


def test_the_reported_p_is_the_corrected_one_and_is_named():
    """The old code preferred pval_norm, so it threw away both of these."""
    _NOTES.clear()
    task = _Task()
    _run_moran()(_adata(), task, measurements=MARKERS, n_permutations=99)
    method = task.outputs["spatial_autocorr_p_method"]
    assert "fdr_bh" in method
    assert "permutations" in method
    assert "Benjamini-Hochberg" in method
    payload = json.loads(task.outputs["spatial_autocorr"])
    # Correction can only raise a p-value, never lower it.
    for stats in payload.values():
        if stats["pval"] is not None and stats["pval_uncorrected"] is not None:
            assert stats["pval"] >= stats["pval_uncorrected"] - 1e-12


def test_an_empty_measurement_list_is_refused_and_reported_to_the_user():
    """genes=[] is the shape that used to reach squidpy as genes=None.

    It must fail loudly AND leave a note, because the whole defect was a
    statistic that vanished with nothing but a log line.
    """
    _NOTES.clear()
    task = _Task()
    n = _run_moran()(_adata(), task, measurements=[], n_permutations=0)
    assert n == 0
    assert "spatial_autocorr" not in task.outputs
    assert len(_NOTES) == 1
    assert "Spatial Autocorrelation tab is absent" in _NOTES[0]


def test_p_column_preference_puts_corrected_permutation_first():
    pref = _const("AUTOCORR_P_PREFERENCE")
    assert pref[0] == "pval_sim_fdr_bh"
    # pval_norm must not outrank any corrected column -- that inversion is
    # exactly what made the run pay for permutations and then ignore them.
    assert pref.index("pval_norm") > pref.index("pval_sim")
    assert pref.index("pval_norm") > pref.index("pval_norm_fdr_bh")


def test_pick_p_column_skips_missing_and_nan():
    pick = _pick_p()
    row = {"pval_sim_fdr_bh": float("nan"), "pval_norm": 0.25}
    val, col = pick(row, _const("AUTOCORR_P_PREFERENCE"))
    assert col == "pval_norm"
    assert val == pytest.approx(0.25)
    val, col = pick({}, _const("AUTOCORR_P_PREFERENCE"))
    assert col is None
