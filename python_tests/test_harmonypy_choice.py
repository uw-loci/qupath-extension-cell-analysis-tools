"""run_clustering._harmonypy: the opt-in 2.x is loaded from its own folder
and the environment's 0.2.0 is left in place afterwards."""

import contextlib
import os
import sys

import pytest

from conftest import load_script_symbol

FAST = "2.1.0"


def _loader(tmp_path, monkeypatch):
    fake = tmp_path / "fast" / "harmonypy"
    fake.mkdir(parents=True)
    (fake / "__init__.py").write_text("__version__ = '%s'\n" % FAST)
    ns = {
        "HARMONYPY_PORTABLE": "0.2.0",
        "HARMONYPY_FAST": FAST,
        "contextlib": contextlib,
        "os": os,
        "sys": sys,
        "_harmonypy_fast_dir": lambda: str(tmp_path / "fast"),
        "_install_harmonypy_fast": lambda target: pytest.fail("should not install"),
        "task": None,
    }
    return load_script_symbol("run_clustering.py", "_harmonypy", ns)


@pytest.mark.skipif(sys.platform == "win32", reason="the fast version is refused on Windows")
def test_the_fast_version_is_loaded_and_then_removed(tmp_path, monkeypatch):
    before = sys.modules.get("harmonypy")
    with _loader(tmp_path, monkeypatch)(FAST) as hm:
        assert hm.__version__ == FAST
    assert sys.modules.get("harmonypy") is before
    assert str(tmp_path / "fast") not in sys.path
