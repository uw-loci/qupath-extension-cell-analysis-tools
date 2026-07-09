# Python tests for the Appose scripts

Tests for the Python under `src/main/resources/qupath/ext/qpcat/scripts/`, which runs
inside the Appose `qupath-qpcat` environment at QuPath runtime.

## Why these tests look unusual

Appose executes each script as a standalone block with **injected globals** (`task`,
`df`, `algorithm_params`, ...). The scripts are never imported as modules, and
`__file__` / sibling scripts are not on `sys.path` -- so `import run_clustering` is not
available, and importing would fail immediately on the missing globals anyway.

`conftest.load_script_symbol(script, symbol)` bridges the gap: it parses the shipped
script with `ast` and executes **only** the requested top-level `def`. Tests therefore
exercise the exact code that ships in the jar. This works only for pure top-level
helpers -- a function that closes over an injected global raises `NameError` when
called.

Two kinds of test live here, and the second is the important one:

1. **Our helpers** -- ordinary unit tests on functions extracted as above.
2. **Dependency contracts** -- assertions about what a third-party library returns
   (e.g. that `harmonypy`'s `Z_corr` is cells-by-markers). Issue #10 was a silent
   orientation change in harmonypy, not a bug in our logic, so no test of our own code
   could have caught it. Pin the contract instead.

## Running

```bash
# From the repo root. Needs pytest + numpy; pandas/harmonypy tests skip if absent.
python3 -m pytest python_tests/ -v
```

To exercise the harmonypy contract tests you need `harmonypy>=0.2.0,<2` (a PyTorch
build) plus `pandas` and `scikit-learn`. Do **not** create a venv for this -- reuse the
DL classifier's `python_server/venv`, per the monorepo `CLAUDE.md`, or let CI do it.

CI runs the full suite on every push that touches `scripts/` or `python_tests/`
(`.github/workflows/python-tests.yml`), installing CPU-only torch so the contract tests
actually run rather than skipping.
