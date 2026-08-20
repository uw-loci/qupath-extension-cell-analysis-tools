"""build_phenotype_counts: rules sharing a cell type must SUM, not overwrite.

Two rules may legitimately carry the same name -- that is how an OR is written
in QP-CAT today ("Macrophage = CD68 pos" on one row, "Macrophage = CD163 pos"
on the next), and both classify to the same PathClass. Labels are rule INDICES,
so the original `counts[name] = c` let the second row's count replace the
first's. The classification stayed correct while the summary under-reported,
which is the worse way round: nothing looks wrong.
"""

import numpy as np
import pytest

from conftest import load_script_symbol

build_phenotype_counts = load_script_symbol(
    "run_phenotyping.py", "build_phenotype_counts", extra_globals={"np": np}
)


def test_distinct_names_are_counted_separately():
    labels = np.array([0, 0, 1, 2, 2, 2])
    names = ["T cell", "B cell", "Unknown"]
    assert build_phenotype_counts(labels, names) == {
        "T cell": 2,
        "B cell": 1,
        "Unknown": 3,
    }


def test_rules_sharing_a_name_are_summed():
    # Two Macrophage rules (the OR idiom) plus one T cell rule.
    labels = np.array([0, 0, 0, 1, 1, 2])
    names = ["Macrophage", "Macrophage", "T cell", "Unknown"]
    counts = build_phenotype_counts(labels, names)
    assert counts["Macrophage"] == 5, "the two Macrophage rules must add up"
    assert counts["T cell"] == 1


def test_the_summary_total_matches_the_cell_count():
    # The property that actually matters: no cell is lost or double counted.
    labels = np.array([0, 1, 1, 2, 3, 3, 3])
    names = ["Macrophage", "Macrophage", "Macrophage", "Unknown"]
    counts = build_phenotype_counts(labels, names)
    assert sum(counts.values()) == labels.size


def test_phenotypes_with_no_cells_are_omitted():
    labels = np.array([0, 0, 2])
    names = ["T cell", "B cell", "Unknown"]
    assert "B cell" not in build_phenotype_counts(labels, names)


def test_names_keep_first_appearance_order():
    labels = np.array([2, 1, 0])
    names = ["first", "second", "third"]
    assert list(build_phenotype_counts(labels, names)) == ["first", "second", "third"]


@pytest.mark.parametrize("labels", [np.array([], dtype=np.int32), np.zeros(0)])
def test_no_cells_gives_no_counts(labels):
    assert build_phenotype_counts(labels, ["T cell", "Unknown"]) == {}
