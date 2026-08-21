"""evaluate_rule: AND over pos/neg, OR within an any-group.

`anypos`/`anyneg` close a real gap: "Macrophage = CD68 OR CD163 OR CD206" needed
one row per marker, and two rows sharing a cell type is the idiom that produced
the counts bug fixed on 2026-08-19. All markers marked `anypos` in a row form ONE
group; the group ANDs with the row's other conditions, so a rule still reads as a
single Boolean statement.
"""

import logging

import numpy as np
import pytest

from conftest import load_script_symbol

# The shipped script logs through a module-level `logger` that Appose provides;
# the AST loader lifts the function out without it, so inject a real one. Kept a
# real logger rather than a stub so a malformed format string still fails here.
evaluate_rule = load_script_symbol(
    "run_phenotyping.py",
    "evaluate_rule",
    extra_globals={"np": np, "logger": logging.getLogger("test.phenotyping")},
)

# cells x markers, values already normalized to [0, 1]; gate is 0.5 throughout.
#            CD3   CD8   CD68  CD163
DATA = np.array(
    [
        [0.9, 0.9, 0.1, 0.1],  # 0: CD3+ CD8+
        [0.9, 0.1, 0.1, 0.1],  # 1: CD3+ only
        [0.1, 0.1, 0.9, 0.1],  # 2: CD68+ only
        [0.1, 0.1, 0.1, 0.9],  # 3: CD163+ only
        [0.1, 0.1, 0.9, 0.9],  # 4: CD68+ CD163+
        [0.1, 0.1, 0.1, 0.1],  # 5: negative for everything
    ]
)
IDX = {"CD3": 0, "CD8": 1, "CD68": 2, "CD163": 3}


def run(rule):
    m, has = evaluate_rule(rule, DATA, IDX, {}, 0.5)
    return set(np.flatnonzero(m).tolist()), has


def test_pos_markers_are_anded():
    got, has = run({"cellType": "T", "CD3": "pos", "CD8": "pos"})
    assert got == {0}, "both markers must clear the gate"
    assert has is True


def test_neg_makes_a_rule_exclusive():
    assert run({"cellType": "T", "CD3": "pos", "CD68": "neg"})[0] == {0, 1}


def test_anypos_is_an_or_within_the_group():
    # The case the feature exists for: one row, three acceptable markers.
    got, _ = run({"cellType": "Mac", "CD68": "anypos", "CD163": "anypos"})
    assert got == {2, 3, 4}


def test_anypos_group_ands_with_pos_conditions():
    got, _ = run({"cellType": "X", "CD3": "pos", "CD68": "anypos", "CD163": "anypos"})
    assert got == set(), "no cell is CD3+ AND (CD68+ or CD163+)"


def test_anyneg_is_an_or_of_below_gate():
    got, _ = run({"cellType": "X", "CD68": "anyneg", "CD163": "anyneg"})
    assert got == {0, 1, 2, 3, 5}, "everything except the double-positive cell 4"


def test_two_any_groups_are_independent():
    got, _ = run({"cellType": "X", "CD3": "anypos", "CD8": "anypos", "CD68": "anyneg"})
    assert got == {0, 1}


def test_an_absent_any_group_imposes_no_constraint():
    # No anypos markers at all -> the row is just its pos/neg conditions.
    assert run({"cellType": "T", "CD3": "pos"})[0] == {0, 1}


def test_an_any_group_of_missing_markers_matches_nothing():
    # The dangerous case: treating "group present but no usable column" as
    # "no constraint" would match EVERY cell.
    got, has = run({"cellType": "X", "NotAMarker": "anypos"})
    assert got == set()
    assert has is True, "the rule did specify a criterion; it just cannot be met"


def test_missing_marker_in_a_pos_condition_is_skipped_not_fatal():
    got, has = run({"cellType": "T", "CD3": "pos", "Ghost": "pos"})
    assert got == {0, 1}
    assert has is True


@pytest.mark.parametrize("junk", ["POS", "positive", "maybe", "any"])
def test_unknown_condition_words_are_skipped(junk):
    got, has = run({"cellType": "T", "CD3": "pos", "CD8": junk})
    assert got == {0, 1}, "the typo'd condition is ignored, the valid one still applies"
    assert has is True


def test_a_rule_with_no_conditions_reports_no_criteria():
    _, has = run({"cellType": "Empty"})
    assert has is False
