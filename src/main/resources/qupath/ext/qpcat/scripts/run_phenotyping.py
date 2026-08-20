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

# Deliberately NOT imputed. Gating asks "is this marker above threshold for
# this cell", and a cell whose marker QuPath could not compute has no defensible
# answer -- imputing the column median would invent a value and could call the
# cell positive. pandas skips NaN when computing the normalization statistics,
# and `NaN > threshold` is False, so such a cell is gated negative for that
# marker. That is the intended behaviour; warn so it is not silent.
n_nonfinite = int(np.count_nonzero(~np.isfinite(data)))
if n_nonfinite > 0:
    logger.warning(
        "%d non-finite measurement value(s) present. Normalization statistics "
        "ignore them, and any cell with a missing marker is gated NEGATIVE for "
        "that marker (not imputed). Check for measurements absent on some cells.",
        n_nonfinite,
    )

df = pd.DataFrame(data, columns=marker_names)

# 2. Normalize
task.update("Normalizing measurements...")

if normalization == "zscore":
    std = df.std()
    std[std == 0] = 1
    df_norm = (df - df.mean()) / std
elif normalization == "minmax":
    dmin = df.min()
    dmax = df.max()
    drange = dmax - dmin
    drange[drange == 0] = 1
    df_norm = (df - dmin) / drange
elif normalization == "percentile":
    p1 = df.quantile(0.01)
    p99 = df.quantile(0.99)
    drange = p99 - p1
    drange[drange == 0] = 1
    df_norm = df.clip(lower=p1, upper=p99, axis=1)
    df_norm = (df_norm - p1) / drange
else:
    df_norm = df.copy()

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

# 4. Evaluate rules (first-match-wins priority)
labels = np.full(n_cells, -1, dtype=np.int32)

for rule_idx, rule in enumerate(rules):
    cell_type = rule.get("cellType", "Unknown")

    # Only evaluate unassigned cells
    unassigned = labels == -1
    if not np.any(unassigned):
        break

    # Start with all unassigned cells as candidates
    matches = unassigned.copy()
    has_criteria = False

    for marker, condition in rule.items():
        if marker == "cellType" or not condition:
            continue
        if marker not in marker_idx:
            logger.warning("Marker '%s' not found in data, skipping", marker)
            continue

        col = marker_idx[marker]
        values = norm_data[:, col]
        has_criteria = True

        # Use per-marker gate, falling back to default
        marker_gate = gates.get(marker, default_gate)

        if condition == "pos":
            matches &= values >= marker_gate
        elif condition == "neg":
            matches &= values < marker_gate
        else:
            logger.warning(
                "Unknown condition '%s' for marker '%s', skipping", condition, marker
            )

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
# are multi-positive"). Computed while Unknown cells are still labelled -1.
unknown_mask = labels == -1
rule_markers = sorted(
    {
        m
        for r in rules
        for m, c in r.items()
        if m != "cellType" and c and m in marker_idx
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

logger.info("Phenotyping results packaged")
