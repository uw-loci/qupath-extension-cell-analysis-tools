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


def test_genes_must_be_explicit_because_var_names_values_may_not_be_indexable():
    """Defect 2, which is PANDAS-VERSION-DEPENDENT -- and that is the point.

    Under the pinned pandas 3.0.5 an Index's ``.values`` is an
    ``ArrowStringArray``, which anndata's ``_normalize_index`` falls through to
    a bare ``IndexError``. Under pandas 2.x it is an object ndarray and the same
    line works fine.

    **CI runs pandas 2.3.3 and the shipped env runs 3.0.5**, so CI could not
    have caught this defect and will not catch the next one of its shape. The
    same skew is recorded in test_stacked_violin_width.py. What this test can
    enforce everywhere is the invariant the fix rests on: pass ``genes``
    explicitly and the behaviour stops depending on the pandas version at all.
    """
    a = _adata()
    values = a.var_names.values

    if isinstance(values, np.ndarray):
        # pandas 2.x: indexing happens to work, so there is nothing to reproduce.
        assert a[:, values].shape[1] == len(MARKERS)
    else:
        # pandas 3.x, the shipped env: exactly the failure that hid the tab.
        with pytest.raises(IndexError):
            _ = a[:, values]

    # Either way, the explicit list is accepted -- which is why the fix works
    # and why `genes` is not an optional argument in compute_autocorr.
    assert a[:, list(a.var_names)].shape[1] == len(MARKERS)


def test_spatial_autocorr_returns_none_without_copy():
    """Defect 1. The results go to adata.uns and the return value is None."""
    a = _adata()
    returned = sq.gr.spatial_autocorr(
        a,
        mode="moran",
        genes=list(a.var_names),
        n_jobs=1,
        show_progress_bar=False,
        seed=0,
    )
    assert returned is None
    assert "moranI" in a.uns
    with pytest.raises(AttributeError):
        _ = returned.index


def test_spatial_autocorr_with_copy_returns_a_frame_with_fdr_columns():
    """What the fixed call relies on: a DataFrame, and a corrected p column."""
    a = _adata()
    df = sq.gr.spatial_autocorr(
        a,
        mode="moran",
        genes=list(a.var_names),
        n_perms=99,
        n_jobs=1,
        show_progress_bar=False,
        seed=0,
        copy=True,
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
        "np": np,
        "logger": logging.getLogger("test.spatial_stats"),
        "AUTOCORR_P_PREFERENCE": _const("AUTOCORR_P_PREFERENCE"),
        "AUTOCORR_P_RAW_PREFERENCE": _const("AUTOCORR_P_RAW_PREFERENCE"),
        "AUTOCORR_DEFAULT_THRESHOLD": _const("AUTOCORR_DEFAULT_THRESHOLD"),
        "AUTOCORR_MIN_AREA_CELLS": _const("AUTOCORR_MIN_AREA_CELLS"),
        # The real one takes (message, detail=None); only the message is user-facing.
        "note_for_user": lambda message, detail=None: _NOTES.append(message),
    }
    for name in (
        "_safe_kwargs",
        "_json_number",
        "pick_p_column",
        "area_slices",
        "area_label",
        "autocorr_passes",
        "_autocorr_frame",
        "per_area_autocorr",
        "describe_p_method",
        "describe_areas",
        "compute_autocorr",
        "run_moran_i",
    ):
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
        assert set(stats) == {"I", "pval", "pval_uncorrected", "areas_above", "n_areas"}
        assert stats["I"] is not None
        # One area: nothing to compare, and the count says so rather than "1 of 1".
        assert stats["n_areas"] == 0 and stats["areas_above"] == 0
    assert task.outputs["spatial_autocorr_threshold"] == pytest.approx(0.05)
    assert task.outputs["spatial_autocorr_areas"] == ""
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
    assert "no Spatial Autocorrelation tab" in _NOTES[0]
    # What the user reads must not contain exception text: the original defect
    # raised a BARE IndexError, which rendered as "(IndexError: )", and the
    # guard's message was 228 characters of pandas internals.
    for leaked in ("Error", "ArrowStringArray", "genes=None", "var_names"):
        assert leaked not in _NOTES[0], _NOTES[0]


def test_p_column_preference_puts_the_floor_free_corrected_p_first():
    """pval_sim is bounded below by 1 / (perms + 1), so its BH-corrected form
    cannot fall below m / (k * (perms + 1)) when k markers sit at that floor:
    0.198 for 1 of 20 at 100 permutations. pval_z_sim has no floor. Either
    corrected column must still outrank every uncorrected one."""
    pref = _const("AUTOCORR_P_PREFERENCE")
    assert pref[0] == "pval_z_sim_fdr_bh"
    assert pref.index("pval_sim_fdr_bh") > pref.index("pval_z_sim_fdr_bh")
    for corrected in ("pval_z_sim_fdr_bh", "pval_norm_fdr_bh", "pval_sim_fdr_bh"):
        for raw in ("pval_z_sim", "pval_norm", "pval_sim", "pval"):
            assert pref.index(raw) > pref.index(corrected), (raw, corrected)


def test_z_sim_p_has_no_permutation_floor():
    """The reason for the preference order, measured rather than asserted."""
    a = _adata(n=400, seed=3)
    # Make one marker strongly structured so its empirical p hits the floor.
    a.X[:, 0] = (a.obsm["spatial"][:, 0] / 1000.0).astype(np.float32)
    n_perms = 49
    df = sq.gr.spatial_autocorr(
        a,
        mode="moran",
        genes=list(a.var_names),
        n_perms=n_perms,
        n_jobs=1,
        show_progress_bar=False,
        seed=0,
        copy=True,
    )
    floor = 1.0 / (n_perms + 1)
    assert df.loc[MARKERS[0], "pval_sim"] == pytest.approx(floor)
    assert df.loc[MARKERS[0], "pval_z_sim"] < floor / 100


# --- the consistency count ----------------------------------------------


def _two_area_adata(n=500, seed=11):
    """Area A: marker 0 follows x (strong structure). Area B: everything iid.

    The pooled I for marker 0 is clearly positive, and that is the number a
    p-value judges. The per-area values are what say it comes from ONE image.
    """
    rng = np.random.default_rng(seed)
    xy_a = rng.uniform(0, 1000, (n, 2))
    xy_b = rng.uniform(0, 1000, (n, 2)) + np.array([5000.0, 0.0])
    X = rng.standard_normal((2 * n, len(MARKERS))).astype(np.float32)
    X[:n, 0] = (xy_a[:, 0] / 1000.0 + rng.normal(0, 0.3, n)).astype(np.float32)
    a = ad.AnnData(X=X)
    a.var_names = pd.Index(list(MARKERS))
    a.obsm["spatial"] = np.vstack([xy_a, xy_b])
    area_ids = np.array([0] * n + [1] * n)
    # Area B sits 4000 px clear of area A, so a 15-NN graph never crosses and
    # is block-diagonal without going through build_spatial_graph.
    sq.gr.spatial_neighbors(a, coord_type="generic", n_neighs=15)
    return a, area_ids


def test_autocorr_passes_mirrors_moran_and_geary():
    passes = _base_globals()["autocorr_passes"]
    assert passes(0.05, "moran") and not passes(0.049, "moran")
    assert passes(0.95, "geary") and not passes(0.951, "geary")
    assert passes(0.2, "moran", threshold=0.1) and not passes(
        0.2, "moran", threshold=0.3
    )
    assert not passes(None, "moran") and not passes(float("nan"), "geary")


def test_consistency_count_separates_one_image_structure_from_cohort_structure():
    _NOTES.clear()
    a, area_ids = _two_area_adata()
    task = _Task()
    n = _base_globals()["run_moran_i"](
        a,
        task,
        measurements=MARKERS,
        n_permutations=99,
        area_ids=area_ids,
        area_names=["img_A", "img_B"],
    )
    assert n == len(MARKERS), _NOTES
    payload = json.loads(task.outputs["spatial_autocorr"])
    per_area = json.loads(task.outputs["spatial_autocorr_per_area"])
    structured = payload[MARKERS[0]]
    assert structured["I"] > 0.05
    assert structured["pval"] < 0.01  # the guard alone would call this a cohort finding
    assert structured["n_areas"] == 2
    assert structured["areas_above"] == 1  # ... and the count says it is one image
    assert per_area[MARKERS[0]]["img_A"] > 0.3
    assert abs(per_area[MARKERS[0]]["img_B"]) < 0.05
    for noise in MARKERS[1:]:
        assert payload[noise]["areas_above"] == 0
    assert task.outputs["spatial_autocorr_areas"] == "2 area(s) evaluated"


def test_small_areas_do_not_vote():
    a, area_ids = _two_area_adata(n=500)
    # Shrink area B below the minimum by relabelling most of it into A.
    min_cells = _const("AUTOCORR_MIN_AREA_CELLS")
    area_ids = area_ids.copy()
    area_ids[500 : 1000 - (min_cells - 1)] = 0
    res = _base_globals()["compute_autocorr"](
        a,
        "moran",
        MARKERS,
        n_permutations=0,
        area_ids=area_ids,
        area_names=["img_A", "img_B"],
    )
    assert res["n_areas"] == 1
    assert [lab for lab, _ in res["skipped_areas"]] == ["img_B"]
    assert "1 skipped" in _base_globals()["describe_areas"](
        res["n_areas"], res["skipped_areas"]
    )


def test_pick_p_column_skips_missing_and_nan():
    pick = _pick_p()
    row = {"pval_sim_fdr_bh": float("nan"), "pval_norm": 0.25}
    val, col = pick(row, _const("AUTOCORR_P_PREFERENCE"))
    assert col == "pval_norm"
    assert val == pytest.approx(0.25)
    val, col = pick({}, _const("AUTOCORR_P_PREFERENCE"))
    assert col is None
