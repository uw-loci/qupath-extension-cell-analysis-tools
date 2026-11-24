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
    """Execute one top-level function or class out of an Appose script and return it.

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
        if isinstance(node, (ast.FunctionDef, ast.ClassDef)) and node.name == symbol:
            module = ast.Module(body=[node], type_ignores=[])
            namespace = dict(extra_globals or {})
            exec(compile(module, str(path), "exec"), namespace)
            return namespace[symbol]

    raise LookupError("No top-level function or class %r in %s" % (symbol, path.name))


def load_script_constant(script_name, name):
    """The value of a top-level ``NAME = <literal>`` assignment in a script."""
    path = SCRIPTS_DIR / script_name
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    for node in tree.body:
        if (
            isinstance(node, ast.Assign)
            and len(node.targets) == 1
            and isinstance(node.targets[0], ast.Name)
            and node.targets[0].id == name
        ):
            return ast.literal_eval(node.value)
    raise LookupError("No top-level constant %r in %s" % (name, path.name))


def worker_globals():
    """What ApposeClusteringService's init puts in every task's global scope from
    model_utils.py, for tests that exec a whole script the way the worker does.
    Grows as scripts come to rely on more of model_utils."""
    import contextlib

    import numpy as np

    normalization_params, normalize_frame = normalization_functions()
    return {
        "refuse_duplicate_names": load_script_symbol(
            "model_utils.py", "refuse_duplicate_names"
        ),
        "require_finite": load_script_symbol(
            "model_utils.py", "require_finite", {"np": np}
        ),
        "FLOAT32_RANGE_REASON": load_script_constant(
            "model_utils.py", "FLOAT32_RANGE_REASON"
        ),
        "single_threaded": load_script_symbol(
            "model_utils.py", "single_threaded", {"contextlib": contextlib}
        ),
        "report_progress": load_script_symbol("model_utils.py", "report_progress"),
        "normalization_params": normalization_params,
        "normalize_frame": normalize_frame,
    }


def normalization_functions():
    """model_utils (normalization_params, normalize_frame), wired as the worker wires them."""
    import numpy as np

    require_finite_scale = load_script_symbol(
        "model_utils.py", "require_finite_scale", {"np": np}
    )
    normalization_params = load_script_symbol(
        "model_utils.py",
        "normalization_params",
        {"np": np, "require_finite_scale": require_finite_scale},
    )
    normalize_frame = load_script_symbol(
        "model_utils.py",
        "normalize_frame",
        {"normalization_params": normalization_params},
    )
    return normalization_params, normalize_frame


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
