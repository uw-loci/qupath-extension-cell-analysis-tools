"""Spatial warnings about the user's data reach the results window, not only the log.

The rule from the 2026-11-23 data-hygiene audit: no logger.warning about the
user's data is ever the only output. These pin the spatial ones that were.
"""

import importlib.util
import sys
from pathlib import Path

import numpy as np

from conftest import SCRIPTS_DIR, requires


def _module():
    spec = importlib.util.spec_from_file_location(
        "qpcat_spatial_stats_notes", Path(SCRIPTS_DIR) / "spatial_stats.py"
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    module.reset_notes()
    return module


def test_a_statistic_skipped_for_memory_is_a_note(monkeypatch):
    m = _module()
    monkeypatch.setattr(m, "_total_ram_gb", lambda: 8.0)
    assert m._refuse_if_too_big("Ripley's L", 1000.0, "Turn it off.")
    assert len(m.SPATIAL_NOTES) == 1 and "Skipped Ripley's L" in m.SPATIAL_NOTES[0]


@requires("scipy")
def test_a_failed_component_count_is_not_written_as_zeros(monkeypatch):
    import scipy.sparse as sp

    m = _module()
    import scipy.sparse.csgraph as csgraph

    def boom(**kwargs):
        raise RuntimeError("simulated")

    monkeypatch.setattr(csgraph, "connected_components", boom)
    n = 6
    conn = sp.csr_matrix(np.ones((n, n)) - np.eye(n))
    coords = np.random.default_rng(0).uniform(0, 10, (n, 2))
    payload = m.compute_spatial_node_measurements(
        conn, conn, coords, "knn", distance_scale=1.0
    )
    assert payload["component_labels"] is None
    assert any("connected-component" in note for note in m.SPATIAL_NOTES)


def test_run_clustering_clears_notes_before_smoothing_can_add_any():
    source = (SCRIPTS_DIR / "run_clustering.py").read_text(encoding="utf-8")
    assert source.count("_qpcat_spatial.reset_notes()") == 1
    assert source.index("_qpcat_spatial.reset_notes()") < source.index(
        "build_smoothing_adjacency"
    )
