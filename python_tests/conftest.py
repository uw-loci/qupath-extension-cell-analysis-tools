"""Shared helpers for the QP-CAT Python script tests.

The scripts under ``src/main/resources/qupath/ext/qpcat/scripts/`` are executed
by Appose as standalone blocks with injected globals -- they are never imported
as modules, and ``__file__`` / sibling scripts are not on ``sys.path``. So the
usual ``import run_clustering`` is not available to tests.

``load_script_symbol`` bridges that: it parses the shipped script with ``ast``
and executes only the requested top-level function definition. Tests therefore
exercise the real code that ships in the jar, without running the script body
(which would immediately fail on the missing injected globals).

Only pure, top-level helpers can be loaded this way -- a function that closes
over an injected global will raise ``NameError`` when called.
"""

import ast
import sys
from pathlib import Path

import pytest

SCRIPTS_DIR = (
    Path(__file__).resolve().parent.parent
    / "src"
    / "main"
    / "resources"
    / "qupath"
    / "ext"
    / "qpcat"
    / "scripts"
)


def load_script_symbol(script_name, symbol, extra_globals=None):
    """Execute one top-level function out of an Appose script and return it.

    Parameters
    ----------
    script_name : str
        File name inside the scripts directory, e.g. "run_clustering.py".
    symbol : str
        Name of a top-level ``def`` in that script.
    extra_globals : dict, optional
        Names the function body needs at call time (e.g. ``{"np": numpy}``).
    """
    path = SCRIPTS_DIR / script_name
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))

    for node in tree.body:
        if isinstance(node, ast.FunctionDef) and node.name == symbol:
            module = ast.Module(body=[node], type_ignores=[])
            namespace = dict(extra_globals or {})
            exec(compile(module, str(path), "exec"), namespace)
            return namespace[symbol]

    raise LookupError("No top-level function %r in %s" % (symbol, path.name))


def requires(module_name):
    """Skip marker for tests that need an optional runtime dependency."""
    return pytest.mark.skipif(
        _missing(module_name),
        reason="%s not installed in this interpreter" % module_name,
    )


def _missing(module_name):
    if module_name in sys.modules:
        return False
    try:
        __import__(module_name)
    except ImportError:
        return True
    return False
