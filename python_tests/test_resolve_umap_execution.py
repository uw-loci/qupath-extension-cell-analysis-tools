"""Pin the UMAP parallelism contract.

Issue #11: UMAP never completed on a >1M-cell dataset. The cause was that QP-CAT
always passed ``random_state`` to ``umap.UMAP``, and umap-learn responds by
forcing ``n_jobs=1`` (umap_.py:1950) AND compiling the layout optimisation
without numba ``prange`` (``parallel = random_state is None``, umap_.py:2891).
A pinned seed therefore costs every core in both halves of UMAP.

This is exactly the class of bug the repo's own rule was written for: "when a
script calls into a third-party library, pin the library's contract with a
test." No test of our own code could have caught the original, because the
coupling lives in umap-learn. So there are two kinds of test here:

* contract tests against the INSTALLED umap-learn, asserting the coupling still
  exists (skipped when umap is not importable, e.g. plain CI);
* behaviour tests for ``resolve_umap_execution``, asserting we never hand
  umap-learn a combination that silently serialises a large run.
"""

import pytest

from conftest import SCRIPTS_DIR, load_script_symbol, requires


class _Logger:
    """Collects warnings so a test can assert the fallback was announced."""

    def __init__(self):
        self.warnings = []

    def warning(self, msg, *args):
        self.warnings.append(msg % args if args else msg)


def _resolve(logger=None):
    return load_script_symbol(
        "model_utils.py",
        "resolve_umap_execution",
        extra_globals={
            "logger": logger or _Logger(),
            # Mirror the module constants; asserted against the real values below.
            "EMBEDDING_FAST_MODE_CELLS": 200000,
            "EMBEDDING_PCA_INIT_CELLS": 100000,
        },
    )


def _constants():
    """Read the real threshold constants out of the shipped script."""
    import ast

    tree = ast.parse((SCRIPTS_DIR / "model_utils.py").read_text(encoding="utf-8"))
    out = {}
    for node in tree.body:
        if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name):
            name = node.targets[0].id
            if name.startswith("EMBEDDING_"):
                out[name] = ast.literal_eval(node.value)
    return out


# --------------------------------------------------------------------------
# The property that actually matters
# --------------------------------------------------------------------------


@pytest.mark.parametrize("mode", ["auto", "fast", "reproducible"])
@pytest.mark.parametrize("n_cells", [100, 50_000, 199_999, 200_000, 1_000_000])
def test_parallel_runs_never_pass_a_seed(mode, n_cells):
    """The load-bearing invariant.

    umap-learn re-enables the n_jobs=1 override for ANY non-None random_state, so
    "parallel" and "seeded" must never be requested together. If a future edit
    sets both, the run silently drops back to one core -- the original bug.
    """
    kwargs, reproducible, _note = _resolve()(n_cells, 42, mode)

    if reproducible:
        assert kwargs["random_state"] == 42
        assert "n_jobs" not in kwargs, "a seeded run must not claim parallelism"
    else:
        assert (
            kwargs["random_state"] is None
        ), "any non-None random_state makes umap-learn force n_jobs=1"
        assert kwargs["n_jobs"] == -1


def test_auto_switches_at_the_documented_threshold():
    consts = _constants()
    threshold = consts["EMBEDDING_FAST_MODE_CELLS"]
    resolve = _resolve()

    _kw, below, _n = resolve(threshold - 1, 42, "auto")
    _kw, at, _n = resolve(threshold, 42, "auto")
    assert below is True, "small runs stay byte-reproducible"
    assert at is False, "large runs trade reproducibility for cores"


def test_explicit_modes_ignore_cell_count():
    resolve = _resolve()
    _kw, repro_big, _n = resolve(10_000_000, 42, "reproducible")
    _kw, fast_small, _n = resolve(10, 42, "fast")
    assert repro_big is True
    assert fast_small is False


def test_reproducible_mode_is_the_pre_fix_behaviour():
    """'reproducible' must reproduce old results exactly, so no extra kwargs
    beyond the seed -- init in particular changes the layout."""
    resolve = _resolve()
    kwargs, reproducible, _note = resolve(1000, 7, "reproducible")
    assert reproducible is True
    assert kwargs == {"random_state": 7}


def test_pca_init_only_above_its_own_threshold():
    consts = _constants()
    threshold = consts["EMBEDDING_PCA_INIT_CELLS"]
    resolve = _resolve()

    kwargs_below, _r, _n = resolve(threshold - 1, 42, "fast")
    kwargs_at, _r, _n = resolve(threshold, 42, "fast")
    assert "init" not in kwargs_below
    assert kwargs_at["init"] == "pca"


@pytest.mark.parametrize("n_cells", [1000, 150_000, 1_000_000, 10_000_000])
def test_reproducible_mode_is_seed_only_at_every_size(n_cells):
    """'reproducible' must stay byte-identical to the pre-0.9.7 call REGARDLESS of
    cell count. init='pca' changes the layout, so it must never leak into this
    branch -- otherwise re-running an old analysis on a big dataset would quietly
    produce a different figure while still claiming reproducibility."""
    kwargs, reproducible, _note = _resolve()(n_cells, 42, "reproducible")
    assert reproducible is True
    assert kwargs == {"random_state": 42}


def test_unknown_mode_falls_back_to_auto_and_says_so():
    log = _Logger()
    kwargs, reproducible, _note = _resolve(log)(1000, 42, "turbo")
    assert reproducible is True  # 1000 cells -> auto picks reproducible
    assert kwargs["random_state"] == 42
    assert any("turbo" in w for w in log.warnings)


def test_note_reports_reproducibility_honestly():
    resolve = _resolve()
    _kw, _r, seeded_note = resolve(1000, 42, "reproducible")
    _kw, _r, fast_note = resolve(1000, 42, "fast")
    assert "reproducible" in seeded_note
    assert "not bit-reproducible" in fast_note


# --------------------------------------------------------------------------
# Contract tests against the installed umap-learn
# --------------------------------------------------------------------------

# NOTE: the skip goes on each test, NOT on the module. A module-level
# importorskip would take the pure-logic tests above down with it wherever
# umap-learn is absent -- which is the ordinary case for the CI interpreter, and
# exactly where those tests are most worth running.


@requires("umap")
def test_umap_still_forces_n_jobs_when_seeded():
    """If this fails, umap-learn decoupled reproducibility from parallelism and
    the whole 'fast mode' trade-off can be revisited."""
    import inspect
    from umap import umap_ as umap_module

    src = inspect.getsource(umap_module.UMAP._validate_parameters)
    assert "self.n_jobs = 1" in src and "random_state is not None" in src, (
        "umap-learn no longer forces n_jobs=1 for a seeded run -- "
        "re-check resolve_umap_execution, the speed penalty may be gone"
    )


@requires("umap")
def test_umap_still_gates_layout_parallelism_on_random_state():
    """The second half of the coupling: simplicial_set_embedding's `parallel`
    argument is passed as `self.random_state is None`.

    It lives in ``UMAP._fit_embed_data``, which ``fit`` delegates to -- not in
    ``fit`` itself.
    """
    import inspect
    from umap import umap_ as umap_module

    src = inspect.getsource(umap_module.UMAP._fit_embed_data)
    assert "self.random_state is None" in src, (
        "umap-learn no longer gates layout parallelism on random_state -- "
        "re-check resolve_umap_execution"
    )


@requires("umap")
def test_pca_init_is_an_accepted_umap_init():
    """We pass init='pca' above EMBEDDING_PCA_INIT_CELLS; a rename upstream must
    not turn that into a ValueError mid-run."""
    import inspect
    from umap import umap_ as umap_module

    src = inspect.getsource(umap_module.UMAP._validate_parameters)
    assert '"pca"' in src, "umap-learn no longer accepts init='pca'"
