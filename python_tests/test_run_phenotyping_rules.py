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
# The module-level constants and the helpers evaluate_rule calls are injected the
# same way, each helper loaded from the shipped source before the next needs it.
CLASS_CONDITION_KEYS = ("classIs", "classIsNot", "classHas", "classLacks")
SCRIPT_GLOBALS = {
    "np": np,
    "logger": logging.getLogger("test.phenotyping"),
    "CLASS_IS": "classIs",
    "CLASS_IS_NOT": "classIsNot",
    "CLASS_HAS": "classHas",
    "CLASS_LACKS": "classLacks",
    "CLASS_CONDITION_KEYS": CLASS_CONDITION_KEYS,
    "RULE_META_KEYS": ("cellType",) + CLASS_CONDITION_KEYS,
    "CLASS_COMPONENT_SEPARATOR": ": ",
}
for _name in (
    "class_components",
    "has_class_condition",
    "class_condition_mask",
    "evaluate_rule",
):
    SCRIPT_GLOBALS[_name] = load_script_symbol(
        "run_phenotyping.py", _name, extra_globals=SCRIPT_GLOBALS
    )
class_components = SCRIPT_GLOBALS["class_components"]
has_class_condition = SCRIPT_GLOBALS["has_class_condition"]
class_condition_mask = SCRIPT_GLOBALS["class_condition_mask"]
evaluate_rule = SCRIPT_GLOBALS["evaluate_rule"]

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


# --- class conditions (issue #27): the class a cell already carries, ANDed in ---

CLASSES = np.array(["Tumor", "Tumor", "Immune", "Immune", "", "Stroma"], dtype=object)


def run_with_classes(rule, classes=CLASSES):
    m, has = evaluate_rule(rule, DATA, IDX, {}, 0.5, classes)
    return set(np.flatnonzero(m).tolist()), has


def test_class_is_ands_with_the_markers():
    # CD3+ cells are 0 and 1; only those the classifier called Tumor pass.
    got, has = run_with_classes({"cellType": "X", "CD3": "pos", "classIs": ["Tumor"]})
    assert got == {0, 1} and has is True
    got, _ = run_with_classes({"cellType": "X", "CD68": "pos", "classIs": ["Tumor"]})
    assert got == set(), "CD68+ cells are not Tumor, so nothing matches"


def test_class_is_not_excludes_and_keeps_unclassified():
    got, _ = run_with_classes({"cellType": "X", "classIsNot": ["Tumor", "Immune"]})
    assert got == {4, 5}, 'the unclassified cell ("") and the Stroma cell remain'


def test_class_only_rule_counts_as_criteria():
    got, has = run_with_classes({"cellType": "X", "classIs": ["Immune"]})
    assert got == {2, 3} and has is True


def test_class_condition_without_classes_matches_nothing():
    m, has = evaluate_rule(
        {"cellType": "X", "CD3": "pos", "classIs": ["Tumor"]}, DATA, IDX, {}, 0.5, None
    )
    assert not m.any() and has is True


def test_rules_without_class_conditions_ignore_the_classes():
    assert run_with_classes({"cellType": "T", "CD3": "pos"}) == ({0, 1}, True)


# --- component conditions (issue #28): one part of a composite classifier's class ---

# What a QuPath composite classifier over CD45 / Ki67 / CD8 writes: the positive
# components joined by ": ", a plain name for one positive, "" for none.
COMPOSITE = np.array(
    [
        "CD45: Ki67",  # 0
        "CD45",  # 1
        "Ki67",  # 2
        "",  # 3: no class
        "CD45: CD8: Ki67",  # 4
        "CD45: CD8",  # 5
    ],
    dtype=object,
)


def run_composite(rule):
    return run_with_classes(rule, COMPOSITE)


def test_class_components_split_on_qupaths_separator():
    assert class_components("CD45: Ki67") == {"CD45", "Ki67"}
    assert class_components("CD45") == {"CD45"}
    assert class_components("CD45: Positive") == {"CD45", "Positive"}
    assert class_components("") == frozenset()
    assert class_components(None) == frozenset()
    assert class_components("Tumor:Ki67") == {"Tumor:Ki67"}, "no space, no split"


def test_has_component_matches_every_derived_name_containing_it():
    got, has = run_composite({"cellType": "X", "classHas": ["CD45"]})
    assert got == {0, 1, 4, 5} and has is True


def test_lacks_component_keeps_the_unclassified_cell():
    got, _ = run_composite({"cellType": "X", "classLacks": ["Ki67"]})
    assert got == {1, 3, 5}, "no class has no components, so it lacks everything"


def test_has_and_lacks_together_is_the_issue_28_example():
    # "CD45+ and Ki67-" without naming every derived class.
    got, _ = run_composite(
        {"cellType": "X", "classHas": ["CD45"], "classLacks": ["Ki67"]}
    )
    assert got == {1, 5}


def test_several_has_components_are_anded():
    got, _ = run_composite({"cellType": "X", "classHas": ["CD45", "Ki67"]})
    assert got == {0, 4}


def test_several_lacks_components_all_exclude():
    got, _ = run_composite({"cellType": "X", "classLacks": ["Ki67", "CD8"]})
    assert got == {1, 3}


def test_component_match_is_exact_not_substring():
    got, _ = run_composite({"cellType": "X", "classHas": ["CD4"]})
    assert got == set(), "CD4 is not a component of CD45"


def test_component_conditions_and_with_whole_name_and_markers():
    # classIs still means the whole name, so combining keeps both meanings.
    got, _ = run_composite({"cellType": "X", "classHas": ["CD45"], "classIs": ["CD45"]})
    assert got == {1}
    # cells 0 and 1 are CD3+; only cell 1 lacks Ki67 (cell 4, 5 are CD3-).
    got, _ = run_composite({"cellType": "X", "CD3": "pos", "classLacks": ["Ki67"]})
    assert got == {1}


def test_has_component_without_classes_matches_nothing():
    m, has = evaluate_rule(
        {"cellType": "X", "classHas": ["CD45"]}, DATA, IDX, {}, 0.5, None
    )
    assert not m.any() and has is True


def test_has_class_condition_sees_every_kind():
    assert has_class_condition({"cellType": "X", "classHas": ["CD45"]})
    assert has_class_condition({"cellType": "X", "classLacks": ["CD45"]})
    assert has_class_condition({"cellType": "X", "classIs": ["Tumor"]})
    assert not has_class_condition({"cellType": "X", "CD3": "pos", "classHas": []})


def test_counts_equal_a_direct_count_over_the_derived_classes():
    # Random composite classes for many cells; the mask must agree with a
    # per-cell split of the name, the definition the docs give.
    rng = np.random.default_rng(28)
    markers = ["CD45", "Ki67", "CD8", "PanCK"]
    classes = []
    for _ in range(5000):
        positives = [m for m in markers if rng.random() < 0.5]
        classes.append(": ".join(positives))
    classes = np.array(classes, dtype=object)
    rule = {"cellType": "X", "classHas": ["CD45", "PanCK"], "classLacks": ["Ki67"]}
    mask = class_condition_mask(rule, classes)
    expected = np.array(
        [
            {"CD45", "PanCK"} <= set(c.split(": ")) and "Ki67" not in c.split(": ")
            for c in classes
        ]
    )
    assert mask.sum() > 0
    np.testing.assert_array_equal(mask, expected)
