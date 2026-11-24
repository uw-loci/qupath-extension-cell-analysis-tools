"""
Phenotyping script for QP-CAT Appose tasks.

Assigns cell phenotypes based on user-defined marker gating rules.
Uses first-match-wins priority: rules are evaluated in order, and each cell
is assigned the first phenotype whose conditions it satisfies.

Inputs (injected by Appose 0.10.0):
  measurements: NDArray (N_cells x N_markers, float64)
  marker_names: list[str]
  normalization: str ("zscore", "minmax", "percentile", "none")
  phenotype_rules: str (JSON) -- list of {cellType, marker: pos/neg, ...}
  gates_json: str (JSON) -- {"marker_name": threshold, ...} per-marker gates
  pheno_gate_max: float (optional) -- default gate for minmax/percentile (default 0.5)

Outputs (via task.outputs):
  phenotype_labels: NDArray (N_cells,) int32 -- index into phenotype_names
  phenotype_names: str (JSON list) -- phenotype name per index
  n_phenotypes: int
  phenotype_counts: str (JSON) -- {phenotype_name: count}
"""

import logging
import json

logger = logging.getLogger("qpcat.phenotyping")

import numpy as np
import pandas as pd
from appose import NDArray as PyNDArray

# Rule keys that test the class a cell already carries. The first two match the
# whole class name; the last two match one COMPONENT of a derived class, so a
# composite classifier's "CD45: Ki67" counts as having CD45 (and lacking CD8).
CLASS_IS = "classIs"
CLASS_IS_NOT = "classIsNot"
CLASS_HAS = "classHas"
CLASS_LACKS = "classLacks"
CLASS_CONDITION_KEYS = (CLASS_IS, CLASS_IS_NOT, CLASS_HAS, CLASS_LACKS)
RULE_META_KEYS = ("cellType",) + CLASS_CONDITION_KEYS
# QuPath joins the components of a derived class with this (PathClass.toString).
CLASS_COMPONENT_SEPARATOR = ": "


def class_components(class_name):
    """The components of a QuPath class name: "CD45: Ki67" -> {"CD45", "Ki67"}.

    The empty name (no class) has no components, so it fails every ``classHas``
    and passes every ``classLacks``, as a cell with no class should.
    """
    if not class_name:
        return frozenset()
    return frozenset(
        part for part in class_name.split(CLASS_COMPONENT_SEPARATOR) if part
    )


def has_class_condition(rule):
    """True when the rule names at least one class under any class-condition key."""
    return any(rule.get(key) for key in CLASS_CONDITION_KEYS)


def class_condition_mask(rule, cell_classes):
    """Boolean mask of the cells whose current class passes the rule's class conditions.

    ``cell_classes`` holds one class name per cell ("" for none). The four
    conditions AND together: the whole name must be one of ``classIs`` and none
    of ``classIsNot``; every component listed under ``classHas`` must be among
    the class's components and no component under ``classLacks`` may be. The
    component tests are evaluated once per distinct class name, not per cell.
    """
    n_cells = len(cell_classes)
    mask = np.ones(n_cells, dtype=bool)
    class_is = list(rule.get(CLASS_IS) or [])
    class_is_not = list(rule.get(CLASS_IS_NOT) or [])
    class_has = set(rule.get(CLASS_HAS) or [])
    class_lacks = set(rule.get(CLASS_LACKS) or [])
    if class_is:
        mask &= np.isin(cell_classes, class_is)
    if class_is_not:
        mask &= ~np.isin(cell_classes, class_is_not)
    if class_has or class_lacks:
        names, inverse = np.unique(
            np.asarray(cell_classes, dtype=str), return_inverse=True
        )
        passes = np.empty(len(names), dtype=bool)
        for i, name in enumerate(names):
            components = class_components(name)
            passes[i] = class_has <= components and not (class_lacks & components)
        mask &= passes[inverse.reshape(-1)]
    return mask


def evaluate_rule(rule, norm_data, marker_idx, gates, default_gate, cell_classes=None):
    """Boolean mask of cells satisfying ONE rule, plus whether it had criteria.

    Besides the marker conditions, a rule may test the cell's CURRENT class
    (the one a classifier or an earlier run gave it, passed in ``cell_classes``,
    one name per cell, "" for none): ``classIs`` / ``classIsNot`` on the whole
    name, ``classHas`` / ``classLacks`` on its components (see
    ``class_condition_mask``). All AND with the rest of the row. A class
    condition with no classes supplied matches nothing, for the same reason an
    any-group on absent markers does.

    A rule is an AND over four kinds of condition:

      pos    / neg    -- EVERY marker so marked must be at/above (below) its gate.
      anypos / anyneg -- AT LEAST ONE marker in that group must be, so the group
                         is an OR that then ANDs with the rest of the row.

    The any-groups are per row, not per marker: all markers marked `anypos` in a
    row form one group. That is what lets "Macrophage = CD68 or CD163 or CD206"
    be a single row instead of three -- and three rows sharing a cell type
    miscount, because labels are rule indices.

    Note the deliberate asymmetry with an EMPTY group: a row with no `anypos`
    markers imposes no any-constraint, whereas a row whose only condition is
    `anypos` on markers absent from the data matches nothing. Silently treating
    the second as "no constraint" would match every cell.

    Returns (matches, has_criteria). Unknown condition words are warned and
    skipped rather than failing the run -- a rule set is user input, and one
    typo should not lose the other twenty rules.
    """
    n_cells = norm_data.shape[0]
    matches = np.ones(n_cells, dtype=bool)
    anypos = np.zeros(n_cells, dtype=bool)
    anyneg = np.zeros(n_cells, dtype=bool)
    has_anypos = False
    has_anyneg = False
    has_criteria = False
    if has_class_condition(rule):
        has_criteria = True
        if cell_classes is None:
            logger.warning(
                "Rule '%s' has a class condition but no cell classes were supplied; "
                "it matches nothing",
                rule.get("cellType", "?"),
            )
            matches &= False
        else:
            matches &= class_condition_mask(rule, cell_classes)

    for marker, condition in rule.items():
        if marker in RULE_META_KEYS or not condition:
            continue
        if condition not in ("pos", "neg", "anypos", "anyneg"):
            logger.warning(
                "Unknown condition '%s' for marker '%s', skipping", condition, marker
            )
            continue
        # Track the group BEFORE the missing-marker check: a rule asking for
        # "any of CD68/CD163" where neither column exists must match nothing,
        # not fall through to an unconstrained row.
        if condition == "anypos":
            has_anypos = True
        elif condition == "anyneg":
            has_anyneg = True
        has_criteria = True

        if marker not in marker_idx:
            logger.warning("Marker '%s' not found in data, skipping", marker)
            continue

        values = norm_data[:, marker_idx[marker]]
        gate = gates.get(marker, default_gate)

        if condition == "pos":
            matches &= values >= gate
        elif condition == "neg":
            matches &= values < gate
        elif condition == "anypos":
            anypos |= values >= gate
        else:
            anyneg |= values < gate

    if has_anypos:
        matches &= anypos
    if has_anyneg:
        matches &= anyneg
    return matches, has_criteria


def build_phenotype_counts(labels, phenotype_names):
    """Cells per phenotype NAME, summing rules that share a name.

    Two rules may legitimately carry the same cell type: that is how an OR is
    written today ("Macrophage = CD68 pos" on one row, "Macrophage = CD163 pos"
    on the next), and both rows classify to the same PathClass. Labels are rule
    INDICES, so a naive {name: count} assignment let the second row's count
    OVERWRITE the first's -- the classification was right while the summary
    under-reported, which is the worse way round because nothing looks wrong.

    Names are emitted in first-appearance order so the summary reads in the
    order the rules were written.
    """
    counts = {}
    for i, name in enumerate(phenotype_names):
        c = int(np.sum(np.asarray(labels) == i))
        if c > 0:
            counts[name] = counts.get(name, 0) + c
    return counts


def nonfinite_gating_note(data, marker_names, max_named=6):
    """One sentence naming the markers with missing or infinite values, or None.

    Such a value is gated negative for its marker and is left out of the
    normalization statistics.
    """
    bad = ~np.isfinite(data)
    per = bad.sum(axis=0)
    if not per.any():
        return None
    named = ["%s (%d)" % (marker_names[j], int(per[j])) for j in np.nonzero(per)[0]]
    more = len(named) - max_named
    shown = ", ".join(named[:max_named]) + (" and %d more" % more if more > 0 else "")
    return (
        "%d cell(s) had a missing or infinite value for at least one marker: %s. "
        "Such a cell is gated NEGATIVE for that marker (it is not imputed), and the "
        "normalization ignores the value." % (int(bad.any(axis=1).sum()), shown)
    )


# What the user has to know about this run, shown by the Phenotyping dialog
# (and printed by the batch); a log line alone is not enough.
phenotype_warnings = []

# 1. Load data
task.update("Loading measurements...")
# The Java side ships float32 when the values round-trip exactly (QuPath
# stores detection measurements as float32 anyway), so widen to float64
# HERE before any arithmetic. Doing the maths in float32 would change
# results -- normalization and the distribution fits below are sensitive
# to it -- which is exactly the regression this upcast exists to prevent.
data = np.array(measurements.ndarray(), dtype=np.float64, copy=True)
n_cells, n_markers = data.shape
logger.info("Received %d cells x %d markers", n_cells, n_markers)
refuse_duplicate_names(marker_names)

# Deliberately NOT imputed. Gating asks "is this marker above threshold for
# this cell", and a cell whose marker QuPath could not compute has no defensible
# answer -- imputing the column median would invent a value and could call the
# cell positive. An infinite value is treated the same way: it becomes NaN here,
# so it neither moves the normalization nor passes a gate (+Inf would otherwise
# pass every "pos" gate). `NaN >= threshold` is False, so such a cell is gated
# negative for that marker.
nonfinite_note = nonfinite_gating_note(data, marker_names)
if nonfinite_note:
    data[~np.isfinite(data)] = np.nan
    phenotype_warnings.append(nonfinite_note)

df = pd.DataFrame(data, columns=marker_names)

# 2. Normalize
task.update("Normalizing measurements...")

df_norm = normalize_frame(df, normalization)

logger.info("Normalization: %s", normalization)

# 3. Parse phenotype rules and per-marker gates
task.update("Applying phenotype rules...")
rules = json.loads(phenotype_rules)
logger.info("Loaded %d phenotype rules", len(rules))

# Parse per-marker gates
gates = json.loads(gates_json)
logger.info("Loaded per-marker gates for %d markers", len(gates))

# Read gate max preference (injected by Appose, fall back to default)
try:
    gate_max = float(pheno_gate_max)
except NameError:
    gate_max = 0.5

# Compute default gate based on normalization
if normalization in ("minmax", "percentile"):
    default_gate = gate_max
elif normalization == "zscore":
    default_gate = 0.0
else:
    default_gate = gate_max

# Build marker name -> column index lookup
marker_idx = {name: i for i, name in enumerate(list(marker_names))}
norm_data = df_norm.values

# The class each cell already carries, for rules with class conditions
# (injected by Appose as JSON, one name per cell, "" for none; absent when the
# caller predates it).
try:
    cell_classes = np.array(json.loads(cell_classes_json), dtype=object)
    if cell_classes.shape[0] != n_cells:
        logger.warning(
            "cell_classes_json has %d entries for %d cells; class conditions disabled",
            cell_classes.shape[0],
            n_cells,
        )
        cell_classes = None
except NameError:
    cell_classes = None
if cell_classes is not None and any(has_class_condition(r) for r in rules):
    n_classed = int(np.sum(cell_classes != ""))
    logger.info(
        "Class conditions in use: %d of %d cells carry a class", n_classed, n_cells
    )
    if n_classed == 0:
        logger.warning(
            "Rules use class conditions but no cell in scope has a class; "
            "apply a classifier first"
        )

# 4. Evaluate rules (first-match-wins priority)
labels = np.full(n_cells, -1, dtype=np.int32)

for rule_idx, rule in enumerate(rules):
    cell_type = rule.get("cellType", "Unknown")

    # Only evaluate unassigned cells
    unassigned = labels == -1
    if not np.any(unassigned):
        break

    rule_matches, has_criteria = evaluate_rule(
        rule, norm_data, marker_idx, gates, default_gate, cell_classes
    )
    # First match wins, so only cells no earlier rule claimed are eligible.
    matches = unassigned & rule_matches

    if has_criteria:
        n_matched = int(np.sum(matches))
        labels[matches] = rule_idx
        logger.info("  %s: %d cells matched", cell_type, n_matched)
    else:
        logger.warning("  %s: no valid criteria, skipping", cell_type)

# Diagnostic: explain WHY cells ended up Unknown. The most common cause is rules
# that require the OTHER markers to be 'neg' (exclusive single-positive rules):
# a cell positive for >= 2 markers then matches no rule and falls through. Break
# the Unknown bucket down by how many rule markers each Unknown cell is positive
# for, so a too-strict rule set is obvious at a glance (e.g. "most of my Unknowns
# are multi-positive"). Computed while Unknown cells are still labeled -1.
unknown_mask = labels == -1
rule_markers = sorted(
    {
        m
        for r in rules
        for m, c in r.items()
        if m not in RULE_META_KEYS and c and m in marker_idx
    }
)
unknown_breakdown = {}
if bool(np.any(unknown_mask)) and rule_markers:
    pos_count = np.zeros(n_cells, dtype=np.int32)
    for m in rule_markers:
        g = gates.get(m, default_gate)
        pos_count += (norm_data[:, marker_idx[m]] >= g).astype(np.int32)
    u_pos = pos_count[unknown_mask]
    unknown_breakdown = {
        "total": int(unknown_mask.sum()),
        "negative_for_all_rule_markers": int(np.sum(u_pos == 0)),
        "positive_for_one_marker": int(np.sum(u_pos == 1)),
        "positive_for_multiple_markers": int(np.sum(u_pos >= 2)),
    }
    logger.info(
        "Unknown breakdown: %d total | %d negative for all rule markers | "
        "%d positive for exactly one | %d positive for >=2 markers "
        "(failed an exclusivity 'neg' condition)",
        unknown_breakdown["total"],
        unknown_breakdown["negative_for_all_rule_markers"],
        unknown_breakdown["positive_for_one_marker"],
        unknown_breakdown["positive_for_multiple_markers"],
    )

# Unassigned cells get "Unknown"
unknown_idx = len(rules)
n_unknown = int(np.sum(labels == -1))
labels[labels == -1] = unknown_idx

# Build phenotype names list
phenotype_names = [r.get("cellType", "Unknown") for r in rules] + ["Unknown"]

# 5. Build counts summary (before the log line, which reports off it)
counts = build_phenotype_counts(labels, phenotype_names)

# Distinct phenotype NAMES present, not distinct rule indices: two rules sharing
# a cell type are one phenotype to the user, and every message built from this
# number says "phenotypes".
n_phenotypes = len(counts)

logger.info(
    "Phenotyping complete: %d distinct phenotypes, %d unknown cells",
    n_phenotypes,
    n_unknown,
)


# 6. Package outputs
task.update("Packaging results...")

labels_nd = PyNDArray(dtype="int32", shape=[n_cells])
np.copyto(labels_nd.ndarray(), labels)
task.outputs["phenotype_labels"] = labels_nd
task.outputs["phenotype_names"] = json.dumps(phenotype_names)
task.outputs["n_phenotypes"] = n_phenotypes
task.outputs["phenotype_counts"] = json.dumps(counts)
task.outputs["unknown_breakdown"] = json.dumps(unknown_breakdown)
task.outputs["warnings"] = json.dumps(phenotype_warnings)
for _w in phenotype_warnings:
    logger.warning("Phenotyping: %s", _w)

logger.info("Phenotyping results packaged")
