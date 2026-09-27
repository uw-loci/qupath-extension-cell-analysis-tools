"""A helper must be defined above its own module-level call.

These scripts are executed top to bottom by the Appose worker, so a `def` placed
after a call that runs at module level is simply not there yet:

    NameError: name 'validate_supplied_labels' is not defined

That shipped. The validator had eight passing tests and every one of them loaded
the function by AST -- `load_script_symbol` compiles the single def and never
executes the module -- so the tests proved the function was correct while the
script could not reach it. Import order is invisible to that kind of test, which
is why it needs its own.

Only module-level calls matter. A helper called from inside another function is
resolved when that function RUNS, by which time the whole module is loaded, so
defining it lower down is ordinary Python and not flagged here.
"""

import ast
import pathlib

import pytest

SCRIPTS = pathlib.Path(__file__).resolve().parents[1] / (
    "src/main/resources/qupath/ext/qpcat/scripts"
)


def script_paths():
    return sorted(p for p in SCRIPTS.glob("*.py") if p.name != "__init__.py")


def module_level_calls(tree):
    """(name, lineno) for every call made outside any def/class body."""
    calls = []

    def walk(node, nested):
        for child in ast.iter_child_nodes(node):
            deeper = nested or isinstance(
                child, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef, ast.Lambda)
            )
            if not deeper and isinstance(child, ast.Call):
                fn = child.func
                if isinstance(fn, ast.Name):
                    calls.append((fn.id, child.lineno))
            walk(child, deeper)

    walk(tree, False)
    return calls


@pytest.mark.parametrize("path", script_paths(), ids=lambda p: p.name)
def test_no_module_level_call_precedes_its_def(path):
    tree = ast.parse(path.read_text(encoding="utf-8"))
    defs = {
        n.name: n.lineno
        for n in tree.body
        if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))
    }
    too_late = [
        f"{name}() called at line {lineno} but defined at line {defs[name]}"
        for name, lineno in module_level_calls(tree)
        if name in defs and defs[name] > lineno
    ]
    assert not too_late, (
        f"{path.name}: these run before their def executes, so the script dies "
        f"with NameError: {too_late}"
    )


def test_the_scan_sees_the_call_it_is_meant_to_guard():
    # A scan that found no module-level calls would make every case above vacuous.
    tree = ast.parse((SCRIPTS / "run_clustering.py").read_text(encoding="utf-8"))
    assert "validate_supplied_labels" in {n for n, _ in module_level_calls(tree)}
