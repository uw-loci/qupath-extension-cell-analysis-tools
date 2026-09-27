"""Every NDArray input must be read with .ndarray().

Appose hands an array input over as an ``appose.NDArray`` wrapper around a
shared-memory buffer, not as a numpy array. Reading it needs ``.ndarray()``.
Omitting that does not fail at the call site: ``np.asarray(wrapper)`` happily
builds a 0-d object array, and the error surfaces later as

    TypeError: int() argument must be a string, a bytes-like object or a real
               number, not 'NDArray'

which names neither the input nor the line that matters. That is exactly how
``supplied_labels`` shipped broken -- its validator had six tests, all of them
behind the marshalling line, so none of them could reach it.

This walks the shipped scripts, reads which inputs their own docstrings declare
as NDArray, and requires each one to be dereferenced somewhere in the file. A
static check, so it costs nothing and covers scripts no unit test imports.
"""

import pathlib
import re

import pytest

SCRIPTS = pathlib.Path(__file__).resolve().parents[1] / (
    "src/main/resources/qupath/ext/qpcat/scripts"
)

# "  name: NDArray (...)" in the docstring's input section. Outputs are built,
# not read, so the scan stops at the Outputs heading.
DECL = re.compile(r"^\s{2}(\w+):\s*NDArray\b", re.M)


def ndarray_inputs(text):
    head = re.split(r"^Outputs\b", text, maxsplit=1, flags=re.M)[0]
    return DECL.findall(head)


def script_paths():
    return sorted(p for p in SCRIPTS.glob("*.py") if p.name != "__init__.py")


@pytest.mark.parametrize("path", script_paths(), ids=lambda p: p.name)
def test_every_declared_ndarray_input_is_dereferenced(path):
    text = path.read_text(encoding="utf-8")
    missing = [n for n in ndarray_inputs(text) if f"{n}.ndarray()" not in text]
    assert not missing, (
        f"{path.name} declares these inputs as NDArray but never calls "
        f".ndarray() on them: {missing}. An Appose NDArray is a wrapper; "
        f"np.asarray() on it makes a 0-d object array."
    )


def test_the_scan_finds_the_inputs_it_is_meant_to_guard():
    # A silent zero-match regex would make every assertion above vacuous.
    declared = ndarray_inputs(
        (SCRIPTS / "run_clustering.py").read_text(encoding="utf-8")
    )
    assert {"measurements", "spatial_coords", "supplied_labels"} <= set(declared)


# The docstring scan above only guards inputs that are DOCUMENTED. The defect it
# was written for was not: `supplied_labels` was sent by Java, read without
# .ndarray(), and absent from the docstring, so a doc-driven check would have
# passed it. The Java call is the real contract, so check that too.
JAVA = pathlib.Path(__file__).resolve().parents[1] / "src/main/java"

# inputs.put("name", expr) where expr is an NDArray: either a local whose name
# ends in Nd (measNd, labelsNd) or a constructor / builder call naming NDArray.
# Limitation worth knowing: it asks whether ANY script dereferences the name, so
# two scripts sharing an input name where only one is correct still passes. It
# catches the shape the real defect had -- an input nothing reads properly.
PUT = re.compile(r'inputs\.put\(\s*"(\w+)"\s*,\s*([^;]*?)\)\s*;', re.S)


def java_ndarray_inputs():
    names = set()
    for path in JAVA.rglob("*.java"):
        text = path.read_text(encoding="utf-8", errors="replace")
        for name, expr in PUT.findall(text):
            if "NDArray" in expr or re.search(r"\w*Nd\b", expr):
                names.add(name)
    return names


def test_every_ndarray_java_sends_is_dereferenced_by_some_script():
    scripts = {p.name: p.read_text(encoding="utf-8") for p in script_paths()}
    orphans = [
        n
        for n in sorted(java_ndarray_inputs())
        if not any(f"{n}.ndarray()" in t for t in scripts.values())
    ]
    assert not orphans, (
        f"Java sends these as NDArray but no shipped script calls .ndarray() on "
        f"them: {orphans}. Either a script reads the wrapper as if it were a "
        f"numpy array, or the input is dead."
    )


def test_the_java_scan_finds_the_puts_it_is_meant_to_guard():
    assert {
        "measurements",
        "supplied_labels",
        "spatial_coords",
    } <= java_ndarray_inputs()
