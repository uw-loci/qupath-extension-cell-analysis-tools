"""
Phenotyping script for PyClustering Appose tasks.

Assigns cell phenotypes based on user-defined marker gating rules.
Uses first-match-wins priority: rules are evaluated in order, and each cell
is assigned the first phenotype whose conditions it satisfies.

Inputs (injected by Appose 0.10.0):
  measurements: NDArray (N_cells x N_markers, float64)
  marker_names: list[str]
  normalization: str ("zscore", "minmax", "percentile", "none")
  phenotype_rules: str (JSON) -- list of {cellType, marker: pos/neg, ...}
  gates_json: str (JSON) -- {"marker_name": threshold, ...} per-marker gates

Outputs (via task.outputs):
  phenotype_labels: NDArray (N_cells,) int32 -- index into phenotype_names
  phenotype_names: str (JSON list) -- phenotype name per index
  n_phenotypes: int
  phenotype_counts: str (JSON) -- {phenotype_name: count}
"""
import logging
import json

logger = logging.getLogger("pyclustering.phenotyping")

import numpy as np
import pandas as pd
from appose import NDArray as PyNDArray

# 1. Load data
task.update("Loading measurements...")
data = measurements.ndarray().copy()
n_cells, n_markers = data.shape
logger.info("Received %d cells x %d markers", n_cells, n_markers)

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

# Compute default gate based on normalization
if normalization in ("minmax", "percentile"):
    default_gate = 0.5
elif normalization == "zscore":
    default_gate = 0.0
else:
    default_gate = 0.5

# Build marker name -> column index lookup
marker_idx = {name: i for i, name in enumerate(list(marker_names))}
norm_data = df_norm.values

# 4. Evaluate rules (first-match-wins priority)
labels = np.full(n_cells, -1, dtype=np.int32)

for rule_idx, rule in enumerate(rules):
    cell_type = rule.get('cellType', 'Unknown')

    # Only evaluate unassigned cells
    unassigned = labels == -1
    if not np.any(unassigned):
        break

    # Start with all unassigned cells as candidates
    matches = unassigned.copy()
    has_criteria = False

    for marker, condition in rule.items():
        if marker == 'cellType' or not condition:
            continue
        if marker not in marker_idx:
            logger.warning("Marker '%s' not found in data, skipping", marker)
            continue

        col = marker_idx[marker]
        values = norm_data[:, col]
        has_criteria = True

        # Use per-marker gate, falling back to default
        marker_gate = gates.get(marker, default_gate)

        if condition == 'pos':
            matches &= values >= marker_gate
        elif condition == 'neg':
            matches &= values < marker_gate
        else:
            logger.warning("Unknown condition '%s' for marker '%s', skipping",
                           condition, marker)

    if has_criteria:
        n_matched = int(np.sum(matches))
        labels[matches] = rule_idx
        logger.info("  %s: %d cells matched", cell_type, n_matched)
    else:
        logger.warning("  %s: no valid criteria, skipping", cell_type)

# Unassigned cells get "Unknown"
unknown_idx = len(rules)
n_unknown = int(np.sum(labels == -1))
labels[labels == -1] = unknown_idx

# Build phenotype names list
phenotype_names = [r.get('cellType', 'Unknown') for r in rules] + ['Unknown']
n_phenotypes = len(set(int(x) for x in labels))

logger.info("Phenotyping complete: %d distinct phenotypes, %d unknown cells",
            n_phenotypes, n_unknown)

# 5. Build counts summary
counts = {}
for i, name in enumerate(phenotype_names):
    c = int(np.sum(labels == i))
    if c > 0:
        counts[name] = c

# 6. Package outputs
task.update("Packaging results...")

labels_nd = PyNDArray(dtype="int32", shape=[n_cells])
np.copyto(labels_nd.ndarray(), labels)
task.outputs['phenotype_labels'] = labels_nd
task.outputs['phenotype_names'] = json.dumps(phenotype_names)
task.outputs['n_phenotypes'] = n_phenotypes
task.outputs['phenotype_counts'] = json.dumps(counts)

logger.info("Phenotyping results packaged")
